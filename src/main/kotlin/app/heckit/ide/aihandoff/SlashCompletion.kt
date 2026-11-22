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
class SlashCommand(val name: String, val description: String, val source: String, val hint: String = "")

/** Punkt vor dem aktuellen Wert in der Werteliste (/model, /effort). */
private object CurrentDot : javax.swing.Icon {
    override fun getIconWidth() = com.intellij.util.ui.JBUI.scale(12)
    override fun getIconHeight() = com.intellij.util.ui.JBUI.scale(12)
    override fun paintIcon(c: java.awt.Component?, g: java.awt.Graphics, x: Int, y: Int) {
        val g2 = g.create() as java.awt.Graphics2D
        g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = c?.foreground ?: com.intellij.util.ui.UIUtil.getLabelForeground()
        val d = com.intellij.util.ui.JBUI.scale(6)
        g2.fillOval(x + (iconWidth - d) / 2, y + (iconHeight - d) / 2, d, d)
        g2.dispose()
    }
}

/**
 * Werte für Befehle, die ohne Wert eine Auswahl im Terminal öffnen (im Session-Reiter unsichtbar - die Session wirkte
 * dann hängend, 2026-11-18). Werte aus `claude --help` und dem Programm von Claude Code 2.1.327.
 */
object SlashArguments {
    private fun v(vararg p: Pair<String, String>) = p.map { SlashCommand(it.first, it.second, "") }

    /** Feste Werte je Befehl (argumentHint der eingebauten Befehle in Claude Code 2.1.327). */
    val fixed: Map<String, List<SlashCommand>> = mapOf(
        "effort" to v("low" to "Fast, little thinking", "medium" to "Balanced", "high" to "More thinking",
            "xhigh" to "Even more thinking", "max" to "Deepest thinking, slowest"),
        "model" to v("default" to "The recommended model for your plan", "fable" to "Latest Fable", "opus" to "Latest Opus",
            "sonnet" to "Latest Sonnet", "haiku" to "Latest Haiku, fastest", "opusplan" to "Opus in plan mode, Sonnet otherwise"),
    )

    /** Diese öffnen ohne Wert eine Auswahl im Terminal (im Session-Reiter unsichtbar - die Session wirkte hängend). */
    private val pickers = setOf("effort", "model")

    /** Hinweise der eingebauten Befehle (argumentHint in Claude Code 2.1.327). */
    val hints: Map<String, String> = mapOf(
        "add-dir" to "<path>", "autocompact" to "[auto|<tokens>]", "background" to "[prompt]", "branch" to "[name]",
        "btw" to "[question]", "bug" to "[report]", "cd" to "<path>", "clear" to "[name]",
        "compact" to "<optional custom summarization instructions>", "config" to "[key=value]", "context" to "[all]",
        "debug" to "[issue description]", "effort" to "<level>", "export" to "[filename]", "feedback" to "[report]",
        "focus" to "[on|off]", "fork" to "[prompt]", "goal" to "[<condition> | clear]", "help" to "[command]", "ide" to "[open]",
        "import" to "[codex|gemini|cursor] [--dry-run]", "mcp" to "[reconnect|enable|disable [<server>|all]]", "model" to "<model>",
        "output-style" to "[style]", "plan" to "[open|<description>]", "rename" to "[name]", "tui" to "[classic|fullscreen]",
        "voice" to "[hold|tap|off]",
    )

    /**
     * Befehle, die ohne Wert einen Dialog im Terminal öffnen (Verwaltung, Auswahl) und bis zum Schließen nichts ins
     * Protokoll schreiben - im Session-Reiter blieben sie bei "Running…" (2026-11-18: /mcp). Nach dem Senden kommt das
     * Terminal nach vorn. Liste nach Bedienung von Claude Code 2.1.327, nicht vollständig nachgelesen.
     */
    private val dialogs = setOf("mcp", "config", "permissions", "agents", "hooks", "memory", "plugin", "output-style",
        "theme", "rewind", "export", "status", "usage", "login", "ide", "doctor", "tui", "voice", "help")

    fun opensDialog(text: String): Boolean = text.trim().takeIf { it.startsWith("/") }?.removePrefix("/")?.let { it in dialogs } == true

    /** Befehl ohne Wert, der eine Auswahl im Terminal öffnen würde. */
    fun needsValue(text: String): String? = text.trim().takeIf { it.startsWith("/") }?.removePrefix("/")?.takeIf { it in pickers }

    /** Folgen nach diesem Wert weitere (Unterordner, Server nach reconnect)? Dann die Liste gleich wieder zeigen. */
    fun continues(cmd: String, done: List<String>) = cmd in setOf("cd", "add-dir") || (cmd == "mcp" && done.isEmpty())

    /**
     * Vorschläge für das Wort an der Schreibmarke: [done] = schon getippte Werte davor, [query] = das angefangene Wort.
     * Null, wenn der Befehl keine Werte kennt.
     */
    fun values(project: Project, cmd: String, done: List<String>, query: String, workDir: String?): List<SlashCommand>? {
        val all: List<SlashCommand> = when {
            done.isEmpty() && cmd in fixed -> fixed.getValue(cmd)
            cmd == "mcp" && done.isEmpty() -> v("reconnect" to "Reconnect a server", "enable" to "Enable a server", "disable" to "Disable a server")
            cmd == "mcp" && done.size == 1 && done[0] in setOf("reconnect", "enable", "disable") ->
                mcpServers(workDir).map { SlashCommand(it, "MCP server", "") } + v("all" to "All servers")
            cmd == "help" && done.isEmpty() -> SlashCommands.all(project, workDir).map { SlashCommand(it.name, it.description, "") }
            (cmd == "cd" || cmd == "add-dir") && done.isEmpty() -> return folders(query, workDir)
            // Sonst aus dem argument-hint des Befehls oder Skills: feste Wörter wie in "[on|off]" - so bekommen auch
            // eigene Skills Vorschläge, ohne dass Handoff sie kennt (2026-11-18).
            else -> fromHint(SlashCommands.all(project, workDir).firstOrNull { it.name == cmd }?.hint.orEmpty(), done.size) ?: return null
        }
        return all.filter { it.name.startsWith(query, ignoreCase = true) }
    }

    /**
     * Feste Werte aus einem argument-hint: die Gruppe an Position [index] (Gruppen durch Leerzeichen außerhalb von
     * Klammern getrennt), Alternativen durch `|`. Platzhalter wie `<path>` und Freitext fallen weg.
     * `[auto|<tokens>]` → auto, `[codex|gemini|cursor] [--dry-run]` → codex, gemini, cursor bzw. danach --dry-run.
     */
    fun fromHint(hint: String, index: Int): List<SlashCommand>? {
        val groups = mutableListOf<String>()
        var depth = 0; val cur = StringBuilder()
        for (ch in hint.trim()) {
            when {
                ch == '[' || ch == '<' || ch == '(' -> { depth++; cur.append(ch) }
                ch == ']' || ch == '>' || ch == ')' -> { depth--; cur.append(ch) }
                ch == ' ' && depth == 0 -> { if (cur.isNotEmpty()) groups += cur.toString(); cur.clear() }
                else -> cur.append(ch)
            }
        }
        if (cur.isNotEmpty()) groups += cur.toString()
        val g = groups.getOrNull(index) ?: return null
        // Nur Gruppen in eckigen Klammern oder mit | sind Auswahlen; "<path>" oder "[prompt]" (ein Wort) ist Freitext.
        val inner = g.removeSurrounding("[", "]")
        if (!inner.contains('|') && !inner.startsWith("--")) return null
        val words = inner.split('|').map { it.trim() }.filter { Regex("^-{0,2}[A-Za-z0-9][\\w.-]*$").matches(it) }
        return words.takeIf { it.isNotEmpty() }?.map { SlashCommand(it, "", "") }
    }

    /** MCP-Server aus `~/.claude.json` (global und für den Ordner) und `.mcp.json` im Ordner. */
    private fun mcpServers(workDir: String?): List<String> = runCatching {
        val names = sortedSetOf<String>()
        fun keys(o: com.google.gson.JsonObject?) { o?.getAsJsonObject("mcpServers")?.keySet()?.let(names::addAll) }
        val home = File(System.getProperty("user.home"))
        File(home, ".claude.json").takeIf { it.isFile }?.let { JsonParser.parseString(it.readText()).asJsonObject }?.let { root ->
            keys(root)
            workDir?.let { keys(root.getAsJsonObject("projects")?.getAsJsonObject(it)) }
        }
        workDir?.let { File(it, ".mcp.json") }?.takeIf { it.isFile }?.let { keys(JsonParser.parseString(it.readText()).asJsonObject) }
        names.toList()
    }.getOrDefault(emptyList())

    /** Unterordner zum angefangenen Pfad (relativ zum Arbeitsordner, `~` für das Home), Name mit `/` am Ende. */
    private fun folders(query: String, workDir: String?): List<SlashCommand> {
        val home = System.getProperty("user.home")
        val expanded = if (query.startsWith("~")) home + query.removePrefix("~") else query
        val parentPart = expanded.substringBeforeLast('/', "")
        val prefix = expanded.substringAfterLast('/')
        val dir = when {
            expanded.startsWith("/") -> File(parentPart.ifEmpty { "/" })
            else -> File(workDir ?: return emptyList(), parentPart)
        }
        val typedParent = query.substringBeforeLast('/', "").let { if (query.contains('/')) "$it/" else "" }
        return dir.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith(prefix, ignoreCase = !com.intellij.openapi.util.SystemInfoRt.isFileSystemCaseSensitive) }
            .filter { prefix.startsWith(".") || !it.name.startsWith(".") }
            .sortedBy { it.name.lowercase() }.take(50)
            .map { SlashCommand(typedParent + it.name + "/", "", "") }
            // Eine Ebene höher als erster Eintrag, solange nur Punkte getippt sind.
            .let { list -> if (prefix.length < 2 && prefix.all { it == '.' } && dir.parentFile != null) listOf(SlashCommand("$typedParent../", "Parent folder", "")) + list else list }
    }
}

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
        "rename" to "Rename the session", "add-dir" to "Add a working directory", "doctor" to "Check the installation",
        "login" to "Sign in", "logout" to "Sign out", "ide" to "Connect to an IDE", "statusline" to "Set up the status line",
        "release-notes" to "Show release notes", "fast" to "Toggle fast mode", "btw" to "Ask a side question",
        "cd" to "Change the working directory", "focus" to "Toggle focus mode", "tui" to "Switch the terminal UI",
        "voice" to "Set up voice input", "autocompact" to "Set when to compact automatically", "goal" to "Set a goal for the session",
        "import" to "Import settings from another tool", "plan" to "Plan mode", "output-style" to "Choose the output style",
        "branch" to "Branch the conversation", "fork" to "Fork the session", "background" to "Run a prompt in the background",
        "debug" to "Debug an issue", "feedback" to "Send feedback",
    ).map { (n, d) -> SlashCommand(n, d, "built-in", SlashArguments.hints[n].orEmpty()) }

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<SlashCommand>>>()

    /**
     * [dir] = Arbeitsordner der Session (sonst der Projektordner): Projekt-Befehle und -Skills aus seinem `.claude` und
     * denen der Ordner darüber bis vor das Home - so passt die Liste zur Session, auch nach `/cd` (2026-11-18).
     * Annahme, nicht nachgelesen: Claude Code sucht Projekt-Skills ebenso aufwärts wie CLAUDE.md.
     */
    fun all(project: Project, dir: String? = null): List<SlashCommand> {
        val start = (dir ?: project.basePath)?.trimEnd('/')
        cache[start.orEmpty()]?.let { (at, list) -> if (System.currentTimeMillis() - at < 15_000) return list }
        val homeDir = File(System.getProperty("user.home"))
        val home = File(homeDir, ".claude")
        val local = generateSequence(start?.let(::File)) { it.parentFile }
            .takeWhile { it != homeDir && it.parentFile != null }.map { File(it, ".claude") }.filter { it.isDirectory }.toList()
        val found = mutableListOf<SlashCommand>()
        runCatching {
            (local + home).forEach { dir ->
                val label = if (dir == home) "user" else "project"
                commands(File(dir, "commands"), "", label, found)
                skills(File(dir, "skills"), "", label, found)
            }
            plugins(home, found)
        }
        // Eigene vor eingebauten, doppelte Namen nur einmal.
        val list = (found + BUILT_IN).distinctBy { it.name }.sortedBy { it.name }
        cache[start.orEmpty()] = System.currentTimeMillis() to list
        return list
    }

    private fun commands(dir: File, prefix: String, source: String, out: MutableList<SlashCommand>) {
        dir.walkTopDown().maxDepth(3).filter { it.isFile && it.extension == "md" }.forEach { f ->
            val rel = f.relativeTo(dir).path.removeSuffix(".md").replace(File.separatorChar, ':')
            val fm = frontmatter(f)
            out += SlashCommand(prefix + rel, fm["description"].orEmpty(), source, fm["argument-hint"].orEmpty())
        }
    }

    private fun skills(dir: File, prefix: String, source: String, out: MutableList<SlashCommand>) {
        dir.listFiles().orEmpty().filter { File(it, "SKILL.md").isFile }.forEach { d ->
            val fm = frontmatter(File(d, "SKILL.md"))
            if (fm["user-invocable"] == "false") return@forEach
            out += SlashCommand(prefix + (fm["name"] ?: d.name), fm["description"].orEmpty(), source, fm["argument-hint"].orEmpty())
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

    /** Arbeitsordner je Session-ID (aus dem Ordnernamen nicht sicher rückrechenbar - Claude Code ersetzt jedes Sonderzeichen). */
    val cwds = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Sessions des Projektordners und des Arbeitsordners der laufenden Session ([workDir]) - eine Session aus PhpStorm Home
     * arbeitet meist in einem anderen Ordner als dem Projekt (2026-11-17: /resume zeigte dort nichts).
     */
    fun list(project: Project, workDir: String? = null, limit: Int = 30): List<SlashCommand> {
        val time = java.text.SimpleDateFormat("dd.MM. HH:mm")
        val files = listOfNotNull(project.basePath, workDir).map { it.trimEnd('/') }.distinct().flatMap { base ->
            val dir = File(System.getProperty("user.home"), ".claude/projects/" + base.replace(Regex("[^A-Za-z0-9]"), "-"))
            dir.listFiles { f -> f.extension == "jsonl" }.orEmpty().onEach { cwds.putIfAbsent(it.nameWithoutExtension, base) }.toList()
        }
        return files.sortedByDescending { it.lastModified() }.take(limit).map { f ->
            // Protokolle sind groß - Ergebnis je Datei und Änderungszeit merken, sonst liest jeder Tastendruck alles neu.
            cache[f.path]?.takeIf { it.first == f.lastModified() }?.let { return@map it.second }
            var title: String? = null
            var first: String? = null
            var custom = false
            runCatching {
                f.useLines { lines -> lines.forEach { l ->
                    if (l.contains("\"custom-title\"")) Regex("\"customTitle\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(l)?.let { title = it.groupValues[1]; custom = true }
                    else if (l.contains("\"ai-title\"")) { if (!custom) Regex("\"aiTitle\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(l)?.let { title = it.groupValues[1] } }
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
class SlashCompletion(
    private val project: Project,
    private val area: JTextComponent,
    /** Kurze Marke für eine per `@` gewählte Datei (geht beim Senden als `@pfad` raus). */
    private val fileToken: (String) -> String = { "@$it" },
    private val enabled: () -> Boolean,
    /** Arbeitsordner der Session - für `/resume` (Sessions dieses Ordners). */
    private val workDir: () -> String? = { null },
    /** Aktueller Wert eines Befehls (/model, /effort) laut Statuszeile der Session - in der Werteliste markiert. */
    private val currentValue: (String) -> String? = { null },
    /** Hinweis unter dem Feld, was ein Befehl als Wert erwartet; null = keiner. */
    private val onHint: (String?) -> Unit = {},
) {
    private val model = javax.swing.DefaultListModel<SlashCommand>()
    private val list = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        visibleRowCount = 8
        cellRenderer = object : ColoredListCellRenderer<SlashCommand>() {
            override fun customizeCellRenderer(list: JList<out SlashCommand>, value: SlashCommand, index: Int, selected: Boolean, hasFocus: Boolean) {
                if (fileMode) {
                    append(value.name)
                    append("  " + value.description, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    return
                }
                if (argMode != null) {
                    // Aktueller Wert: Punkt links vor dem Namen, die anderen mit gleich breitem Leer-Icon - so stehen die Namen bündig.
                    icon = if (current == value.name) CurrentDot else com.intellij.util.ui.EmptyIcon.create(CurrentDot.iconWidth, CurrentDot.iconHeight)
                    append(value.name)
                    append("  " + value.description, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    return
                }
                if (resumeMode) {
                    append(value.description.ifBlank { value.name.take(8) })
                    append("  " + value.source, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    return
                }
                append("/" + value.name)
                if (value.hint.isNotBlank()) append(" " + value.hint, SimpleTextAttributes.GRAYED_ATTRIBUTES)
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
    /** Werteliste für /model bzw. /effort: der Befehl. */
    private var argMode: String? = null
    private var argDone: List<String> = emptyList()
    private var current: String? = null
    /** `@…`: offene Editor-Dateien statt Befehle; [SlashCommand.source] hält dann den Pfad. */
    private var fileMode = false
    /** Anfang (Position des `/`) des Befehls, für den die Liste gerade gilt. */
    private var slashAt = 0
    /** Per Esc geschlossen: für dieses `/` nicht wieder öffnen, bis ein neues getippt wird. */
    private var dismissedAt = -1

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
        key("ESCAPE", "handoff.slash.escape") { dismissedAt = slashAt; hide() }
    }

    /** Für die Enter-Taste des Feldes: true, wenn Enter einen Vorschlag übernommen hat. */
    fun acceptIfVisible(): Boolean { if (!isVisible) return false; accept(); return true }

    private fun later() = SwingUtilities.invokeLater { update() }

    private fun update() {
        val text = area.text
        if (!area.isShowing) return hide()
        if (enabled()) Regex("^/resume\\s+(.*)$").find(text)?.let { m ->
            val q = m.groupValues[1].trim()
            val sessions = RecentSessions.list(project, workDir()).filter { q.isEmpty() || it.name.startsWith(q) || it.description.contains(q, ignoreCase = true) }
            if (sessions.isEmpty()) return hide()
            resumeMode = true; slashAt = 0
            model.clear(); sessions.forEach(model::addElement)
            list.selectedIndex = 0
            if (!isVisible) show() else place()
            return
        }
        resumeMode = false
        argMode = null
        argDone = emptyList()
        // Hinweis, was der Befehl erwartet (argument-hint), solange nach "/befehl " noch nichts steht.
        onHint(Regex("^/([\\w:-]+) $").find(text)?.groupValues?.get(1)?.let { n ->
            SlashCommands.all(project, workDir()).firstOrNull { it.name == n }?.hint?.takeIf { it.isNotBlank() }?.let { "/$n $it" }
        })
        Regex("^/([\\w:-]+) ([^\n]*)$").find(text)?.let { m ->
            if (area.caretPosition != text.length) return@let
            val cmd = m.groupValues[1]
            val words = m.groupValues[2].split(' ')
            val q = words.last()
            val done = words.dropLast(1).filter { it.isNotEmpty() }
            val hits = SlashArguments.values(project, cmd, done, q, workDir()) ?: return@let
            if (hits.isEmpty() || (hits.size == 1 && hits[0].name == q)) return hide()
            argMode = cmd; argDone = done; slashAt = 0
            current = if (done.isEmpty()) currentValue(cmd) else null
            model.clear(); hits.forEach(model::addElement)
            list.selectedIndex = 0
            if (!isVisible) show() else place()
            return
        }
        fileMode = false
        // `@` am Anfang oder nach Leerraum: offene Dateien als Kontext (geht auch ohne Terminal - es ist nur Text).
        run {
            val caret = area.caretPosition.coerceIn(0, text.length)
            val start = text.lastIndexOf('@', caret - 1).takeIf { it >= 0 } ?: return@run
            if (start > 0 && !text[start - 1].isWhitespace()) return@run
            val typed = text.substring(start + 1, caret)
            if (typed.any { it.isWhitespace() }) return@run
            if (start == dismissedAt) return hide()
            val open = openFiles().filter { typed.isEmpty() || it.name.contains(typed, ignoreCase = true) || it.description.contains(typed, ignoreCase = true) }
                .sortedBy { if (it.name.startsWith(typed, ignoreCase = true)) 0 else 1 }
            // Ab zwei Buchstaben auch Dateien aus dem ganzen Projekt, unter den offenen.
            val files = if (typed.length < 2) open else (open + sessionFiles(typed)).distinctBy { it.source }
            if (files.isEmpty()) return hide()
            fileMode = true; slashAt = start
            model.clear(); files.forEach(model::addElement)
            list.selectedIndex = 0
            if (!isVisible) show() else place()
            return
        }
        if (!enabled()) return hide()
        // Auch mitten im Prompt: `/` am Anfang oder nach einem Leerraum, bis zur Schreibmarke ohne Leerzeichen. Pfade
        // (/Users/…) finden keinen Befehl und schließen die Liste von selbst.
        val caret = area.caretPosition.coerceIn(0, text.length)
        val start = text.lastIndexOf('/', caret - 1).takeIf { it >= 0 } ?: return hide().also { dismissedAt = -1 }
        if (start > 0 && !text[start - 1].isWhitespace()) return hide()
        val typed = text.substring(start + 1, caret)
        if (typed.any { it.isWhitespace() || it == '/' }) return hide()
        if (start != dismissedAt) dismissedAt = -1 else return hide()
        slashAt = start
        val all = SlashCommands.all(project, workDir())
        val matches = all.filter { it.name.startsWith(typed, ignoreCase = true) } +
            all.filter { !it.name.startsWith(typed, ignoreCase = true) && it.name.contains(typed, ignoreCase = true) }
        // Befehl ganz getippt ("/mcp" ohne Leerzeichen): gleich seine Werte zeigen - sonst schloss die Liste und Enter
        // schickte den nackten Befehl (2026-11-18). Esc schließt die Liste, dann geht er ohne Wert raus.
        if (start == 0 && caret == text.length && matches.any { it.name == typed }) {
            SlashArguments.values(project, typed, emptyList(), "", workDir())?.takeIf { it.isNotEmpty() }?.let { hits ->
                argMode = typed; argDone = emptyList(); slashAt = 0
                current = currentValue(typed)
                model.clear(); hits.forEach(model::addElement)
                list.selectedIndex = 0
                if (!isVisible) show() else place()
                return
            }
        }
        if (matches.isEmpty() || (matches.size == 1 && matches[0].name == typed)) return hide()
        model.clear(); matches.forEach(model::addElement)
        list.selectedIndex = 0
        if (!isVisible) show() else place()
    }

    private fun show() {
        val p = JBPopupFactory.getInstance().createComponentPopupBuilder(JBScrollPane(list).apply { border = JBUI.Borders.empty() }, null)
            .setRequestFocus(false).setFocusable(false).setCancelOnClickOutside(true).setResizable(false).createPopup()
        popup = p
        val (at, size) = bounds(p)
        p.size = size
        p.show(RelativePoint(area, at))
    }

    /**
     * Größe und Lage nach jedem Tippen neu: Die Liste steht direkt über der Zeile mit dem `/` bzw. `@` und beginnt an
     * dessen Spalte. Nur die Höhe anzupassen ließ sie oben stehen - beim Kürzen entstand eine Lücke zum Text (2026-11-16).
     */
    private fun place() {
        val p = popup ?: return
        val (at, size) = bounds(p)
        p.size = size
        p.setLocation(RelativePoint(area, at).screenPoint)
    }

    private fun bounds(p: JBPopup): Pair<Point, java.awt.Dimension> {
        list.visibleRowCount = model.size().coerceIn(1, 8)
        // popup.size setzt die bevorzugte Größe des Inhalts fest - ohne Zurücksetzen bliebe die Liste so hoch wie beim
        // ersten Zeigen, auch wenn Tippen sie auf wenige Treffer kürzt (2026-11-17).
        p.content.preferredSize = null
        list.invalidate()
        val height = p.content.preferredSize.height
        val width = minOf(maxOf(JBUI.scale(420), list.preferredSize.width + JBUI.scale(24)), maxOf(area.width, JBUI.scale(420)), JBUI.scale(640))
        val r = runCatching { area.modelToView2D(slashAt.coerceIn(0, area.document.length)) }.getOrNull()
        val x = ((r?.x ?: 0.0).toInt() - JBUI.scale(4)).coerceIn(0, maxOf(0, area.width - width))
        val y = (r?.y ?: 0.0).toInt() - height - JBUI.scale(4)
        return Point(x, y) to java.awt.Dimension(width, height)
    }

    fun hide() { popup?.cancel(); popup = null }

    /** Offene Editor-Dateien (zuletzt gewählte zuerst), ohne Handoff-eigene Reiter: Name, Ordner relativ zum Projekt, Pfad. */
    private fun openFiles(): List<SlashCommand> {
        val fem = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
        val selected = fem.selectedFiles.toSet()
        val base = project.basePath
        return fem.openFiles.filter { it.isInLocalFileSystem }.sortedBy { if (it in selected) 0 else 1 }.map { f ->
            val dir = f.parent?.path.orEmpty()
            val shown = if (base != null && dir.startsWith(base)) dir.removePrefix(base).removePrefix("/").ifEmpty { "." } else dir.replaceFirst(System.getProperty("user.home"), "~")
            SlashCommand(f.name, shown, f.path)
        }.distinctBy { it.source }
    }

    /**
     * Projektdateien, deren Name [typed] enthält (Namensindex, nur Projektinhalt): Treffer am Namensanfang zuerst, dann
     * kürzere Namen; höchstens 30. Während der Indizierung keine Treffer.
     */
    /**
     * Dateien für `@` aus dem Arbeitsordner der Session und seinen Unterordnern, Pfade relativ zu ihm (so versteht sie
     * Claude Code). Liegt der Ordner im Projekt, über den Namensindex; außerhalb (Ordner-Session, PhpStorm Home, nach
     * `/cd ..`) gibt es keinen Index - dann selbst suchen, flach und ohne Build-/Abhängigkeitsordner (2026-11-18).
     */
    private fun sessionFiles(typed: String): List<SlashCommand> {
        val base = project.basePath?.trimEnd('/')
        val dir = workDir()?.trimEnd('/') ?: base ?: return emptyList()
        fun shown(path: String) = File(path).parent.orEmpty().let { p ->
            if (p == dir) "." else if (p.startsWith("$dir/")) p.removePrefix("$dir/") else p.replaceFirst(System.getProperty("user.home"), "~")
        }
        if (base != null && (dir == base || dir.startsWith("$base/")))
            return projectFiles(typed, 300).filter { it.source == dir || it.source.startsWith("$dir/") }.take(30).map { SlashCommand(it.name, shown(it.source), it.source) }
        val skip = setOf("node_modules", ".git", "build", "dist", "out", "target", "vendor", ".gradle", ".idea", ".intellijPlatform", "__pycache__", ".venv")
        val until = System.currentTimeMillis() + 300
        return File(dir).walkTopDown().maxDepth(6)
            .onEnter { it.path == dir || (it.name !in skip && !it.name.startsWith(".")) }
            .takeWhile { System.currentTimeMillis() < until }
            .filter { it.isFile && it.name.contains(typed, ignoreCase = true) }
            .take(200).toList()
            .sortedWith(compareBy({ if (it.name.startsWith(typed, ignoreCase = true)) 0 else 1 }, { it.name.length }))
            .take(30).map { SlashCommand(it.name, shown(it.path), it.path) }
    }

    private fun projectFiles(typed: String, limit: Int = 30): List<SlashCommand> = runCatching {
        com.intellij.openapi.application.ApplicationManager.getApplication().runReadAction(com.intellij.openapi.util.Computable<List<SlashCommand>> {
            if (com.intellij.openapi.project.DumbService.isDumb(project)) return@Computable emptyList()
            val scope = com.intellij.psi.search.GlobalSearchScope.projectScope(project)
            val names = mutableListOf<String>()
            com.intellij.psi.search.FilenameIndex.processAllFileNames({ n -> if (n.contains(typed, ignoreCase = true)) names += n; names.size < 500 }, scope, null)
            val base = project.basePath
            names.sortedWith(compareBy({ if (it.startsWith(typed, ignoreCase = true)) 0 else 1 }, { it.length }, { it }))
                .asSequence()
                .flatMap { n -> com.intellij.psi.search.FilenameIndex.getVirtualFilesByName(n, scope).asSequence().filter { !it.isDirectory && it.isInLocalFileSystem } }
                .take(limit)
                .map { f ->
                    val dir = f.parent?.path.orEmpty()
                    SlashCommand(f.name, if (base != null && dir.startsWith(base)) dir.removePrefix(base).removePrefix("/").ifEmpty { "." } else dir, f.path)
                }.toList()
        })
    }.getOrDefault(emptyList())

    private fun move(delta: Int) {
        if (model.isEmpty) return
        list.selectedIndex = (list.selectedIndex + delta).mod(model.size())
        list.ensureIndexIsVisible(list.selectedIndex)
    }

    private fun accept() {
        val c = list.selectedValue ?: return hide()
        hide()
        if (resumeMode) { area.text = "/resume " + c.name; area.caretPosition = area.text.length; return }
        argMode?.let { cmd ->
            // Ordner bleiben ohne Leerzeichen offen für den nächsten Unterordner.
            val more = SlashArguments.continues(cmd, argDone)
            area.text = "/$cmd " + (argDone + c.name).joinToString(" ") + (if (c.name.endsWith("/")) "" else " ")
            area.caretPosition = area.text.length
            if (more) later()
            return
        }
        if (fileMode) {
            val caret = area.caretPosition.coerceIn(slashAt, area.text.length)
            val insert = fileToken(c.source) + " "
            area.text = area.text.substring(0, slashAt) + insert + area.text.substring(caret).trimStart(' ')
            area.caretPosition = slashAt + insert.length
            return
        }
        val caret = area.caretPosition.coerceIn(slashAt, area.text.length)
        val insert = "/" + c.name + " "
        area.text = area.text.substring(0, slashAt) + insert + area.text.substring(caret).trimStart(' ')
        area.caretPosition = slashAt + insert.length
        // Gleich die Sessions zeigen.
        later()
    }
}

/**
 * Knopf "/" im Session-Reiter: alle Slash-Befehle mit Beschreibung, Tippen filtert. Enter oder Doppelklick führt den Befehl
 * gleich aus, Tab bzw. "Insert" setzt ihn an der Schreibmarke in den Prompt (z. B. um noch Argumente anzuhängen).
 */
object SlashPicker {
    fun show(project: Project, anchor: javax.swing.JComponent, run: (String) -> Unit, insert: (String) -> Unit, dir: String? = null) {
        val all = SlashCommands.all(project, dir)
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
        val runButton = javax.swing.JButton("Run").apply { primary(); addActionListener { pick(run) } }
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
