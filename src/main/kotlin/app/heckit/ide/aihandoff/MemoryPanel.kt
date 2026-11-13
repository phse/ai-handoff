package app.heckit.ide.aihandoff

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.event.DocumentEvent

/**
 * Was Claude Code in jeder Session liest: CLAUDE.md (global und Projekt) und das Memory des Projekts.
 * Zeigt Umfang (≈ Tokens), Warnungen (zu groß, nicht im Index, Index zeigt ins Leere) und offene [[Links]]; Klick öffnet die Datei.
 */
class MemoryPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val home = File(System.getProperty("user.home"))
    private val claudeDir = File(home, ".claude")
    private val base = project.basePath?.let { File(it) }
    /** Claude Code legt das Memory unter dem Projektpfad ab, alle Zeichen außer Buchstaben/Ziffern werden zu „-“. */
    private val memoryDir = base?.let { File(claudeDir, "projects/" + it.absolutePath.replace(Regex("[^A-Za-z0-9]"), "-") + "/memory") }

    private val search = SearchTextField(false)
    private val summary = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val root = javax.swing.tree.DefaultMutableTreeNode()
    private val model = javax.swing.tree.DefaultTreeModel(root)
    // Kennzahlen (Tokens, Typ) stehen fest am rechten Rand des sichtbaren Bereichs - gezeichnet über dem Text,
    // mit Hintergrund dahinter, damit lange Pfade darunter verschwinden statt waagerecht zu scrollen.
    private val tree = object : com.intellij.ui.treeStructure.Tree(model) {
        override fun paintComponent(g: java.awt.Graphics) {
            super.paintComponent(g)
            val g2 = g.create() as java.awt.Graphics2D
            g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            // Kennzahlen etwas kräftiger, damit sie neben den grauen Zusätzen hervorstechen.
            val metaFont = JBUI.Fonts.smallFont().deriveFont(java.awt.Font.BOLD)
            val vis = visibleRect
            val pad = JBUI.scale(8)
            for (row in 0 until rowCount) {
                val b = getRowBounds(row) ?: continue
                if (b.y > vis.y + vis.height || b.y + b.height < vis.y) continue
                val n = (getPathForRow(row).lastPathComponent as? javax.swing.tree.DefaultMutableTreeNode)?.userObject as? Node ?: continue
                if (n.meta.isEmpty()) continue
                g2.font = if (n.kind == Kind.REFERENCE || n.kind == Kind.INFO) JBUI.Fonts.smallFont() else metaFont
                val fm = g2.fontMetrics
                val w = fm.stringWidth(n.meta)
                val x = vis.x + vis.width - w - pad
                val selected = isRowSelected(row)
                g2.color = if (selected) com.intellij.util.ui.UIUtil.getTreeSelectionBackground(hasFocus()) else background
                g2.fillRect(x - pad, b.y, w + 2 * pad, b.height)
                g2.color = if (selected) com.intellij.util.ui.UIUtil.getTreeSelectionForeground(hasFocus())
                    else if (n.kind == Kind.REFERENCE || n.kind == Kind.INFO) UIUtil.getContextHelpForeground() else UIUtil.getLabelForeground()
                g2.drawString(n.meta, x, b.y + (b.height + fm.ascent - fm.descent) / 2)
            }
            g2.dispose()
        }
    }.apply {
        isRootVisible = false; showsRootHandles = true
        // Doppelklick öffnet nur die Datei; auf- und zuklappen über den Pfeil (bzw. ← →).
        toggleClickCount = 0
        // Keine aufgeklappte Vollzeile beim Darüberfahren - sie läuft unter den Kennzahlen weiter; der Pfad steht im Tooltip.
        setExpandableItemsEnabled(false)
        cellRenderer = Renderer()
    }
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    /** Aufgeklappte Knoten (über Neuaufbauten hinweg) - vor init, weil init schon rebuild() aufruft. */
    private val expandedKeys = HashSet<String>()
    private val collapsedKeys = HashSet<String>()

    init {
        add(search.apply { border = JBUI.Borders.empty(6, 8, 0, 8) }, BorderLayout.NORTH)
        add(JBScrollPane(tree).apply {
            border = JBUI.Borders.emptyTop(6)
            horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            viewport.addChangeListener { tree.repaint() }
        }, BorderLayout.CENTER)
        add(summary.apply { border = JBUI.Borders.empty(4, 10, 6, 10) }, BorderLayout.SOUTH)
        tree.addTreeExpansionListener(object : javax.swing.event.TreeExpansionListener {
            override fun treeExpanded(e: javax.swing.event.TreeExpansionEvent) { key(e.path)?.let { expandedKeys += it; collapsedKeys -= it } }
            override fun treeCollapsed(e: javax.swing.event.TreeExpansionEvent) { key(e.path)?.let { collapsedKeys += it; expandedKeys -= it } }
        })
        // Doppelklick oder Enter öffnet Datei bzw. Stelle; einfacher Klick auf Abschnitt/Fundstelle springt gleich.
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val n = tree.getPathForLocation(e.x, e.y)?.lastPathComponent.let { (it as? javax.swing.tree.DefaultMutableTreeNode)?.userObject as? Node } ?: return
                if (e.clickCount == 2 || (n.line != null && n.jump)) n.file?.let { open(it, n.line ?: 0) }
            }
        })
        tree.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) {
                if (e.keyCode != java.awt.event.KeyEvent.VK_ENTER) return
                val n = (tree.lastSelectedPathComponent as? javax.swing.tree.DefaultMutableTreeNode)?.userObject as? Node ?: return
                n.file?.let { open(it, n.line ?: 0) }
            }
        })
        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = rebuild()
        })
        // Änderungen an den Dateien (auch durch Claude) gleich übernehmen - gebündelt, damit Serien nicht flackern.
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                // Nur angezeigte Dateien, neue Memory-Dateien und CLAUDE*.md - nicht jede .md im Projekt.
                val mem = memoryDir?.path
                if (events.any { e -> e.path in watched || (mem != null && e.path.startsWith(mem)) || e.path.substringAfterLast('/').startsWith("CLAUDE") }) { alarm.cancelAllRequests(); alarm.addRequest({ rebuild() }, 300) }
            }
        })
        rebuild()
    }

    fun refresh() {
        // Dateien außerhalb des Projekts kennt das VFS oft nicht - einmal einlesen, damit Änderungen künftig gemeldet werden.
        listOfNotNull(File(claudeDir, "CLAUDE.md"), memoryDir).forEach { LocalFileSystem.getInstance().refreshAndFindFileByIoFile(it) }
        rebuild()
    }

    // ---- Daten ----

    private class Entry(val file: File, val title: String, val subtitle: String, val text: String) {
        val chars get() = text.length
        val tokens get() = (text.length + 3) / 4
        val lines get() = if (text.isEmpty()) 0 else text.count { it == '\n' } + 1
    }

    private fun read(f: File) = runCatching { f.readText() }.getOrDefault("")

    /** Zeilen außerhalb von Code-Blöcken (```), mit Zeilennummer ab 0. */
    private fun proseLines(text: String): List<Pair<Int, String>> {
        var fence = false
        return text.lines().mapIndexedNotNull { i, l ->
            if (l.trimStart().startsWith("```")) { fence = !fence; null } else if (fence) null else i to l
        }
    }

    /** `@pfad`-Importe einer CLAUDE.md (relativ zur Datei oder mit ~), wie Claude Code sie einbindet. */
    private fun imports(f: File, text: String): List<File> = proseLines(text).flatMap { (_, l) ->
        Regex("(?:^|\\s)@((?:~|\\.{1,2})?/?[\\w./-]+\\.\\w+)").findAll(l).map { it.groupValues[1] }.toList()
    }.map { p -> if (p.startsWith("~/")) File(home, p.removePrefix("~/")) else File(f.parentFile, p).normalize() }
        .filter { it.isFile }.distinct()

    /**
     * Nur erwähnte, nicht importierte Dateien (z. B. „Nachschlagen bei Bedarf: ~/.claude/wissen/kirby.md“): Claude liest sie erst
     * bei Bedarf - sie zählen nicht zur Session, werden aber mit ihrem Umfang gezeigt.
     */
    private fun references(f: File, text: String, skip: Set<File>): List<File> =
        Regex("(?<![@\\w])((?:~|\\.{1,2})?/[\\w./-]+\\.md)\\b").findAll(text).map { it.groupValues[1] }
            .map { p -> if (p.startsWith("~/")) File(home, p.removePrefix("~/")) else if (p.startsWith("/")) File(p) else File(f.parentFile, p).normalize() }
            .filter { it.isFile && it !in skip && it != f }.distinct().toList()

    /** Abschnitte nach Überschriften (# …) mit Startzeile und Text. */
    private class Section(val title: String, val line: Int, val level: Int, val text: String) {
        val tokens get() = (text.length + 3) / 4
    }
    private fun sections(text: String): List<Section> {
        val all = text.lines()
        val heads = proseLines(text).mapNotNull { (i, l) ->
            Regex("^(#{1,4})\\s+(.*)").find(l)?.let { Triple(i, it.groupValues[1].length, it.groupValues[2].trim()) }
        }
        if (heads.isEmpty()) return emptyList()
        val out = mutableListOf<Section>()
        if (heads.first().first > 0) out += Section("(before first heading)", 0, 0, all.subList(0, heads.first().first).joinToString("\n"))
        heads.forEachIndexed { k, (i, lvl, t) ->
            val end = heads.getOrNull(k + 1)?.first ?: all.size
            out += Section(t, i, lvl, all.subList(i, end).joinToString("\n"))
        }
        return out.filter { it.text.isNotBlank() }
    }

    /** Absatz einer Datei für den Doppelt-Vergleich: Wortfolgen (6er-Schindeln). */
    private class Para(val file: File, val line: Int, val preview: String, val shingles: Set<String>)
    private fun paragraphs(f: File, text: String): List<Para> {
        val out = mutableListOf<Para>()
        var start = -1; val buf = StringBuilder()
        fun flush() {
            val words = Regex("[\\p{L}\\p{N}]+").findAll(buf.toString().lowercase()).map { it.value }.toList()
            if (words.size >= 12) out += Para(f, start, buf.toString().trim().replace(Regex("\\s+"), " ").take(70),
                words.windowed(6).map { it.joinToString(" ") }.toSet())
            buf.clear(); start = -1
        }
        for ((i, l) in proseLines(text)) {
            if (l.isBlank() || l.startsWith("#") || l.startsWith("---")) flush() else { if (start < 0) start = i; buf.append(l).append(' ') }
        }
        flush()
        return out
    }

    /** Ähnliche Absätze in verschiedenen Dateien (Anteil gemeinsamer Wortfolgen ≥ 40 % des kleineren Absatzes). */
    private fun duplicates(files: List<Pair<File, String>>): Map<File, List<Pair<Para, Para>>> {
        val paras = files.flatMap { (f, t) -> paragraphs(f, t) }
        val out = HashMap<File, MutableList<Pair<Para, Para>>>()
        for (a in paras.indices) for (b in a + 1 until paras.size) {
            val x = paras[a]; val y = paras[b]
            if (x.file == y.file) continue
            val common = x.shingles.count { it in y.shingles }
            if (common * 10 >= minOf(x.shingles.size, y.shingles.size) * 4) {
                out.getOrPut(x.file) { mutableListOf() } += x to y
                out.getOrPut(y.file) { mutableListOf() } += y to x
            }
        }
        return out
    }


    /** CLAUDE.md-Dateien in der Reihenfolge, in der Claude Code sie einliest: global, dann vom Home-Ordner abwärts zum Projekt. */
    private fun instructionFiles(): List<Entry> {
        val out = mutableListOf<Entry>()
        File(claudeDir, "CLAUDE.md").takeIf { it.isFile }?.let { out += Entry(it, "Global", "~/.claude/CLAUDE.md", read(it)) }
        val dirs = generateSequence(base) { it.parentFile }.takeWhile { it != home.parentFile }.toList().reversed()
        for (d in dirs) for (name in listOf("CLAUDE.md", ".claude/CLAUDE.md", "CLAUDE.local.md")) {
            val f = File(d, name)
            if (f.isFile && f != File(claudeDir, "CLAUDE.md")) out += Entry(f, if (d == base) "Project" else "Parent folder", shortPath(f), read(f))
        }
        // @-Importe zählen mit (bis 5 Ebenen tief wie in Claude Code), direkt hinter der einbindenden Datei.
        val seen = out.map { it.file }.toMutableSet()
        fun withImports(e: Entry, depth: Int): List<Entry> = listOf(e) + if (depth >= 5) emptyList() else
            imports(e.file, e.text).filter { seen.add(it) }.flatMap { withImports(Entry(it, "Imported", "${shortPath(it)} · from ${e.file.name}", read(it)), depth + 1) }
        return out.flatMap { withImports(it, 0) }
    }

    private class Memory(val file: File, val name: String, val description: String, val type: String, val links: List<String>, val text: String)

    private fun memories(): List<Memory> = memoryDir?.listFiles { f -> f.isFile && f.name.endsWith(".md") && f.name != "MEMORY.md" }
        .orEmpty().sortedBy { it.name }.map { f ->
            val text = read(f)
            val front = Regex("^---\\n(.*?)\\n---", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1).orEmpty()
            fun field(key: String) = Regex("^\\s*$key:\\s*(.*)$", RegexOption.MULTILINE).find(front)?.groupValues?.get(1)?.trim().orEmpty()
            Memory(
                f, field("name").ifEmpty { f.nameWithoutExtension }, field("description"), field("type"),
                Regex("\\[\\[([^\\]]+)]]").findAll(text).map { it.groupValues[1].trim() }.distinct().toList(), text,
            )
        }

    private fun shortPath(f: File) = f.absolutePath.let { p ->
        base?.absolutePath?.let { b -> if (p.startsWith("$b/")) return@let p.removePrefix("$b/") }
        if (p.startsWith(home.absolutePath)) "~" + p.removePrefix(home.absolutePath) else p
    }

    // ---- Oberfläche ----


    private enum class Kind { GROUP, FILE, SECTION, WARNING, INFO, DUPLICATE, REFERENCE }
    /** Ein Knoten: Text, grauer Zusatz, Kennzahl rechts; [file]/[line] zum Öffnen, [jump] = schon ein Klick öffnet. */
    private class Node(
        val kind: Kind, val text: String, val extra: String = "", val meta: String = "",
        val file: File? = null, val line: Int? = null, val key: String? = null, val jump: Boolean = false,
    ) { override fun toString() = text }

    private fun key(path: javax.swing.tree.TreePath) = ((path.lastPathComponent as? javax.swing.tree.DefaultMutableTreeNode)?.userObject as? Node)?.key

    private inner class Renderer : com.intellij.ui.ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(tree: javax.swing.JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
            val n = (value as? javax.swing.tree.DefaultMutableTreeNode)?.userObject as? Node ?: return
            val grey = com.intellij.ui.SimpleTextAttributes.GRAYED_ATTRIBUTES
            val small = com.intellij.ui.SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES
            when (n.kind) {
                Kind.GROUP -> { icon = AllIcons.Nodes.Folder; append(n.text, com.intellij.ui.SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES) }
                Kind.FILE -> {
                    icon = com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getFileTypeByFileName(n.file?.name ?: "x.md").icon
                    append(n.text, com.intellij.ui.SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }
                Kind.SECTION -> { icon = null; append(n.text, com.intellij.ui.SimpleTextAttributes.REGULAR_ATTRIBUTES) }
                Kind.WARNING -> { icon = AllIcons.General.Warning; append(n.text, com.intellij.ui.SimpleTextAttributes.REGULAR_ATTRIBUTES) }
                Kind.INFO -> { icon = AllIcons.General.Note; append(n.text, grey) }
                Kind.REFERENCE -> {
                    icon = com.intellij.openapi.fileTypes.FileTypeManager.getInstance().getFileTypeByFileName(n.file?.name ?: "x.md").icon
                    append(n.text, grey)
                }
                Kind.DUPLICATE -> { icon = AllIcons.Actions.Copy; append(n.text, com.intellij.ui.SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) }
            }
            if (n.extra.isNotEmpty()) append("  " + n.extra, grey)
            toolTipText = n.file?.absolutePath
        }
    }

    private fun node(n: Node, parent: javax.swing.tree.DefaultMutableTreeNode) = javax.swing.tree.DefaultMutableTreeNode(n).also { parent.add(it) }

    /** Neu aufbauen: Dateien lesen und vergleichen im Hintergrund, nur das Einsetzen in den Baum im UI-Thread. */
    private var generation = 0
    @Volatile private var watched: Set<String> = emptySet()
    private fun rebuild() {
        val gen = ++generation
        val q = search.text.trim().lowercase()
        ApplicationManager.getApplication().executeOnPooledThread {
            val built = runCatching { build(q) }.getOrNull() ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater({
                if (gen == generation && !project.isDisposed) apply(built, q)
            }, com.intellij.openapi.application.ModalityState.any())
        }
    }

    private class Built(val root: javax.swing.tree.DefaultMutableTreeNode, val summary: String, val expand: List<javax.swing.tree.DefaultMutableTreeNode>)

    private fun apply(b: Built, q: String) {
        summary.text = b.summary
        root.removeAllChildren()
        b.root.children().toList().forEach { root.add(it as javax.swing.tree.DefaultMutableTreeNode) }
        model.reload()
        fun expand(n: javax.swing.tree.DefaultMutableTreeNode) = tree.expandPath(javax.swing.tree.TreePath(n.path))
        b.expand.filter { (it.userObject as Node).key !in collapsedKeys }.forEach { expand(it) }
        root.depthFirstEnumeration().toList().map { it as javax.swing.tree.DefaultMutableTreeNode }
            .filter { (it.userObject as? Node)?.key in expandedKeys }.reversed().forEach { expand(it) }
        if (q.isNotEmpty()) com.intellij.util.ui.tree.TreeUtil.expandAll(tree)
    }

    private fun build(q: String): Built {
        val root = javax.swing.tree.DefaultMutableTreeNode()
        fun matches(vararg s: String) = q.isEmpty() || s.any { it.lowercase().contains(q) }

        val instructions = instructionFiles()
        val mems = memories()
        val dups = duplicates(instructions.map { it.file to it.text } + mems.map { it.file to it.text })
        val index = memoryDir?.let { File(it, "MEMORY.md") }?.takeIf { it.isFile }
        val indexText = index?.let { read(it) }.orEmpty()
        val indexLines = indexText.lines()
        // Claude Code lädt nur die ersten 200 Zeilen des Index.
        val indexTokens = (indexLines.take(200).joinToString("\n").length + 3) / 4
        val instrTokens = instructions.sumOf { it.tokens }
        val summaryText = "Loaded into every session: ≈ ${fmt(instrTokens + indexTokens)} tokens"
        watched = (instructions.map { it.file.path } + mems.map { it.file.path } + listOfNotNull(index?.path)).toSet()
        val toExpand = mutableListOf<javax.swing.tree.DefaultMutableTreeNode>()

        fun fileChildren(parent: javax.swing.tree.DefaultMutableTreeNode, file: File, text: String, warnings: List<String>, infos: List<String>, withSections: Boolean) {
            warnings.forEach { node(Node(Kind.WARNING, it), parent) }
            infos.forEach { node(Node(Kind.INFO, it), parent) }
            dups[file]?.forEach { (mine, other) ->
                node(Node(Kind.DUPLICATE, "Similar: „${mine.preview}…“", meta = "${other.file.name}:${other.line + 1}", file = other.file, line = other.line, jump = true), parent)
            }
            if (withSections) sections(text).takeIf { it.size > 1 }?.let { parts ->
                parts.forEach { node(Node(Kind.SECTION, it.title, meta = "≈ ${fmt(it.tokens)}", file = file, line = it.line, jump = true), parent) }
            }
            if (warnings.isNotEmpty()) toExpand += parent
        }

        fun referenceChildren(parent: javax.swing.tree.DefaultMutableTreeNode, file: File, text: String) {
            val refs = references(file, text, instructions.map { it.file }.toSet())
            if (refs.isEmpty()) return
            val texts = refs.associateWith { read(it) }
            val sum = texts.values.sumOf { (it.length + 3) / 4 }
            val g = node(Node(Kind.INFO, "On demand (${refs.size})", "read only when needed, not loaded", "≈ ${fmt(sum)}", key = "r:" + file.path), parent)
            refs.forEach { rf -> node(Node(Kind.REFERENCE, shortPath(rf), meta = "≈ ${fmt((texts.getValue(rf).length + 3) / 4)}", file = rf, jump = true), g) }
        }

        val instr = node(Node(Kind.GROUP, "Instructions", meta = "≈ ${fmt(instrTokens)} tokens", key = "g:instr"), root)
        toExpand += instr
        val byFile = HashMap<File, javax.swing.tree.DefaultMutableTreeNode>()
        instructions.filter { matches(it.title, it.subtitle, it.text) }.forEach { e ->
            // Importierte Dateien hängen unter der einbindenden Datei.
            val parent = if (e.title == "Imported") instructions.firstOrNull { p -> e.subtitle.endsWith("from ${p.file.name}") }?.let { byFile[it.file] } ?: instr else instr
            val n = node(Node(Kind.FILE, e.title, shortPath(e.file), "≈ ${fmt(e.tokens)}", file = e.file, key = "f:" + e.file.path), parent)
            byFile[e.file] = n
            fileChildren(n, e.file, e.text, listOfNotNull(if (e.chars > 40_000) "Over 40k characters - Claude Code warns that this slows it down" else null), emptyList(), true)
            referenceChildren(n, e.file, e.text)
        }
        if (instructions.isEmpty()) node(Node(Kind.INFO, "No CLAUDE.md found"), instr)

        val mem = node(Node(Kind.GROUP, "Memory", meta = if (index != null) "index ≈ ${fmt(indexTokens)} tokens" else "", key = "g:mem"), root)
        toExpand += mem
        if (memoryDir == null || !memoryDir.isDirectory) node(Node(Kind.INFO, "No memory for this project yet"), mem)
        val indexed = Regex("\\(([^)]+\\.md)\\)").findAll(indexText).map { it.groupValues[1] }.toSet()
        if (index != null && matches("MEMORY.md index", indexText)) {
            val warns = mutableListOf<String>()
            if (indexLines.size > 200) warns += "${indexLines.size} lines - only the first 200 are loaded"
            indexed.filter { !File(memoryDir, it).isFile }.forEach { warns += "Points to missing file $it" }
            val n = node(Node(Kind.FILE, "MEMORY.md", "index", "${indexLines.size} lines", file = index, key = "f:" + index.path), mem)
            fileChildren(n, index, indexText, warns, emptyList(), false)
        }
        val names = mems.map { it.name }.toSet() + mems.map { it.file.nameWithoutExtension }
        mems.filter { matches(it.name, it.description, it.text) }.forEach { m ->
            val warns = mutableListOf<String>()
            if (index != null && m.file.name !in indexed) warns += "Not listed in MEMORY.md - Claude won't find it"
            if (m.description.isEmpty()) warns += "No description in frontmatter"
            val open = m.links.filter { it !in names }
            val infos = if (open.isEmpty()) emptyList() else listOf("Open links: " + open.joinToString(", ") { "[[$it]]" })
            val n = node(Node(Kind.FILE, m.name, m.description, m.type, file = m.file, key = "f:" + m.file.path), mem)
            fileChildren(n, m.file, m.text, warns, infos, false)
        }

        return Built(root, summaryText, toExpand)
    }

    private fun fmt(n: Int) = if (n >= 1000) "%.1fk".format(n / 1000.0) else n.toString()

    private fun open(file: File, line: Int = 0) {
        // Aus dem MouseListener ohne Write-Intent-Lock - deshalb per invokeLater.
        ApplicationManager.getApplication().invokeLater {
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file) ?: return@invokeLater
            OpenFileDescriptor(project, vf, line, 0).navigate(true)
        }
    }

    override fun dispose() {}
}
