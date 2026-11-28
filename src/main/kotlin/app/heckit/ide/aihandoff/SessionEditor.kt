package app.heckit.ide.aihandoff

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.beans.PropertyChangeListener
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.AbstractAction
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.HyperlinkEvent

/**
 * Eine Claude-Code-Session als Editor-Reiter: oben der Verlauf aus dem Protokoll der Session (Markdown gerendert), unten
 * ein Eingabefeld. Läuft die Session in einem Terminal-Reiter, den Handoff gestartet hat ([tab]), geht die Eingabe dorthin
 * (auch Slash-Befehle, Rückfragen per Taste); sonst nur als Antwort auf "Claude is done" über den Stop-Hook.
 * Nach `/clear` beginnt im selben Terminal eine neue Session - der Reiter folgt ihr über [tab].
 */
class SessionFile(val tab: String?, val sessionId: String?, restoredTranscript: String? = null, restoredTitle: String? = null, restoredCwd: String? = null) :
    LightVirtualFile("Claude", SessionFileType, "") {
    init { isWritable = false }
    /** Claude arbeitet gerade - der Reiter zeigt dann einen Spinner statt des Handoff-Symbols ([SessionIconProvider]). */
    @Volatile var busy: Boolean = false
    /** Mit `claude --resume` gestartet, die Session hat sich noch nicht gemeldet: Reiter zeigt "Resuming session…" mit Spinner. */
    @Volatile var resuming: Boolean = false
    /** Arbeitsordner der Session - auch außerhalb des Projekts möglich ("New Claude Session in Folder…"). */
    @Volatile var cwd: String? = restoredCwd
    /** Titel der Session (von Claude Code vergeben), für den Reiter. */
    @Volatile var title: String? = restoredTitle
    /** Zuletzt bekanntes Protokoll - nach einem IDE-Neustart gibt es die Hook-Daten der Session oft nicht mehr. */
    @Volatile var transcript: String? = restoredTranscript
    /** Nur das Protokoll ansehen (`.jsonl` im Editor): ohne Eingabe, unten nur "Resume in New Tab". */
    var logOnly: Boolean = false

    /** Aktuelle Session: im eigenen Terminal die neueste, sonst die feste. */
    fun currentSession(project: Project): String? =
        tab?.let { WaitingSessions.getInstance(project).sessionOfTab(it)?.id } ?: sessionId
}

object SessionFileType : FileType {
    override fun getName() = "Handoff Session"
    override fun getDescription() = "Claude Code session (Handoff)"
    override fun getDefaultExtension() = ""
    override fun getIcon(): Icon = HandoffIcons.editor()
    override fun isBinary() = false
    override fun isReadOnly() = true
}

class SessionEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file is SessionFile
    override fun acceptRequiresReadAction() = false
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = SessionEditor(project, file as SessionFile)
    override fun getEditorTypeId() = "handoff-session"
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

/** Symbol des Session-Reiters: Spinner, solange Claude arbeitet, sonst das Handoff-Symbol. */
class SessionIconProvider : com.intellij.ide.FileIconProvider {
    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? =
        // Spinner nur im Reiter: Reiter fragen mit ICON_FLAG_READ_STATUS (EditorsSplitters), die Navigationsleiste mit 0
        // (PsiNavBarItem.getIcon) - und frischt ihr Symbol nicht auf, der Spinner blieb dort stehen (2026-11-18).
        (file as? SessionFile)?.let { if (it.busy && flags and com.intellij.openapi.util.Iconable.ICON_FLAG_READ_STATUS != 0) HandoffSpinner.icon else HandoffIcons.editor() }
}

/**
 * Eigener Spinner für den Reiter, wie der Umschlag bei Mail View (HkLoading): Die Sprechblase aus dem Handoff-Symbol wird
 * nachgefahren - gezeichnet, gelöscht, rückwärts gezeichnet, gelöscht. [com.intellij.ui.AnimatedIcon] zeigt die Bilder der
 * Reihe nach und lässt die Komponente, auf der es liegt (der Reiter), neu zeichnen.
 */
object HandoffSpinner {
    private const val PERIOD = 2200
    private const val FRAMES = 44
    private val color = JBColor(0x6C707E, 0xCED0D6)

    /** Blase aus `handoff.svg` (13er-Raster, Ecken r = 1), mittig ins 16er-Raster, ein Strich ab der Spitze. */
    private val BUBBLE: java.awt.Shape = java.awt.geom.Path2D.Double().apply {
        moveTo(6.0, 9.0); lineTo(3.0, 11.5); lineTo(3.0, 9.0); lineTo(2.0, 9.0)
        quadTo(1.0, 9.0, 1.0, 8.0); lineTo(1.0, 2.5); quadTo(1.0, 1.5, 2.0, 1.5); lineTo(11.0, 1.5)
        quadTo(12.0, 1.5, 12.0, 2.5); lineTo(12.0, 8.0); quadTo(12.0, 9.0, 11.0, 9.0); lineTo(6.0, 9.0)
    }.let { java.awt.geom.AffineTransform.getTranslateInstance(1.5, 1.8).createTransformedShape(it) }

    private val points: List<java.awt.geom.Point2D.Double> = buildList {
        val it = java.awt.geom.FlatteningPathIterator(BUBBLE.getPathIterator(null), 0.01)
        val c = DoubleArray(6)
        while (!it.isDone) { if (it.currentSegment(c) != java.awt.geom.PathIterator.SEG_CLOSE) add(java.awt.geom.Point2D.Double(c[0], c[1])); it.next() }
    }
    private val lengths = DoubleArray(points.size).also { l -> for (i in 1 until points.size) l[i] = l[i - 1] + points[i].distance(points[i - 1]) }

    private fun ease(x: Double) = if (x < 0.5) 4 * x * x * x else 1 - Math.pow(-2 * x + 2, 3.0) / 2

    /** Sichtbarer Teil der Länge (0…1) zur Phase [p]: wie pingPong in Mail View. */
    private fun part(p: Double): Pair<Double, Double> = when {
        p < 0.22 -> 0.0 to ease(p / 0.22)
        p < 0.28 -> 0.0 to 1.0
        p < 0.50 -> ease((p - 0.28) / 0.22) to 1.0
        p < 0.72 -> 1 - ease((p - 0.5) / 0.22) to 1.0
        p < 0.78 -> 0.0 to 1.0
        else -> 0.0 to 1 - ease((p - 0.78) / 0.22)
    }

    private fun at(d: Double): java.awt.geom.Point2D.Double {
        val i = lengths.indexOfFirst { it >= d }.let { if (it < 1) 1 else it }
        val seg = lengths[i] - lengths[i - 1]
        val f = if (seg == 0.0) 0.0 else (d - lengths[i - 1]) / seg
        return java.awt.geom.Point2D.Double(points[i - 1].x + f * (points[i].x - points[i - 1].x), points[i - 1].y + f * (points[i].y - points[i - 1].y))
    }

    private class Frame(private val phase: Double) : Icon {
        override fun getIconWidth() = JBUI.scale(16)
        override fun getIconHeight() = JBUI.scale(16)
        override fun paintIcon(c: java.awt.Component?, g: java.awt.Graphics, x: Int, y: Int) {
            val (from, to) = part(phase)
            if (to - from < 0.002) return
            val g2 = g.create() as java.awt.Graphics2D
            try {
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(java.awt.RenderingHints.KEY_STROKE_CONTROL, java.awt.RenderingHints.VALUE_STROKE_PURE)
                g2.translate(x, y); g2.scale(JBUI.scale(16) / 16.0, JBUI.scale(16) / 16.0)
                g2.color = color
                g2.stroke = java.awt.BasicStroke(1.2f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
                val total = lengths.last(); val a = from * total; val b = to * total
                val path = java.awt.geom.Path2D.Double()
                at(a).let { path.moveTo(it.x, it.y) }
                for (i in points.indices) if (lengths[i] > a && lengths[i] < b) path.lineTo(points[i].x, points[i].y)
                at(b).let { path.lineTo(it.x, it.y) }
                g2.draw(path)
            } finally { g2.dispose() }
        }
    }

    val icon: Icon by lazy { com.intellij.ui.AnimatedIcon(PERIOD / FRAMES, *Array<Icon>(FRAMES) { Frame(it / FRAMES.toDouble()) }) }
}

class SessionTabTitleProvider : EditorTabTitleProvider, DumbAware {
    override fun getEditorTabTitle(project: Project, file: VirtualFile): String? =
        (file as? SessionFile)?.let { f ->
            // Ordner außerhalb des Projekts (oder ein Unterordner) gleich im Reiter, damit man die Sessions auseinanderhält.
            val dir = f.cwd?.trimEnd('/')?.takeIf { it != project.basePath?.trimEnd('/') }?.let { " (" + File(it).name + ")" } ?: ""
            // Das Handoff-Symbol zeigt schon, dass es eine Claude-Session ist.
            // Punkt vorne: Die Session wartet auf dich (Karte, Freigabe, Rückfrage) - auch wenn der Reiter nicht vorne ist.
            (if (SessionEditors.needsYou(project, f)) "● " else "") + (f.title?.let { StringUtil.shortenTextWithEllipsis(it, 40, 0) } ?: if (f.resuming) "Resuming session…" else "New Session") + dir
        }

    override fun getEditorTabTooltipHtml(project: Project, file: VirtualFile): com.intellij.openapi.util.text.HtmlChunk? =
        (file as? SessionFile)?.let { f -> com.intellij.openapi.util.text.HtmlChunk.text(SessionEditors.shortPath(f.cwd ?: project.basePath ?: return null)) }
}

/** Öffnen, Starten und Aufräumen der Session-Reiter. */
object SessionEditors {
    /** Letzter Inhalt von statusline.json je Session-ID (Modell, Effort, Kontext) - die Datei kennt nur die zuletzt aktive. */
    val statusBySession = java.util.concurrent.ConcurrentHashMap<String, com.google.gson.JsonObject>()
    /** Wartet etwas in dieser Session auf den Nutzer: eine Karte von ihr oder eine Freigabe/Rückfrage im Terminal (nicht: fertig). */
    fun needsYou(project: Project, f: SessionFile): Boolean {
        val id = f.currentSession(project) ?: return false
        if (HandoffService.getInstance(project).proposals.any { it.isPending && it.owner == id }) return true
        return project.getServiceIfCreated(WaitingSessions::class.java)?.sessions.orEmpty().any { it.id == id && !it.finished }
    }

    /** Zuletzt angezeigter Session-Reiter je Projekt. */
    val lastSelected = java.util.concurrent.ConcurrentHashMap<Project, SessionFile>()

    /** In den Eingabefeld des Reiters [file] eine `@`-Marke für [path] setzen und den Reiter nach vorn holen. */
    fun insertMention(project: Project, file: SessionFile, path: String, label: String? = null) {
        OpenFileDescriptor(project, file).navigate(true)
        FileEditorManager.getInstance(project).getEditors(file).filterIsInstance<SessionEditor>().firstOrNull()?.insertMention(path, label)
    }

    /** Offener Session-Reiter zu Terminal-Reiter oder Session, sonst null. */
    fun openTab(project: Project, tab: String?, sessionId: String?): SessionFile? =
        FileEditorManager.getInstance(project).openFiles.filterIsInstance<SessionFile>().firstOrNull { f ->
            (tab != null && f.tab == tab) || (sessionId != null && (f.sessionId == sessionId || f.currentSession(project) == sessionId))
        }

    /** Schon offenen Reiter der Session zeigen, sonst einen neuen öffnen. */
    fun open(project: Project, tab: String?, sessionId: String?, focus: Boolean = true, cwd: String? = null, resuming: Boolean = false) {
        val open = openTab(project, tab, sessionId)
        OpenFileDescriptor(project, open ?: SessionFile(tab, sessionId, restoredCwd = cwd).also { it.resuming = resuming }).navigate(focus)
        if (focus) com.intellij.ide.impl.ProjectUtil.focusProjectWindow(project, true)
    }

    /**
     * Session im Reiter zeigen, der den Terminal-Reiter [tab] bedient. Ein schon offener Reiter der Session ohne dieses
     * Terminal (vorher nur das Protokoll, oder Terminal nach Plugin-Update vergessen) wird ersetzt - [SessionFile.tab] ist fest.
     */
    fun openWithTerminal(project: Project, tab: String, sessionId: String) {
        val fem = FileEditorManager.getInstance(project)
        fem.openFiles.filterIsInstance<SessionFile>()
            .filter { f -> f.tab != tab && !f.logOnly && (f.sessionId == sessionId || f.currentSession(project) == sessionId) }
            .forEach { fem.closeFile(it) }
        open(project, tab, sessionId, focus = true)
    }

    private val startDirs = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Startordner einer Session: das erste `cwd` in ihrem Protokoll (steht in den ersten Zeilen). */
    fun startDir(transcript: String): String? = startDirs[transcript] ?: runCatching {
        File(transcript).bufferedReader().useLines { lines ->
            lines.take(200).firstNotNullOfOrNull { l ->
                if (!l.contains("\"cwd\"")) null
                else runCatching { JsonParser.parseString(l).asJsonObject.get("cwd")?.asString }.getOrNull()
            }
        }
    }.getOrNull()?.trimEnd('/')?.also { startDirs[transcript] = it }

    /** Pfad mit `~` statt Home-Ordner, für Anzeigen. */
    fun shortPath(path: String): String {
        val home = System.getProperty("user.home")
        return if (path == home || path.startsWith("$home/")) "~" + path.removePrefix(home) else path
    }

    /**
     * "New Claude Session": Terminal-Reiter mit `claude` im Hintergrund, die Session im Editor-Reiter. [dir]: anderer Ordner
     * als das Projekt; Sessions dort zeigt dieses Projekt dann auch (Karten, Reiter).
     */
    fun newSession(project: Project, dir: String? = null, command: String = "claude"): Boolean {
        if (!SessionTerminals.available) {
            Messages.showErrorDialog(project, "This needs the IDE's new terminal (2026.1 or later).", "New Claude Session")
            return false
        }
        if (!WaitingHook.isInstalled()) {
            val msg = "Claude sessions in editor tabs need Handoff's hooks for Claude Code (\"Show When Claude Is Waiting\").\n\n" +
                "Handoff will create ~/.claude/handoff/hook.sh, add it as a hook in ~/.claude/settings.json (a backup is kept as " +
                "settings.json.bak-handoff; existing hooks stay) and add the command /show-in-editor to ~/.claude/commands."
            if (Messages.showOkCancelDialog(project, msg, "New Claude Session", "Set Up and Start", "Cancel", null) != Messages.OK) return false
            runCatching { WaitingHook.install() }.onFailure {
                Messages.showErrorDialog(project, it.message ?: it.toString(), "New Claude Session"); return false
            }
        }
        val sessions = WaitingSessions.getInstance(project)
        dir?.let { sessions.addDir(it) }
        val tab = SessionTerminals.start(project, command, dir)
        if (tab == null) {
            Messages.showErrorDialog(project, "Could not open a terminal tab (see idea.log).", "New Claude Session")
            return false
        }
        sessions.start()
        open(project, tab, null, cwd = dir ?: project.basePath, resuming = command.startsWith("claude --resume"))
        return true
    }

    /** Frühere Session fortsetzen ("Resume Claude Session…"): schon offenen Reiter zeigen, sonst `claude --resume` in ihrem Ordner. */
    fun resumeOld(project: Project, sessionId: String, cwd: String) {
        val manager = FileEditorManager.getInstance(project)
        val open = manager.openFiles.filterIsInstance<SessionFile>().firstOrNull { it.currentSession(project) == sessionId }
        if (open != null && (open.tab == null || SessionTerminals.isOpen(project, open.tab))) {
            if (open.tab != null) { OpenFileDescriptor(project, open).navigate(true); return }
            resume(project, open, sessionId); return
        }
        newSession(project, cwd.takeIf { it.trimEnd('/') != project.basePath?.trimEnd('/') }, "claude --resume $sessionId")
    }

    /**
     * Neue Karte: Handoff-Fenster öffnen - außer ein Session-Reiter ist vorn, der zeigt die Karte selbst am Ende des
     * Gesprächs; das aufspringende Fenster würde dort nur stören.
     */
    /** Neue Karte sichtbar machen: steht sie im gerade offenen Session-Reiter, reicht das, sonst das Handoff-Fenster zeigen. */
    fun revealCards(project: Project, card: Proposal? = null) {
        val tab = FileEditorManager.getInstance(project).selectedEditor?.file as? SessionFile
        val owner = card?.owner
        if (tab != null && (owner == null || owner == tab.currentSession(project))) return
        com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("Handoff")?.show()
    }

    fun closeAll(project: Project) {
        val manager = FileEditorManager.getInstance(project)
        manager.openFiles.filterIsInstance<SessionFile>().forEach { manager.closeFile(it) }
    }

    /** Laufende Session in neuem Terminal fortsetzen (`claude --resume`); der Reiter folgt dann dem Terminal. */
    fun resume(project: Project, file: SessionFile, sessionId: String, prompt: String? = null) {
        val dir = file.transcript?.let { startDir(it) } ?: file.cwd ?: WaitingSessions.getInstance(project).infos[sessionId]?.cwd
        // Ein Prompt geht als Argument mit: `claude --resume <id> "<prompt>"` schickt ihn gleich nach dem Start ab.
        val arg = prompt?.trim()?.takeIf { it.isNotEmpty() }?.let { " '" + it.replace("'", "'\\''") + "'" }.orEmpty()
        val tab = SessionTerminals.start(project, "claude --resume $sessionId$arg", dir) ?: return
        // Erst den neuen Reiter öffnen (landet neben dem alten und ist vorn), dann den alten schließen. Andersherum sprang
        // nach dem Schließen der Nachbar-Reiter einer anderen Session nach vorn, und der neue landete am Ende (2026-11-22).
        // Session, Protokoll und Titel gleich mitgeben - sonst bleibt der Reiter leer, bis der Hook der Session meldet.
        val fresh = SessionFile(tab, sessionId, restoredTranscript = file.transcript, restoredTitle = file.title, restoredCwd = dir)
        OpenFileDescriptor(project, fresh).navigate(true)
        FileEditorManager.getInstance(project).closeFile(file)
        com.intellij.ide.impl.ProjectUtil.focusProjectWindow(project, true)
        // Ein Befehl mit Dialog (/help, /mcp …) als Resume-Prompt: Terminal nach vorn, sonst wartete der Dialog dort
        // unbemerkt (2026-11-22).
        if (prompt != null && SlashArguments.opensDialog(prompt)) SessionTerminals.show(project, tab)
    }

    private const val KEY = "app.heckit.ide.aihandoff.sessionTabs"
    /** Beim Schließen des Projekts nicht mehr mitschreiben - sonst löschte das Schließen der Reiter die Liste. */
    private val closing: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Offene Session-Reiter (Session, Protokoll, Titel) merken, damit sie nach einem Neustart wieder aufgehen. */
    fun save(project: Project) {
        if (project.isDisposed || project.locationHash in closing || project.locationHash in switching) return
        val lines = FileEditorManager.getInstance(project).openFiles.filterIsInstance<SessionFile>().mapNotNull { f ->
            val id = f.currentSession(project) ?: return@mapNotNull null
            listOf(id, f.transcript.orEmpty(), f.title.orEmpty().replace('\t', ' ').replace('\n', ' '), f.cwd.orEmpty()).joinToString("\t")
        }.distinct()
        com.intellij.ide.util.PropertiesComponent.getInstance(project).setList(KEY, lines.ifEmpty { null })
    }

    /**
     * Branch-Wechsel läuft: Die IDE schließt dabei alle Editor-Reiter und öffnet die des Ziel-Branches („Restore workspace on
     * branch switch“: BranchContextTracker.branchHasChanged → WorkingContextManager.clearContext/loadContext, nachgelesen in
     * 2026.1). Session-Reiter sind dort nicht gespeichert - ohne Schutz gingen sie verloren, und mit ihnen die ruhenden
     * Claude-Terminals.
     */
    private val switching: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Projekt (oder IDE) schließt gerade oder der Branch wechselt - dann Terminals nicht wegräumen. */
    fun isClosing(project: Project) = project.isDisposed || project.locationHash in closing || project.locationHash in switching

    fun restore(project: Project) {
        com.intellij.openapi.application.ApplicationManager.getApplication().messageBus.connect(project)
            .subscribe(com.intellij.openapi.project.ProjectManager.TOPIC, object : com.intellij.openapi.project.ProjectManagerListener {
                override fun projectClosing(p: Project) { if (p === project) closing += project.locationHash }
            })
        project.messageBus.connect(project).subscribe(com.intellij.openapi.vcs.BranchChangeListener.VCS_BRANCH_CHANGED,
            object : com.intellij.openapi.vcs.BranchChangeListener {
                private var open: List<SessionFile> = emptyList()
                override fun branchWillChange(branchName: String) {
                    open = FileEditorManager.getInstance(project).openFiles.filterIsInstance<SessionFile>()
                    switching += project.locationHash
                }
                override fun branchHasChanged(branchName: String) {
                    // Das Umschalten der Reiter läuft danach als eigene Transaktion auf dem EDT - etwas warten, dann die
                    // verlorenen Session-Reiter (dieselben Dateien samt Terminal) wieder öffnen.
                    val files = open; open = emptyList()
                    javax.swing.Timer(1500) {
                        if (project.isDisposed) return@Timer
                        val manager = FileEditorManager.getInstance(project)
                        files.filter { it !in manager.openFiles }.forEach { OpenFileDescriptor(project, it).navigate(false) }
                        switching -= project.locationHash
                        save(project)
                    }.apply { isRepeats = false; start() }
                }
            })
        val lines = com.intellij.ide.util.PropertiesComponent.getInstance(project).getList(KEY).orEmpty()
        if (lines.isEmpty()) return
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater({
            val manager = FileEditorManager.getInstance(project)
            for (line in lines) {
                val parts = line.split('\t')
                val id = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: continue
                if (manager.openFiles.any { it is SessionFile && it.currentSession(project) == id }) continue
                OpenFileDescriptor(project, SessionFile(null, id, parts.getOrNull(1)?.ifBlank { null }, parts.getOrNull(2)?.ifBlank { null }, parts.getOrNull(3)?.ifBlank { null })).navigate(false)
            }
            // Vom Terminal wiederhergestellte, leere Claude-Reiter des letzten Laufs gleich wegräumen.
            SessionTerminals.closeOrphans(project)
        }, project.disposed)
    }
}

/** Auch über "Find Action" erreichbar. */
class NewSessionAction : com.intellij.openapi.actionSystem.AnAction(com.intellij.icons.AllIcons.General.Add), DumbAware {
    override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.BGT
    override fun update(e: com.intellij.openapi.actionSystem.AnActionEvent) {
        e.presentation.isEnabled = e.project != null
        // Im Handoff-Fenster als "+", im Menü New mit dem Handoff-Symbol.
        e.presentation.icon = if (e.place == com.intellij.openapi.actionSystem.ActionPlaces.TOOLWINDOW_TITLE) com.intellij.icons.AllIcons.General.Add else HandoffIcons.editor()
    }
    override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) { e.project?.let { SessionEditors.newSession(it) } }
}

/**
 * Bilder ins Eingabefeld ziehen oder einfügen: Dateien kommen als Pfad in den Prompt (Claude Code liest sie dann),
 * Bilder aus der Zwischenablage (Bildschirmfoto) werden vorher als PNG unter `~/.claude/handoff/images/` gespeichert.
 * Text geht wie gewohnt an den ursprünglichen Handler.
 */
internal class ImageDropHandler(private val delegate: javax.swing.TransferHandler, private val shorten: (String) -> String) : javax.swing.TransferHandler() {
    private val files = java.awt.datatransfer.DataFlavor.javaFileListFlavor
    private val image = java.awt.datatransfer.DataFlavor.imageFlavor

    override fun canImport(support: TransferSupport) =
        support.isDataFlavorSupported(files) || support.isDataFlavorSupported(image) || delegate.canImport(support)

    override fun importData(support: TransferSupport): Boolean {
        val area = support.component as? javax.swing.text.JTextComponent ?: return delegate.importData(support)
        val paths: List<String> = runCatching {
            when {
                support.isDataFlavorSupported(files) ->
                    (support.transferable.getTransferData(files) as List<*>).filterIsInstance<File>().map { it.absolutePath }
                support.isDataFlavorSupported(image) && !support.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.stringFlavor) -> {
                    val img = support.transferable.getTransferData(image) as java.awt.Image
                    listOf(savePng(img).absolutePath)
                }
                support.isDataFlavorSupported(java.awt.datatransfer.DataFlavor.stringFlavor) ->
                    pathsIn(support.transferable.getTransferData(java.awt.datatransfer.DataFlavor.stringFlavor) as String)
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
        if (paths.isEmpty()) return delegate.importData(support)
        val text = paths.joinToString(" ") { shorten(it) }
        val pos = if (support.isDrop) area.viewToModel2D((support.dropLocation as javax.swing.text.JTextComponent.DropLocation).dropPoint.let { it }) .coerceAtLeast(0) else area.caretPosition
        val before = area.text.getOrNull(pos - 1)
        area.document.insertString(pos, (if (before != null && !before.isWhitespace()) " " else "") + text + " ", null)
        return true
    }

    /**
     * Eingefügter Text, der nur aus Pfaden vorhandener Dateien besteht (z. B. Finder ⌥⌘C, Copy Absolute Path, auch mehrere
     * Zeilen, mit Anführungszeichen oder `\ ` vor Leerzeichen), wird wie eine gezogene Datei zur Marke. Steht noch anderer Text
     * dabei, bleibt alles Text.
     */
    private fun pathsIn(text: String): List<String> {
        val lines = text.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty() || lines.size > 20) return emptyList()
        val paths = lines.map { line ->
            val unquoted = if (line.length > 1 && (line[0] == '"' || line[0] == '\'') && line.last() == line[0]) line.substring(1, line.length - 1)
                else line.replace("\\ ", " ")
            if (unquoted.startsWith("~/")) System.getProperty("user.home") + unquoted.substring(1) else unquoted
        }
        return if (paths.all { it.startsWith("/") && File(it).exists() }) paths.map { File(it).absolutePath } else emptyList()
    }

    private fun savePng(img: java.awt.Image): File {
        val dir = File(System.getProperty("user.home"), ".claude/handoff/images").apply { mkdirs() }
        val buffered = java.awt.image.BufferedImage(img.getWidth(null), img.getHeight(null), java.awt.image.BufferedImage.TYPE_INT_ARGB)
        buffered.createGraphics().apply { drawImage(img, 0, 0, null); dispose() }
        val file = File(dir, "paste-" + SimpleDateFormat("yyyyMMdd-HHmmss").format(Date()) + ".png")
        javax.imageio.ImageIO.write(buffered, "png", file)
        return file
    }

    override fun getSourceActions(c: JComponent) = delegate.getSourceActions(c)
    override fun exportToClipboard(comp: JComponent, clip: java.awt.datatransfer.Clipboard, action: Int) = delegate.exportToClipboard(comp, clip, action)
    override fun exportAsDrag(comp: JComponent, e: java.awt.event.InputEvent, action: Int) = delegate.exportAsDrag(comp, e, action)
}

/**
 * Links in Claudes Nachrichten im Session-Reiter: Dateien öffnen sich im Editor (absolut oder relativ zum Arbeitsordner,
 * mit `:zeile` bzw. `#Lzeile`), `settings:<Name>` öffnet die Einstellungsseite, `action:<ActionId>` führt eine IDE-Aktion aus,
 * `handoff:…` eine Plugin-Aktion, alles andere geht in den Browser. Code-Spannen, die eine vorhandene Datei nennen
 * (`src/Foo.kt:12`), werden zu Links.
 */
object SessionLinks {
    private val CODE = Regex("<code>([^<]{2,300})</code>")
    private val WITH_LINE = Regex("""^(.+?)(?::(\d+)(?::\d+)?|#L(\d+))?$""")

    fun open(project: Project, link: String, base: String?) {
        when {
            link.startsWith("settings:") -> com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                .showSettingsDialog(project, urlDecode(link.removePrefix("settings:")))
            link.startsWith("action:") -> runAction(project, link.removePrefix("action:"))
            link.startsWith("handoff:") -> Actions.run(project, link.removePrefix("handoff:"))
            link.startsWith("http://") || link.startsWith("https://") || link.startsWith("mailto:") -> BrowserUtil.browse(link)
            else -> if (!openFile(project, link.removePrefix("file://"), base)) openDir(project, link, base)
        }
    }

    /**
     * Ordner-Link: im Projektfenster auswählen, liegt er außerhalb des Projekts, im Finder zeigen. Nie an den Browser geben -
     * ein relativer Pfad wie `example-plugin-project/x` öffnete sonst eine kaputte Web-Adresse (2026-11-16).
     */
    private fun openDir(project: Project, link: String, base: String?) {
        val dir = fileOf(link, base)?.takeIf { it.isDirectory } ?: return
        val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(dir)
        if (vf != null && com.intellij.openapi.roots.ProjectFileIndex.getInstance(project).isInContent(vf)) {
            com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("Project")?.activate {
                com.intellij.ide.projectView.ProjectView.getInstance(project).select(null, vf, true)
            }
        } else com.intellij.ide.actions.RevealFileAction.openDirectory(dir)
    }

    private fun runAction(project: Project, id: String) {
        val manager = com.intellij.openapi.actionSystem.ActionManager.getInstance()
        val action = manager.getAction(id) ?: return
        manager.tryToExecute(action, null, null, "HandoffSession", true)
    }

    /** Datei (mit Zeile) finden; null, wenn es sie nicht gibt. */
    fun resolve(text: String, base: String?): Pair<File, Int?>? {
        // Raw text first: decoding turns "+" into a space ("a+b.md"); decoded for links with %20 and the like.
        val raw = text.trim()
        return resolveRaw(raw, base) ?: urlDecode(raw).takeIf { it != raw }?.let { resolveRaw(it, base) }
    }

    private fun resolveRaw(text: String, base: String?): Pair<File, Int?>? {
        val m = WITH_LINE.matchEntire(text) ?: return null
        val path = m.groupValues[1]
        if (path.contains('\n') || path.startsWith("-")) return null
        val line = (m.groupValues[2].ifEmpty { m.groupValues[3] }).toIntOrNull()
        val f = File(path.replaceFirst(Regex("^~(?=/)"), System.getProperty("user.home"))).let { if (it.isAbsolute || base == null) it else File(base, path) }
        return if (f.isFile) f to line else null
    }

    fun openFile(project: Project, text: String, base: String?): Boolean {
        val (f, line) = resolve(text, base) ?: return false
        // Archive, Programme u. Ä. nicht als Editor-Reiter, sondern im Finder zeigen (z. B. ein Plugin-ZIP zum Installieren).
        if (f.extension.lowercase() in setOf("zip", "jar", "dmg", "app", "pkg", "gz", "tgz", "7z", "rar")) {
            com.intellij.ide.actions.RevealFileAction.openFile(f.toPath()); return true
        }
        val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f) ?: return false
        OpenFileDescriptor(project, vf, (line ?: 1) - 1, 0).navigate(true)
        return true
    }

    private val SEGMENT = Regex("(<[^>]+>)|([^<]+)")
    // Klammern gehören zur Adresse, solange sie paarig sind (TYPO3: edit[pages][5693]=edit, Wikipedia: Foo_(Bar)) - eine
    // schließende ohne öffnende am Ende ist Satzzeichen ("(siehe https://x.de)"). Abschneiden übernimmt [trimUrl] (2026-11-17).
    private val URL = Regex("""https?://[^\s<>"'`]+""")

    private fun trimUrl(raw: String): String {
        var u = raw
        while (u.isNotEmpty()) {
            val last = u.last()
            u = when {
                last in ".,;:!?" -> u.dropLast(1)
                last == ')' && u.count { it == ')' } > u.count { it == '(' } -> u.dropLast(1)
                last == ']' && u.count { it == ']' } > u.count { it == '[' } -> u.dropLast(1)
                else -> return u
            }
        }
        return u
    }
    private val PATH = Regex("""(?<![\w/.~-])(~?/[\w.@%+-]+(?:/[\w.@%+-]+)+(?::\d+)?)""")

    private fun a(href: String, inner: String) = "<a href=\"${StringUtil.escapeXmlEntities(href)}\">$inner</a>"

    /**
     * Möglichst viel klickbar machen: Code-Spannen mit einem vorhandenen Dateipfad, nackte Web-Adressen und vorhandene
     * absolute Pfade im Fließtext. Nicht innerhalb von Links und Codeblöcken.
     */
    fun linkify(html: String, base: String?): String {
        val out = StringBuilder()
        var inLink = 0; var inPre = 0
        SEGMENT.findAll(html).forEach { m ->
            val tag = m.groupValues[1]
            if (tag.isNotEmpty()) {
                val t = tag.lowercase()
                when {
                    t.startsWith("<a ") || t == "<a>" -> inLink++
                    t == "</a>" -> inLink = (inLink - 1).coerceAtLeast(0)
                    t.startsWith("<pre") -> inPre++
                    t == "</pre>" -> inPre = (inPre - 1).coerceAtLeast(0)
                }
                out.append(tag); return@forEach
            }
            var text = m.groupValues[2]
            if (inLink == 0 && inPre == 0) {
                text = URL.replace(text) { u -> val t = trimUrl(u.value); a(StringUtil.unescapeXmlEntities(t), t) + u.value.substring(t.length) }
                // Pfade nur außerhalb der eben gesetzten Links.
                text = text.split(Regex("(?=<a )|(?<=</a>)")).joinToString("") { part ->
                    if (part.startsWith("<a ")) part
                    else PATH.replace(part) { p -> val raw = StringUtil.unescapeXmlEntities(p.value); if (resolve(raw, base) != null || File(raw.replaceFirst(Regex("^~(?=/)"), System.getProperty("user.home"))).isDirectory) a(raw, p.value) else p.value }
                }
            }
            out.append(text)
        }
        // Code-Spannen danach (sie stehen nicht in Links).
        val withCode = CODE.replace(out.toString()) { m ->
            val raw = StringUtil.unescapeXmlEntities(m.groupValues[1])
            if (raw.contains(' ') || !raw.contains('.') && !raw.contains('/')) return@replace m.value
            if (resolve(raw, base) == null) m.value else a(raw, m.value)
        }
        return boldTargets(withCode)
    }

    private val BOLD_TAG = Regex("<b>([^<]{2,80})</b>")

    /**
     * Fett geschriebene Namen von Einstellungsseiten und IDE-Aktionen ("**Install Plugin from Disk…**") werden zu Links, auch
     * wenn Claude keinen gesetzt hat - nur bei genau einem Treffer ([IdeTargets]), nicht innerhalb vorhandener Links.
     */
    private fun boldTargets(html: String): String {
        if (!IdeTargets.ready()) return html
        val out = StringBuilder(); var last = 0
        for (m in BOLD_TAG.findAll(html)) {
            val before = html.substring(0, m.range.first)
            val open = Regex("<a[ >]").findAll(before).count() - Regex("</a>").findAll(before).count()
            if (open > 0) continue
            val target = IdeTargets.find(StringUtil.unescapeXmlEntities(m.groupValues[1])) ?: continue
            out.append(html, last, m.range.first).append(a(target, m.value)); last = m.range.last + 1
        }
        return if (last == 0) html else out.append(html, last, html.length).toString()
    }

    /** Tooltip für Datei- und Ordner-Links: Pfad relativ zum Projekt, außerhalb absolut; mit Zeile, wenn der Link eine hat. */
    fun pathTip(project: Project, link: String, base: String?): String? {
        val f = fileOf(link, base) ?: return null
        val line = resolve(link.removePrefix("file://"), base)?.second
        val root = project.basePath?.let { File(it).canonicalFile }
        val abs = runCatching { f.canonicalFile }.getOrDefault(f.absoluteFile)
        val shown = root?.let { r -> abs.relativeToOrNull(r)?.path?.takeIf { !it.startsWith("..") } }?.ifEmpty { "." } ?: abs.path
        return if (line != null) "$shown:$line" else shown
    }

    /** Was ein Link ist: Datei bzw. Ordner, Web-Adresse oder IDE-Ziel. */
    fun fileOf(link: String, base: String?): File? {
        if (link.contains(":") && !link.startsWith("/") && !link.startsWith("~") && !link.startsWith("file:") && !Regex("^[^:]+:\\d+").containsMatchIn(link)) return null
        val raw = link.removePrefix("file://")
        resolve(raw, base)?.let { return it.first }
        val d = File(raw.replaceFirst(Regex("^~(?=/)"), System.getProperty("user.home"))).let { if (it.isAbsolute || base == null) it else File(base, raw) }
        return d.takeIf { it.isDirectory }
    }

    /** Rechtsklick auf einen Link: passende Aktionen (Öffnen, im Finder zeigen, Terminal dort, kopieren). */
    fun menu(project: Project, link: String, base: String?): com.intellij.openapi.actionSystem.ActionGroup {
        fun act(text: String, run: () -> Unit) = object : com.intellij.openapi.project.DumbAwareAction(text) {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) = run()
        }
        fun copy(text: String) = act("Copy") { com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(text)) }
        val group = com.intellij.openapi.actionSystem.DefaultActionGroup()
        val file = fileOf(link, base)
        when {
            file != null -> {
                if (file.isFile) group.add(act("Open in Editor") { open(project, link, base) })
                group.add(act("Reveal in Finder") { com.intellij.ide.actions.RevealFileAction.openFile(file.toPath()) })
                if (file.isDirectory) group.add(act("Open as Project") {
                    com.intellij.ide.impl.ProjectUtil.openOrImport(file.path, null, true)
                })
                if (SessionTerminals.available) group.add(act("Open in Terminal") { SessionTerminals.shell(project, (if (file.isDirectory) file else file.parentFile).path) })
                group.add(copy(file.path).apply { templatePresentation.text = "Copy Path" })
            }
            link.startsWith("http") -> {
                group.add(act("Open in Browser") { BrowserUtil.browse(link) })
                group.add(copy(link).apply { templatePresentation.text = "Copy Link" })
            }
            else -> group.add(act("Open") { open(project, link, base) })
        }
        return group
    }
}

/** Ein Eintrag im Verlauf. */
class TranscriptEntry(val kind: Kind, val text: String, val path: String? = null, val line: Int? = null) {
    /** TOOL: erste Zeile der Fehlermeldung, wenn der Aufruf fehlschlug (`tool_result` mit `is_error`). */
    @Volatile var error: String? = null
    /** USER: Zeitpunkt (ms) aus dem Protokoll. */
    @Volatile var time: Long? = null
    /** USER: Kennung der Nutzer-Nachricht im Protokoll (nur echte Prompts, nicht eingereihte) - Ziel für „Rewind“. */
    @Volatile var uuid: String? = null
    /** TOOL: ganzer Aufruf und Ergebnis, zum Aufklappen. */
    @Volatile var detail: String? = null
    @Volatile var output: String? = null
    /** IMAGE: Vorschaugröße in px ([path] ist die Bilddatei). */
    var width = 0
    var height = 0
    /**
     * INFO: graue Statuszeile (Dauer, Away-Summary); DIVIDER: Trennlinie (Compact); FILE: Datei, die die IDE oder der Nutzer
     * mitgeschickt hat - [path]/[line] als Link.
     */
    enum class Kind { USER, ASSISTANT, TOOL, NOTE, SUMMARY, INFO, DIVIDER, FILE, IMAGE }
}

/**
 * Einträge, die ein Rewind aus Handoff verworfen hat, je Session (`~/.claude/handoff/rewound/<id>`): Claude Code lässt sie
 * im Protokoll stehen und setzt die Unterhaltung an einer früheren Nachricht fort.
 */
object Rewinds {
    private val dir = File(System.getProperty("user.home"), ".claude/handoff/rewound")
    fun load(sessionId: String): Set<String> = runCatching { File(dir, sessionId).readLines().filter { it.isNotBlank() }.toSet() }.getOrDefault(emptySet())
    fun add(sessionId: String, uuids: List<String>) {
        if (uuids.isEmpty()) return
        runCatching { dir.mkdirs(); File(dir, sessionId).appendText(uuids.joinToString("\n", postfix = "\n")) }
    }
}

/**
 * Liest das Protokoll einer Session (JSONL von Claude Code) nach und nach: Nur neue, vollständige Zeilen werden gelesen.
 * Seitenpfade (Subagenten), Meta-Einträge und Tool-Ergebnisse bleiben weg.
 */
class Transcript(val path: String) {
    val entries = mutableListOf<TranscriptEntry>()
    var title: String? = null
    /** Per /rename gesetzt: gilt vor dem automatischen Titel, auch vor später geschriebenen ai-title-Zeilen. */
    private var customTitle = false
    /** Remote Control verbunden - letzter `bridge-session`-Eintrag mit Kennung (leer = getrennt), siehe [TranscriptMeta.Info.remote]. */
    @Volatile var remote = false
        private set
    /** Letzter Werkzeugaufruf ungekürzt (Name und Befehl bzw. Datei) - zeigt bei einer Freigabe, worum es geht. */
    var lastTool: String? = null
        private set
    private var offset = 0L
    /**
     * Laufende Hintergrundaufgaben (Kennung → Werkzeugzeile). Claude Code vermerkt den Start im Ergebnis des Befehls
     * (`toolUseResult.backgroundTaskId`), das Ende als Nutzer-Nachricht `<task-notification>` mit `<task-id>` (2.1.325).
     */
    val background = linkedMapOf<String, String>()
    private val toolLines = HashMap<String, String>()
    private val toolEntries = HashMap<String, TranscriptEntry>()
    /**
     * Für „Rewind“: Einträge in Dateireihenfolge, ihre Eltern und welche davon Nachrichten (user/assistant) sind. Nach einem
     * Rewind setzt Claude Code die Unterhaltung an einer früheren Nachricht fort - die verworfenen Einträge bleiben in der
     * Datei stehen ([Rewinds] merkt sie sich, sie werden übersprungen).
     */
    private val order = ArrayList<String>()
    private val parents = HashMap<String, String?>()
    private val messages = HashSet<String>()
    private val discarded: MutableSet<String> = Rewinds.load(File(path).nameWithoutExtension).toMutableSet()

    /** Nachricht, bei der die Unterhaltung vor dem Prompt [promptUuid] weitergeht - null beim ersten Prompt. */
    fun resumeTarget(promptUuid: String): String? {
        var p = parents[promptUuid]
        while (p != null && p !in messages) p = parents[p]
        return p
    }

    /** Ab [uuid] (ausschließlich) gelesene Einträge verwerfen und das Protokoll neu einlesen. */
    @Synchronized fun discardAfter(uuid: String) {
        val i = order.indexOf(uuid).takeIf { it >= 0 } ?: return
        val gone = order.subList(i + 1, order.size).toList()
        Rewinds.add(File(path).nameWithoutExtension, gone)
        discarded += gone
        offset = 0; entries.clear(); background.clear(); toolLines.clear(); toolEntries.clear(); order.clear(); parents.clear(); messages.clear()
    }

    /** Neue Zeilen einlesen; true, wenn sich etwas geändert hat. */
    @Synchronized fun update(): Boolean {
        val file = File(path)
        if (!file.isFile) return false
        val length = file.length()
        if (length < offset) { offset = 0; remote = false; entries.clear(); background.clear(); toolLines.clear(); toolEntries.clear(); order.clear(); parents.clear(); messages.clear() }
        if (length == offset) return false
        val bytes = RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            ByteArray((length - offset).coerceAtMost(32L * 1024 * 1024).toInt()).also { raf.readFully(it) }
        }
        val end = bytes.lastIndexOf('\n'.code.toByte())
        if (end < 0) return false
        offset += end + 1
        val before = listOf(entries.size, title, background.keys.toList(), remote)
        String(bytes, 0, end, Charsets.UTF_8).lineSequence().forEach { line -> if (line.isNotBlank()) runCatching { parse(JsonParser.parseString(line).asJsonObject) } }
        return listOf(entries.size, title, background.keys.toList(), remote) != before
    }

    private fun JsonObject.str(k: String) = get(k)?.takeIf { it.isJsonPrimitive }?.asString

    private fun parse(o: JsonObject) {
        if (o.get("isSidechain")?.asBoolean == true) return
        val uuid = o.str("uuid")
        if (uuid != null) {
            if (uuid in discarded) return
            order += uuid
            parents[uuid] = o.str("parentUuid")
            if (o.str("type") == "user" || o.str("type") == "assistant") messages += uuid
        }
        if (o.get("isMeta")?.asBoolean == true) return
        when (o.str("type")) {
            "ai-title" -> if (!customTitle) title = o.str("aiTitle") ?: title
            "custom-title" -> o.str("customTitle")?.takeIf { it.isNotBlank() }?.let { title = it; customTitle = true }
            "bridge-session" -> remote = !o.str("bridgeSessionId").isNullOrEmpty()
            // Während Claude arbeitet abgeschickte Prompts stehen nur als Anhang im Protokoll.
            "attachment" -> {
                val a = o.getAsJsonObject("attachment") ?: return
                if (a.str("type") == "queued_command") a.str("prompt")?.trim()?.let { prompt ->
                    // Endet eine Hintergrund-Aufgabe, während Claude arbeitet, kommt die <task-notification> als eingereihter
                    // Befehl statt als Nutzer-Nachricht - ohne diese Prüfung blieben solche Aufgaben ewig "running" (2026-11-16).
                    TASK_DONE.findAll(prompt).forEach { background.remove(it.groupValues[1]) }
                    userText(prompt)?.let { it.time = time(o); entries += it }
                }
                attachment(a)?.let { entries += it }
            }
            // Lokale Slash-Befehle (/rename …) und ihre Ausgabe schreibt Claude Code als "system"/"local_command" - ohne diesen
            // Zweig blieb der Befehl im Tab ewig "Queued", obwohl er längst gelaufen war (2026-11-16).
            "system" -> when (o.str("subtype")) {
                "local_command" -> o.str("content")?.let { userText(it) }?.let { entries += it }
                // Unbekannter Befehl/Skill: Claude Code schreibt nur Warnungen, keinen Prompt - der Befehl blieb im Tab bei
                // "Running…" (2026-11-18). Als Prompt mit der Warnung darunter zeigen; die Args-Zeile hängt am Prompt.
                "informational" -> o.str("content")?.let { c ->
                    val unknown = Regex("^Unknown (?:command|skill): (/\\S+)").find(c)?.groupValues?.get(1)
                    val args = Regex("^Args from unknown skill: (.*)$").find(c)?.groupValues?.get(1)
                    when {
                        unknown != null -> { userText(unknown)?.let { it.time = time(o); entries += it }; entries += TranscriptEntry(TranscriptEntry.Kind.INFO, "⚠ $c") }
                        args != null -> entries.lastOrNull { it.kind == TranscriptEntry.Kind.USER }?.let { u ->
                            entries[entries.lastIndexOf(u)] = TranscriptEntry(TranscriptEntry.Kind.USER, u.text + " " + args).also { it.time = u.time }
                        }
                        o.str("level") == "warning" -> entries += TranscriptEntry(TranscriptEntry.Kind.INFO, "⚠ $c")
                        else -> {}
                    }
                }
                "turn_duration" -> o.get("durationMs")?.asLong?.let { entries += TranscriptEntry(TranscriptEntry.Kind.INFO, "⏱ Worked for " + duration(it)) }
                "compact_boundary" -> {
                    val m = o.getAsJsonObject("compactMetadata")
                    val parts = listOfNotNull("Conversation compacted", m?.str("trigger"), m?.get("preTokens")?.asLong?.let { "${(it + 500) / 1000}k tokens" })
                    entries += TranscriptEntry(TranscriptEntry.Kind.DIVIDER, parts.joinToString(" · "))
                }
                // Rückblick, den Claude Code nach einer Pause schreibt.
                "away_summary" -> o.str("content")?.replace(Regex("\\s*\\(disable recaps[^)]*\\)"), "")?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { entries += TranscriptEntry(TranscriptEntry.Kind.INFO, "↩ While you were away: $it") }
            }
            "user" -> {
                val content = o.getAsJsonObject("message")?.get("content") ?: return
                trackBackground(o, content)
                results(content)
                val text = texts(content).joinToString("\n").trim()
                // Zusammenfassung nach /compact: steht als Nutzer-Nachricht im Protokoll, wird eingeklappt gezeigt.
                if (o.get("isCompactSummary")?.asBoolean == true) { if (text.isNotEmpty()) entries += TranscriptEntry(TranscriptEntry.Kind.SUMMARY, text); return }
                userText(text)?.let { it.time = time(o); if (it.kind == TranscriptEntry.Kind.USER) it.uuid = uuid; entries += it }
                images(content)
            }
            "assistant" -> {
                val content = o.getAsJsonObject("message")?.get("content")?.takeIf { it.isJsonArray } ?: return
                content.asJsonArray.forEach { item ->
                    val c = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                    when (c.str("type")) {
                        "text" -> c.str("text")?.trim()?.takeIf { it.isNotEmpty() }?.let { entries += TranscriptEntry(TranscriptEntry.Kind.ASSISTANT, it) }
                        "tool_use" -> {
                            val line = toolLine(c.str("name").orEmpty(), c.getAsJsonObject("input"))
                            c.str("id")?.let { toolLines[it] = line }
                            if (c.str("name") == "TaskStop") c.getAsJsonObject("input")?.let { i -> (i.str("task_id") ?: i.str("shell_id"))?.let { background.remove(it) } }
                            entries += TranscriptEntry(TranscriptEntry.Kind.TOOL, line).also { e ->
                                e.detail = toolDetail(c.getAsJsonObject("input"))
                                c.str("id")?.let { toolEntries[it] = e }
                            }
                            lastTool = fullTool(c.str("name").orEmpty(), c.getAsJsonObject("input"))
                        }
                    }
                }
            }
        }
    }

    private fun trackBackground(o: JsonObject, content: JsonElement) {
        o.getAsJsonObject("toolUseResult")?.str("backgroundTaskId")?.let { task ->
            val toolId = content.takeIf { it.isJsonArray }?.asJsonArray?.firstNotNullOfOrNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject?.str("tool_use_id") }
            background[task] = toolId?.let { toolLines[it] } ?: "Background task"
        }
        texts(content).forEach { t -> TASK_DONE.findAll(t).forEach { background.remove(it.groupValues[1]) } }
    }

    /**
     * Ergebnisse der Werkzeugaufrufe: zum Aufklappen an die Zeile des Aufrufs; schlug er fehl, bekommt die Zeile die erste
     * Zeile der Fehlermeldung.
     */
    private fun results(content: JsonElement) {
        if (!content.isJsonArray) return
        content.asJsonArray.forEach { item ->
            val c = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            if (c.str("type") != "tool_result") return@forEach
            val entry = c.str("tool_use_id")?.let { toolEntries[it] } ?: return@forEach
            val text = c.get("content")?.let { texts(it).joinToString("\n") }.orEmpty()
            entry.output = clip(text.trimEnd())
            if (c.get("is_error")?.asBoolean != true) return@forEach
            entry.error = text.replace(Regex("</?tool_use_error>"), "").lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
                ?.let { StringUtil.shortenTextWithEllipsis(it, 160, 0) } ?: "failed"
        }
    }

    /** Dateien, die mit dem nächsten Prompt mitgehen: in der IDE offen oder markiert, per @ angehängt, vom Nutzer geändert. */
    private fun attachment(a: JsonObject): TranscriptEntry? {
        val path = a.str("filename") ?: return null
        val (label, line) = when (a.str("type")) {
            "opened_file_in_ide" -> "Open in the IDE:" to null
            "selected_lines_in_ide" -> {
                val from = a.get("lineStart")?.asInt; val to = a.get("lineEnd")?.asInt
                (if (from != null && to != null && to != from) "Selected lines $from–$to in" else "Selected line ${from ?: ""} in".replace("  ", " ")) to from
            }
            "file" -> "Attached:" to null
            "edited_text_file" -> "You edited" to null
            else -> return null
        }
        return TranscriptEntry(TranscriptEntry.Kind.FILE, label, path, line)
    }

    private fun clip(t: String) = if (t.length <= 6000) t else t.take(6000) + "\n… (${t.length - 6000} more characters)"

    /** Aufruf zum Aufklappen: ein Befehl als Text, sonst die Eingabe als JSON. */
    private fun toolDetail(input: JsonObject?): String? {
        input ?: return null
        input.get("command")?.takeIf { it.isJsonPrimitive }?.asString?.let { return clip(it) }
        return clip(com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(input))
    }

    private fun time(o: JsonObject): Long? = o.str("timestamp")?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }

    /**
     * Eingefügte Bilder als Vorschau: Die Daten stehen base64 im Protokoll; Swing-HTML zeigt nur Bilder aus Dateien, deshalb
     * einmal je Inhalt in den Temp-Ordner schreiben.
     */
    private fun images(content: JsonElement) {
        if (!content.isJsonArray) return
        content.asJsonArray.forEach { item ->
            val c = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            if (c.str("type") != "image") return@forEach
            val src = c.getAsJsonObject("source")?.takeIf { it.str("type") == "base64" } ?: return@forEach
            val data = src.str("data") ?: return@forEach
            runCatching {
                val ext = src.str("media_type")?.substringAfter('/')?.takeIf { it.matches(Regex("[a-z]{3,4}")) } ?: "png"
                val name = Integer.toHexString(data.hashCode()) + "-" + data.length + "." + ext
                val f = File(IMAGE_DIR, name)
                if (!f.isFile) { IMAGE_DIR.mkdirs(); f.writeBytes(java.util.Base64.getMimeDecoder().decode(data)) }
                val img = javax.imageio.ImageIO.read(f) ?: return@runCatching
                val scale = minOf(1.0, 240.0 / img.width, 160.0 / img.height)
                entries += TranscriptEntry(TranscriptEntry.Kind.IMAGE, "image", f.path).also {
                    it.width = (img.width * scale).toInt().coerceAtLeast(1); it.height = (img.height * scale).toInt().coerceAtLeast(1)
                }
            }
        }
    }

    private fun duration(ms: Long): String {
        val s = (ms + 500) / 1000
        return when { s < 60 -> "$s s"; s < 3600 -> "${s / 60} min ${s % 60} s"; else -> "${s / 3600} h ${s / 60 % 60} min" }
    }

    private companion object {
        val IMAGE_DIR = File(System.getProperty("java.io.tmpdir"), "handoff-images")
        val TASK_DONE = Regex("<task-notification>\\s*<task-id>([^<]+)</task-id>")
        private val FROM_IDE = Regex("(?s)The user sent this follow-up prompt from the IDE \\(Handoff tool window\\):\\s*(.*?)\\s*(?:</system-reminder>|$)")
    }

    private fun texts(content: JsonElement): List<String> = when {
        content.isJsonPrimitive -> listOf(content.asString)
        content.isJsonArray -> content.asJsonArray.mapNotNull { item ->
            val c = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            when (c.str("type")) { "text" -> c.str("text"); "image" -> "[image]"; else -> null }
        }
        else -> emptyList()
    }

    /** Prompts des Nutzers; Slash-Befehle kommen als `<command-name>`, Ausgaben lokaler Befehle als `<local-command-stdout>`. */
    private fun userText(raw: String): TranscriptEntry? {
        // Eingefügten Text (Handoff schickt mehrzeilige Prompts als Einfügen) schließt Claude Code in
        // <pasted_content id="abcd">…</pasted_content id="abcd"> ein - ohne Abzug fiele der Prompt unten als "<…" weg.
        val text = unwrapPasted(raw)
        if (text.isEmpty()) return null
        // Abbruch per Esc: Claude Code schreibt ihn als Nutzer-Nachricht "[Request interrupted by user…]".
        if (text.startsWith("[Request interrupted by user")) return TranscriptEntry(TranscriptEntry.Kind.NOTE, "Interrupted")
        Regex("<command-name>(.*?)</command-name>").find(text)?.let { m ->
            val args = Regex("(?s)<command-args>(.*?)</command-args>").find(text)?.groupValues?.get(1)?.trim().orEmpty()
            return TranscriptEntry(TranscriptEntry.Kind.USER, (m.groupValues[1].trim() + " " + args).trim())
        }
        Regex("(?s)<local-command-stdout>(.*?)</local-command-stdout>").find(text)?.let { m ->
            // "(ctrl+o to see full summary)" gilt nur im Terminal - hier klappt die Zusammenfassung per Klick auf.
            val out = m.groupValues[1].replace(Regex("\u001B\\[[0-9;]*m"), "").replace(Regex("\\s*\\(ctrl\\+o[^)]*\\)"), "").trim()
            return if (out.isEmpty()) null else TranscriptEntry(TranscriptEntry.Kind.NOTE, out)
        }
        // Prompt aus der IDE über den wartenden Stop-Hook: Claude Code 2.1.327 schreibt ihn als <task-notification> mit
        // "Stop hook blocking error from command "Stop": The user sent …" in einem <system-reminder>. Ohne diesen Zweig fehlte
        // die Sprechblase im Verlauf und blieb als "Running…" unten kleben, obwohl Claude längst geantwortet hatte (2026-11-19).
        FROM_IDE.find(text)?.let { m -> return m.groupValues[1].trim().takeIf { it.isNotEmpty() }?.let { TranscriptEntry(TranscriptEntry.Kind.USER, it) } }
        if (text.startsWith("<")) return null
        return TranscriptEntry(TranscriptEntry.Kind.USER, text)
    }

    /** Werkzeugaufruf für die Freigabe: Name und der ganze Befehl (bzw. Datei, Adresse …), nicht gekürzt. */
    private fun fullTool(name: String, input: JsonObject?): String {
        val short = name.removePrefix("mcp__").replace("__", " · ")
        val detail = input?.let { i ->
            listOf("command", "file_path", "pattern", "url", "query", "description", "prompt")
                .firstNotNullOfOrNull { k -> i.get(k)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() } }
        }
        return if (detail != null) "$short: $detail" else short
    }

    private fun toolLine(name: String, input: JsonObject?): String {
        val short = name.removePrefix("mcp__").replace("__", " · ")
        val detail = input?.let { i ->
            listOf("command", "file_path", "pattern", "url", "query", "description", "prompt", "title", "summary", "reason")
                .firstNotNullOfOrNull { k -> i.get(k)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() } }
        }?.lineSequence()?.first()?.let { StringUtil.shortenTextWithEllipsis(it, 140, 0) }
        return if (detail != null) "$short: $detail" else short
    }
}

private class SessionEditor(private val project: Project, private val file: SessionFile) : UserDataHolderBase(), FileEditor {
    private val sessions = WaitingSessions.getInstance(project)
    private val time = SimpleDateFormat("HH:mm")
    private var transcript: Transcript? = null
    private var stateKey: String? = null
    private var loading = false
    /** Aus diesem Reiter gesendete Prompts, bis sie im Protokoll stehen. */
    private val pending = mutableListOf<String>()
    /**
     * Texte hinter den Kopier-Links des aktuellen Verlaufs (Kennung → Text), bei jedem Neuzeichnen neu. Hier oben: Der
     * Konstruktor zeichnet den Verlauf schon (weiter unten deklariert war das Feld dann noch null, Sandbox 2026-11-21).
     */
    private val copyTexts = HashMap<String, String>()
    /**
     * Dateien der Session nur neu suchen, wenn neue Einträge da sind (prüft jeden Pfad im Dateisystem). Hier oben: Der
     * Konstruktor ruft updateState schon auf - weiter unten deklariert war das Feld null (NPE, Reiter ging nicht auf, 2026-11-27).
     */
    private var attachedCache: Pair<Int, List<Attachments.Item>> = -1 to emptyList()
    /** Zahl der Prompts im Protokoll, als der älteste vorgemerkte abgeschickt wurde. */
    private var pendingBase = 0
    /** Davon per Ctrl+Enter sofort gesendet. */
    private val sentNow = mutableSetOf<String>()
    /** Abgeschickt, während Claude nichts tat - läuft also gleich, ist nicht vorgemerkt (z. B. /compact per ▶, 2026-11-18). */
    private val startedIdle = mutableSetOf<String>()
    /** Vorschlag von Claude Code für den nächsten Prompt (grau im leeren Feld, Tab übernimmt ihn). */
    // Hier oben deklariert: renderTranscript (und damit applyEmptyText) läuft schon im init (2026-11-18).
    private var suggestion: String? = null
    private var baseEmptyText = ""
    /** Aufgeklappte Gruppen von Werkzeug-Schritten (Index des ersten Schritts im Protokoll). */
    private val expanded = mutableSetOf<Int>()
    /** Aufgeklappte einzelne Werkzeug-Schritte (Index im Protokoll): ganzer Aufruf und Ergebnis. */
    private val openTools = mutableSetOf<Int>()
    /** So viele Einträge (die neuesten) zeigen; "Show earlier entries" erhöht. */
    private var limit = MAX_ENTRIES

    /** Stil mit den Farben des aktuellen Themes - nach einem Wechsel neu gebaut (die Farben stehen als Text im CSS). */
    private fun styledKit() = HTMLEditorKitBuilder().withViewFactoryExtensions(
        com.intellij.util.ui.ExtendableHTMLViewFactory.Extensions.WORD_WRAP, BUBBLE).build().also { kit ->
            val fg = UIUtil.getContextHelpForeground()
            val grey = "#%02x%02x%02x".format(fg.red, fg.green, fg.blue)
            val bg = JBColor.namedColor("EditorTabs.underlinedTabBackground", JBColor(0xEEF2F8, 0x2E3440))
            val userBg = "#%02x%02x%02x".format(bg.red, bg.green, bg.blue)
            kit.styleSheet.addRule("body { margin: ${JBUI.scale(12)}px ${JBUI.scale(24)}px; }")
            kit.styleSheet.addRule("h1 { font-size: 140%; } h2 { font-size: 120%; } h3 { font-size: 108%; }")
            kit.styleSheet.addRule("pre { padding: ${JBUI.scale(6)}px; border: 1px solid $grey; }")
            kit.styleSheet.addRule("td, th { padding: 3px ${JBUI.scale(8)}px 3px 0; text-align: left; vertical-align: top; }")
            kit.styleSheet.addRule(".user { padding: ${JBUI.scale(6)}px ${JBUI.scale(10)}px; margin-top: ${JBUI.scale(14)}px; margin-bottom: ${JBUI.scale(3)}px; }")
            kit.styleSheet.addRule(".tool { color: $grey; margin-top: 2px; margin-bottom: 2px; }")
            kit.styleSheet.addRule(".note { color: $grey; }")
            kit.styleSheet.addRule(".error { color: ${com.intellij.ui.ColorUtil.toHtmlColor(JBColor.namedColor("Label.errorForeground", JBColor(0xC7222D, 0xE55765)))}; }")
            kit.styleSheet.addRule(".summary { color: $grey; margin-left: 14px; }")
            kit.styleSheet.addRule(".divider { color: $grey; text-align: center; margin-top: 8px; margin-bottom: 8px; }")
            kit.styleSheet.addRule(".pending { color: $grey; }")
            kit.styleSheet.addRule(".copyline { font-size: 90%; margin-top: 0; margin-bottom: 2px; }")
            kit.styleSheet.addRule("a.copy { color: $grey; text-decoration: none; }")
            kit.styleSheet.addRule(".time { color: $grey; font-size: 90%; padding: 0 0 0 ${JBUI.scale(12)}px; text-align: right; vertical-align: top; }")
            kit.styleSheet.addRule("td.prompt { padding: 0; vertical-align: top; }")
        }

    private val pane = JEditorPane().apply {
        editorKit = styledKit()
        isEditable = false
        addHyperlinkListener { e ->
            if (e.eventType != HyperlinkEvent.EventType.ACTIVATED) return@addHyperlinkListener
            val link = e.description ?: e.url?.toString() ?: return@addHyperlinkListener
            if (link.startsWith(RUN_LINK)) {
                val cmd = urlDecode(link.removePrefix(RUN_LINK))
                if (file.tab != null && terminalOpen) runCommand(cmd)
                else notice("Slash commands only reach sessions started here (New Claude Session).")
                return@addHyperlinkListener
            }
            if (link.startsWith(REWIND_LINK)) { rewind(link.removePrefix(REWIND_LINK)); return@addHyperlinkListener }
            if (link.startsWith(COPY_LINK)) {
                copyTexts[link.removePrefix(COPY_LINK)]?.let { text ->
                    com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(text))
                    notice("Copied to the clipboard.", error = false)
                }
                return@addHyperlinkListener
            }
            if (link.startsWith(PROMPT_LINK)) {
                input.text = urlDecode(link.removePrefix(PROMPT_LINK))
                input.requestFocusInWindow(); input.caretPosition = input.document.length
                return@addHyperlinkListener
            }
            if (link.startsWith(TOOL_LINK)) {
                link.removePrefix(TOOL_LINK).toIntOrNull()?.let { if (!openTools.remove(it)) openTools += it; renderTranscript(force = false) }
                return@addHyperlinkListener
            }
            if (link == MORE_LINK) { limit += MAX_ENTRIES; renderTranscript(force = false); SwingUtilities.invokeLater { scroll.verticalScrollBar.value = 0 }; return@addHyperlinkListener }
            if (link.startsWith(STEPS_LINK)) {
                link.removePrefix(STEPS_LINK).toIntOrNull()?.let { if (!expanded.remove(it)) expanded += it; renderTranscript(force = false) }
                return@addHyperlinkListener
            }
            SessionLinks.open(project, link, baseDir())
        }
        // Tooltips für Slash-Befehle und Datei-Links (Swing-HTML zeigt kein title-Attribut an).
        addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
            private var tipHref: String? = null
            private var tipText: String? = null
            override fun mouseMoved(e: java.awt.event.MouseEvent) {
                val href = linkAt(e.point)
                val tip = when {
                    href?.startsWith(RUN_LINK) == true -> "Run now"
                    href?.startsWith(PROMPT_LINK) == true -> "Use as prompt"
                    href?.startsWith(REWIND_LINK) == true -> "Rewind to before this prompt…"
                    href == null || href.startsWith(STEPS_LINK) || href.startsWith(TOOL_LINK) || href == MORE_LINK ||
                        href.startsWith(COPY_LINK) -> null
                    // Datei-/Ordner-Links: Pfad zeigen; das Dateisystem nur abfragen, wenn sich der Link ändert.
                    else -> if (href == tipHref) tipText else SessionLinks.pathTip(project, href, baseDir()).also { tipHref = href; tipText = it }
                }
                if (tip != toolTipText) toolTipText = tip
            }
        })
        // Rechtsklick auf einen Link: Aktionen je nach Ziel.
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mousePressed(e: java.awt.event.MouseEvent) = popup(e)
            override fun mouseReleased(e: java.awt.event.MouseEvent) = popup(e)
            private fun popup(e: java.awt.event.MouseEvent) {
                if (!e.isPopupTrigger) return
                val href = linkAt(e.point)?.takeUnless { it.startsWith(STEPS_LINK) || it.startsWith(TOOL_LINK) || it == MORE_LINK || it.startsWith(PROMPT_LINK) || it.startsWith(RUN_LINK) || it.startsWith(REWIND_LINK) } ?: return
                com.intellij.openapi.actionSystem.ActionManager.getInstance()
                    .createActionPopupMenu("HandoffSession", SessionLinks.menu(project, href, baseDir())).component.show(this@apply, e.x, e.y)
                e.consume()
            }
        })
    }

    /** href des Links unter dem Mauszeiger. */
    private fun linkAt(p: java.awt.Point): String? {
        val pos = pane.viewToModel2D(p).takeIf { it >= 0 } ?: return null
        val doc = pane.document as? javax.swing.text.html.HTMLDocument ?: return null
        val attrs = doc.getCharacterElement(pos).attributes.getAttribute(javax.swing.text.html.HTML.Tag.A) as? javax.swing.text.AttributeSet ?: return null
        return attrs.getAttribute(javax.swing.text.html.HTML.Attribute.HREF) as? String
    }
    /** Wartende Handoff-Karten (Befehle, Fragen, Reviews) am Ende des Verlaufs, damit man hier antworten kann. */
    private val cards: HandoffPanel = HandoffPanel(project, compact = true, sessionOf = { file.currentSession(project) }, onNewCard = { SwingUtilities.invokeLater { if (scroll.isShowing) scrollToEnd() else endOnShow = true } }).apply { border = JBUI.Borders.empty(0, JBUI.scale(24) - 8, JBUI.scale(12), JBUI.scale(24) - 8) }

    /** Drehendes Symbol unter dem letzten Prompt, solange Claude arbeitet - man sieht es, ohne nach oben zu schauen. */
    private val busy = JBLabel("Claude is working…", com.intellij.ui.AnimatedIcon.Default(), javax.swing.SwingConstants.LEFT).apply {
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.empty(0, JBUI.scale(24), JBUI.scale(10), 0)
        isVisible = false
    }

    /** Verlauf und Karten untereinander, zusammen scrollend; folgt der Breite, damit Text und Karten umbrechen. */
    private val conversation = object : JPanel(), javax.swing.Scrollable {
        override fun getPreferredScrollableViewportSize(): java.awt.Dimension = preferredSize
        override fun getScrollableUnitIncrement(r: java.awt.Rectangle, o: Int, d: Int) = JBUI.scale(16)
        override fun getScrollableBlockIncrement(r: java.awt.Rectangle, o: Int, d: Int) = r.height
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }.apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = pane.background
        pane.alignmentX = 0f; cards.alignmentX = 0f; busy.alignmentX = 0f
        add(pane); add(busy); add(cards)
    }
    private val scroll: JBScrollPane = JBScrollPane(conversation).apply { border = JBUI.Borders.empty() }

    /**
     * Wird der Reiter schmaler (z. B. Handoff-Fenster geht auf), bricht der Verlauf um, wird höher und die Ansicht sprang
     * nach oben (2026-11-16). War sie am Ende, bleibt sie jetzt dort; Bewegungen der Leiste während einer Breitenänderung
     * zählen nicht als Scrollen des Nutzers.
     */
    private var lastWidth = -1
    private var stickToEnd = true
    /**
     * Neue Karte, während der Reiter im Hintergrund war: beim nächsten Zeigen ans Ende. Im Hintergrund legt Swing nichts neu
     * aus (RepaintManager.addInvalidComponent übergeht unsichtbare Wurzeln) - ein Scrollen zur Karte nahm deren veraltete
     * Position und sprang nach oben, ebenso Leisten-Änderungen beim Entfernen der Karte (2026-11-16).
     */
    private var endOnShow = false
    init {
        scroll.addHierarchyListener { e ->
            if (e.changeFlags and java.awt.event.HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && scroll.isShowing) {
                if (stickToEnd || endOnShow) scrollToEnd()
                endOnShow = false
            }
        }
        scroll.verticalScrollBar.model.addChangeListener {
            if (!scroll.isShowing) return@addChangeListener
            val bar = scroll.verticalScrollBar
            val w = scroll.viewport.width
            // Auch eine Höhenänderung ohne neue Breite (Umbruch kommt verzögert, Bilder laden nach) ist kein Scrollen des
            // Nutzers: dann zurück zur gemerkten Stelle statt sie zu überschreiben - sonst sprang die Ansicht beim Öffnen
            // des Handoff-Fensters weiter nach oben (2026-11-18, zweites Mal).
            val h = bar.maximum
            if (w != lastWidth || (h != lastHeight && !renderingNow)) {
                lastWidth = w; lastHeight = h
                if (stickToEnd) scrollToEnd() else restoreAnchor()
            }
            else if (!restoring) {
                stickToEnd = bar.value + bar.visibleAmount >= bar.maximum - JBUI.scale(40)
                anchor = anchorAt(bar.value)
            }
        }
    }

    /**
     * Stelle im Text, die oben in der Ansicht steht (Zeichenposition, Abstand darunter). Nach dem Umbrechen bei neuer Breite
     * scrollt die Ansicht dorthin zurück - der Pixelwert allein zeigte danach eine viel frühere Stelle, die Ansicht sprang
     * beim Öffnen des Handoff-Fensters weit nach oben (2026-11-18).
     */
    private var anchor: Pair<Int, Int>? = null
    private var lastHeight = -1
    /** Neuer Inhalt wird gerade gesetzt - dessen Höhenänderung ist gewollt (renderTranscript scrollt selbst). */
    private var renderingNow = false
    private var restoring = false

    private fun anchorAt(y: Int): Pair<Int, Int>? = runCatching {
        val py = y - pane.y
        if (py < 0) return null
        val pos = pane.viewToModel2D(java.awt.Point(JBUI.scale(4), py))
        val top = pane.modelToView2D(pos)?.bounds?.y ?: return null
        pos to (py - top)
    }.getOrNull()

    private fun restoreAnchor() {
        val (pos, offset) = anchor ?: return
        restoring = true
        // Erst nach dem neuen Layout steht die Position der Stelle fest.
        SwingUtilities.invokeLater {
            try {
                val r = runCatching { pane.modelToView2D(pos.coerceAtMost(pane.document.length))?.bounds }.getOrNull()
                if (r != null) scroll.verticalScrollBar.value = pane.y + r.y + offset
            } finally { SwingUtilities.invokeLater { restoring = false } }
        }
    }

    // Darf in der Breite schrumpfen, nie in der Höhe: Mindesthöhe 0 ließ BoxLayout die Zeile oben anheften und unten
    // abschneiden (2026-11-16, Unterlängen fehlten).
    private val status = object : JBLabel() { override fun getMinimumSize() = java.awt.Dimension(0, preferredSize.height) }
    /** Arbeitsordner der Session; Klick zeigt ihn im Finder bzw. Explorer. */
    private val dirLink = object : ActionLink("", { _: java.awt.event.ActionEvent -> currentDir()?.let { com.intellij.ide.actions.RevealFileAction.openDirectory(File(it)) } }) {
        override fun getMinimumSize() = java.awt.Dimension(0, preferredSize.height)
    }.apply {
        foreground = UIUtil.getContextHelpForeground()
        toolTipText = "Working directory of the session - click to reveal"
        horizontalAlignment = javax.swing.SwingConstants.LEFT
        // Darf schrumpfen: Bei schmalem Reiter wird der Pfad mit "…" gekürzt, statt unter "Show Terminal" zu liegen.
    }
    /** Plan-Limits (5 Stunden, Woche) und - für diese Session - Modell und Kontext, aus den Daten der Statuszeile. */
    private val limits = object : JBLabel() { override fun getMinimumSize() = java.awt.Dimension(0, preferredSize.height) }.apply {
        foreground = UIUtil.getContextHelpForeground()
        horizontalAlignment = javax.swing.SwingConstants.CENTER
        border = JBUI.Borders.emptyLeft(12)
    }
    private val headerLinks = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(12), 0)).apply { isOpaque = false }
    // Links und Status bekommen ihre volle Breite, die Mitte (Pfad, dann Limits) den Rest - BorderLayout.WEST mit Status und
    // Pfad überlagerte bei schmalem Reiter die Links (2026-11-15).
    private val header = JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
        border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(6, 12))
        // Status, Pfad und Limits teilen sich den Rest und werden mit "…" gekürzt; nur die Links rechts behalten ihre Breite
        // (2026-11-15: "Claude is working… since" lag bei schmalem Reiter unter "Interrupt (Esc)").
        add(JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(status); add(Box.createHorizontalStrut(JBUI.scale(12))); add(dirLink); add(Box.createHorizontalGlue()); add(limits)
        }, BorderLayout.CENTER)
        // Links senkrecht mittig wie die Texte links (FlowLayout setzt sie sonst oben an).
        add(JPanel(java.awt.GridBagLayout()).apply { isOpaque = false; add(headerLinks) }, BorderLayout.EAST)
    }
    private var limitsReadAt = 0L
    private var limitTips: List<String> = emptyList()

    /**
     * Ein Tooltip mit allem, was die Kopfzeile zeigt (Status, Ordner, Modell, Kontext, Limits) plus die Reset-Zeiten - an jedem
     * Teil gleich, denn bei schmalem Reiter sind die Texte mit "…" gekürzt (2026-11-18).
     */
    private fun updateHeaderTip() {
        val lines = mutableListOf<String>()
        status.text?.takeIf { it.isNotBlank() }?.let { lines += "<b>${StringUtil.escapeXmlEntities(it)}</b>" }
        currentDir()?.let { lines += StringUtil.escapeXmlEntities(it) }
        // <nobr>: Der Tooltip der IDE bricht HTML sonst schon bei rund 250 px um - Pfad und Rücksetzzeit rissen mitten
        // durch ("Do. 00:" / "20", 2026-11-18).
        val head = lines.joinToString("<br>") { "<nobr>$it</nobr>" }
        // Modell, Kontext und Limits als Tabelle: Name | Balken in der Farbe des Verbrauchs | Prozent und Rücksetzzeit.
        val rows = limitTips.takeIf { it.isNotEmpty() }?.joinToString("", "<table cellspacing=\"0\" cellpadding=\"2\">", "</table>")
        val tip = listOfNotNull(head.takeIf { it.isNotEmpty() }, rows).takeIf { it.isNotEmpty() }?.joinToString("<br>", "<html>", "</html>")
        if (status.toolTipText != tip) status.toolTipText = tip
        if (limits.toolTipText != tip) limits.toolTipText = tip
        val dirTip = tip?.replace("</html>", "<br><br>Click to reveal the folder</html>")
        if (dirLink.toolTipText != dirTip) dirLink.toolTipText = dirTip
    }

    private val statusBySession get() = SessionEditors.statusBySession

    private fun updateLimits(id: String?) {
        val now = System.currentTimeMillis()
        if (now - limitsReadAt < 5000) return
        limitsReadAt = now
        val file = PlanLimits.data
        if (!PlanLimits.isInstalled() || !file.isFile) { limits.text = ""; return }
        val l = PlanLimits.read()
        val o = runCatching { JsonParser.parseString(file.readText()).asJsonObject }.getOrNull()
        // statusline.json hält nur die Session, die zuletzt ihre Statuszeile aktualisiert hat - darum je Session merken,
        // sonst fehlten Modell, Effort und Kontext im Tooltip, sobald eine andere Session gearbeitet hat.
        o?.get("session_id")?.takeIf { it.isJsonPrimitive }?.asString?.let { statusBySession[it] = o }
        val mine = id?.let { statusBySession[it] }
        val parts = mutableListOf<String>()
        val tips = mutableListOf<String>()
        if (mine != null) {
            fun str(vararg path: String): String? {
                var e: com.google.gson.JsonElement? = mine
                for (k in path) e = e?.takeIf { it.isJsonObject }?.asJsonObject?.get(k)
                return e?.takeIf { it.isJsonPrimitive }?.asString
            }
            val model = str("model", "display_name")
            val effort = str("effort", "level")
            model?.let { parts += it }
            model?.let { m -> tips += row("Model", "", StringUtil.escapeXmlEntities(m + (effort?.let { ", effort $it" } ?: ""))) }
            str("context_window", "used_percentage")?.toDoubleOrNull()?.let { pct ->
                parts += "Context ${pct.toInt()} %"
                val used = str("context_window", "total_input_tokens")?.toLongOrNull()
                val size = str("context_window", "context_window_size")?.toLongOrNull()
                fun k(n: Long) = if (n >= 1_000_000 && n % 1_000_000 == 0L) "${n / 1_000_000}M" else "${(n + 500) / 1000}k"
                tips += row("Context", UsageBars.html(pct), pct(pct) + (if (used != null && size != null) " · ${k(used)} of ${k(size)} tokens" else ""))
            }
        }
        val fmt = java.time.format.DateTimeFormatter.ofPattern("EEE HH:mm").withZone(java.time.ZoneId.systemDefault())
        // Tooltip: je Limit eine Zeile mit Verbrauch und Rücksetzzeit statt zwei getrennter Zeilen.
        fun limit(label: String, short: String, w: PlanLimits.Window) {
            parts += "$short ${w.usedPercent.toInt()} %"
            tips += row(label, UsageBars.html(w.usedPercent), pct(w.usedPercent) +
                (w.resetsAt?.let { " · resets ${fmt.format(it)} (${UsageBars.until(it)})" } ?: ""))
        }
        l?.fiveHour?.let { limit("5-hour", "5h", it) }
        l?.sevenDay?.let { limit("Weekly", "Week", it) }
        limits.text = parts.joinToString("  ·  ")
        limitTips = tips
        updateHeaderTip()
        // Fast aufgebraucht: auffällig.
        val max = listOfNotNull(l?.fiveHour?.usedPercent, l?.sevenDay?.usedPercent).maxOrNull() ?: 0.0
        limits.foreground = if (max >= 60) UsageBars.color(max) else UIUtil.getContextHelpForeground()
    }

    /** Zeile im Tooltip der Kopfzeile (schon HTML): Name | Balken | Text. */
    private fun row(name: String, bar: String, text: String) =
        "<tr><td><nobr>$name</nobr></td><td valign=\"middle\">$bar</td><td><nobr>$text</nobr></td></tr>"

    /** Prozent fett in der Farbe des Verbrauchs. */
    private fun pct(p: Double) = "<b><font color=\"${com.intellij.ui.ColorUtil.toHtmlColor(UsageBars.color(p))}\">${p.toInt()} %</font></b>"

    /** Rückfrage (Freigabe) mit Knöpfen; nur sichtbar, wenn Claude auf eine Eingabe wartet. */
    private val question = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false }


    /**
     * Eingabefeld. Den Vorschlag von Claude Code zeichnet es selbst: Der Platzhalter der IDE (emptyText) verschwindet,
     * sobald das Feld den Fokus hat - genau dann soll man aber sehen, dass Tab ihn übernimmt (2026-11-16).
     */
    private val input = object : JBTextArea(3, 0) {
        override fun paintComponent(g: java.awt.Graphics) {
            super.paintComponent(g)
            val s = suggestion ?: return
            if (document.length > 0) return
            val g2 = g.create() as java.awt.Graphics2D
            try {
                com.intellij.util.ui.GraphicsUtil.setupAntialiasing(g2)
                g2.font = font
                val fm = g2.fontMetrics
                val i = insets
                g2.color = UIUtil.getContextHelpForeground()
                // Umbrechen auf die Breite des Felds, dazu der Hinweis auf Tab.
                val width = this.width - i.left - i.right
                var y = i.top + fm.ascent
                val words = "$s   ⇥ Tab".split(' ')
                var line = StringBuilder()
                for (w in words) {
                    val next = if (line.isEmpty()) w else "$line $w"
                    if (fm.stringWidth(next) > width && line.isNotEmpty()) { g2.drawString(line.toString(), i.left, y); y += fm.height; line = StringBuilder(w) }
                    else line = StringBuilder(next)
                }
                if (line.isNotEmpty()) g2.drawString(line.toString(), i.left, y)
            } finally { g2.dispose() }
            // Der Vorschlag liegt über der Schreibmarke am Zeilenanfang - sie danach noch einmal zeichnen, sonst ist bei
            // leerem Feld kein Cursor zu sehen (2026-11-16).
            caret?.paint(g)
        }
    }.apply {
        lineWrap = true; wrapStyleWord = true; font = UIUtil.getLabelFont()
        border = JBUI.Borders.empty(6, 8)
        inputMap.put(KeyStroke.getKeyStroke("ENTER"), "handoff.send")
        inputMap.put(KeyStroke.getKeyStroke("shift ENTER"), "insert-break")
        // Ctrl+Enter (auch Cmd+Enter) wie im Terminal: sofort senden, statt bis nach Claudes laufendem Schritt zu warten.
        // Tab bei leerem Feld übernimmt den Vorschlag von Claude Code (wie im Terminal); die Slash-Liste hängt sich davor.
        inputMap.put(KeyStroke.getKeyStroke("TAB"), "handoff.suggestion")
        // Esc wie im Terminal: Claude unterbrechen, solange es arbeitet (die Slash-Liste schließt Esc vorher, falls offen).
        inputMap.put(KeyStroke.getKeyStroke("ESCAPE"), "handoff.interrupt")
        actionMap.put("handoff.interrupt", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) {
                if (dictating) { dictating = false; return }
                val tab = file.tab ?: return
                if (terminalOpen && file.busy) SessionTerminals.keys(project, tab, "\u001b")
            }
        })
        actionMap.put("handoff.suggestion", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) {
                val s = suggestion
                if (text.isEmpty() && s != null) text = s else transferFocus()
            }
        })
        actionMap.put("handoff.send", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) {
                if (slash.acceptIfVisible()) return
                send()
            }
        })
        transferHandler = ImageDropHandler(transferHandler) { path -> attach(path) }
        // ⌘V erreicht den TransferHandler nicht: Die Editor-Aktion $Paste (TextComponentEditorAction) fügt auch in Swing-Textfelder
        // ein, über EditorCopyPasteHelper - eingefügte Pfade blieben Text (2026-11-19, per Log belegt). Eigene Aktion am Feld
        // geht vor und reicht die Zwischenablage an den Handler; ohne Dateien/Bild fügt er wie gewohnt Text ein.
        val field = this
        object : com.intellij.openapi.project.DumbAwareAction() {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                val contents = com.intellij.openapi.ide.CopyPasteManager.getInstance().contents ?: return
                field.transferHandler.importData(javax.swing.TransferHandler.TransferSupport(field, contents))
            }
        }.registerCustomShortcutSet(com.intellij.openapi.actionSystem.ActionManager.getInstance()
            .getAction(com.intellij.openapi.actionSystem.IdeActions.ACTION_PASTE).shortcutSet, this)
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mousePressed(e: java.awt.event.MouseEvent) { if (watchKeys) SwingUtilities.invokeLater { logInputState("clicked") } }
        })
        addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusLost(e: java.awt.event.FocusEvent) { if (dictating && e.oppositeComponent !== dictate) dictating = false }
        })
        addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) {
                if (watchKeys) { watchKeys = false; logger<SessionEditor>().info("Handoff: input first key after send: ${e.keyCode}") }
                if (e.keyCode == java.awt.event.KeyEvent.VK_ESCAPE && dictating) dictating = false
            }
        })
        document.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) { SwingUtilities.invokeLater { updateMarkLink() } }
        })
    }

    /** Bilder im Prompt: Bereiche markieren wie beim Prüfen von Bildern (Rahmen mit Notiz). */
    private val markLink = ActionLink("Mark Areas in Image…") { markImages() }.apply { isVisible = false }
    private val IMAGE = Regex("""(?:"([^"]+\.(?:png|jpe?g|gif|webp))"|(\S+\.(?:png|jpe?g|gif|webp)))""", RegexOption.IGNORE_CASE)

    /**
     * Gezogene oder eingefügte Dateien stehen im Feld kurz als `[Image #1]` bzw. `[File #2: name.pdf]` (wie im Terminal);
     * beim Senden wird daraus wieder der Pfad. Wer eine Marke von Hand ändert, schickt sie als Text.
     */
    private val attachments = LinkedHashMap<String, String>()

    /** [kind] = eigene Bezeichnung der Marke, z. B. "Drawing" für die Kopie mit Zeichnung (statt des langen Cache-Pfads). */
    private fun attach(path: String, kind: String? = null): String {
        attachments.entries.firstOrNull { it.value == path }?.let { return it.key }
        val n = attachments.size + 1
        val token = when {
            kind != null -> "[$kind #$n]"
            IMAGE.matches(path) || IMAGE.matches("\"$path\"") -> "[Image #$n]"
            else -> "[File #$n: ${File(path).name}]"
        }
        attachments[token] = path
        return token
    }

    /** Per `@` gewählte Datei: Marke wie bei gezogenen Dateien, geht als `@pfad` an Claude (Claude Code liest sie dann mit). */
    private fun mention(path: String): String {
        attachments.entries.firstOrNull { it.value == "@$path" }?.let { return it.key }
        val token = "[@${File(path).name} #${attachments.size + 1}]"
        attachments[token] = "@$path"
        return token
    }

    /** Prompt mit Pfaden statt Marken. */
    private fun expanded(text: String = input.text): String =
        attachments.entries.fold(text) { t, (token, path) ->
            // @-Erwähnungen: Claude Code erkennt Leerzeichen im Pfad nur mit Backslash (`@a\ b`, Regex in 2.1.325).
            t.replace(token, when { path.startsWith("@") -> path.replace(" ", "\\ "); path.contains(' ') -> "\"$path\""; else -> path })
        }

    private val previewSizes = HashMap<String, String>()

    /** width/height fürs Vorschaubild im Tooltip (Swing-HTML skaliert nicht von selbst proportional), max. 240 px. */
    private fun previewSize(path: String): String = previewSizes.getOrPut(path) {
        val (w, h) = runCatching {
            javax.imageio.ImageIO.createImageInputStream(File(path)).use { stream ->
                val reader = javax.imageio.ImageIO.getImageReaders(stream).next()
                try { reader.input = stream; reader.getWidth(0) to reader.getHeight(0) } finally { reader.dispose() }
            }
        }.getOrDefault(240 to 160)
        val f = minOf(1.0, 240.0 / maxOf(w, h))
        "width=\"${(w * f).toInt()}\" height=\"${(h * f).toInt()}\""
    }

    /** Marke an [pos] (Bereich start until end), sonst null; [inside] = nur echt innerhalb, nicht an den Rändern. */
    private fun tokenAt(pos: Int, inside: Boolean = false): IntRange? {
        val text = input.text
        for (token in attachments.keys) {
            var i = text.indexOf(token)
            while (i >= 0) {
                val end = i + token.length
                if (if (inside) pos in (i + 1) until end else pos in i..end) return i until end
                i = text.indexOf(token, i + 1)
            }
        }
        return null
    }

    /**
     * Marken wie `[Image #1]` verhalten sich wie ein Zeichen: Backspace/Entf löschen sie ganz, die Schreibmarke springt über sie,
     * und der Tooltip zeigt Pfad und bei Bildern ein Vorschaubild. Halb gelöscht ginge sie sonst als Text ohne Pfad raus.
     */
    init {
        fun remover(stroke: String, before: Boolean) {
            val ks = KeyStroke.getKeyStroke(stroke)
            val previous = input.inputMap.get(ks)?.let { input.actionMap.get(it) }
            val name = "handoff.token.$stroke"
            input.inputMap.put(ks, name)
            input.actionMap.put(name, object : AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent) {
                    val c = input.caretPosition
                    val r = if (input.selectionStart == input.selectionEnd) tokenAt(if (before) c - 1 else c + 1, inside = false)
                        ?.takeIf { if (before) it.last + 1 == c else it.first == c } else null
                    if (r != null) input.document.remove(r.first, r.last + 1 - r.first) else previous?.actionPerformed(e)
                }
            })
        }
        remover("BACK_SPACE", before = true)
        remover("DELETE", before = false)
        var last = 0
        input.addCaretListener { e ->
            val pos = e.dot
            if (e.dot != e.mark) { last = pos; return@addCaretListener }
            val r = tokenAt(pos, inside = true)
            if (r != null) SwingUtilities.invokeLater {
                // Von links kommend ans Ende, von rechts kommend an den Anfang.
                val to = if (pos >= last) r.last + 1 else r.first
                if (input.caretPosition == pos && to <= input.document.length) input.caretPosition = to
            }
            last = pos
        }
        input.addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
            override fun mouseMoved(e: java.awt.event.MouseEvent) {
                val pos = input.viewToModel2D(e.point)
                val r = tokenAt(pos, inside = true)
                val tip = r?.let { attachments[input.text.substring(it.first, it.last + 1)] }?.let { v ->
                    val path = v.removePrefix("@")
                    val esc = StringUtil.escapeXmlEntities(path)
                    if (IMAGE.matches(path) || IMAGE.matches("\"$path\""))
                        "<html>$esc<br><img src=\"${File(path).toURI()}\" ${previewSize(path)}></html>" else "<html>$esc</html>"
                }
                if (tip != input.toolTipText) input.toolTipText = tip
            }
        })
    }

    private fun imagesInPrompt(): List<String> = IMAGE.findAll(expanded()).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }
        .filter { File(it).isFile && File(it).parentFile?.name != "handoff-drawings" }.distinct().toList()

    private fun updateMarkLink() {
        val n = imagesInPrompt().size
        markLink.isVisible = n > 0
        markLink.text = if (n > 1) "Mark Areas in Images…" else "Mark Areas in Image…"
    }

    private val MARK_LINE = Regex("""^- (.+), area x=(\d+) y=(\d+) w=(\d+) h=(\d+) px(?:: (.*))?$""")
    private val MARK_HEAD = "Marked areas (pixels of the original image):"
    private val DRAW_HEAD = "Drawings (copies of the images with my drawing on top - read them):"
    private val DRAW_LINE = Regex("""^- (.+), drawing: (.+\.png|\[Drawing #\d+])$""")
    /** Zeichnungen je Bildpfad - lassen sich aus dem Prompt-Text nicht zurücklesen. */
    private val promptShapes = HashMap<String, List<Shape>>()

    /**
     * Rahmen ziehen und beschriften; das Ergebnis steht als Text im Prompt (Bild, Pixelbereich, Notiz). Schon eingetragene
     * Rahmen liest der Viewer beim nächsten Öffnen wieder ein, und der Block wird ersetzt statt angehängt.
     */
    private fun markImages() {
        val paths = imagesInPrompt().takeIf { it.isNotEmpty() } ?: return
        val lines = input.text.lines()
        val initial = HashMap<Int, List<Mark>>()
        lines.mapNotNull { MARK_LINE.matchEntire(it.trim()) }.forEach { m ->
            val i = paths.indexOfFirst { File(it).name == m.groupValues[1] }.takeIf { it >= 0 } ?: return@forEach
            val (x, y, w, h) = (2..5).map { m.groupValues[it].toInt() }
            initial[i] = initial[i].orEmpty() + Mark(x, y, w, h, m.groupValues[6])
        }
        val dialog = MarkupDialog(project, paths, 0, initial, true, paths.indices.associateWith { promptShapes[paths[it]].orEmpty() })
        if (!dialog.showAndGet()) return
        val drawnBefore = lines.mapNotNull { DRAW_LINE.matchEntire(it.trim()) }
            .associate { it.groupValues[1] to (attachments[it.groupValues[2]] ?: it.groupValues[2]) }
        val drawings = dialog.allShapes.toSortedMap().mapNotNull { (i, list) ->
            val path = paths[i]; val name = File(path).name
            if (list.isEmpty()) { promptShapes.remove(path); return@mapNotNull null }
            // Unverändert: die vorhandene Kopie behalten.
            val file = drawnBefore[name]?.takeIf { promptShapes[path] == list } ?: Drawing.save(path, list) ?: return@mapNotNull null
            promptShapes[path] = list.map { it.copy() }
            "- $name, drawing: ${attach(file, "Drawing")}"
        }
        val marks = dialog.allMarks.toSortedMap().flatMap { (i, list) ->
            list.map { m -> "- ${File(paths[i]).name}, area x=${m.x} y=${m.y} w=${m.w} h=${m.h} px" + (if (m.note.isNotBlank()) ": ${m.note.trim()}" else "") }
        }
        // Alte Blöcke raus, ein neuer ans Ende.
        val kept = lines.filterNot { it.trim() == MARK_HEAD || it.trim() == DRAW_HEAD || MARK_LINE.matches(it.trim()) || DRAW_LINE.matches(it.trim()) }
            .joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trimEnd()
        val blocks = listOfNotNull(
            marks.takeIf { it.isNotEmpty() }?.let { MARK_HEAD + "\n" + it.joinToString("\n") },
            drawings.takeIf { it.isNotEmpty() }?.let { DRAW_HEAD + "\n" + it.joinToString("\n") },
        )
        input.text = if (blocks.isEmpty()) kept else kept + (if (kept.isEmpty()) "" else "\n\n") + blocks.joinToString("\n\n") + "\n"
        input.requestFocusInWindow()
    }
    private val send = JButton("Send").apply {
        primary()
        addActionListener { val id = sessionId; if (resumable && id != null) resumeHere(id, expanded()) else send() }
    }
    /** Liste der Slash-Befehle: ausführen oder an der Schreibmarke in den Prompt übernehmen. */
    private val commands = SmallButton("/").apply {
        toolTipText = "Slash commands: run one or insert it at the cursor"
        addActionListener { SlashPicker.show(project, this, run = ::runCommand, insert = ::insertCommand, dir = currentDir()) }
    }

    /**
     * Remote Control (`/remote-control`, kurz `/rc`): Session vom Handy oder von claude.ai/code aus steuern. Hervorgehoben,
     * solange sie verbunden ist; ein Klick öffnet dann den Dialog im Terminal (QR-Code, „Disconnect this session“).
     */
    private val remote = SmallButton(null, HandoffIcons.globe()).apply {
        addActionListener { runCommand("/remote-control") }
    }
    private var remoteKey: Pair<Boolean, Boolean>? = null

    private fun updateRemote() {
        val on = terminalOpen && transcript?.remote == true
        val key = terminalOpen to on
        if (key == remoteKey) return
        remoteKey = key
        remote.icon = HandoffIcons.globe(on)
        remote.isSelected = on
        remote.isEnabled = terminalOpen
        remote.toolTipText = when {
            on -> "Remote Control is on - click for the QR code or to disconnect (/remote-control)"
            terminalOpen -> "Remote Control: control this session from your phone or claude.ai/code (/remote-control)"
            else -> "Remote Control needs a session running in the IDE terminal"
        }
    }

    /**
     * Diktieren (macOS): Feld fokussieren und die Diktierfunktion des Systems starten - dieselbe Aktion wie
     * "Bearbeiten > Diktat starten" (`startDictation:` an die Responder-Kette). Der Text kommt über die Eingabemethode ins Feld.
     */
    private val dictate = SmallButton(null, HandoffIcons.mic()).apply {
        toolTipText = "Dictate (macOS dictation)"
        isVisible = com.intellij.openapi.util.SystemInfo.isMac
        addActionListener { toggleDictation() }
    }

    /** Diktat läuft (soweit Handoff es weiß: gestartet und Feld noch fokussiert) - Knopf hervorgehoben, Hinweis darunter. */
    private var dictating = false
        set(value) {
            field = value
            // Nur das Symbol wechselt (rotes, gefülltes Mikrofon) - eine Hintergrundfarbe füllte das Rechteck um den runden Knopf.
            dictate.icon = HandoffIcons.mic(on = value)
            dictate.toolTipText = if (value) "Stop dictation" else "Dictate (macOS dictation)"
            if (value) hint.text = "Dictating - speak now. The microphone of macOS shows at the cursor; click here again or press Esc to stop."
            else { stateKey = null; renderHint() }  // Hinweis neu setzen
            dictate.repaint()
        }

    /**
     * Diktieren (macOS): Feld fokussieren und die Diktierfunktion des Systems starten bzw. beenden - dieselbe Aktion wie
     * "Bearbeiten > Diktat starten" (`startDictation:` an die Responder-Kette; während des Diktats beendet sie es).
     * Der Text kommt über die Eingabemethode ins Feld.
     */
    private fun toggleDictation() {
        val stop = dictating
        input.requestFocusInWindow()
        fun done(handled: Boolean) {
            logger<SessionEditor>().info("Handoff: startDictation handled=$handled (stop=$stop)")
            if (!handled) { dictating = false; notice("Dictation did not start - switch it on in System Settings > Keyboard > Dictation."); return }
            dictating = !stop
        }
        Dictation.toggle(::done)
    }

    private fun askValue(cmd: String) {
        input.text = cmd.trim() + " "
        input.caretPosition = input.text.length
        input.requestFocusInWindow()
        hint.text = "Pick a value - without one, Claude Code opens a picker in the terminal."
    }

    /** Befehl öffnet einen Dialog im Terminal: dorthin wechseln und sagen, warum. */
    private fun showDialogTerminal(tab: String) {
        SessionTerminals.show(project, tab)
        hint.text = "This command opens a dialog in the terminal - use it there, Esc closes it."
    }

    /**
     * Zurück vor einen Prompt, wie `/rewind` in Claude Code, aber ohne dessen Auswahl im Terminal: Claude Code beenden
     * (`/exit`), dann in derselben Shell `claude --resume <id> --rewind-files <prompt>` (Dateien auf den Stand vor dem Prompt)
     * und/oder `claude --resume <id> --resume-session-at <vorige Nachricht>` (Unterhaltung ab dort). Beide Schalter nutzt
     * das Agent SDK (`rewindFiles`, `resumeSessionAt`); in `claude --help` stehen sie nicht (2.1.327 per Testsession
     * geprüft, 2026-11-19). Der Prompt landet danach im Eingabefeld.
     */
    private fun rewind(promptUuid: String) {
        val t = transcript ?: return
        val id = sessionId ?: return
        val tab = file.tab
        if (tab == null || !terminalOpen) { notice("Rewind needs the session in the IDE terminal (New Claude Session, or Open in Handoff in the terminal's menu)."); return }
        if (file.busy) { notice("Rewind works when Claude is done - wait, or interrupt with Esc."); return }
        val prompt = t.entries.firstOrNull { it.uuid == promptUuid }?.text.orEmpty()
        val target = t.resumeTarget(promptUuid)
        val options = if (target != null) arrayOf("Code and Conversation", "Conversation Only", "Code Only", "Cancel") else arrayOf("Code Only", "Cancel")
        val dialog = RewindDialog(project, prompt, options.filter { it != "Cancel" }, first = target == null)
        if (!dialog.showAndGet()) return
        val label = dialog.chosen ?: return
        val code = label.startsWith("Code")
        val conversation = label != "Code Only" && label != "Cancel"
        if (!code && !conversation) return
        val cmd = listOfNotNull(
            if (code) "claude --resume $id --rewind-files $promptUuid" else null,
            if (conversation) "claude --resume $id --resume-session-at $target" else "claude --resume $id",
        ).joinToString(" && ")
        hint.text = "Rewinding…"
        // Prozess vor dem /exit merken: Der Hook schreibt .pid bei jedem Ereignis neu, SessionEnd löscht sie.
        val pid = WaitingSessions.getInstance(project).infos[id]?.pid ?: File(WaitingHook.waiting, "$id.pid").takeIf { it.isFile }
            ?.let { runCatching { it.readText().trim().toLong() }.getOrNull() }
        if (!SessionTerminals.send(project, tab, "/exit")) { notice("Could not reach the terminal."); return }
        // Erst wenn der Claude-Prozess beendet ist, liest wieder die Shell - sonst ginge der Befehl als Prompt an Claude.
        // Das Verschwinden der Hook-Daten (SessionEnd) allein reichte nicht: In der Sandbox landete der Befehl am 2026-11-19
        // trotzdem im Eingabefeld von Claude. Ohne bekannten Prozess bleibt es bei SessionEnd, dazu eine Pause für die Shell.
        val info = File(WaitingHook.waiting, "$id.info")
        fun running() = if (pid != null) ProcessHandle.of(pid).map { it.isAlive }.orElse(false) else info.exists()
        val until = System.currentTimeMillis() + 15_000
        var goneAt = 0L
        javax.swing.Timer(300, null).apply {
            addActionListener {
                val now = System.currentTimeMillis()
                if (running()) { if (now < until) return@addActionListener }
                else { if (goneAt == 0L) goneAt = now; if (now - goneAt < 700) return@addActionListener }
                stop()
                if (running()) { notice("Claude Code did not exit - rewind cancelled. Try /exit in the terminal."); return@addActionListener }
                SessionTerminals.send(project, tab, cmd)
                if (conversation && target != null) {
                    t.discardAfter(target)
                    input.text = prompt; input.caretPosition = input.document.length
                }
                notice(when { code && conversation -> "Code and conversation went back."; code -> "Code went back."; else -> "Conversation went back." }, error = false)
                renderTranscript(force = true)
            }
            start()
        }
    }

    /**
     * Öffnet der Befehl im Terminal eine Auswahl? `/remote-control` (`/rc`) nur bei bestehender Verbindung (Dialog mit QR-Code
     * und „Disconnect this session“); beim Verbinden kommt keine Frage, das Terminal blieb sonst unnötig offen (2026-11-22).
     */
    private fun opensDialog(cmd: String): Boolean {
        val name = cmd.trim().takeIf { it.startsWith("/") }?.removePrefix("/")?.substringBefore(' ')
        if (name == "remote-control" || name == "rc") return transcript?.remote == true
        return SlashArguments.opensDialog(cmd)
    }

    private fun runCommand(cmd: String) {
        if (SlashArguments.needsValue(cmd) != null) { askValue(cmd); return }
        if (opensDialog(cmd)) file.tab?.let { t -> SwingUtilities.invokeLater { showDialogTerminal(t) } }
        val tab = file.tab ?: return
        if (terminalOpen && SessionTerminals.send(project, tab, cmd)) {
            // Wie beim Senden gleich als Prompt mit "Queued" zeigen - ohne Rückmeldung wirkte ▶ (etwa bei /compact, das
            // eine Weile braucht) wie kaputt und wurde mehrfach geklickt (2026-11-17).
            if (pending.isEmpty()) pendingBase = transcript?.entries?.count { it.kind == TranscriptEntry.Kind.USER } ?: 0
            if (!file.busy) startedIdle += cmd
            pending += cmd; renderTranscript(force = true)
            input.requestFocusInWindow()
        }
    }

    /** Datei als `@`-Marke ins Eingabefeld (z. B. ein Datenbank-Ergebnis aus dem Kontextmenü), Fokus ins Feld. */
    fun insertMention(path: String, label: String? = null) {
        val token = mention(path).let { t -> label?.let { "$it $t" } ?: t }
        insertCommand(token)
    }

    private fun insertCommand(cmd: String) {
        val pos = input.caretPosition.coerceIn(0, input.document.length)
        val before = if (pos > 0 && !input.document.getText(pos - 1, 1).isBlank()) " " else ""
        input.document.insertString(pos, "$before$cmd ", null)
        input.requestFocusInWindow()
    }

    /** Suche im Verlauf (Ctrl/Cmd+F): Treffer markiert, Enter/Shift+Enter springt, Esc schließt. */
    private val searchField = com.intellij.ui.SearchTextField(false)
    private val searchCount = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private var matches: List<Int> = emptyList()
    private var current = 0
    private val search: JPanel = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
        border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(4, 12))
        isVisible = false
        add(searchField, BorderLayout.CENTER)
        add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            add(searchCount)
            add(ActionLink("Close") { closeSearch() })
        }, BorderLayout.EAST)
        searchField.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) { current = 0; highlight(scrollTo = true) }
        })
        searchField.textEditor.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) {
                when (e.keyCode) {
                    java.awt.event.KeyEvent.VK_ENTER -> { step(if (e.isShiftDown) -1 else 1); e.consume() }
                    java.awt.event.KeyEvent.VK_ESCAPE -> { closeSearch(); e.consume() }
                }
            }
        })
    }

    private fun openSearch() {
        search.isVisible = true
        pane.selectedText?.takeIf { it.isNotBlank() && '\n' !in it }?.let { searchField.text = it }
        searchField.requestFocusInWindow(); searchField.selectText()
        search.revalidate()
    }

    private fun closeSearch() {
        search.isVisible = false
        pane.highlighter.removeAllHighlights()
        matches = emptyList()
        search.revalidate()
    }

    private fun step(d: Int) {
        if (matches.isEmpty()) return
        current = (current + d + matches.size) % matches.size
        highlight(scrollTo = true)
    }

    private fun highlight(scrollTo: Boolean) {
        val h = pane.highlighter
        h.removeAllHighlights()
        val q = searchField.text.trim()
        val doc = pane.document
        val text = runCatching { doc.getText(0, doc.length) }.getOrDefault("")
        matches = if (q.isEmpty()) emptyList() else buildList {
            var i = text.indexOf(q, ignoreCase = true)
            while (i >= 0) { add(i); i = text.indexOf(q, i + q.length, ignoreCase = true) }
        }
        if (current >= matches.size) current = 0
        searchCount.text = if (q.isEmpty()) "" else if (matches.isEmpty()) "No matches" else "${current + 1}/${matches.size}"
        val all = javax.swing.text.DefaultHighlighter.DefaultHighlightPainter(JBColor.namedColor("SearchMatch.endBackground", JBColor(0xFFE57F, 0x5C4A1E)))
        val cur = javax.swing.text.DefaultHighlighter.DefaultHighlightPainter(JBColor.namedColor("SearchMatch.startBackground", JBColor(0xFFB830, 0x8A6A12)))
        matches.forEachIndexed { k, i -> runCatching { h.addHighlight(i, i + q.length, if (k == current) cur else all) } }
        if (scrollTo && matches.isNotEmpty()) runCatching {
            pane.modelToView2D(matches[current])?.bounds?.let { r -> r.grow(0, JBUI.scale(60)); pane.scrollRectToVisible(r) }
        }
    }

    /** Session läuft nicht im Terminal der IDE (z. B. nach einem Neustart): Knopf "Resume" statt "Send". */
    private var resumable = false
    private val hint = JBLabel().apply {
        foreground = UIUtil.getContextHelpForeground(); font = JBUI.Fonts.smallFont()
        // Maus über der Meldung: stehen lassen; danach noch etwas Zeit zum Wegsehen.
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseEntered(e: java.awt.event.MouseEvent) { if (noticeText != null) { hintHovered = true; renderHint() } }
            override fun mouseExited(e: java.awt.event.MouseEvent) {
                if (!hintHovered) return
                hintHovered = false
                noticeUntil = maxOf(noticeUntil, System.currentTimeMillis() + 2_000 + FADE_MS); renderHint()
            }
        })
    }

    private var filesOpen = com.intellij.ide.util.PropertiesComponent.getInstance().getBoolean(FILES_KEY, false)
    private val filesPanel = FilesPanel(project, { baseDir() }, { i, name -> jumpTo(i, name) }, { if (filesOpen) toggleFiles() })
    /** Verlauf links, Dateiliste rechts (ein-/ausblendbar, Breite gemerkt). */
    private val filesHost = com.intellij.ui.OnePixelSplitter(false, "handoff.files.split", 0.72f).apply {
        firstComponent = JPanel(BorderLayout()).apply { add(search, BorderLayout.NORTH); add(scroll, BorderLayout.CENTER) }
        secondComponent = if (filesOpen) filesPanel else null
    }
    private val root = JPanel(BorderLayout()).apply {
        add(header, BorderLayout.NORTH)
        add(filesHost, BorderLayout.CENTER)
        if (file.logOnly) add(logBar(), BorderLayout.SOUTH) else add(JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            border = JBUI.Borders.compound(JBUI.Borders.customLineTop(JBColor.border()), JBUI.Borders.empty(8, 12))
            add(question, BorderLayout.NORTH)
            add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                isOpaque = false
                add(JBScrollPane(input).apply { border = JBUI.Borders.customLine(JBColor.border()) }, BorderLayout.CENTER)
                add(JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                    isOpaque = false
                    // Mikrofon, Remote Control und "/" nebeneinander über Send.
                    add(JPanel(java.awt.GridLayout(1, 0, JBUI.scale(4), 0)).apply { isOpaque = false; add(dictate); add(remote); add(commands) }, BorderLayout.NORTH)
                    add(send, BorderLayout.SOUTH)
                }, BorderLayout.EAST)
            }, BorderLayout.CENTER)
            add(JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
                isOpaque = false
                add(hint, BorderLayout.CENTER)
                add(markLink, BorderLayout.EAST)
            }, BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
    }

    /** Fuß der reinen Protokoll-Ansicht: Fortsetzen öffnet einen eigenen Session-Reiter, die `.jsonl` bleibt, wie sie ist. */
    private fun logBar(): JComponent = JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
        border = JBUI.Borders.compound(JBUI.Borders.customLineTop(JBColor.border()), JBUI.Borders.empty(8, 12))
        add(JBLabel("Saved session log, read-only. Resume continues it in a new session tab (claude --resume).").apply {
            foreground = UIUtil.getContextHelpForeground()
        }, BorderLayout.CENTER)
        add(javax.swing.JButton("Resume in New Tab").apply {
            addActionListener {
                val id = file.currentSession(project) ?: return@addActionListener
                SessionEditors.resumeOld(project, id, file.cwd ?: project.basePath ?: return@addActionListener)
            }
        }, BorderLayout.EAST)
    }

    // Vor dem init-Block unten anlegen: Er ruft refresh() schon im Konstruktor auf, ein weiter unten deklariertes Feld war da
    // noch null (NullPointerException in exited(), der Reiter ging nicht auf; 1.11.4, 2026-11-26).
    private val seenRunning: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    /** Normaler Hinweis unter dem Feld (je nach Zustand), solange keine Meldung ihn überdeckt. */
    private var baseHint = ""
    private var hintHovered = false
    private val fadeTimer = Timer(40) { renderHint() }
    private var noticeText: String? = null
    private var noticeUntil = 0L
    private var noticeError = true
    private var noticeWarn = false
    private val timer = Timer(700) { refresh() }

    init {
        refresh()
        object : com.intellij.openapi.project.DumbAwareAction() {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) = openSearch()
        }.registerCustomShortcutSet(com.intellij.openapi.actionSystem.CommonShortcuts.getFind(), root, this)
        // Theme gewechselt: Farben im CSS (Prompt-Hintergrund, Grau) und den Hintergrund des Verlaufs neu setzen (2026-11-15:
        // nach Dark -> Light blieben die Prompts dunkelblau).
        com.intellij.openapi.application.ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(com.intellij.ide.ui.LafManagerListener.TOPIC, com.intellij.ide.ui.LafManagerListener {
                SwingUtilities.invokeLater {
                    pane.editorKit = styledKit()
                    conversation.background = pane.background
                    renderTranscript(force = false)
                    dictate.icon = HandoffIcons.mic(on = dictating)
                    remoteKey = null; updateRemote()
                }
            })
    }

    private val sessionId get() = file.currentSession(project)
    private val terminalOpen get() = file.tab?.let { SessionTerminals.isOpen(project, it) } == true
    /** Vorschläge für Slash-Befehle - nur, wenn sie die Session erreichen (Terminal). */
    private val slash: SlashCompletion = SlashCompletion(project, input, fileToken = { mention(it) }, enabled = { terminalOpen }, workDir = { currentDir() },
        currentValue = ::currentValue, onHint = { h -> if (h != argHint) { argHint = h; if (h != null) hint.text = h else updateState(sessionId) } })

    /** Hinweis aus der Slash-Liste, was ein Befehl als Wert erwartet - hat Vorrang vor dem üblichen Hinweis. */
    private var argHint: String? = null

    /**
     * Aktueller Arbeitsordner: nach `/cd` meldet ihn die Statuszeile von Claude Code (cwd) - so folgt die Kopfzeile dem
     * Wechsel (2026-11-18); sonst der Startordner der Session.
     */
    private fun currentDir(): String? =
        sessionId?.let { statusBySession[it] }?.get("cwd")?.takeIf { it.isJsonPrimitive }?.asString?.trimEnd('/') ?: file.cwd ?: baseDir()

    /** Aktuelles Modell bzw. Effort aus statusline.json dieser Session; Modell als Alias (claude-opus-5-5 → opus). */
    private fun currentValue(cmd: String): String? {
        val o = sessionId?.let { statusBySession[it] } ?: return null
        fun str(a: String, b: String) = o.getAsJsonObject(a)?.get(b)?.takeIf { it.isJsonPrimitive }?.asString
        return when (cmd) {
            "effort" -> str("effort", "level")
            "model" -> str("model", "id")?.let { id -> listOf("fable", "opus", "sonnet", "haiku").firstOrNull { id.contains(it, ignoreCase = true) } }
            else -> null
        }
    }

    /**
     * Diktat und Eingabemethode zurücksetzen, bevor das Feld geleert wird: Nach einem diktierten und abgeschickten Prompt
     * nahm das Feld keine Tasten mehr an (2026-11-16) - vermutlich hing die Eingabemethode noch im Diktat.
     */
    private fun endDictation() {
        // Nicht erneut startDictation: schicken - war das Diktat schon von Hand beendet, startete es neu.
        dictating = false
        runCatching { input.inputContext?.endComposition() }
        // Kein enableInputMethods(false/true) mehr: Vermutlich nahm das Feld danach unter macOS gar keine Tasten mehr an
        // (2026-11-16, nach einem gedroppten Bild und Send eingefroren).
    }

    /** Fürs Log: Zustand des Felds nach dem Senden und ob danach Tasten ankommen (eingefrorenes Feld, 2026-11-16). */
    private var watchKeys = false

    private fun logInputState(what: String): Unit = logger<SessionEditor>().info(
        "Handoff: input $what enabled=${input.isEnabled} editable=${input.isEditable} focusOwner=${input.isFocusOwner} " +
            "windowActive=${SwingUtilities.getWindowAncestor(input)?.isActive} ime=${input.inputContext?.locale}")

    init {
        // Ctrl+Enter (auch Cmd+Enter) wie im Terminal: sofort senden, statt bis nach Claudes laufendem Schritt zu warten.
        // Als AnAction am Feld, nicht per inputMap: Die Tastenbelegung der IDE greift vor der inputMap (Ctrl+Enter öffnete
        // „Generate“, 2026-11-16); am Feld registrierte Kürzel haben Vorrang vor den globalen.
        object : com.intellij.openapi.project.DumbAwareAction() {
            override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) { send(now = true) }
        }.registerCustomShortcutSet(com.intellij.openapi.actionSystem.CustomShortcutSet(
            com.intellij.openapi.actionSystem.KeyboardShortcut(KeyStroke.getKeyStroke("ctrl ENTER"), null),
            com.intellij.openapi.actionSystem.KeyboardShortcut(KeyStroke.getKeyStroke("meta ENTER"), null)), input, this)
    }

    /** Vorgemerkte Prompts, die Claude erst nach dem laufenden Schritt liest (nicht schon per Ctrl+Enter gesendet). */
    private fun queued(): List<String> =
        if (file.tab != null && terminalOpen && file.busy) pending.filter { it !in sentNow && it !in startedIdle } else emptyList()

    private fun send(now: Boolean = false) {
        // Leeres Feld + Ctrl+Enter: die vorgemerkten Prompts sofort schicken. Claude Code macht bei leerer Eingabe aus
        // `chat:sendNow` ein "sendQueuedNow" (im Bundle von 2.1.327 nachgelesen, 2026-11-18).
        if (now && input.text.isBlank()) {
            val waiting = queued()
            val tab = file.tab
            if (waiting.isNotEmpty() && tab != null && SessionTerminals.keys(project, tab, "\u0018\u0013")) {
                sentNow += waiting; renderTranscript(force = true)
            }
            return
        }
        val shown = input.text.trim().takeIf { it.isNotEmpty() } ?: return
        // Ein spätes Ergebnis, das Claude schon hat, nicht noch einmal schicken (h12 kam doppelt, 2026-11-18).
        if (HandoffService.getInstance(project).alreadyDelivered(shown)) {
            logger<SessionEditor>().warn("Handoff: late result in the input blocked (suggestion=${shown == suggestion}): ${shown.take(40)}")
            notice("Claude already got this result - it is not sent again.")
            input.text = ""
            return
        }
        // /model, /effort ohne Wert öffneten eine unsichtbare Auswahl im Terminal: stattdessen die Werteliste zeigen.
        if (SlashArguments.needsValue(shown) != null) { askValue(shown); return }
        if (resumeFromTab(shown)) return
        // Unsichtbare Zeichen (Unicode "Format": BOM, Zero-Width-Space/-Joiner, Word-Joiner, weiches Trennzeichen, LRM …) entfernt
        // Claude Code (2.1.332) selbst und schluckt dann das Enter: "Removed 6 invisible characters · review and press Enter to
        // send" - der Prompt blieb im Terminal stehen (2026-11-26, per PTY nachgestellt). Daher hier entfernen und es sagen.
        val raw = expanded(shown)
        // Endet der Prompt mit einem @pfad, öffnet Claude Code die Dateiauswahl und das Enter wählt dort, statt zu senden -
        // der Prompt blieb im Terminal stehen (2026-11-26, Datensatz aus dem DB-Ergebnis; per PTY nachgestellt). Ein
        // Leerzeichen dahinter schließt die Auswahl.
        val cleaned = raw.filterNot { Character.getType(it) == Character.FORMAT.toInt() }
        val removed = raw.length - cleaned.length
        val text = if (Regex("""@\S+$""").containsMatchIn(cleaned)) "$cleaned " else cleaned
        if (text.isBlank()) return
        endDictation()
        watchKeys = true
        SwingUtilities.invokeLater { logInputState("after send") }
        // Session läuft nicht mehr (z. B. nach einem IDE-Neustart): fortsetzen und den Prompt gleich mitgeben.
        val id = sessionId
        if (resumable && id != null && !(file.tab != null && terminalOpen && !exited(id)) && !(sessions.canReply(id))) {
            resumeHere(id, text); return
        }
        val tab = file.tab
        // Steht im Terminal schon Text (z. B. ein "@datei" von „Send to Claude Code“), ginge er mit diesem Prompt ab: erst warnen,
        // ein zweites Senden mit demselben Text im Terminal schickt trotzdem.
        // Hinweise wie "[⧉ In datei.md]" (offene Editor-Datei bzw. Auswahl, zeigt Claude Code in der Eingabezeile) sind kein
        // getippter Text und gehen nicht mit - sonst warnte das erste Enter nach jedem Wechsel der Editor-Datei (2026-11-18).
        var typed = (if (tab != null && terminalOpen) SessionTerminals.typedInput(project, tab) else null)
            ?.replace(EDITOR_HINTS, "")?.trim()?.takeIf { it.isNotEmpty() }
        // Steht im Terminal genau der Prompt, den man schickt (Vorschlag von Claude Code, der als getippt erkannt wurde, oder
        // dort schon getippt): Zeile leeren und senden statt warnen - er ginge sonst doppelt ab (2026-11-26, "/session-kuerzen").
        if (typed != null && tab != null && (typed == shown || typed == text.trimEnd())) {
            logger<SessionEditor>().info("Handoff: terminal input equals the prompt, clearing it before sending: ${typed.take(80)}")
            SessionTerminals.keys(project, tab, "\u0015")
            typed = null
        }
        if (typed != null && typed != warnedTyped) {
            logger<SessionEditor>().info("Handoff: send held back, terminal input holds: ${typed.take(80)}")
            warnedTyped = typed
            notice("The terminal input already holds \"${typed.take(60)}\". Send again to send both (\"${typed.take(20)} …\"), or clear it there (Ctrl+U).", warn = true)
            return
        }
        // Zweites Senden trotz Text im Terminal: mit Leerzeichen anhängen - sonst klebte "foo" an "say hi" ("foosay hi").
        val joinSpace = typed != null && !typed.last().isWhitespace()
        warnedTyped = null
        // Über den wartenden Stop-Hook kommt Text nur als Hinweis an, ein Slash-Befehl liefe dort nie - er blieb als
        // "Running…" stehen und sperrte das Feld (2026-11-22).
        if (text.startsWith("/") && !(tab != null && terminalOpen)) {
            notice("Slash commands only reach sessions running in the IDE terminal (New Claude Session or Resume).")
            return
        }
        val ok = when {
            tab != null && terminalOpen -> (if (joinSpace) " $text" else text).let { t -> if (now) SessionTerminals.sendNow(project, tab, t) else SessionTerminals.send(project, tab, t) }
            else -> sessionId?.let { sessions.reply(it, text) } == true
        }
        if (ok) {
            noticeText = null; renderHint()
            if (removed > 0) notice("Removed $removed invisible character${if (removed > 1) "s" else ""} (BOM, zero-width …) - Claude Code would not send them.", warn = true)
            if (pending.isEmpty()) pendingBase = transcript?.entries?.count { it.kind == TranscriptEntry.Kind.USER } ?: 0
            if (!file.busy) startedIdle += text
            pending += text; if (now && tab != null && file.busy) sentNow += text; input.text = ""; attachments.clear(); renderTranscript(force = true)
            if (tab != null && opensDialog(text)) showDialogTerminal(tab)
        }
        else {
            notice(if (text.startsWith("/")) "Slash commands only reach sessions started here (New Claude Session)." else "Claude is busy - send when it is done, or type in the terminal.")
        }
    }

    /**
     * `/resume` nicht ans Terminal geben: Claude Code öffnet dort eine Auswahl bzw. wechselt das Protokoll, das der Reiter
     * nicht mehr verfolgt - der Prompt blieb ewig "Queued" (2026-11-17). Stattdessen die gewählte Session in einem eigenen
     * Reiter fortsetzen; ohne eindeutigen Treffer die Liste zeigen.
     */
    private fun resumeFromTab(text: String): Boolean {
        val m = Regex("^/resume(?:\\s+(.*))?$").matchEntire(text) ?: return false
        val q = m.groupValues[1].trim()
        val sessions = RecentSessions.list(project, baseDir())
        val hit = sessions.firstOrNull { it.name == q }
            ?: sessions.filter { q.isNotEmpty() && (it.name.startsWith(q) || it.description.equals(q, ignoreCase = true)) }.singleOrNull()
            ?: sessions.filter { q.isNotEmpty() && it.description.contains(q, ignoreCase = true) }.singleOrNull()
        if (hit == null) {
            if (input.text.trim() == "/resume") input.text = "/resume "
            input.requestFocusInWindow(); input.caretPosition = input.document.length
            hint.text = if (sessions.isEmpty()) "No earlier sessions in this folder." else "Pick a session from the list (type to filter)."
            return true
        }
        input.text = ""
        SessionEditors.resumeOld(project, hit.name, RecentSessions.cwds[hit.name] ?: baseDir() ?: project.basePath.orEmpty())
        return true
    }

    /** Arbeitsordner der Session für relative Pfade, sonst der Projektordner. */
    private fun baseDir(): String? = sessionId?.let { sessions.infos[it]?.cwd } ?: file.cwd ?: project.basePath

    private fun scrollToEnd() = SwingUtilities.invokeLater { scroll.verticalScrollBar.let { it.value = it.maximum } }

    private var needsYou = false

    /**
     * Nach `/cd` verschiebt Claude Code das Protokoll in den Projektordner des neuen Arbeitsordners (Eintrag "relocated");
     * die alte Datei ist dann weg und der Reiter blieb bei "Running…" stehen (2026-11-18). Dann per Session-ID suchen:
     * zuerst der transcript_path der Statuszeile, sonst ~/.claude/projects/<ordner>/<id>.jsonl.
     */
    private fun relocated(path: String, id: String?): String {
        if (id == null || File(path).isFile) return path
        statusBySession[id]?.get("transcript_path")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { File(it).isFile }?.let { return it }
        val now = System.currentTimeMillis()
        if (now - relocateSearchAt < 3000) return path
        relocateSearchAt = now
        return File(System.getProperty("user.home"), ".claude/projects").listFiles().orEmpty()
            .map { File(it, "$id.jsonl") }.firstOrNull { it.isFile }?.path ?: path
    }
    private var relocateSearchAt = 0L

    private fun refresh() {
        SessionEditors.needsYou(project, file).let { if (it != needsYou) { needsYou = it; updateTabTitle() } }
        val id = sessionId
        val info = id?.let { sessions.infos[it] }
        // Neue Session im selben Terminal (nach /clear): Protokoll wechseln.
        val path = (info?.transcript ?: file.transcript)?.let { relocated(it, id) }
        if (path != null && file.transcript != path) { file.transcript = path; SessionEditors.save(project) }
        if (path != null && transcript?.path != path) {
            // Andere Session im Reiter (neues `claude`, /clear): Vorgemerktes gehörte zur alten und kommt dort nie an.
            // pendingBase zählte die alte Datei - in der neuen wurde es nie erreicht, "Running…" klebte (2026-11-26).
            if (transcript?.path?.let { File(it).nameWithoutExtension } != File(path).nameWithoutExtension) {
                pending.clear(); sentNow.clear(); startedIdle.clear(); pendingBase = 0
            }
            transcript = Transcript(path); loading = false; renderTranscript(force = true)
        }
        // Einlesen im Hintergrund - das erste Lesen eines langen Protokolls dauert.
        val t = transcript
        if (t != null && !loading) {
            loading = true
            com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
                val changed = runCatching { t.update() }.getOrDefault(false)
                SwingUtilities.invokeLater {
                    if (transcript !== t) return@invokeLater
                    loading = false
                    if (changed) renderTranscript(force = false)
                }
            }
        }
        transcript?.title?.takeIf { it != file.title }?.let { file.title = it; updateTabTitle(); SessionEditors.save(project) }
        file.title?.let { t -> file.tab?.let { SessionTerminals.rename(project, it, TerminalBridge.TAB_NAME + " " + StringUtil.shortenTextWithEllipsis(t, 30, 0)) } }
        // Ordner, in dem die Session gestartet wurde - die Hooks melden den aktuellen (folgt jedem `cd` von Claude), und
        // `claude --resume` findet die Session nur im Startordner (2026-11-15: Resume landete im Unterordner).
        (path?.let { SessionEditors.startDir(it) } ?: info?.cwd?.takeIf { file.cwd == null })
            ?.takeIf { it != file.cwd }?.let { file.cwd = it; updateTabTitle(); SessionEditors.save(project) }
        currentDir()?.let { SessionEditors.shortPath(it) }?.takeIf { it != dirLink.text }?.let { dirLink.text = it }
        updateState(id)
        updateSuggestion(id)
        updateLimits(id)
        updateRemote()
    }

    private fun updateTabTitle() = runCatching {
        com.intellij.openapi.fileEditor.ex.FileEditorManagerEx.getInstanceEx(project).updateFilePresentation(file)
        // Auch die Navigationsleiste (Breadcrumb unten) zeichnet Symbol und Name neu - sie hört auf dieses Thema, nicht auf
        // updateFilePresentation; sonst drehte sich dort der Spinner weiter, obwohl Claude fertig war (2026-11-18).
        com.intellij.ide.ui.VirtualFileAppearanceListener.fireVirtualFileAppearanceChanged(file)
    }

    /**
     * Werkzeugzeile; schlug der Aufruf fehl, rot mit ✗ und der ersten Zeile der Meldung. Klick klappt den ganzen Aufruf und
     * das Ergebnis auf.
     */
    private fun toolHtml(html: StringBuilder, e: TranscriptEntry, key: Int) {
        val err = e.error
        val open = key in openTools
        val canOpen = e.detail != null || e.output != null
        val label = StringUtil.escapeXmlEntities(e.text)
        val text = if (canOpen) "<a href=\"$TOOL_LINK$key\">$label</a>" else label
        html.append("<div class=\"tool\"><a name=\"e$key\"></a>")
        if (err == null) html.append(if (open) "▾ " else "⚙ ").append(text)
        else html.append("<span class=\"error\">✗ </span>").append(text).append("<span class=\"error\"> — ").append(StringUtil.escapeXmlEntities(err)).append("</span>")
        html.append("</div>")
        if (!open) return
        e.detail?.let { html.append("<pre class=\"note\">").append(StringUtil.escapeXmlEntities(it)).append("</pre>") }
        html.append("<pre class=\"note\">").append(StringUtil.escapeXmlEntities(e.output?.ifBlank { "(no output)" } ?: "(no result yet)")).append("</pre>")
    }

    /** Uhrzeit am Prompt, bei älteren Tagen mit Datum; im Screenshot-Modus nicht (verrät Arbeitszeiten). */
    private fun timeHtml(t: Long?): String {
        if (t == null || System.getProperty("handoff.screenshots") != null) return ""
        val today = java.time.LocalDate.now()
        val at = java.time.Instant.ofEpochMilli(t).atZone(java.time.ZoneId.systemDefault())
        val text = if (at.toLocalDate() == today) time.format(Date(t)) else SimpleDateFormat("d.M. HH:mm").format(Date(t))
        return text
    }

    private fun appendEntry(html: StringBuilder, e: TranscriptEntry, key: Int) {
        when (e.kind) {
            // Uhrzeit oben rechts in der Sprechblase: Swing-HTML kennt kein float/position, darum eine Tabelle.
            TranscriptEntry.Kind.USER -> {
                val prompt = StringUtil.escapeXmlEntities(e.text).replace("\n", "<br>")
                val t = timeHtml(e.time)
                html.append("<div class=\"user\">")
                if (t.isEmpty()) html.append(prompt)
                else {
                    // ↶ vor der Uhrzeit: zurück vor diesen Prompt (nur in Sessions im Terminal der IDE, siehe [rewind]).
                    val back = e.uuid?.takeIf { file.tab != null && !file.logOnly }?.let { "<a href=\"$REWIND_LINK$it\">↶</a>&nbsp;&nbsp;" }.orEmpty()
                    html.append("<table width=\"100%\" cellspacing=\"0\" cellpadding=\"0\"><tr><td class=\"prompt\" valign=\"top\">").append(prompt)
                        .append("</td><td class=\"time\" align=\"right\" valign=\"top\" nowrap>").append(back).append(t).append("</td></tr></table>")
                }
                html.append("</div>")
            }
            TranscriptEntry.Kind.ASSISTANT -> {
                // Kopieren: je Codeblock und die ganze Nachricht als Markdown - Markieren im Verlauf nimmt Links und ▶ mit.
                val code = Md.codeBlocks(e.text)
                var n = 0
                val body = PRE.replace(SessionLinks.linkify(promptLinks(Md.document(e.text)), baseDir())) { m ->
                    val id = "c$key-${n++}"
                    copyTexts[id] = code.getOrNull(n - 1) ?: StringUtil.unescapeXmlEntities(m.groupValues[2].replace(TAG, ""))
                    copyLink(id) + m.value
                }
                html.append(body)
                copyTexts["m$key"] = e.text
                html.append(copyLink("m$key", "Copy message"))
            }
            TranscriptEntry.Kind.TOOL -> toolHtml(html, e, key)
            TranscriptEntry.Kind.IMAGE -> {
                val src = File(e.path ?: return).toURI().toString()
                html.append("<div class=\"tool\"><a href=\"").append(StringUtil.escapeXmlEntities(e.path)).append("\"><img src=\"")
                    .append(src).append("\" width=\"${JBUI.scale(e.width)}\" height=\"${JBUI.scale(e.height)}\" border=\"0\"></a></div>")
            }
            TranscriptEntry.Kind.INFO -> html.append("<div class=\"tool\">").append(StringUtil.escapeXmlEntities(e.text).replace("\n", "<br>")).append("</div>")
            TranscriptEntry.Kind.DIVIDER -> html.append("<div class=\"divider\">— ").append(StringUtil.escapeXmlEntities(e.text)).append(" —</div>")
            TranscriptEntry.Kind.FILE -> {
                val p = e.path.orEmpty()
                val base = baseDir()?.trimEnd('/')
                val shown = if (base != null && p.startsWith("$base/")) p.removePrefix("$base/") else File(p).name
                val href = p + (e.line?.let { ":$it" } ?: "")
                html.append("<div class=\"tool\">⎘ ").append(StringUtil.escapeXmlEntities(e.text)).append(" ")
                // Datei gibt es nicht mehr (umbenannt, gelöscht): kein Link, der ins Leere führt.
                if (File(p).exists()) html.append("<a href=\"").append(StringUtil.escapeXmlEntities(href)).append("\">")
                    .append(StringUtil.escapeXmlEntities(shown)).append("</a>")
                else html.append(StringUtil.escapeXmlEntities(shown)).append(" <i>(no longer there)</i>")
                html.append("</div>")
            }
            // Kurze Rückmeldungen lokaler Befehle ("Compacted", "Interrupted") als graue Statuszeile wie Tool-Zeilen -
            // als umrandeter Kasten wirkten sie wie ein Eingabefeld; mehrzeilige Ausgaben (/cost, /context) bleiben Kasten.
            TranscriptEntry.Kind.NOTE -> if ('\n' !in e.text && e.text.length <= 120)
                html.append("<div class=\"tool\">").append(if (e.text == "Interrupted") "⏹ " else "✓ ")
                    .append(StringUtil.escapeXmlEntities(e.text)).append("</div>")
            else html.append("<pre class=\"note\">").append(StringUtil.escapeXmlEntities(e.text)).append("</pre>")
            TranscriptEntry.Kind.SUMMARY -> {}
        }
    }

    private fun copyLink(id: String, label: String = "Copy") =
        "<div class=\"copyline\" align=\"right\"><a class=\"copy\" href=\"$COPY_LINK$id\">⧉ $label</a></div>"

    /**
     * Slash-Befehle, die Claude vorschlägt (eigener Absatz oder Code-Spanne, die mit einem bekannten Befehl beginnt, z. B.
     * `/compact Behalte nur …` oder `/session-kuerzen`), als Link mit ↗: Klick übernimmt den Befehl ins Eingabefeld.
     */
    private fun promptLinks(html: String): String {
        val names = SlashCommands.all(project, currentDir()).mapTo(HashSet()) { it.name }
        fun command(plain: String): String? {
            val t = plain.trim()
            val name = SLASH.matchAt(t, 0)?.groupValues?.get(1) ?: return null
            return t.takeIf { name in names && !t.contains('\n') }
        }
        // ▶ führt den Befehl gleich aus, der Befehl samt ↗ übernimmt ihn ins Eingabefeld.
        fun link(cmd: String, inner: String): String {
            val enc = java.net.URLEncoder.encode(cmd, Charsets.UTF_8).replace("+", "%20")
            return "<a href=\"$RUN_LINK$enc\">▶</a>&nbsp;<a href=\"$PROMPT_LINK$enc\">$inner&nbsp;↗</a>"
        }
        val paragraphs = PARAGRAPH.replace(html) { m ->
            val plain = StringUtil.unescapeXmlEntities(m.groupValues[1].replace(TAG, ""))
            val cmd = command(plain) ?: return@replace m.value
            "<p>" + link(cmd, StringUtil.escapeXmlEntities(cmd)) + "</p>"
        }
        // Codeblöcke: jede Zeile, die ein Befehl ist (z. B. ein ```-Block mit `/rename …`).
        val blocks = PRE.replace(paragraphs) { m ->
            m.groupValues[1] + m.groupValues[2].split("\n").joinToString("\n") { line ->
                command(StringUtil.unescapeXmlEntities(line))?.let { link(it, line.trim()) } ?: line
            } + "</pre>"
        }
        // Code-Spannen außerhalb von Codeblöcken und der eben gesetzten Links.
        return blocks.split(Regex("(?=<pre)|(?<=</pre>)|(?=<a )|(?<=</a>)")).joinToString("") { part ->
            if (part.startsWith("<pre") || part.startsWith("<a ")) part
            else INLINE_CODE.replace(part) { m ->
                val cmd = command(StringUtil.unescapeXmlEntities(m.groupValues[1])) ?: return@replace m.value
                link(cmd, m.value)
            }
        }
    }

    private fun renderTranscript(force: Boolean) {
        val t = transcript ?: return
        val bar = scroll.verticalScrollBar
        val atEnd = force || bar.value + bar.visibleAmount >= bar.maximum - JBUI.scale(40)
        val shown = t.entries.takeLast(limit)
        val html = StringBuilder("<html><body>")
        copyTexts.clear()
        if (t.entries.size > shown.size) (t.entries.size - shown.size).let { n ->
            html.append("<p class=\"note\">$n earlier entries not shown · <a href=\"$MORE_LINK\">Show ${minOf(n, MAX_ENTRIES)} more</a></p>")
        }
        if (shown.isEmpty()) html.append("<p class=\"note\">No messages yet.</p>")
        // Aufeinanderfolgende Werkzeug-Schritte eingeklappt: eine Zeile mit Anzahl und letztem Schritt, Klick klappt auf.
        val offset = t.entries.size - shown.size
        var i = 0
        while (i < shown.size) {
            val e = shown[i]
            if (e.kind == TranscriptEntry.Kind.TOOL) {
                var end = i
                while (end + 1 < shown.size && shown[end + 1].kind == TranscriptEntry.Kind.TOOL) end++
                val count = end - i + 1
                if (count >= 2) {
                    val key = offset + i
                    val open = key in expanded
                    html.append("<div class=\"tool\">")
                    if (!open) for (k in i..end) html.append("<a name=\"e${offset + k}\"></a>")
                    html.append("<a href=\"$STEPS_LINK$key\">${if (open) "▾" else "▸"} $count steps</a>")
                    val failed = (i..end).count { shown[it].error != null }
                    if (failed > 0) html.append(" · <span class=\"error\">$failed failed</span>")
                    if (!open) html.append(" · ").append(StringUtil.escapeXmlEntities(StringUtil.shortenTextWithEllipsis(shown[end].text, 120, 0)))
                    html.append("</div>")
                    if (open) for (k in i..end) toolHtml(html, shown[k], offset + k)
                    i = end + 1
                    continue
                }
            }
            if (e.kind == TranscriptEntry.Kind.SUMMARY) {
                val key = offset + i
                val open = key in expanded
                html.append("<div class=\"tool\"><a href=\"$STEPS_LINK$key\">${if (open) "▾" else "▸"} Summary of the earlier conversation</a></div>")
                // Grau und als Markdown, nicht im Prompt-Stil - die Zusammenfassung schreibt Claude Code, nicht der Nutzer.
                if (open) html.append("<div class=\"summary\">").append(Md.document(e.text)).append("</div>")
                i++
                continue
            }
            i++
            // One faulty entry must not stop the whole transcript (2026-11-23: a "%" did).
            val mark = html.length
            try {
                appendEntry(html, e, offset + i - 1)
                // Sprungmarke für die Dateiliste: in das erste Element des Eintrags (ein leeres <a> davor machte eine Leerzeile).
                if (html.length > mark) anchorAt(html, mark)?.let { html.insert(it, "<a name=\"e${offset + i - 1}\"></a>") }
            } catch (ex: Exception) {
                if (ex is com.intellij.openapi.progress.ProcessCanceledException) throw ex
                logger<SessionEditor>().warn("Handoff: entry failed to render", ex)
                html.setLength(mark)
                html.append("<pre>").append(StringUtil.escapeXmlEntities(e.text)).append("</pre>")
            }
        }
        // Abgeschickt, aber noch nicht im Protokoll (Claude arbeitet noch und hat ihn nur vorgemerkt): grau zeigen.
        // Claude Code speichert Prompts teils verändert (Bildpfade als [Image #1], eingefügter Text gekürzt) - der Text passt
        // dann nie. Deshalb auch zählen: Jeder neue Prompt im Protokoll seit dem Absenden löst den ältesten vorgemerkten ab.
        val users = t.entries.count { it.kind == TranscriptEntry.Kind.USER }
        if (users > pendingBase && pending.isNotEmpty()) { repeat(minOf(users - pendingBase, pending.size)) { pending.removeAt(0) }; pendingBase = users }
        // Zusätzlich über den Text: Steht der Prompt schon im Protokoll, ist er nicht mehr vorgemerkt (die Zählung allein
        // ließ ihn am 2026-11-15 stehen, Ursache nicht belegt).
        if (pending.isNotEmpty()) {
            fun norm(s: String) = s.replace(Regex("\\s+"), " ").trim()
            val recent = t.entries.filter { it.kind == TranscriptEntry.Kind.USER }.takeLast(20).map { norm(it.text) }.toSet()
            pending.removeAll { norm(it) in recent }
        }
        // Swing-HTML kennt nur eine Klasse je Element - grau per innerem span.
        sentNow.retainAll(pending.toSet())
        startedIdle.retainAll(pending.toSet())
        applyEmptyText()
        for (p in pending) html.append("<div class=\"user\"><span class=\"pending\">").append(StringUtil.escapeXmlEntities(p).replace("\n", "<br>"))
            .append(if (p in startedIdle) (if (p.startsWith("/")) "<br><i>Running…</i></span></div>" else "<br><i>Sent - Claude is on it.</i></span></div>")
                else if (p in sentNow) "<br><i>Sent now - Claude stops its current step and reads it right away.</i></span></div>"
                else "<br><i>Queued - Claude reads it after its current step (Ctrl+Enter sends right away).</i></span></div>")
        // Eine Zeile für alle Hintergrund-Aufgaben: Anzahl und die zuletzt gestartete.
        backgroundTasks().takeIf { it.isNotEmpty() }?.let { tasks ->
            html.append("<div class=\"tool\">⧗ ").append(if (tasks.size == 1) "Running in the background" else "${tasks.size} tasks running in the background")
                .append(" · ").append(StringUtil.escapeXmlEntities(StringUtil.shortenTextWithEllipsis(tasks.last(), 100, 0))).append("</div>")
        }
        html.append("</body></html>")
        val value = bar.value
        renderingNow = true
        pane.text = html.toString()
        if (search.isVisible) highlight(scrollTo = false)
        if (atEnd) scrollToEnd() else SwingUtilities.invokeLater { bar.value = value }
        // Erst nach dem Layout des neuen Inhalts wieder auf fremde Höhenänderungen achten.
        SwingUtilities.invokeLater { SwingUtilities.invokeLater { lastHeight = bar.maximum; renderingNow = false } }
    }

    private fun attachments(): List<Attachments.Item> {
        val t = transcript ?: return emptyList()
        val entries = synchronized(t) { t.entries.toList() }
        if (attachedCache.first != entries.size) {
            attachedCache = entries.size to Attachments.collect(entries, baseDir()?.trimEnd('/'))
            filesPanel.update(attachedCache.second)
        }
        return attachedCache.second
    }

    private fun toggleFiles() {
        filesOpen = !filesOpen
        com.intellij.ide.util.PropertiesComponent.getInstance().setValue(FILES_KEY, filesOpen, false)
        filesHost.secondComponent = if (filesOpen) filesPanel else null
        stateKey = null
        updateState(sessionId)
    }

    /**
     * Stelle für die Sprungmarke: direkt vor dem ersten Text des Eintrags. Eine Marke gleich nach `<div class="user">`
     * erzeugte eine leere Zeile oben in der Sprechblase (implizierter Absatz vor der Tabelle, 2026-11-27).
     */
    private fun anchorAt(html: StringBuilder, from: Int): Int? {
        var k = html.indexOf(">", from)
        while (k in 0 until html.length - 1) {
            val open = html.lastIndexOf("<", k)
            val tag = html.substring(open, k + 1)
            if (html[k + 1] != '<' && !tag.startsWith("<a ") && !tag.startsWith("</")) return k + 1
            k = html.indexOf(">", k + 1)
        }
        // Ohne Text (Bild): gleich nach dem ersten Tag - dort ist es ein inline-Inhalt (div.tool mit Bild-Link).
        return html.indexOf(">", from).takeIf { it >= 0 }?.plus(1)
    }

    /** Zum Eintrag [index] im Verlauf scrollen; liegt er vor den gezeigten Einträgen, mehr zeigen, Schritt-Gruppen aufklappen. */
    private fun jumpTo(index: Int, needle: String? = null) {
        val t = transcript ?: return
        val entries = synchronized(t) { t.entries.toList() }
        val need = entries.size - index
        if (need > limit) limit = need
        if (entries.getOrNull(index)?.kind == TranscriptEntry.Kind.TOOL) {
            val from = entries.size - limit
            var start = index
            while (start - 1 >= maxOf(0, from) && entries[start - 1].kind == TranscriptEntry.Kind.TOOL) start--
            expanded += start
        }
        renderTranscript(force = false)
        SwingUtilities.invokeLater { SwingUtilities.invokeLater { pane.scrollToReference("e$index"); flash("e$index", needle) } }
    }

    /**
     * Eintrag mit der Sprungmarke [name] kurz umrahmen, der Hintergrund flackert einmal auf und blendet aus; der Dateiname
     * [needle] darin bekommt zusätzlich einen kräftigeren Hintergrund (2026-11-27: statt markiertem Text).
     */
    private val flashTags = mutableListOf<Any>()
    private var flashStart = 0L
    /** 0..1: schnell einblenden, kurz halten, langsam ausblenden. */
    private fun flashAlpha(): Float {
        val t = (System.currentTimeMillis() - flashStart).toFloat()
        return when { t < 150 -> t / 150; t < 900 -> 1f; t < 2000 -> 1f - (t - 900) / 1100; else -> 0f }.coerceIn(0f, 1f)
    }
    private fun clearFlash() { flashTags.forEach { runCatching { pane.highlighter.removeHighlight(it) } }; flashTags.clear() }
    private val flashTimer = Timer(30) { e ->
        if (System.currentTimeMillis() - flashStart > 2000) { clearFlash(); (e.source as Timer).stop() }
        pane.repaint()
    }

    /** Rahmen um den ganzen Eintrag, volle Breite, runde Ecken. */
    private inner class BlockPainter(private val bubble: Boolean) : javax.swing.text.Highlighter.HighlightPainter {
        override fun paint(g: java.awt.Graphics, p0: Int, p1: Int, bounds: java.awt.Shape, c: javax.swing.text.JTextComponent) {
            val a = flashAlpha().takeIf { it > 0 } ?: return
            // Sprechblase: deren echte Umrisse aus dem View-Baum (der Innenabstand oben ist größer als geschätzt, der Rahmen
            // lag oben hinter der Blase - sah nach mehr Abstand oben aus, 2026-11-27).
            val box = if (bubble) bubbleBounds(c, p0, p1) else null
            val y = box?.let { (it.y - JBUI.scale(1)) to (it.y + it.height + JBUI.scale(1)) } ?: blockSpan(c, p0, p1) ?: return
            // Sprechblase (Prompt): Rahmen um die ganze Blase samt Innenabstand.
            // Sprechblase: knapp außerhalb ihres Innenabstands (6 px) - oben ist Platz (margin-top 14), unten nur 3 px bis
            // zur nächsten Zeile. Sonst wenig Luft, damit der Rahmen nicht über die Nachbarzeilen läuft (2026-11-27).
            val padTop = JBUI.scale(3); val padBottom = JBUI.scale(3)
            val x = JBUI.scale(14); val w = c.width - 2 * x
            val top = y.first - padTop; val h = y.second - y.first + padTop + padBottom
            val g2 = g.create() as java.awt.Graphics2D
            try {
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                val focus = JBUI.CurrentTheme.Focus.focusColor()
                val arc = JBUI.scale(10)
                g2.color = com.intellij.ui.ColorUtil.withAlpha(focus, 0.16 * a)
                g2.fillRoundRect(x, top, w, h, arc, arc)
                g2.color = com.intellij.ui.ColorUtil.withAlpha(focus, 0.9 * a)
                g2.stroke = java.awt.BasicStroke(JBUI.scale(2).toFloat())
                g2.drawRoundRect(x, top, w, h, arc, arc)
            } finally { g2.dispose() }
        }
    }

    /**
     * Oberkante und Unterkante aller sichtbaren Zeichen in [p0, p1). Nicht nur erstes/letztes Zeichen: In der Sprechblase
     * steht die Uhrzeit als letzte Zelle oben rechts - der Rahmen endete mitten im Prompt (2026-11-27).
     */
    private fun blockSpan(c: javax.swing.text.JTextComponent, p0: Int, p1: Int): Pair<Int, Int>? {
        val text = runCatching { c.document.getText(p0, p1 - p0) }.getOrDefault("")
        var top = Int.MAX_VALUE; var bottom = Int.MIN_VALUE
        val step = maxOf(1, text.length / 400)
        var i = 0
        while (i < text.length) {
            if (!text[i].isWhitespace()) runCatching { c.modelToView2D(p0 + i) }.getOrNull()?.let { r ->
                top = minOf(top, r.y.toInt()); bottom = maxOf(bottom, (r.y + r.height).toInt())
            }
            i += step
        }
        text.indexOfLast { !it.isWhitespace() }.takeIf { it >= 0 }?.let { j -> runCatching { c.modelToView2D(p0 + j) }.getOrNull()?.let { r -> bottom = maxOf(bottom, (r.y + r.height).toInt()) } }
        return if (top == Int.MAX_VALUE) null else top to bottom
    }

    /** Umriss des `div.user` (Sprechblase), in dem der Eintrag steht, über die Views des Dokuments. */
    private fun bubbleBounds(c: javax.swing.text.JTextComponent, p0: Int, p1: Int): java.awt.Rectangle? {
        val doc = c.document as? javax.swing.text.html.HTMLDocument ?: return null
        val text = runCatching { doc.getText(p0, p1 - p0) }.getOrDefault("")
        val pos = p0 + (text.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: return null)
        var el: javax.swing.text.Element? = doc.getCharacterElement(pos)
        // CLASS nur am Element selbst (isDefined): getAttribute erbt sie von den Eltern - sonst endete die Suche beim Text.
        while (el != null && !(el.name == "div" && el.attributes.isDefined(javax.swing.text.html.HTML.Attribute.CLASS) &&
                el.attributes.getAttribute(javax.swing.text.html.HTML.Attribute.CLASS) == "user")) el = el.parentElement
        val target = el ?: return null
        fun find(v: javax.swing.text.View, a: java.awt.Shape): java.awt.Rectangle? {
            if (v.element === target) {
                // Die Allokation eines Blocks enthält seine CSS-Ränder (margin) - die gehören nicht zur Blase.
                fun m(k: javax.swing.text.html.CSS.Attribute) = v.attributes.getAttribute(k)?.toString()?.let { Regex("[0-9.]+").find(it)?.value?.toFloatOrNull() }?.toInt() ?: 0
                val r = a.bounds
                val top = m(javax.swing.text.html.CSS.Attribute.MARGIN_TOP); val bottom = m(javax.swing.text.html.CSS.Attribute.MARGIN_BOTTOM)
                return java.awt.Rectangle(r.x, r.y + top, r.width, r.height - top - bottom)
            }
            for (i in 0 until v.viewCount) {
                val child = v.getView(i)
                if (child.startOffset > target.startOffset || child.endOffset < target.endOffset) continue
                val ca = v.getChildAllocation(i, a) ?: continue
                find(child, ca)?.let { return it }
            }
            return null
        }
        val root = c.ui.getRootView(c)
        val ins = c.insets
        return find(root, java.awt.Rectangle(ins.left, ins.top, c.width - ins.left - ins.right, c.height - ins.top - ins.bottom))
    }

    /** Kräftigerer Hintergrund hinter dem Dateinamen. */
    private inner class WordPainter : javax.swing.text.LayeredHighlighter.LayerPainter() {
        override fun paint(g: java.awt.Graphics, p0: Int, p1: Int, bounds: java.awt.Shape, c: javax.swing.text.JTextComponent) {}
        override fun paintLayer(g: java.awt.Graphics, p0: Int, p1: Int, bounds: java.awt.Shape, c: javax.swing.text.JTextComponent, view: javax.swing.text.View): java.awt.Shape? {
            val a = flashAlpha().takeIf { it > 0 } ?: return null
            val r = runCatching { view.modelToView(p0, javax.swing.text.Position.Bias.Forward, p1, javax.swing.text.Position.Bias.Backward, bounds).bounds }.getOrNull() ?: return null
            val g2 = g.create() as java.awt.Graphics2D
            try {
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = com.intellij.ui.ColorUtil.withAlpha(JBUI.CurrentTheme.Focus.focusColor(), 0.45 * a)
                g2.fillRoundRect(r.x - JBUI.scale(2), r.y, r.width + JBUI.scale(4), r.height, JBUI.scale(4), JBUI.scale(4))
            } finally { g2.dispose() }
            return r
        }
    }

    private fun flash(name: String, needle: String? = null) {
        val doc = pane.document as? javax.swing.text.html.HTMLDocument ?: return
        val marks = mutableListOf<Pair<String, Int>>()
        val it = doc.getIterator(javax.swing.text.html.HTML.Tag.A)
        while (it.isValid) {
            (it.attributes?.getAttribute(javax.swing.text.html.HTML.Attribute.NAME) as? String)?.let { n -> marks += n to it.startOffset }
            it.next()
        }
        val start = marks.firstOrNull { it.first == name }?.second ?: return
        val end = marks.map { it.second }.filter { it > start }.minOrNull() ?: minOf(doc.length, start + 2000)
        clearFlash()
        runCatching { pane.highlighter.addHighlight(start, end, BlockPainter(transcript?.entries?.getOrNull(name.removePrefix("e").toIntOrNull() ?: -1)?.kind == TranscriptEntry.Kind.USER)) }.getOrNull()?.let { flashTags += it }
        if (!needle.isNullOrEmpty()) {
            val text = runCatching { doc.getText(start, end - start) }.getOrDefault("")
            var i = text.indexOf(needle)
            while (i >= 0) {
                runCatching { pane.highlighter.addHighlight(start + i, start + i + needle.length, WordPainter()) }.getOrNull()?.let { flashTags += it }
                i = text.indexOf(needle, i + needle.length)
            }
        }
        // Nicht an die Oberkante (dort wurde der Rahmen abgeschnitten), sondern mit Abstand, bei kurzen Einträgen ins obere Drittel.
        blockSpan(pane, start, end)?.let { (top, bottom) ->
            val view = scroll.viewport.extentSize.height
            val margin = JBUI.scale(24)
            val y = if (bottom - top + 2 * margin < view) top - maxOf(margin, (view - (bottom - top)) / 3) else top - margin
            scroll.verticalScrollBar.value = y.coerceAtLeast(0)
        }
        flashStart = System.currentTimeMillis()
        flashTimer.restart()
    }

    /** Hintergrundaufgaben der Session - nur solange sie läuft (mit ihr enden auch die Aufgaben). */
    private fun backgroundTasks(): Collection<String> {
        val id = sessionId ?: return emptyList()
        if (id !in sessions.infos && !terminalOpen) return emptyList()
        return transcript?.background?.values?.toList().orEmpty()
    }

    /** Sessions, die in diesem Reiter schon liefen - nur dann heißt "keine Hook-Daten mehr", dass Claude beendet ist. */
    private fun exited(id: String?): Boolean {
        if (id == null) return false
        if (id in sessions.infos) { seenRunning += id; return false }
        return id in seenRunning
    }

    /** Fortsetzen: im eigenen Terminal, wenn dort nur noch die Shell läuft, sonst in einem neuen. */
    private fun resumeHere(id: String, prompt: String? = null) {
        val tab = file.tab
        if (tab != null && terminalOpen && exited(id)) {
            val arg = prompt?.trim()?.takeIf { it.isNotEmpty() }?.let { " '" + it.replace("'", "'\\''") + "'" }.orEmpty()
            if (SessionTerminals.send(project, tab, "claude --resume $id$arg")) { input.text = ""; seenRunning -= id; return }
        }
        SessionEditors.resume(project, file, id, prompt)
    }

    /** Kopfzeile, Rückfrage und Eingabe nach dem Zustand der Session; nur neu aufbauen, wenn er sich ändert. */
    private fun updateState(id: String?) {
        val waiting = sessions.waiting(id)
        val working = sessions.workingOn(id)
        val tab = file.tab
        // Claude beendet, Terminal steht noch (Shell, z. B. nach /logout oder /exit): wie ohne Terminal - Resume statt
        // Send, sonst ging der Prompt an die Shell (2026-11-26).
        val shellOnly = terminalOpen && exited(id)
        val terminal = terminalOpen && !shellOnly
        val canSend = terminal || (id != null && sessions.canReply(id))
        // ConcurrentHashMap: `null in set` wirft.
        val wasSent = id != null && id in sessions.sent
        val tasks = backgroundTasks().size
        // Offene Karte zu einer Freigabe/Frage dieser Session: Sie ersetzt die Ja/Nein-Leiste unten (beides zugleich verwirrte).
        val askCard = id != null && HandoffService.getInstance(project).proposals.any { it.isPending && it.askSession == id }
        val attached = attachments()
        val key = listOf(id, shellOnly, waiting?.at, waiting?.listening, working?.since, terminal, canSend, wasSent, tasks, askCard, attached.size, filesOpen, filesPanel.count).joinToString("|")
        if (key == stateKey) return
        stateKey = key

        val (text, color) = when {
            waiting?.finished == true -> "Claude is done · ${time.format(Date(waiting.at))}" to JBColor(0x2A7AA0, 0x5AA8D0)
            waiting != null -> "Claude is waiting for you" to JBColor(0xB04079, 0xC86A9A)
            working != null || wasSent -> "Claude is working…" + (working?.let { " since ${time.format(Date(it.since))}" } ?: "") to JBColor(0x5A9E6F, 0x6FBF85)
            tab != null && !terminal -> "Terminal closed" to UIUtil.getContextHelpForeground()
            id != null && id !in sessions.infos -> "Not running" to UIUtil.getContextHelpForeground()
            id == null && terminal && file.resuming -> "Resuming session…" to UIUtil.getContextHelpForeground()
            id == null && terminal -> "Starting Claude…" to UIUtil.getContextHelpForeground()
            else -> "Ready" to UIUtil.getContextHelpForeground()
        }
        status.text = if (tasks > 0 && working == null && !wasSent) "$text · $tasks background task${if (tasks > 1) "s" else ""} running" else text
        updateHeaderTip()
        status.foreground = color
        // Fertig/wartend geht vor: Ein noch gemerktes "gesendet" ließ den Spinner im Reiter und in der Navigationsleiste
        // (dort als Strich) weiterlaufen, obwohl die Kopfzeile "Claude is done" zeigte (2026-11-18).
        // Session meldet sich (Hook mit ID): Laden ist vorbei.
        if (id != null && file.resuming) { file.resuming = false; updateTabTitle() }
        val loading = file.resuming && terminal
        val isBusy = waiting == null && (working != null || wasSent || loading)
        busy.text = if (loading && working == null && !wasSent) "Resuming session…" else "Claude is working…"
        if (file.busy != isBusy) { file.busy = isBusy; updateTabTitle() }
        if (busy.isVisible != isBusy) { busy.isVisible = isBusy; conversation.revalidate(); if (isBusy) scrollToEnd() }

        headerLinks.removeAll()
        // Dateien der Session (von Claude und von dir) als Seitenleiste ein- und ausblenden.
        if (attached.isNotEmpty()) headerLinks.add(ActionLink(if (filesOpen) "Hide Files" else "Files (${filesPanel.count})") { toggleFiles() })
        if (tab != null && terminal) {
            if (working != null) headerLinks.add(ActionLink("Interrupt (Esc)") { SessionTerminals.keys(project, tab, "\u001b") })
            headerLinks.add(ActionLink("Show Terminal") { SessionTerminals.show(project, tab) })
        }
        // Session läuft nicht mehr (Terminal zu, IDE neu gestartet): im neuen Terminal fortsetzen.
        // Läuft sie doch noch anderswo (z. B. iTerm), entstünde ein zweiter Strang - deshalb nur, wenn nicht gerade gearbeitet wird.
        resumable = !terminal && id != null && working == null && !wasSent && SessionTerminals.available
        if (resumable && !file.logOnly) headerLinks.add(ActionLink("Resume in Terminal") { resumeHere(id!!) })
        headerLinks.revalidate(); headerLinks.repaint()

        question.removeAll()
        if (waiting != null && !waiting.finished) {
            question.add(JBLabel("<html>${Md.html(waiting.text.ifBlank { "Claude needs your input." })}</html>").apply { alignmentX = 0f })
            question.add(Box.createVerticalStrut(JBUI.scale(6)))
            question.add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false; alignmentX = 0f
                // Frage von Claude Code (AskUserQuestion) im Terminal: meldet sich auch als permission_prompt, aber Ja/Nein passt nicht
                // (Taste 1 hakte nur die erste Option an) - dann nur der Weg ins Terminal (2026-11-15).
                val asking = transcript?.entries?.lastOrNull()?.let { it.kind == TranscriptEntry.Kind.TOOL && it.text.startsWith("AskUserQuestion") } == true
                if (tab != null && terminal && waiting.notificationType == "permission_prompt" && !asking && !askCard) {
                    // Worum es geht: der Werkzeugaufruf, auf den Claude Code wartet - sonst stand nur "needs your permission" da.
                    transcript?.lastTool?.let { t: String ->
                        question.add(JBScrollPane(javax.swing.JTextArea(t).apply {
                            isEditable = false; lineWrap = true; wrapStyleWord = false
                            font = com.intellij.openapi.editor.colors.EditorColorsManager.getInstance().globalScheme.getFont(com.intellij.openapi.editor.colors.EditorFontType.PLAIN)
                            background = UIUtil.getPanelBackground(); border = JBUI.Borders.empty(4, 6)
                            rows = minOf(6, t.length / 90 + t.count { it == '\n' } + 1)
                        }).apply { alignmentX = 0f; border = JBUI.Borders.customLine(JBColor.border()); maximumSize = java.awt.Dimension(Int.MAX_VALUE, JBUI.scale(130)) }, question.componentCount - 1)
                        question.add(Box.createVerticalStrut(JBUI.scale(6)), question.componentCount - 1)
                    }
                    fun key(label: String, keys: String, tip: String) = JButton(label).apply {
                        toolTipText = tip
                        addActionListener { SessionTerminals.keys(project, tab, keys) }
                    }
                    add(key("Yes", "1", "Option 1 in the terminal"))
                    add(Box.createHorizontalStrut(JBUI.scale(6)))
                    add(key("Yes, Don't Ask Again", "2", "Option 2 in the terminal"))
                    add(Box.createHorizontalStrut(JBUI.scale(6)))
                    add(key("No", "\u001b", "Esc in the terminal; tell Claude what to do instead below"))
                    add(Box.createHorizontalStrut(JBUI.scale(12)))
                    add(ActionLink("Show Terminal") { SessionTerminals.show(project, tab) })
                } else if (tab != null && terminal) {
                    add(ActionLink("Answer in the Terminal") { SessionTerminals.show(project, tab) })
                } else {
                    add(JBLabel("Answer in the terminal where this session runs.").apply { foreground = UIUtil.getContextHelpForeground() })
                }
            })
        }
        question.isVisible = question.componentCount > 0
        question.revalidate(); question.repaint()

        // Auch, wenn die Session erst fortgesetzt werden muss: Der Prompt geht dann beim Fortsetzen mit (2026-11-15: Feld war
        // nach einem IDE-Neustart gesperrt, man konnte nur Bilder hineinziehen).
        input.isEnabled = canSend || resumable
        // DefaultCaret macht die Schreibmarke nur beim Fokuserhalt sichtbar, und nur wenn das Feld da aktiv ist. Bekam es den
        // Fokus, während es gesperrt war (Claude noch nicht bereit), blieb sie nach dem Freigeben unsichtbar (2026-11-18).
        if (input.isEnabled && input.isFocusOwner && !input.caret.isVisible) input.caret.isVisible = true
        send.isEnabled = canSend || resumable
        // Slash-Befehle erreichen nur Sessions im eigenen Terminal.
        commands.isEnabled = terminal
        dictate.isEnabled = canSend
        send.text = if (!canSend && resumable) "Resume" else "Send"
        baseEmptyText = when {
            terminal -> "Prompt or /command for Claude"
            canSend -> "Next prompt for Claude"
            else -> "Claude is not waiting for a prompt"
        }
        applyEmptyText()
        baseHint = when {
            argHint != null -> argHint!!
            terminal -> "Enter sends, Ctrl+Enter sends right away while Claude works, Shift+Enter adds a line. Slash commands included."
            canSend -> "Enter sends, Shift+Enter adds a line. Session started outside Handoff: no slash commands."
            resumable && shellOnly -> "Claude Code has exited in its terminal. Resume continues this session there (claude --resume)."
            resumable && tab != null -> "Terminal tab not found. Still running there? Type /show-in-editor in it. Otherwise Resume (claude --resume)."
            resumable -> "Not running in the IDE terminal. Resume continues it there (claude --resume); only if it is not still open elsewhere."
            id == null && tab == null -> "Session not found."
            else -> "Send when Claude is done, or type in the terminal."
        }
        renderHint()
    }


    /**
     * Meldung oder normaler Hinweis. Eine Meldung bleibt [NOTICE_MS] stehen, verblasst dann in [FADE_MS] zum normalen Hinweis;
     * solange die Maus darüber steht, bleibt sie (2026-11-26: Warnungen sollen von selbst gehen, aber lesbar bleiben).
     */
    private fun renderHint() {
        if (dictating) return
        val now = System.currentTimeMillis()
        val left = noticeUntil - now
        val note = noticeText?.takeIf { hintHovered || left > 0 }
        val strong = when {
            note != null && noticeWarn -> WARN_COLOR
            note != null && noticeError -> JBColor.namedColor("Label.errorForeground", JBColor.RED)
            else -> null
        }
        val grey = UIUtil.getContextHelpForeground()
        hint.foreground = if (strong != null && !hintHovered && left < FADE_MS) blend(strong, grey, left.toDouble() / FADE_MS) else strong ?: grey
        // Warnungen gelb mit Symbol, Fehler rot mit Symbol - grau ging das unter (2026-11-26).
        hint.icon = when {
            note != null && noticeWarn -> com.intellij.icons.AllIcons.General.Warning
            note != null && noticeError -> com.intellij.icons.AllIcons.General.Error
            else -> null
        }
        hint.text = note ?: baseHint
        if (note == null) { noticeText = null; fadeTimer.stop() }
    }

    private fun blend(a: java.awt.Color, b: java.awt.Color, t: Double): java.awt.Color {
        val k = t.coerceIn(0.0, 1.0)
        fun mix(x: Int, y: Int) = (y + (x - y) * k).toInt()
        return java.awt.Color(mix(a.red, b.red), mix(a.green, b.green), mix(a.blue, b.blue))
    }



    /**
     * One-off message (refused send, error) that stays for a while: updateState rewrites the hint on every refresh, so
     * a plain hint.text vanished after a few ms and nobody saw it (2026-11-23).
     */
    private fun notice(text: String, error: Boolean = true, warn: Boolean = false) {
        noticeText = text; noticeError = error; noticeWarn = warn; noticeUntil = System.currentTimeMillis() + NOTICE_MS + FADE_MS
        renderHint(); fadeTimer.start()
    }

    /** Text im Terminal-Eingabefeld, vor dem [send] schon gewarnt hat. */
    private var warnedTyped: String? = null


    private fun applyEmptyText() {
        // Mit Vorschlag zeichnet das Feld selbst (auch mit Fokus); der Platzhalter der IDE bleibt dann leer.
        val waiting = queued().size
        val text = when {
            suggestion != null -> ""
            waiting > 0 -> "Ctrl+Enter sends the queued " + (if (waiting == 1) "prompt" else "$waiting prompts") + " right away"
            else -> baseEmptyText
        }
        if (input.emptyText.text != text) { input.emptyText.text = text; input.repaint() }
    }

    /** Nur, wenn Claude fertig ist und im eigenen Terminal läuft - dann steht der Vorschlag hinter "❯". */
    private fun updateSuggestion(id: String?) {
        val tab = file.tab
        // Fertig heißt auch "Ready" ohne Wartezustand (z. B. nachdem eine Karte die Antwort brachte oder "Claude is done"
        // weggeklickt wurde) - nur nicht, solange Claude arbeitet oder auf eine Freigabe wartet. Vorher fehlte der Vorschlag
        // dann (2026-11-26, zweiter Vorschlag "Ask me to review it again").
        val waiting = sessions.waiting(id)
        val done = (waiting == null || waiting.finished) && sessions.workingOn(id) == null && (id == null || id !in sessions.sent)
        // Vorangestellte Dateien wie "[offene-datei.md]" (Claude Code hängt die offene Editor-Datei als Kontext an) weglassen -
        // sie passen selten zum nächsten Prompt.
        val s = (if (tab != null && done && terminalOpen) SessionTerminals.promptSuggestion(project, tab) else null)
            ?.replace(Regex("""^(?:\[[^\]\n]+]\s*)+"""), "")?.trim()?.takeIf { it.isNotEmpty() }
            // Ein Ergebnis, das Handoff selbst ins Terminal getippt hat, ist kein Vorschlag von Claude Code.
            ?.takeUnless { it.startsWith("Result of h") && it.contains("handed over earlier through Handoff") }
            // Platzhalter einer frischen Session ("Try \"fix typecheck errors\"") ist kein Vorschlag.
            ?.takeUnless { it.startsWith("Try \"") }
        if (s != suggestion) { suggestion = s; applyEmptyText() }
    }

    override fun getComponent(): JComponent = root
    override fun getPreferredFocusedComponent(): JComponent = input
    override fun getName() = "Claude Session"

    /** Zuletzt angezeigter Session-Reiter des Projekts - Ziel von "Send to Claude Session <Titel>". */
    override fun selectNotify() { SessionEditors.lastSelected[project] = file }
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) {}
    override fun isModified() = false
    override fun isValid() = true
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun dispose() {
        timer.stop(); fadeTimer.stop()
        SessionEditors.lastSelected.remove(project, file)
        slash.hide()
        com.intellij.openapi.util.Disposer.dispose(cards)
        // Nach dem Schließen dieses Reiters (nicht beim Schließen des Projekts) die Liste ohne ihn neu schreiben.
        // Ruht Claude, auch den Terminal-Reiter schließen - sonst sammeln sich dort unzählige "Claude"-Reiter (2026-11-17). Die
        // Session lässt sich per /resume wieder öffnen; arbeitet Claude gerade, bleibt das Terminal offen.
        val tab = file.tab.takeIf { !file.busy && !SessionEditors.isClosing(project) }
        val id = sessionId
        ApplicationManager.getApplication().invokeLater({
            SessionEditors.save(project)
            if (tab != null && FileEditorManager.getInstance(project).openFiles.none { it is SessionFile && it.tab == tab }) {
                SessionTerminals.close(project, tab)
                id?.let { sessions.ended(it) }
            }
        }, project.disposed)
    }

    companion object {
        /** Mehr Einträge machen den HTML-Viewer träge; der ganze Verlauf steht im Terminal. */
        private const val MAX_ENTRIES = 300
        private const val STEPS_LINK = "handoff-steps:"
        private const val TOOL_LINK = "handoff-tool:"
        private const val FILES_KEY = "handoff.files.open"
        private const val MORE_LINK = "handoff-more"
        private const val PROMPT_LINK = "handoff-prompt:"
        private const val RUN_LINK = "handoff-run:"
        private const val REWIND_LINK = "handoff-rewind:"
        private const val COPY_LINK = "handoff-copy:"
        private val PRE = Regex("(<pre[^>]*>)(.*?)</pre>", RegexOption.DOT_MATCHES_ALL)
        private val SLASH = Regex("""/([\w:.-]+)(?=\s|$)""")
        private val PARAGRAPH = Regex("<p>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)
        private val INLINE_CODE = Regex("<code>([^<]{2,2000})</code>")
        private val TAG = Regex("<[^>]+>")
        private const val NOTICE_MS = 8_000L
        private const val FADE_MS = 800L
    }

    // Der Timer startet erst hier, am Ende des Konstruktors: refresh() nutzt Felder, die weiter unten in der Klasse stehen.
    init { timer.start() }
}

private val PASTED = Regex("""</?pasted_content id="[0-9a-f]{4}">\n?""")

/** Marker von Claude Code um eingefügten Text entfernen (siehe [Transcript]). */
fun unwrapPasted(text: String): String = if (text.contains("pasted_content id=\"")) text.replace(PASTED, "").trim() else text

/**
 * Schmaler Knopf (Mikrofon, "/") neben einem zweiten über Send: Die IDE gibt Knöpfen eine Mindestbreite von gut 70 px.
 * "JButton.buttonType" = "square" umging sie, verschluckte aber den Text, sobald der Knopf ausgegraut war (2026-11-15).
 */
private class SmallButton(text: String?, icon: Icon? = null) : JButton(text, icon) {
    override fun getPreferredSize(): java.awt.Dimension = super.getPreferredSize().apply { width = JBUI.scale(34) }
    override fun getMinimumSize(): java.awt.Dimension = preferredSize
}

/**
 * Namen von Einstellungsseiten und IDE-Aktionen → Link (`settings:<Name>` bzw. `action:<Id>`). Der Index entsteht einmal im
 * Hintergrund; bis dahin verlinkt [SessionLinks] nichts zusätzlich. Mehrdeutige Namen fallen heraus.
 */
object IdeTargets {
    @Volatile private var settings: Map<String, String>? = null
    @Volatile private var actions: Map<String, String>? = null
    private val started = java.util.concurrent.atomic.AtomicBoolean()

    private fun norm(s: String) = s.replace('\u00a0', ' ').trim().removeSuffix("…").removeSuffix("...").trim().lowercase().replace(Regex("\\s+"), " ")

    fun ready(): Boolean {
        if (started.compareAndSet(false, true)) com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread { build() }
        return actions != null
    }

    private fun build() {
        val s = HashMap<String, String?>()
        runCatching {
            com.intellij.openapi.options.Configurable.APPLICATION_CONFIGURABLE.extensionList.forEach { ep ->
                runCatching { ep.displayName }.getOrNull()?.takeIf { it.isNotBlank() }?.let { n -> s.merge(norm(n), n) { a, b -> if (a == b) a else null } }
            }
        }
        settings = s.filterValues { it != null }.mapValues { it.value!! }
        val a = HashMap<String, String?>()
        runCatching {
            val manager = com.intellij.openapi.actionSystem.ActionManager.getInstance()
            for (id in manager.getActionIdList("")) {
                val text = runCatching { manager.getActionOrStub(id)?.templatePresentation?.text }.getOrNull()?.takeIf { it.length >= 4 } ?: continue
                a.merge(norm(text), id) { x, y -> if (x == y) x else null }
            }
        }
        actions = a.filterValues { it != null }.mapValues { it.value!! }
    }

    /** Link für einen fett geschriebenen Namen; "Settings | Tools | MCP Server" zählt als Seite "MCP Server". */
    fun find(name: String): String? {
        val n = norm(name.substringAfterLast('|'))
        if (n.length < 3) return null
        settings?.get(n)?.let { return "settings:" + java.net.URLEncoder.encode(it, Charsets.UTF_8).replace("+", "%20") }
        if (name.contains('|') || name.trimEnd().endsWith(":")) return null
        // Aktionen führen etwas aus: einzelne Wörter wie **Run** oder **Send** sind meist keine Aktion gemeint - nur mehrere
        // Wörter oder Namen mit "…" (Dialog-Aktionen) verlinken.
        val t = name.trim()
        if (!t.contains(' ') && !t.endsWith("…") && !t.endsWith("...")) return null
        return actions?.get(n)?.let { "action:$it" }
    }
}

/** Session-Ansicht für ein Protokoll außerhalb eines Session-Reiters (siehe [SessionLogEditorProvider]). */
internal fun createSessionEditor(project: Project, file: SessionFile): FileEditor = SessionEditor(project, file)

/**
 * Prompts als Sprechblase: Swing-HTML kennt kein `border-radius`, darum malt diese View den Hintergrund von
 * `<div class="user">` selbst mit runden Ecken (Farbe wie zuvor im CSS).
 */
private object BUBBLE : com.intellij.util.ui.ExtendableHTMLViewFactory.Extension {
    override fun invoke(elem: javax.swing.text.Element, view: javax.swing.text.View): javax.swing.text.View? =
    if (view is javax.swing.text.html.BlockView && elem.attributes.getAttribute(javax.swing.text.html.HTML.Attribute.CLASS) == "user")
        object : javax.swing.text.html.BlockView(elem, javax.swing.text.View.Y_AXIS) {
            override fun paint(g: java.awt.Graphics, a: java.awt.Shape) {
                val r = a.bounds
                val g2 = g.create() as java.awt.Graphics2D
                try {
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                    g2.color = JBColor.namedColor("EditorTabs.underlinedTabBackground", JBColor(0xEEF2F8, 0x2E3440))
                    val arc = JBUI.scale(16).toFloat()
                    // Ränder (margin-top/-bottom) gehören nicht zur Blase.
                    val top = getTopInset() - getInsetFromCss(javax.swing.text.html.CSS.Attribute.PADDING_TOP)
                    val bottom = getBottomInset() - getInsetFromCss(javax.swing.text.html.CSS.Attribute.PADDING_BOTTOM)
                    g2.fill(java.awt.geom.RoundRectangle2D.Float(r.x.toFloat(), r.y + top, r.width.toFloat(), r.height - top - bottom, arc, arc))
                } finally { g2.dispose() }
                super.paint(g, a)
            }
            private fun getInsetFromCss(k: javax.swing.text.html.CSS.Attribute): Float =
                (attributes.getAttribute(k)?.toString()?.removeSuffix("px")?.toFloatOrNull() ?: 0f)
        }
    else view
}

/**
 * Rückfrage zum Rewind. Eigener Dialog statt Messages: Dessen AlertDialog scrollt den Text ab etwa sieben Zeilen bzw.
 * 330 Zeichen (`wrapToScrollPaneIfNeeded`, Schriftgröße × 9,45 px), und die Erklärung war nur per Scrollen lesbar (2026-11-19).
 */
private class RewindDialog(project: Project, private val prompt: String, private val options: List<String>, private val first: Boolean) :
    com.intellij.openapi.ui.DialogWrapper(project) {
    var chosen: String? = null

    init {
        title = "Rewind"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val esc = { t: String -> StringUtil.escapeXmlEntities(t) }
        val quoted = esc(StringUtil.shortenTextWithEllipsis(prompt.replace('\n', ' '), 160, 0))
        val html = "<html><div style='width:${JBUI.scale(420)}px'>Go back to before this prompt?<br><br><i>\"$quoted\"</i><br><br>" +
            "<b>Code:</b> files Claude changed from here on go back to their state before the prompt (your own edits in between are lost).<br><br>" +
            "<b>Conversation:</b> Claude forgets this prompt and everything after it; the prompt goes back into the input field." +
            (if (first) "<br><br>This is the first prompt, so only the code can go back." else "") + "</div></html>"
        return JBLabel(html, com.intellij.openapi.ui.Messages.getQuestionIcon(), JBLabel.LEFT).apply {
            verticalTextPosition = JBLabel.TOP
            verticalAlignment = JBLabel.TOP
            iconTextGap = JBUI.scale(12)
        }
    }

    override fun createActions(): Array<javax.swing.Action> = options.mapIndexed { i, label ->
        object : DialogWrapperAction(label) {
            init { if (i == 0) putValue(DEFAULT_ACTION, true) }
            override fun doAction(e: java.awt.event.ActionEvent?) { chosen = label; close(OK_EXIT_CODE) }
        }
    }.toTypedArray<javax.swing.Action>() + cancelAction
}

/** URL-decodes; a stray "%" (e.g. SQL "LIKE '/x/%'") keeps the text as is instead of throwing. */
internal fun urlDecode(text: String): String =
    try { java.net.URLDecoder.decode(text, Charsets.UTF_8) } catch (_: IllegalArgumentException) { text }

/**
 * Hints like "[⧉ In datei.md]" at the start of Claude Code's input line (open editor file or selection). The name may
 * contain brackets itself - a SQL console is "console [live2023]" - so a hint ends at a "]" followed by a space or the
 * line end, not at the first "]" (the left-over "]" counted as typed text and swallowed the first Enter, 2026-11-23).
 */
/** Gelb für Hinweise unter dem Eingabefeld, die eine Handlung brauchen (hell: dunkles Gelb, damit es lesbar bleibt). */
private val WARN_COLOR = JBColor(0x9A6700, 0xE2B340)

internal val EDITOR_HINTS = Regex("""^(?:\[[^\n]*?](?=\s|$)\s*)+""")
