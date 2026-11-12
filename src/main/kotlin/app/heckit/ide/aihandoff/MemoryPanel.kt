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
    private val list = object : JPanel(), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(r: Rectangle, o: Int, d: Int) = JBUI.scale(16)
        override fun getScrollableBlockIncrement(r: Rectangle, o: Int, d: Int) = r.height
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }.apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); border = JBUI.Borders.empty(8) }
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)

    init {
        add(search.apply { border = JBUI.Borders.empty(6, 8, 0, 8) }, BorderLayout.NORTH)
        add(JBScrollPane(list).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        search.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) = rebuild()
        })
        // Änderungen an den Dateien (auch durch Claude) gleich übernehmen - gebündelt, damit Serien nicht flackern.
        project.messageBus.connect(this).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (events.any { it.path.endsWith(".md") }) { alarm.cancelAllRequests(); alarm.addRequest({ rebuild() }, 300) }
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

    /** CLAUDE.md-Dateien in der Reihenfolge, in der Claude Code sie einliest: global, dann vom Home-Ordner abwärts zum Projekt. */
    private fun instructionFiles(): List<Entry> {
        val out = mutableListOf<Entry>()
        File(claudeDir, "CLAUDE.md").takeIf { it.isFile }?.let { out += Entry(it, "Global", "~/.claude/CLAUDE.md", read(it)) }
        val dirs = generateSequence(base) { it.parentFile }.takeWhile { it != home.parentFile }.toList().reversed()
        for (d in dirs) for (name in listOf("CLAUDE.md", ".claude/CLAUDE.md", "CLAUDE.local.md")) {
            val f = File(d, name)
            if (f.isFile && f != File(claudeDir, "CLAUDE.md")) out += Entry(f, if (d == base) "Project" else "Parent folder", shortPath(f), read(f))
        }
        return out
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

    private fun rebuild() {
        list.removeAll()
        val q = search.text.trim().lowercase()
        fun matches(vararg s: String) = q.isEmpty() || s.any { it.lowercase().contains(q) }

        val instructions = instructionFiles()
        val index = memoryDir?.let { File(it, "MEMORY.md") }?.takeIf { it.isFile }
        val indexText = index?.let { read(it) }.orEmpty()
        val indexLines = indexText.lines()
        // Claude Code lädt nur die ersten 200 Zeilen des Index.
        val loadedIndex = indexLines.take(200).joinToString("\n")
        val total = instructions.sumOf { it.tokens } + (loadedIndex.length + 3) / 4
        list.add(hint("Loaded into every session: ≈ ${fmt(total)} tokens"))

        section("Instructions (CLAUDE.md)")
        if (instructions.isEmpty()) list.add(hint("No CLAUDE.md found."))
        instructions.filter { matches(it.title, it.subtitle, it.text) }.forEach { e ->
            val warn = if (e.chars > 40_000) "Over 40k characters - Claude Code warns that this slows it down" else null
            list.add(row(e.file, e.title, e.subtitle, "${e.lines} lines · ≈ ${fmt(e.tokens)} tokens", listOfNotNull(warn), emptyList()))
        }

        section("Memory")
        val mems = memories()
        if (memoryDir == null || (!memoryDir.isDirectory)) list.add(hint("No memory for this project yet."))
        val indexed = Regex("\\(([^)]+\\.md)\\)").findAll(indexText).map { it.groupValues[1] }.toSet()
        if (index != null && matches("MEMORY.md index", indexText)) {
            val warns = mutableListOf<String>()
            if (indexLines.size > 200) warns += "${indexLines.size} lines - only the first 200 are loaded"
            indexed.filter { !File(memoryDir, it).isFile }.forEach { warns += "Index points to missing file $it" }
            list.add(row(index, "MEMORY.md", "Index, loaded every session", "${indexLines.size} lines · ≈ ${fmt((loadedIndex.length + 3) / 4)} tokens", warns, emptyList()))
        }
        val names = mems.map { it.name }.toSet() + mems.map { it.file.nameWithoutExtension }
        mems.filter { matches(it.name, it.description, it.text) }.forEach { m ->
            val warns = mutableListOf<String>()
            if (index != null && m.file.name !in indexed) warns += "Not listed in MEMORY.md - Claude won't find it"
            if (m.description.isEmpty()) warns += "No description in frontmatter"
            val open = m.links.filter { it !in names }
            val infos = if (open.isEmpty()) emptyList() else listOf("Open links: " + open.joinToString(", ") { "[[$it]]" })
            list.add(row(m.file, m.name, m.description.ifEmpty { m.file.name }, m.type, warns, infos))
        }
        list.add(Box.createVerticalGlue())
        list.revalidate(); list.repaint()
    }

    private fun fmt(n: Int) = if (n >= 1000) "%.1fk".format(n / 1000.0) else n.toString()

    private fun section(title: String) {
        list.add(Box.createVerticalStrut(JBUI.scale(10)))
        list.add(JBLabel(title).apply { font = font.deriveFont(Font.BOLD); alignmentX = LEFT_ALIGNMENT; border = JBUI.Borders.emptyBottom(4) })
    }

    private fun hint(text: String) = JBLabel(text).apply { foreground = UIUtil.getContextHelpForeground(); alignmentX = LEFT_ALIGNMENT }

    /** Eine Datei: Titel und Kennzahl oben, grau die Beschreibung, darunter Warnungen (gelb) und Hinweise (grau). */
    private fun row(file: File, title: String, subtitle: String, meta: String, warnings: List<String>, infos: List<String>): JPanel {
        val p = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = LEFT_ALIGNMENT
            border = JBUI.Borders.empty(5, 6)
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = file.absolutePath
        }
        p.add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false; alignmentX = LEFT_ALIGNMENT
            add(JBLabel(title).apply { font = font.deriveFont(Font.BOLD) }, BorderLayout.CENTER)
            add(JBLabel(meta).apply { foreground = UIUtil.getContextHelpForeground(); font = JBUI.Fonts.smallFont() }, BorderLayout.EAST)
        })
        p.add(JBLabel(subtitle).apply { foreground = UIUtil.getContextHelpForeground(); alignmentX = LEFT_ALIGNMENT })
        warnings.forEach { p.add(JBLabel(it, AllIcons.General.Warning, JBLabel.LEFT).apply { alignmentX = LEFT_ALIGNMENT; font = JBUI.Fonts.smallFont() }) }
        infos.forEach { p.add(JBLabel(it).apply { foreground = UIUtil.getContextHelpForeground(); alignmentX = LEFT_ALIGNMENT; font = JBUI.Fonts.smallFont() }) }
        p.maximumSize = Dimension(Int.MAX_VALUE, p.preferredSize.height)
        val hover = JBColor.namedColor("List.hoverBackground", JBColor(0xEDF3FF, 0x2E436E))
        p.addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) { p.isOpaque = true; p.background = hover; p.repaint() }
            override fun mouseExited(e: MouseEvent) { p.isOpaque = false; p.repaint() }
            override fun mouseClicked(e: MouseEvent) = open(file)
        })
        return p
    }

    private fun open(file: File) {
        // Aus dem MouseListener ohne Write-Intent-Lock - deshalb per invokeLater.
        ApplicationManager.getApplication().invokeLater {
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file) ?: return@invokeLater
            OpenFileDescriptor(project, vf).navigate(true)
        }
    }

    override fun dispose() {}
}
