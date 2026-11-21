package app.heckit.ide.aihandoff

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Rectangle
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Reiter „Sessions“ im Handoff-Fenster: alle laufenden Claude-Code-Sessions des Projekts auf einen Blick - Zustand, Titel,
 * Ordner, Kontext, letzte Aktivität, laufende Aufgabe (Changelist) und ob Remote Control an ist. Dazu Session-Reiter, deren
 * Session nicht mehr läuft. Ein Klick führt in den Session-Reiter.
 */
class SessionsPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private enum class State(val label: String, val color: JBColor, val order: Int) {
        WAITING("Waiting for you", JBColor(0xB04079, 0xC86A9A), 0),
        WORKING("Working", JBColor(0x5A9E6F, 0x6FBF85), 1),
        DONE("Done", JBColor(0x2A7AA0, 0x5AA8D0), 2),
        IDLE("Running", JBColor.namedColor("Label.infoForeground", JBColor(0x6C707E, 0x868A91)), 3),
        STOPPED("Not running", JBColor.namedColor("Label.infoForeground", JBColor(0x6C707E, 0x868A91)), 4),
    }

    /** Was eine Zeile zeigt - im Hintergrund gesammelt (Titel aus dem Protokoll), gebaut auf dem EDT. */
    private data class Row(
        val id: String?, val tab: String?, val state: State, val since: Long?, val title: String, val dir: String?,
        val last: Long, val detail: String?, val task: String?, val remote: Boolean, val context: Int?, val model: String?,
        val tabOpen: Boolean, val terminal: Boolean, val file: SessionFile?,
    )

    private val list = object : JPanel(), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(r: Rectangle, o: Int, d: Int) = JBUI.scale(16)
        override fun getScrollableBlockIncrement(r: Rectangle, o: Int, d: Int) = r.height
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }.apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); border = JBUI.Borders.empty(8) }
    private val time = java.text.SimpleDateFormat("HH:mm")
    private val busy = AtomicBoolean()
    private var lastRows: List<Row>? = null
    private var builtAt = 0L
    private val timer = Timer(5000) { refresh() }

    init {
        add(JBScrollPane(list).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        HandoffService.getInstance(project).addListener({ refresh() }, this)
        project.messageBus.connect(this).subscribe(com.intellij.openapi.fileEditor.FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : com.intellij.openapi.fileEditor.FileEditorManagerListener {
                override fun fileOpened(source: FileEditorManager, file: com.intellij.openapi.vfs.VirtualFile) { if (file is SessionFile) refresh() }
                override fun fileClosed(source: FileEditorManager, file: com.intellij.openapi.vfs.VirtualFile) { if (file is SessionFile) SwingUtilities.invokeLater { refresh() } }
            })
        // Reiter wird sichtbar: gleich auffrischen statt bis zum nächsten Takt zu warten (unsichtbar wird nicht gelesen).
        addHierarchyListener { e ->
            if (e.changeFlags and java.awt.event.HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && isShowing) refresh()
        }
        timer.start()
        refresh()
    }

    /** Daten im Hintergrund sammeln (das erste Lesen eines Protokolls dauert), dann die Liste neu bauen, wenn sich etwas geändert hat. */
    fun refresh() {
        if (!isShowing && lastRows != null) return
        if (!busy.compareAndSet(false, true)) return
        val sessions = WaitingSessions.getInstance(project)
        val files = FileEditorManager.getInstance(project).openFiles.filterIsInstance<SessionFile>()
        // Terminal-API nur auf dem EDT fragen.
        val terminals = sessions.infos.values.mapNotNull { it.tab }.associateWith { SessionTerminals.isOpen(project, it) }
        ApplicationManager.getApplication().executeOnPooledThread {
            val rows = runCatching { collect(sessions, files, terminals) }.getOrDefault(emptyList())
            SwingUtilities.invokeLater {
                busy.set(false)
                if (project.isDisposed) return@invokeLater
                // Auch ohne Änderung ab und zu neu bauen - sonst stünde "just now" ewig da.
                val now = System.currentTimeMillis()
                if (rows != lastRows || now - builtAt > 30_000) { lastRows = rows; builtAt = now; rebuild(rows) }
            }
        }
    }

    private fun collect(sessions: WaitingSessions, files: List<SessionFile>, terminals: Map<String, Boolean>): List<Row> {
        readStatusLine()
        val infos = sessions.infos
        val rows = mutableListOf<Row>()
        for (info in infos.values) {
            val waiting = sessions.waiting(info.id)
            val working = sessions.workingOn(info.id)
            val state = when {
                waiting?.finished == false -> State.WAITING
                working != null -> State.WORKING
                waiting?.finished == true -> State.DONE
                else -> State.IDLE
            }
            val file = files.firstOrNull { (info.tab != null && it.tab == info.tab) || it.sessionId == info.id }
            val transcript = info.transcript?.takeIf { File(it).isFile }
            val meta = transcript?.let { TranscriptMeta.of(it) }
            val status = SessionEditors.statusBySession[info.id]
            val detail = when (state) {
                State.WAITING -> waiting?.text
                State.WORKING -> working?.prompt?.removePrefix("The user sent this follow-up prompt from the IDE (Handoff tool window):")?.trim()
                State.DONE -> waiting?.text
                else -> null
            }?.let { Md.plain(Md.preview(it, maxLines = 2, maxChars = 140).first) }?.takeIf { it.isNotBlank() }
            rows += Row(
                info.id, info.tab, state, working?.since ?: waiting?.at, meta?.title ?: file?.title ?: "Untitled session", info.cwd,
                maxOf(info.at, transcript?.let { File(it).lastModified() } ?: 0L), detail,
                TaskChangelists.getInstance(project).taskOf(info.id)?.name, meta?.remote == true,
                status?.let { pct(it) }, status?.let { str(it, "model", "display_name") },
                file != null, terminals[info.tab] == true, file,
            )
        }
        // Session-Reiter ohne laufende Session (nach einem Neustart, Session beendet).
        for (f in files) {
            if (f.logOnly) continue
            val id = f.currentSession(project)
            if (id != null && id in infos) continue
            if (rows.any { it.file === f }) continue
            val transcript = f.transcript?.takeIf { File(it).isFile }
            rows += Row(id, f.tab, State.STOPPED, null, f.title ?: transcript?.let { TranscriptMeta.of(it).title } ?: "Untitled session", f.cwd,
                transcript?.let { File(it).lastModified() } ?: 0L, null, null, false, null, null, true, false, f)
        }
        return rows.sortedWith(compareBy<Row> { it.state.order }.thenByDescending { it.last })
    }

    /** statusline.json kennt nur die Session, die zuletzt ihre Statuszeile geschrieben hat - je Session merken (wie im Session-Reiter). */
    private fun readStatusLine() {
        val file = PlanLimits.data
        if (!file.isFile) return
        val o = runCatching { JsonParser.parseString(file.readText()).asJsonObject }.getOrNull() ?: return
        o.get("session_id")?.takeIf { it.isJsonPrimitive }?.asString?.let { SessionEditors.statusBySession[it] = o }
    }

    private fun str(o: JsonObject, vararg path: String): String? {
        var e: com.google.gson.JsonElement? = o
        for (k in path) e = e?.takeIf { it.isJsonObject }?.asJsonObject?.get(k)
        return e?.takeIf { it.isJsonPrimitive }?.asString
    }

    private fun pct(o: JsonObject) = str(o, "context_window", "used_percentage")?.toDoubleOrNull()?.toInt()

    private fun rebuild(rows: List<Row>) {
        list.removeAll()
        val running = rows.count { it.state != State.STOPPED }
        list.add(JPanel(BorderLayout()).apply {
            isOpaque = false; alignmentX = LEFT_ALIGNMENT; border = JBUI.Borders.emptyBottom(8)
            add(JBLabel(when (running) { 0 -> "No Claude Code session is running in this project."; 1 -> "1 session running"; else -> "$running sessions running" })
                .apply { foreground = UIUtil.getContextHelpForeground() }, BorderLayout.CENTER)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        })
        if (rows.isEmpty()) {
            list.add(link("New Claude Session") { SessionEditors.newSession(project) })
            if (!WaitingHook.isInstalled()) list.add(grey("Sessions show up here once \"Show When Claude Is Waiting\" is on (menu ⋮ of this tool window)."))
        }
        rows.forEach { list.add(card(it)) }
        list.add(Box.createVerticalGlue())
        list.revalidate(); list.repaint()
    }

    private fun card(r: Row): JComponent {
        val card = JPanel(BorderLayout(0, JBUI.scale(2))).apply {
            alignmentX = LEFT_ALIGNMENT
            border = JBUI.Borders.compound(JBUI.Borders.emptyBottom(8),
                JBUI.Borders.compound(JBUI.Borders.customLine(if (r.state == State.STOPPED || r.state == State.IDLE) JBColor.border() else r.state.color, 1),
                    JBUI.Borders.empty(8, 10)))
        }
        val head = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(JBLabel(StringUtil.shortenTextWithEllipsis(r.title, 70, 0)).apply {
                font = JBUI.Fonts.label().asBold(); toolTipText = r.title
                if (r.remote && r.state != State.STOPPED) { icon = HandoffIcons.globe(on = true); toolTipText = r.title + " - Remote Control is on" }
            }, BorderLayout.CENTER)
            add(JBLabel(ago(r.last)).apply { foreground = UIUtil.getContextHelpForeground(); toolTipText = "Last activity " + time.format(java.util.Date(r.last)) }, BorderLayout.EAST)
        }
        val body = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false
            val state = r.state.label + (if (r.state == State.WORKING || r.state == State.WAITING || r.state == State.DONE) r.since?.let { " · since " + time.format(java.util.Date(it)) }.orEmpty() else "")
            add(JBLabel(state).apply { foreground = r.state.color; alignmentX = LEFT_ALIGNMENT })
            val facts = listOfNotNull(
                r.dir?.let { d -> project.basePath?.trimEnd('/')?.let { b -> if (d == b) null else if (d.startsWith("$b/")) d.removePrefix("$b/") else SessionEditors.shortPath(d) } ?: d },
                r.model, r.context?.let { "Context $it %" },
            )
            if (facts.isNotEmpty()) add(grey(facts.joinToString("  ·  ")))
            r.task?.let { add(grey("Task: " + it.removePrefix("Claude: "))) }
            r.detail?.let { add(grey(it)) }
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false; alignmentX = LEFT_ALIGNMENT; border = JBUI.Borders.emptyTop(4)
                add(link(if (r.tabOpen) "Show in Editor" else "Open in Editor") {
                    if (r.file != null) com.intellij.openapi.fileEditor.OpenFileDescriptor(project, r.file).navigate(true)
                    else SessionEditors.open(project, r.tab, r.id, cwd = r.dir)
                })
                if (r.terminal && r.tab != null) {
                    add(Box.createHorizontalStrut(JBUI.scale(16)))
                    add(link("Show Terminal") { SessionTerminals.show(project, r.tab) })
                }
            })
        }
        card.add(head, BorderLayout.NORTH)
        card.add(body, BorderLayout.CENTER)
        return JPanel(BorderLayout()).apply {
            isOpaque = false; alignmentX = LEFT_ALIGNMENT
            add(card, BorderLayout.CENTER)
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
        }
    }

    private fun ago(at: Long): String {
        if (at <= 0) return ""
        val s = (System.currentTimeMillis() - at) / 1000
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60} min ago"
            s < 86400 -> "${s / 3600} h ago"
            else -> "${s / 86400} d ago"
        }
    }

    private fun grey(text: String) = JBLabel(text).apply {
        foreground = UIUtil.getContextHelpForeground(); alignmentX = LEFT_ALIGNMENT; toolTipText = text.takeIf { it.length > 60 }
    }

    private fun link(text: String, action: () -> Unit) = ActionLink(text) { action() }.apply { alignmentX = LEFT_ALIGNMENT }

    override fun dispose() { timer.stop() }
}
