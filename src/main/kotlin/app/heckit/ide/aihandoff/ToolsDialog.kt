package app.heckit.ide.aihandoff

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import java.io.File
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Handoff-Werkzeuge an- und abschalten, je Werkzeug zwei Schalter:
 * - "In CLAUDE.md": Anweisung, wann Claude es nutzen soll. Claude ändert die Datei selbst (Prompt an eine wartende
 *   Session oder in die Zwischenablage) - die Dateien sind von Hand geschrieben, das Plugin schreibt nicht hinein.
 * - "Allowed": ohne Haken steht `mcp__<server>__<tool>` unter `permissions.deny` in `~/.claude/settings.json`;
 *   Claude Code kann das Werkzeug dann gar nicht aufrufen. Das schreibt das Plugin direkt.
 */
class ToolsDialog(private val project: Project, private val calls: Map<String, Int>, private val periodLabel: String) : DialogWrapper(project) {
    /** Eine Funktion von Handoff mit ihren Werkzeugen (Aufgaben-Changelist hat zwei), wie in der Anleitung beschrieben. */
    private class Group(val title: String, val description: String, val icon: javax.swing.Icon, val tools: List<String>)

    private val groups = listOf(
        Group("Commands you run yourself", "Deploys, logins, releases: Claude proposes the command, you run, edit or dismiss it.",
            com.intellij.icons.AllIcons.Actions.Execute, listOf("handoff_propose_command")),
        Group("Command sequences", "Several commands as one card (verify, tag, push), stops at the first failure.",
            com.intellij.icons.AllIcons.Actions.RunAll, listOf("handoff_propose_sequence")),
        Group("Questions with options", "Claude asks as a card instead of in the terminal: one or several answers, plus your own.",
            com.intellij.icons.AllIcons.General.ContextHelp, listOf("handoff_ask")),
        Group("Images to review or choose", "Icons, layouts, screenshots: approve one or pick a variant, mark areas for Claude.",
            com.intellij.icons.AllIcons.FileTypes.Image, listOf("handoff_show_images")),
        Group("Review with line notes", "Claude asks you to check its changes in the diff; you approve or send notes on lines.",
            com.intellij.icons.AllIcons.Actions.Diff, listOf("handoff_request_review")),
        Group("Notes from the editor", "Claude fetches notes you left on lines without a review.",
            com.intellij.icons.AllIcons.General.Balloon, listOf("handoff_get_notes")),
        Group("One changelist per task", "Each task's changes land in their own changelist \"Claude: <title>\" to review and commit.",
            com.intellij.icons.AllIcons.Vcs.Changelist, listOf("handoff_begin_task", "handoff_end_task")),
        Group("Waiting for your answer", "Claude fetches the outcome of every card. Needed by all features above.",
            com.intellij.icons.AllIcons.Actions.Refresh, listOf("handoff_wait_for_result")),
    ).let { known -> known + Features.allTools.filter { t -> known.none { t in it.tools } }.map { Group(it, "", com.intellij.icons.AllIcons.Nodes.Plugin, listOf(it)) } }

    private val tools = groups.flatMap { it.tools }
    private val mentioned = Features.mentioned(project)
    private val denied = Permissions.denied()
    private val inMd = groups.associateWith { g -> JBCheckBox("In CLAUDE.md", g.tools.all { it in mentioned }) }
    private val allowed = groups.associateWith { g -> JBCheckBox("Allowed", g.tools.none { it in denied }) }
    private val target get() = WaitingSessions.getInstance(project).let { w -> w.sessions.firstOrNull { it.listening && it.id !in w.sent } }
    private val hint = JBLabel().apply { foreground = UIUtil.getContextHelpForeground(); font = JBUI.Fonts.smallFont() }

    private fun mdChanges() = groups.filter { g -> inMd.getValue(g).isSelected != g.tools.all { it in mentioned } }

    private fun prompt(): String? {
        val add = mdChanges().filter { inMd.getValue(it).isSelected }.flatMap { it.tools }
        val remove = mdChanges().filter { !inMd.getValue(it).isSelected }.flatMap { it.tools }
        if (add.isEmpty() && remove.isEmpty()) return null
        return buildString {
            append("Please update the Handoff instructions in my CLAUDE.md (global ~/.claude/CLAUDE.md or the project's, wherever the ")
            append("Handoff section is). Keep the file's style and language and show me what you changed.\n")
            if (add.isNotEmpty()) {
                append("\nAdd instructions for: ${add.joinToString()}. Based on these lines from the Handoff guide:\n\n")
                Features.all.filter { f -> f.tools.any { it in add } }.forEach { append(it.text).append("\n") }
            }
            if (remove.isNotEmpty()) append("\nRemove the instructions that tell you to use: ${remove.joinToString()}. Keep the rest.\n")
        }
    }

    private val applyAction = object : AbstractAction("Apply") {
        override fun actionPerformed(e: ActionEvent) {
            Permissions.setDenied(groups.filter { !allowed.getValue(it).isSelected }.flatMap { it.tools }.toSet(), tools.toSet())
            prompt()?.let { p ->
                val s = target
                if (s != null) WaitingSessions.getInstance(project).reply(s, p)
                else CopyPasteManager.getInstance().setContents(StringSelection(p))
            }
            close(OK_EXIT_CODE)
        }
    }

    init {
        title = "Handoff Tools"
        (inMd.values + allowed.values).forEach { it.addActionListener { update() } }
        update()
        init()
    }

    private fun update() {
        hint.text = "<html><div width='${JBUI.scale(520)}'>" + when {
            mdChanges().isEmpty() -> "In CLAUDE.md: your instructions tell Claude when to use it; Claude makes the change in the file. " +
                "Without \"Allowed\", Claude Code cannot call the tool at all (permissions.deny in ~/.claude/settings.json)."
            target != null -> "Apply asks the Claude Code session waiting in this project to update your CLAUDE.md."
            else -> "No Claude Code session is waiting: Apply copies a prompt for the CLAUDE.md changes - paste it into Claude Code."
        } + "</div></html>"
    }

    private fun row(g: Group): JPanel {
        val n = g.tools.sumOf { calls[it] ?: 0 }
        val small = JBUI.Fonts.smallFont()
        val grey = UIUtil.getContextHelpForeground()
        return JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
            isOpaque = false
            // Rechts Platz für die Scrollleiste, die über dem Inhalt liegt.
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(com.intellij.ui.JBColor.border()), JBUI.Borders.empty(8, 0, 8, 16))
            add(JBLabel(g.icon).apply {
                verticalAlignment = javax.swing.SwingConstants.TOP; border = JBUI.Borders.emptyTop(2)
                preferredSize = JBUI.size(20, 20)
            }, BorderLayout.WEST)
            add(JPanel().apply {
                layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS); isOpaque = false
                add(JBLabel(g.title).apply { font = JBUI.Fonts.label().asBold() })
                if (g.description.isNotEmpty()) add(JBLabel("<html><div width='${JBUI.scale(330)}'>${g.description.replace("<", "&lt;")}</div></html>"))
                add(JBLabel(g.tools.joinToString(" · ") + " · " + when (n) { 0 -> "not used"; 1 -> "1 call"; else -> "$n calls" } + " ($periodLabel)").apply {
                    font = small; foreground = grey; border = JBUI.Borders.emptyTop(2)
                })
            }, BorderLayout.CENTER)
            add(JPanel().apply {
                layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS); isOpaque = false
                add(inMd.getValue(g).apply { toolTipText = "Your CLAUDE.md tells Claude when to use it" })
                add(allowed.getValue(g).apply { toolTipText = "Claude Code may call it at all" })
            }, BorderLayout.EAST)
        }
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
        add(JBLabel("<html><div width='${JBUI.scale(520)}'>What Claude can hand over to you in the IDE. Switch off what you don't need.</div></html>"), BorderLayout.NORTH)
        val list = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            groups.forEach { add(row(it)) }
        }
        add(com.intellij.ui.components.JBScrollPane(list).apply {
            border = JBUI.Borders.empty(); preferredSize = JBUI.size(600, 460)
            horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }, BorderLayout.CENTER)
        add(hint, BorderLayout.SOUTH)
    }

    override fun createActions(): Array<Action> = arrayOf(applyAction.also { it.putValue(DEFAULT_ACTION, true) }, cancelAction)
}

/** Sperren von Handoff-Werkzeugen über `permissions.deny` in den Einstellungen von Claude Code. */
object Permissions {
    private val RULE = Regex("""^mcp__(.+)__(handoff_[a-z_]+)$""")

    /** Gesperrte Werkzeuge (egal unter welchem Servernamen). */
    fun denied(): Set<String> = denyList(PlanLimits.readSettings()).mapNotNull { RULE.find(it)?.groupValues?.get(2) }.toSet()

    private fun denyList(settings: JsonObject): List<String> =
        settings.getAsJsonObject("permissions")?.getAsJsonArray("deny")?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty()

    /**
     * Namen, unter denen der MCP-Server der IDE in Claude Code eingetragen ist: aus `~/.claude.json` die lokalen Server
     * (URL auf 127.0.0.1/localhost, so trägt Auto-Configure der IDE sie ein), sonst alle; dazu, was schon in Regeln steht.
     */
    private fun serverNames(settings: JsonObject): Set<String> {
        val names = mutableSetOf<String>()
        denyList(settings).mapNotNullTo(names) { RULE.find(it)?.groupValues?.get(1) }
        val json = runCatching { JsonParser.parseString(File(System.getProperty("user.home"), ".claude.json").readText()).asJsonObject }.getOrNull()
        val servers = mutableMapOf<String, String>()
        fun collect(o: JsonObject?) = o?.getAsJsonObject("mcpServers")?.entrySet()?.forEach { (k, v) -> servers[k] = v.toString() }
        collect(json)
        json?.getAsJsonObject("projects")?.entrySet()?.forEach { (_, v) -> if (v.isJsonObject) collect(v.asJsonObject) }
        val local = servers.filterValues { "127.0.0.1" in it || "localhost" in it }.keys
        names += local.ifEmpty { servers.keys }
        return names
    }

    fun setDenied(deny: Set<String>, all: Set<String>) {
        val settings = PlanLimits.readSettings()
        val names = serverNames(settings)
        val kept = denyList(settings).filter { r -> RULE.find(r)?.groupValues?.get(2)?.let { it !in all } ?: true }
        val rules = kept + deny.sorted().flatMap { t -> names.map { "mcp__${it}__$t" } }
        if (rules == denyList(settings)) return
        val perms = settings.getAsJsonObject("permissions") ?: JsonObject().also { settings.add("permissions", it) }
        if (rules.isEmpty()) perms.remove("deny") else perms.add("deny", JsonArray().apply { rules.forEach(::add) })
        PlanLimits.writeSettings(settings)
    }
}
