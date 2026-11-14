package app.heckit.ide.aihandoff

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.ScrollPaneConstants
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * Token-Verbrauch von Claude Code in diesem Projekt, aus den Session-Protokollen unter ~/.claude/projects/<projekt>/.
 * Nur Tokens, keine Kosten: die Protokolle enthalten keine Preise, und das Abo-Kontingent kennt nur Anthropic.
 */
class UsagePanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val sessionsDir = project.basePath?.let {
        File(System.getProperty("user.home"), ".claude/projects/" + File(it).absolutePath.replace(Regex("[^A-Za-z0-9]"), "-"))
    }
    private val zone = ZoneId.systemDefault()

    /** Eine Antwort von Claude (je Nachrichten-ID nur einmal - die Protokolle enthalten Wiederholungen). */
    private class Turn(val time: Instant, val input: Long, val cacheWrite: Long, val cacheRead: Long, val output: Long) {
        val total get() = input + cacheWrite + cacheRead + output
    }
    private class Session(val file: File, val title: String, val turns: List<Turn>, val handoffCalls: List<Pair<Instant, String>> = emptyList()) {
        val start get() = turns.first().time
        val end get() = turns.last().time
    }

    /** Eingelesene Sessions je Datei, nur neu lesen, wenn sich die Datei geändert hat. */
    private val cache = HashMap<File, Pair<Long, Session?>>()

    private enum class Period(val label: String, val days: Long) { TODAY("Today", 1), WEEK("7 days", 7), MONTH("30 days", 30) }
    private var period = Period.WEEK

    private class Row(val text: String, val extra: String = "", val meta: String = "", val strong: Boolean = true,
                      val file: File? = null, val key: String? = null, val icon: javax.swing.Icon? = null) {
        override fun toString() = text
    }

    private val root = DefaultMutableTreeNode()
    private val model = DefaultTreeModel(root)
    private val tree = MetaTree(model) { o -> (o as? Row)?.let { it.meta to it.strong } }.apply {
        cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(t: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
                val r = (value as? DefaultMutableTreeNode)?.userObject as? Row ?: return
                icon = r.icon
                append(r.text, if (r.key?.startsWith("d:") == true) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
                if (r.extra.isNotEmpty()) append("  " + r.extra, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                toolTipText = r.file?.name
            }
        }
    }
    private val expandedKeys = HashSet<String>()
    private val summary = JBLabel().apply { border = JBUI.Borders.empty(6, 10, 2, 10) }
    private val detail = JBLabel().apply { foreground = UIUtil.getContextHelpForeground(); border = JBUI.Borders.empty(0, 10, 6, 10) }
    private val buttons = Period.entries.associateWith { p ->
        javax.swing.JToggleButton(p.label).apply { addActionListener { period = p; refresh() } }
    }
    private var generation = 0
    private var lastCalls: Map<String, Int> = emptyMap()
    private var lastPeriod = ""

    fun manageTools() { if (ToolsDialog(project, lastCalls, lastPeriod).showAndGet()) refresh() }

    // ---- Plan-Limits (über die Statuszeile von Claude Code) ----
    private val limits = JPanel().apply {
        layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
        border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(com.intellij.ui.JBColor.border()), JBUI.Borders.empty(2, 10, 8, 10))
    }
    // Die Datei ändert sich mit jeder Antwort von Claude - alle 20 s nachsehen, solange der Reiter sichtbar ist.
    private val limitsTimer = javax.swing.Timer(20_000) { if (isShowing) showLimits() }.apply { start() }
    private var limitsStamp = -1L

    private fun showLimits(force: Boolean = false) {
        val stamp = if (PlanLimits.data.isFile) PlanLimits.data.lastModified() else 0L
        if (!force && stamp == limitsStamp) return
        limitsStamp = stamp
        limits.removeAll()
        val l = if (PlanLimits.isInstalled()) PlanLimits.read() else null
        when {
            !PlanLimits.isInstalled() -> {
                limits.add(JBLabel("<html>Plan limits (5-hour, weekly) are only passed to Claude Code's status line. " +
                    "Handoff can add a small status line script that keeps them for the IDE.</html>").apply {
                    foreground = UIUtil.getContextHelpForeground(); alignmentX = LEFT_ALIGNMENT
                })
                limits.add(javax.swing.Box.createVerticalStrut(JBUI.scale(4)))
                limits.add(javax.swing.JButton("Show Plan Limits…").apply {
                    alignmentX = LEFT_ALIGNMENT
                    addActionListener { installLimits() }
                })
            }
            l == null || (l.fiveHour == null && l.sevenDay == null) -> limits.add(JBLabel(
                if (l == null) "Plan limits appear after Claude Code's next response."
                else "No plan limits reported (only Pro and Max plans have them)."
            ).apply { foreground = UIUtil.getContextHelpForeground(); alignmentX = LEFT_ALIGNMENT })
            else -> {
                l.fiveHour?.let { limits.add(limitRow("5-hour limit", it, DateTimeFormatter.ofPattern("HH:mm"))) }
                l.sevenDay?.let { limits.add(limitRow("Weekly limit", it, DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH))) }
                val age = java.time.Duration.between(l.updated, Instant.now()).toMinutes()
                limits.add(JBLabel("Updated " + if (age < 1) "just now" else "$age min ago").apply {
                    foreground = UIUtil.getContextHelpForeground(); font = JBUI.Fonts.smallFont(); alignmentX = LEFT_ALIGNMENT
                })
            }
        }
        limits.revalidate(); limits.repaint()
    }

    private fun limitRow(name: String, w: PlanLimits.Window, fmt: DateTimeFormatter) = JPanel(BorderLayout(JBUI.scale(8), JBUI.scale(2))).apply {
        alignmentX = LEFT_ALIGNMENT; border = JBUI.Borders.emptyBottom(6)
        val reset = w.resetsAt?.atZone(zone)?.format(fmt)?.let { " · resets $it" }.orEmpty()
        add(JBLabel("<html><b>$name</b>  ${w.usedPercent.toInt()} %$reset</html>"), BorderLayout.NORTH)
        add(javax.swing.JProgressBar(0, 100).apply { value = w.usedPercent.toInt().coerceIn(0, 100) }, BorderLayout.CENTER)
        maximumSize = java.awt.Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    private fun installLimits() {
        val previous = PlanLimits.currentCommand()
        val msg = "Handoff will create ~/.claude/handoff/statusline.sh and set it as Claude Code's status line in ~/.claude/settings.json " +
            "(a backup is kept as settings.json.bak-handoff).\n\nThe status line then shows \"5h 23% · week 41%\" in Claude Code." +
            (if (previous != null) "\n\nYour current status line ($previous) keeps working - the script calls it." else "")
        if (com.intellij.openapi.ui.Messages.showOkCancelDialog(project, msg, "Show Plan Limits", "Set Up", "Cancel", null) != com.intellij.openapi.ui.Messages.OK) return
        runCatching { PlanLimits.install() }.onFailure {
            com.intellij.openapi.ui.Messages.showErrorDialog(project, it.message ?: it.toString(), "Show Plan Limits")
        }
        showLimits(force = true)
    }

    init {
        add(JPanel(BorderLayout()).apply {
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(6))).apply {
                val group = javax.swing.ButtonGroup()
                buttons.values.forEach { group.add(it); add(it) }
            }, BorderLayout.NORTH)
            add(JPanel(BorderLayout()).apply { add(summary, BorderLayout.NORTH); add(detail, BorderLayout.SOUTH) }, BorderLayout.CENTER)
            add(limits, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        add(JBScrollPane(tree).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            viewport.addChangeListener { tree.repaint() }
        }, BorderLayout.CENTER)
        tree.addTreeExpansionListener(object : javax.swing.event.TreeExpansionListener {
            override fun treeExpanded(e: javax.swing.event.TreeExpansionEvent) { key(e.path)?.let { expandedKeys += it } }
            override fun treeCollapsed(e: javax.swing.event.TreeExpansionEvent) { key(e.path)?.let { expandedKeys -= it } }
        })
        // Doppelklick öffnet das Protokoll der Session.
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount != 2) return
                val r = (tree.getPathForLocation(e.x, e.y)?.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? Row ?: return
                if (r.key == "tools" || r.key?.startsWith("tool:") == true) { manageTools(); return }
                r.file?.let { f ->
                    ApplicationManager.getApplication().invokeLater {
                        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f)?.let { OpenFileDescriptor(project, it).navigate(true) }
                    }
                }
            }
        })
        buttons.getValue(period).isSelected = true
        refresh()
        showLimits(force = true)
    }

    private fun key(p: TreePath) = ((p.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? Row)?.key

    /** Protokolle im Hintergrund einlesen (nur geänderte), dann den Baum im UI-Thread neu setzen. */
    fun refresh() {
        val gen = ++generation
        val p = period
        ApplicationManager.getApplication().executeOnPooledThread {
            val sessions = runCatching { load() }.getOrDefault(emptyList())
            ApplicationManager.getApplication().invokeLater({ if (gen == generation && !project.isDisposed) { show(sessions, p); showLimits() } }, ModalityState.any())
        }
    }

    private fun load(): List<Session> {
        val files = sessionsDir?.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }.orEmpty()
        return synchronized(cache) {
            cache.keys.retainAll(files.toSet())
            files.mapNotNull { f ->
                val stamp = f.lastModified()
                cache[f]?.takeIf { it.first == stamp }?.second ?: parse(f).also { cache[f] = stamp to it }
            }
        }
    }

    private fun parse(f: File): Session? {
        val seen = HashSet<String>()
        val turns = mutableListOf<Turn>()
        val calls = mutableListOf<Pair<Instant, String>>()
        val callIds = HashSet<String>()
        var title: String? = null
        var firstPrompt: String? = null
        f.bufferedReader().useLines { lines ->
            for (line in lines) {
                // Schneller Vorfilter: nur Zeilen mit Verbrauch, Titel oder (für den Ersatztitel) die erste Eingabe.
                val isUsage = line.contains("\"usage\"")
                val isTitle = line.contains("\"ai-title\"")
                val isPrompt = firstPrompt == null && line.contains("\"type\":\"user\"")
                if (!isUsage && !isTitle && !isPrompt) continue
                val o = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: continue
                when (o.str("type")) {
                    "ai-title" -> title = o.str("aiTitle") ?: title
                    "user" -> if (firstPrompt == null) {
                        val c = o.getAsJsonObject("message")?.get("content")
                        if (c != null && c.isJsonPrimitive) firstPrompt = c.asString.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                    }
                    "assistant" -> {
                        val m = o.getAsJsonObject("message") ?: continue
                        // Aufrufe von Handoff-Werkzeugen (mcp__<server>__handoff_…); je Inhaltsblock eine Zeile, daher vor der ID-Prüfung.
                        if (line.contains("__handoff_")) m.getAsJsonArray("content")?.forEach { c ->
                            val b = c.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                            val name = b.str("name") ?: return@forEach
                            if (b.str("type") == "tool_use" && name.contains("__handoff_") && callIds.add(b.str("id") ?: name + calls.size)) {
                                o.str("timestamp")?.let { runCatching { Instant.parse(it) }.getOrNull() }?.let { calls += it to name.substringAfterLast("__") }
                            }
                        }
                        val u = m.getAsJsonObject("usage") ?: continue
                        val id = m.str("id") ?: o.str("requestId") ?: o.str("uuid") ?: continue
                        if (!seen.add(id)) continue
                        val t = o.str("timestamp")?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: continue
                        turns += Turn(t, u.num("input_tokens"), u.num("cache_creation_input_tokens"), u.num("cache_read_input_tokens"), u.num("output_tokens"))
                    }
                }
            }
        }
        if (turns.isEmpty()) return null
        turns.sortBy { it.time }
        return Session(f, title ?: firstPrompt?.take(80) ?: f.nameWithoutExtension, turns, calls)
    }

    private fun JsonObject.str(k: String) = get(k)?.takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.num(k: String) = get(k)?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L

    private fun show(sessions: List<Session>, p: Period) {
        val today = LocalDate.now(zone)
        val from = today.minusDays(p.days - 1)
        // Antworten des Zeitraums, je Tag und Session.
        val inRange = sessions.flatMap { s -> s.turns.map { s to it } }.filter { (_, t) -> !t.time.atZone(zone).toLocalDate().isBefore(from) }
        val all = inRange.map { it.second }
        val total = all.sumOf { it.total }
        val read = all.sumOf { it.cacheRead }
        summary.text = "<html><b>≈ ${fmt(total)} tokens</b> · ${all.size} responses · ${inRange.map { it.first }.distinct().size} sessions</html>"
        detail.text = if (all.isEmpty()) "No Claude Code sessions in this period."
            else "Input ${fmt(all.sumOf { it.input + it.cacheWrite })} · from cache ${fmt(read)} (${if (total > 0) read * 100 / total else 0} %) · output ${fmt(all.sumOf { it.output })}"

        root.removeAllChildren()
        val dayFmt = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH)
        val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
        inRange.groupBy { it.second.time.atZone(zone).toLocalDate() }.toSortedMap(compareByDescending { it }).forEach { (day, items) ->
            val label = when (day) { today -> "Today"; today.minusDays(1) -> "Yesterday"; else -> day.format(dayFmt) }
            val dayNode = DefaultMutableTreeNode(Row(label, "${items.size} responses", "≈ ${fmt(items.sumOf { it.second.total })}", key = "d:$day"))
            root.add(dayNode)
            items.groupBy { it.first }.entries.sortedByDescending { e -> e.value.maxOf { it.second.time } }.forEach { (s, ts) ->
                val first = ts.minOf { it.second.time }.atZone(zone); val last = ts.maxOf { it.second.time }.atZone(zone)
                dayNode.add(DefaultMutableTreeNode(Row(
                    s.title, "${first.format(timeFmt)}–${last.format(timeFmt)} · ${ts.size}", "≈ ${fmt(ts.sumOf { it.second.total })}",
                    file = s.file, icon = AllIcons.General.Balloon,
                )))
            }
        }
        // Handoff-Werkzeuge: wie oft Claude sie im Zeitraum aufgerufen hat und ob die CLAUDE.md sie nennt.
        val counts = sessions.flatMap { it.handoffCalls }.filter { !it.first.atZone(zone).toLocalDate().isBefore(from) }.groupingBy { it.second }.eachCount()
        val mentioned = Features.mentioned(project)
        val denied = Permissions.denied()
        val used = Features.allTools.count { (counts[it] ?: 0) > 0 }
        lastCalls = counts; lastPeriod = p.label
        val toolsNode = DefaultMutableTreeNode(Row("Handoff tools", "$used of ${Features.allTools.size} used · double-click to manage", key = "tools"))
        Features.allTools.forEach { t ->
            val n = counts[t] ?: 0
            toolsNode.add(DefaultMutableTreeNode(Row(t, if (n == 0) "not used" else if (n == 1) "1 call" else "$n calls",
                listOfNotNull(if (t in mentioned) "in CLAUDE.md" else "not in CLAUDE.md", "blocked".takeIf { t in denied }).joinToString(" · "),
                strong = t in mentioned && t !in denied, key = "tool:$t",
                icon = if (n > 0) AllIcons.General.InspectionsOK else AllIcons.General.InspectionsPause)))
        }
        root.add(toolsNode)
        model.reload()
        // Heute bzw. der jüngste Tag ist aufgeklappt, sonst was der Nutzer aufgeklappt hat.
        (0 until root.childCount).map { root.getChildAt(it) as DefaultMutableTreeNode }.forEachIndexed { i, n ->
            if (i == 0 || (n.userObject as Row).key in expandedKeys) tree.expandPath(TreePath(n.path))
        }
    }

    private fun fmt(n: Long) = when {
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
        n >= 1000 -> "%.1fk".format(n / 1000.0)
        else -> n.toString()
    }

    override fun dispose() { limitsTimer.stop() }
}
