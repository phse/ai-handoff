package app.heckit.ide.aihandoff

import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.AbstractAction
import javax.swing.JList
import javax.swing.KeyStroke
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.JTextComponent

/** Ein Slash-Befehl von Claude Code: eingebaut, eigener Befehl, Skill oder aus einem Plugin. */
class SlashCommand(val name: String, val description: String, val source: String)

/**
 * Welche Slash-Befehle es gibt. Eingebaute fest hinterlegt (Claude Code meldet sie nirgends), dazu eigene Befehle
 * (`.claude/commands`), Skills (`.claude/skills`) - global und im Projekt - und die der eingeschalteten Plugins.
 */
object SlashCommands {
    private val BUILT_IN = listOf(
        "clear" to "Start a new conversation", "compact" to "Summarize the conversation to free context",
        "context" to "Show what fills the context", "cost" to "Show token usage and cost", "usage" to "Show plan usage limits",
        "model" to "Switch the model", "effort" to "Set the reasoning effort", "resume" to "Resume an earlier session",
        "rewind" to "Go back to an earlier point", "memory" to "Edit CLAUDE.md memory", "init" to "Create a CLAUDE.md for this project",
        "review" to "Review a pull request", "mcp" to "Manage MCP servers", "agents" to "Manage subagents",
        "permissions" to "Allow or deny tools", "hooks" to "Manage hooks", "config" to "Open settings", "status" to "Show status",
        "plugin" to "Manage plugins", "export" to "Export the conversation", "help" to "Show help", "exit" to "Quit Claude Code",
    ).map { (n, d) -> SlashCommand(n, d, "built-in") }

    @Volatile private var cache: Pair<Long, List<SlashCommand>>? = null

    fun all(project: Project): List<SlashCommand> {
        cache?.let { (at, list) -> if (System.currentTimeMillis() - at < 15_000) return list }
        val home = File(System.getProperty("user.home"), ".claude")
        val base = project.basePath?.let { File(it, ".claude") }
        val found = mutableListOf<SlashCommand>()
        runCatching {
            listOfNotNull(base, home).forEach { dir ->
                val label = if (dir == home) "user" else "project"
                commands(File(dir, "commands"), "", label, found)
                skills(File(dir, "skills"), "", label, found)
            }
            plugins(home, found)
        }
        // Eigene vor eingebauten, doppelte Namen nur einmal.
        val list = (found + BUILT_IN).distinctBy { it.name }.sortedBy { it.name }
        cache = System.currentTimeMillis() to list
        return list
    }

    private fun commands(dir: File, prefix: String, source: String, out: MutableList<SlashCommand>) {
        dir.walkTopDown().maxDepth(3).filter { it.isFile && it.extension == "md" }.forEach { f ->
            val rel = f.relativeTo(dir).path.removeSuffix(".md").replace(File.separatorChar, ':')
            out += SlashCommand(prefix + rel, frontmatter(f)["description"].orEmpty(), source)
        }
    }

    private fun skills(dir: File, prefix: String, source: String, out: MutableList<SlashCommand>) {
        dir.listFiles().orEmpty().filter { File(it, "SKILL.md").isFile }.forEach { d ->
            val fm = frontmatter(File(d, "SKILL.md"))
            if (fm["user-invocable"] == "false") return@forEach
            out += SlashCommand(prefix + (fm["name"] ?: d.name), fm["description"].orEmpty(), source)
        }
    }

    /** Eingeschaltete Plugins aus `installed_plugins.json` und `enabledPlugins` der Einstellungen; Namen als `plugin:befehl`. */
    private fun plugins(home: File, out: MutableList<SlashCommand>) {
        val installed = File(home, "plugins/installed_plugins.json").takeIf { it.isFile } ?: return
        val enabled = runCatching { PlanLimits.readSettings().getAsJsonObject("enabledPlugins") }.getOrNull()
        val plugins = JsonParser.parseString(installed.readText()).asJsonObject.getAsJsonObject("plugins") ?: return
        for (key in plugins.keySet()) {
            if (enabled?.get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean == false) continue
            val path = plugins.getAsJsonArray(key)?.lastOrNull()?.asJsonObject?.get("installPath")?.asString ?: continue
            val name = key.substringBefore('@')
            commands(File(path, "commands"), "$name:", name, out)
            skills(File(path, "skills"), "$name:", name, out)
        }
    }

    /** Einfache Frontmatter-Felder (`key: wert`, einzeilig). */
    private fun frontmatter(f: File): Map<String, String> {
        val lines = runCatching { f.useLines { it.take(40).toList() } }.getOrDefault(emptyList())
        if (lines.firstOrNull()?.trim() != "---") return emptyMap()
        return lines.drop(1).takeWhile { it.trim() != "---" }.mapNotNull { l ->
            val i = l.indexOf(':').takeIf { it > 0 && !l.startsWith(" ") } ?: return@mapNotNull null
            l.substring(0, i).trim() to l.substring(i + 1).trim().trim('"', '\'')
        }.toMap()
    }
}

/** Frühere Sessions des Projekts für `/resume <id>`: neueste zuerst, mit Titel (ai-title) bzw. erstem Prompt. */
object RecentSessions {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, SlashCommand>>()

    fun list(project: Project, limit: Int = 30): List<SlashCommand> {
        val base = project.basePath ?: return emptyList()
        val dir = File(System.getProperty("user.home"), ".claude/projects/" + base.replace(Regex("[^A-Za-z0-9]"), "-"))
        val time = java.text.SimpleDateFormat("dd.MM. HH:mm")
        return dir.listFiles { f -> f.extension == "jsonl" }.orEmpty().sortedByDescending { it.lastModified() }.take(limit).map { f ->
            // Protokolle sind groß - Ergebnis je Datei und Änderungszeit merken, sonst liest jeder Tastendruck alles neu.
            cache[f.path]?.takeIf { it.first == f.lastModified() }?.let { return@map it.second }
            var title: String? = null
            var first: String? = null
            runCatching {
                f.useLines { lines -> lines.forEach { l ->
                    if (l.contains("\"ai-title\"")) Regex("\"aiTitle\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(l)?.let { title = it.groupValues[1] }
                    else if (first == null && l.contains("\"lastPrompt\"")) Regex("\"lastPrompt\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(l)?.let { first = it.groupValues[1] }
                } }
            }
            SlashCommand(f.nameWithoutExtension, (title ?: first ?: "").replace("\\n", " ").take(80), time.format(java.util.Date(f.lastModified())))
                .also { cache[f.path] = f.lastModified() to it }
        }
    }
}

/**
 * Vorschläge beim Tippen von `/` im Eingabefeld: Liste über dem Feld, ↑/↓ wählt, Enter oder Tab übernimmt, Esc schließt.
 * Das Feld behält den Fokus. [enabled]: nur, wenn Slash-Befehle die Session auch erreichen (Terminal).
 */
class SlashCompletion(private val project: Project, private val area: JTextComponent, private val enabled: () -> Boolean) {
    private val model = javax.swing.DefaultListModel<SlashCommand>()
    private val list = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        visibleRowCount = 8
        cellRenderer = object : ColoredListCellRenderer<SlashCommand>() {
            override fun customizeCellRenderer(list: JList<out SlashCommand>, value: SlashCommand, index: Int, selected: Boolean, hasFocus: Boolean) {
                if (resumeMode) {
                    append(value.description.ifBlank { value.name.take(8) })
                    append("  " + value.source, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    return
                }
                append("/" + value.name)
                append("  " + value.source, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                if (value.description.isNotBlank()) append("  " + value.description.take(90), SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { accept() }
        })
    }
    private var popup: JBPopup? = null
    /** `/resume <...>`: Liste der früheren Sessions statt der Befehle. */
    private var resumeMode = false

    val isVisible get() = popup?.isVisible == true

    init {
        area.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = later()
            override fun removeUpdate(e: DocumentEvent) = later()
            override fun changedUpdate(e: DocumentEvent) {}
        })
        fun key(stroke: String, name: String, action: () -> Unit) {
            val previous = area.inputMap.get(KeyStroke.getKeyStroke(stroke))?.let { area.actionMap.get(it) }
            area.inputMap.put(KeyStroke.getKeyStroke(stroke), name)
            area.actionMap.put(name, object : AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent) { if (isVisible) action() else previous?.actionPerformed(e) }
            })
        }
        key("UP", "handoff.slash.up") { move(-1) }
        key("DOWN", "handoff.slash.down") { move(1) }
        key("TAB", "handoff.slash.tab") { accept() }
        key("ESCAPE", "handoff.slash.escape") { hide() }
    }

    /** Für die Enter-Taste des Feldes: true, wenn Enter einen Vorschlag übernommen hat. */
    fun acceptIfVisible(): Boolean { if (!isVisible) return false; accept(); return true }

    private fun later() = SwingUtilities.invokeLater { update() }

    private fun update() {
        val text = area.text
        if (!enabled() || !area.isShowing) return hide()
        Regex("^/resume\\s+(\\S*)$").find(text)?.let { m ->
            val q = m.groupValues[1]
            val sessions = RecentSessions.list(project).filter { q.isEmpty() || it.name.startsWith(q) || it.description.contains(q, ignoreCase = true) }
            if (sessions.isEmpty()) return hide()
            resumeMode = true
            model.clear(); sessions.forEach(model::addElement)
            list.selectedIndex = 0
            if (!isVisible) show() else popup?.pack(true, true)
            return
        }
        resumeMode = false
        val typed = text.takeIf { it.startsWith("/") && it.none { c -> c.isWhitespace() } }?.drop(1) ?: return hide()
        val all = SlashCommands.all(project)
        val matches = all.filter { it.name.startsWith(typed, ignoreCase = true) } +
            all.filter { !it.name.startsWith(typed, ignoreCase = true) && it.name.contains(typed, ignoreCase = true) }
        if (matches.isEmpty() || (matches.size == 1 && matches[0].name == typed)) return hide()
        model.clear(); matches.forEach(model::addElement)
        list.selectedIndex = 0
        if (!isVisible) show() else popup?.pack(true, true)
    }

    private fun show() {
        val p = JBPopupFactory.getInstance().createComponentPopupBuilder(JBScrollPane(list).apply { border = JBUI.Borders.empty() }, null)
            .setRequestFocus(false).setFocusable(false).setCancelOnClickOutside(true).setResizable(false).createPopup()
        popup = p
        val size = p.content.preferredSize
        val width = maxOf(area.width, JBUI.scale(420))
        p.size = java.awt.Dimension(width, size.height)
        // Über dem Feld - es steht am unteren Rand des Reiters.
        p.show(RelativePoint(area, Point(0, -size.height - JBUI.scale(4))))
    }

    fun hide() { popup?.cancel(); popup = null }

    private fun move(delta: Int) {
        if (model.isEmpty) return
        list.selectedIndex = (list.selectedIndex + delta).mod(model.size())
        list.ensureIndexIsVisible(list.selectedIndex)
    }

    private fun accept() {
        val c = list.selectedValue ?: return hide()
        hide()
        if (resumeMode) { area.text = "/resume " + c.name; area.caretPosition = area.text.length; return }
        area.text = "/" + c.name + " "
        area.caretPosition = area.text.length
        // Gleich die Sessions zeigen.
        if (c.name == "resume") later()
    }
}

/**
 * Knopf "/" im Session-Reiter: alle Slash-Befehle mit Beschreibung, Tippen filtert. Enter oder Doppelklick führt den Befehl
 * gleich aus, Tab bzw. "Insert" setzt ihn an der Schreibmarke in den Prompt (z. B. um noch Argumente anzuhängen).
 */
object SlashPicker {
    fun show(project: Project, anchor: javax.swing.JComponent, run: (String) -> Unit, insert: (String) -> Unit) {
        val all = SlashCommands.all(project)
        val model = javax.swing.DefaultListModel<SlashCommand>().apply { all.forEach(::addElement) }
        val list = com.intellij.ui.components.JBList(model).apply {
            selectionMode = javax.swing.ListSelectionModel.SINGLE_SELECTION
            selectedIndex = 0
            cellRenderer = object : ColoredListCellRenderer<SlashCommand>() {
                override fun customizeCellRenderer(list: JList<out SlashCommand>, value: SlashCommand, index: Int, selected: Boolean, hasFocus: Boolean) {
                    append("/" + value.name)
                    if (value.description.isNotBlank()) append("  " + value.description.take(90), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    append("  " + value.source, SimpleTextAttributes.GRAY_SMALL_ATTRIBUTES)
                }
            }
        }
        val filter = com.intellij.ui.SearchTextField(false)
        fun refilter() {
            val q = filter.text.trim().removePrefix("/")
            model.clear()
            all.filter { q.isEmpty() || it.name.contains(q, true) || it.description.contains(q, true) }.forEach(model::addElement)
            if (model.size() > 0) list.selectedIndex = 0
        }
        filter.addDocumentListener(object : com.intellij.ui.DocumentAdapter() { override fun textChanged(e: DocumentEvent) = refilter() })

        var popup: com.intellij.openapi.ui.popup.JBPopup? = null
        fun pick(action: (String) -> Unit) {
            val c = list.selectedValue ?: return
            popup?.cancel()
            action("/" + c.name)
        }
        val runButton = javax.swing.JButton("Run").apply { putClientProperty("JButton.buttonType", "default"); addActionListener { pick(run) } }
        val insertButton = javax.swing.JButton("Insert").apply { toolTipText = "Insert at the cursor in the prompt (Tab)"; addActionListener { pick(insert) } }
        val panel = javax.swing.JPanel(java.awt.BorderLayout(0, com.intellij.util.ui.JBUI.scale(6))).apply {
            border = com.intellij.util.ui.JBUI.Borders.empty(8)
            add(filter, java.awt.BorderLayout.NORTH)
            add(com.intellij.ui.components.JBScrollPane(list).apply { preferredSize = com.intellij.util.ui.JBUI.size(560, 320) }, java.awt.BorderLayout.CENTER)
            add(javax.swing.JPanel(java.awt.BorderLayout()).apply {
                isOpaque = false
                add(com.intellij.ui.components.JBLabel("Enter runs, Tab inserts at the cursor").apply {
                    foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground(); font = com.intellij.util.ui.JBUI.Fonts.smallFont()
                }, java.awt.BorderLayout.WEST)
                add(javax.swing.JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, com.intellij.util.ui.JBUI.scale(6), 0)).apply {
                    isOpaque = false; add(insertButton); add(runButton)
                }, java.awt.BorderLayout.EAST)
            }, java.awt.BorderLayout.SOUTH)
        }
        // Tasten im Suchfeld: ↑/↓ bewegen die Auswahl, Enter führt aus, Tab übernimmt.
        val field = filter.textEditor
        field.focusTraversalKeysEnabled = false
        field.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) {
                when (e.keyCode) {
                    java.awt.event.KeyEvent.VK_DOWN -> { list.selectedIndex = minOf(list.selectedIndex + 1, model.size() - 1); list.ensureIndexIsVisible(list.selectedIndex); e.consume() }
                    java.awt.event.KeyEvent.VK_UP -> { list.selectedIndex = maxOf(list.selectedIndex - 1, 0); list.ensureIndexIsVisible(list.selectedIndex); e.consume() }
                    java.awt.event.KeyEvent.VK_ENTER -> { pick(run); e.consume() }
                    java.awt.event.KeyEvent.VK_TAB -> { pick(insert); e.consume() }
                }
            }
        })
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 2) pick(run) }
        })
        popup = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().createComponentPopupBuilder(panel, field)
            .setTitle("Slash Commands").setRequestFocus(true).setFocusable(true).setMovable(true).setResizable(true)
            .createPopup()
        popup.showUnderneathOf(anchor)
    }
}
