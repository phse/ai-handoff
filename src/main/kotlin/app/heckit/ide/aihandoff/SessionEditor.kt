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
    /** Arbeitsordner der Session - auch außerhalb des Projekts möglich ("New Claude Session in Folder…"). */
    @Volatile var cwd: String? = restoredCwd
    /** Titel der Session (von Claude Code vergeben), für den Reiter. */
    @Volatile var title: String? = restoredTitle
    /** Zuletzt bekanntes Protokoll - nach einem IDE-Neustart gibt es die Hook-Daten der Session oft nicht mehr. */
    @Volatile var transcript: String? = restoredTranscript

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
        (file as? SessionFile)?.let { if (it.busy) HandoffSpinner.icon else HandoffIcons.editor() }
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
            (if (SessionEditors.needsYou(project, f)) "● " else "") + (f.title?.let { StringUtil.shortenTextWithEllipsis(it, 40, 0) } ?: "New Session") + dir
        }

    override fun getEditorTabTooltipHtml(project: Project, file: VirtualFile): com.intellij.openapi.util.text.HtmlChunk? =
        (file as? SessionFile)?.let { f -> com.intellij.openapi.util.text.HtmlChunk.text(SessionEditors.shortPath(f.cwd ?: project.basePath ?: return null)) }
}

/** Öffnen, Starten und Aufräumen der Session-Reiter. */
object SessionEditors {
    /** Wartet etwas in dieser Session auf den Nutzer: eine Karte von ihr oder eine Freigabe/Rückfrage im Terminal (nicht: fertig). */
    fun needsYou(project: Project, f: SessionFile): Boolean {
        val id = f.currentSession(project) ?: return false
        if (HandoffService.getInstance(project).proposals.any { it.isPending && it.owner == id }) return true
        return project.getServiceIfCreated(WaitingSessions::class.java)?.sessions.orEmpty().any { it.id == id && !it.finished }
    }

    /** Schon offenen Reiter der Session zeigen, sonst einen neuen öffnen. */
    fun open(project: Project, tab: String?, sessionId: String?, focus: Boolean = true, cwd: String? = null) {
        val manager = FileEditorManager.getInstance(project)
        val open = manager.openFiles.filterIsInstance<SessionFile>().firstOrNull { f ->
            (tab != null && f.tab == tab) || (sessionId != null && (f.sessionId == sessionId || f.currentSession(project) == sessionId))
        }
        OpenFileDescriptor(project, open ?: SessionFile(tab, sessionId, restoredCwd = cwd)).navigate(focus)
        if (focus) com.intellij.ide.impl.ProjectUtil.focusProjectWindow(project, true)
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
        open(project, tab, null, cwd = dir ?: project.basePath)
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
        FileEditorManager.getInstance(project).closeFile(file)
        open(project, tab, null, cwd = dir)
    }

    private const val KEY = "app.heckit.ide.aihandoff.sessionTabs"
    /** Beim Schließen des Projekts nicht mehr mitschreiben - sonst löschte das Schließen der Reiter die Liste. */
    private val closing: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Offene Session-Reiter (Session, Protokoll, Titel) merken, damit sie nach einem Neustart wieder aufgehen. */
    fun save(project: Project) {
        if (project.isDisposed || project.locationHash in closing) return
        val lines = FileEditorManager.getInstance(project).openFiles.filterIsInstance<SessionFile>().mapNotNull { f ->
            val id = f.currentSession(project) ?: return@mapNotNull null
            listOf(id, f.transcript.orEmpty(), f.title.orEmpty().replace('\t', ' ').replace('\n', ' '), f.cwd.orEmpty()).joinToString("\t")
        }.distinct()
        com.intellij.ide.util.PropertiesComponent.getInstance(project).setList(KEY, lines.ifEmpty { null })
    }

    /** Projekt (oder IDE) schließt gerade - dann Terminals nicht wegräumen, die IDE beendet sie ohnehin. */
    fun isClosing(project: Project) = project.isDisposed || project.locationHash in closing

    fun restore(project: Project) {
        com.intellij.openapi.application.ApplicationManager.getApplication().messageBus.connect(project)
            .subscribe(com.intellij.openapi.project.ProjectManager.TOPIC, object : com.intellij.openapi.project.ProjectManagerListener {
                override fun projectClosing(p: Project) { if (p === project) closing += project.locationHash }
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
private class ImageDropHandler(private val delegate: javax.swing.TransferHandler, private val shorten: (String) -> String) : javax.swing.TransferHandler() {
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
                .showSettingsDialog(project, java.net.URLDecoder.decode(link.removePrefix("settings:"), Charsets.UTF_8))
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
        val m = WITH_LINE.matchEntire(java.net.URLDecoder.decode(text.trim(), Charsets.UTF_8)) ?: return null
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
class TranscriptEntry(val kind: Kind, val text: String) {
    enum class Kind { USER, ASSISTANT, TOOL, NOTE, SUMMARY }
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

    /** Neue Zeilen einlesen; true, wenn sich etwas geändert hat. */
    fun update(): Boolean {
        val file = File(path)
        if (!file.isFile) return false
        val length = file.length()
        if (length < offset) { offset = 0; entries.clear(); background.clear(); toolLines.clear() }
        if (length == offset) return false
        val bytes = RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            ByteArray((length - offset).coerceAtMost(32L * 1024 * 1024).toInt()).also { raf.readFully(it) }
        }
        val end = bytes.lastIndexOf('\n'.code.toByte())
        if (end < 0) return false
        offset += end + 1
        val before = Triple(entries.size, title, background.keys.toList())
        String(bytes, 0, end, Charsets.UTF_8).lineSequence().forEach { line -> if (line.isNotBlank()) runCatching { parse(JsonParser.parseString(line).asJsonObject) } }
        return Triple(entries.size, title, background.keys.toList()) != before
    }

    private fun JsonObject.str(k: String) = get(k)?.takeIf { it.isJsonPrimitive }?.asString

    private fun parse(o: JsonObject) {
        if (o.get("isSidechain")?.asBoolean == true || o.get("isMeta")?.asBoolean == true) return
        when (o.str("type")) {
            "ai-title" -> if (!customTitle) title = o.str("aiTitle") ?: title
            "custom-title" -> o.str("customTitle")?.takeIf { it.isNotBlank() }?.let { title = it; customTitle = true }
            // Während Claude arbeitet abgeschickte Prompts stehen nur als Anhang im Protokoll.
            "attachment" -> {
                val a = o.getAsJsonObject("attachment") ?: return
                if (a.str("type") == "queued_command") a.str("prompt")?.trim()?.let { prompt ->
                    // Endet eine Hintergrund-Aufgabe, während Claude arbeitet, kommt die <task-notification> als eingereihter
                    // Befehl statt als Nutzer-Nachricht - ohne diese Prüfung blieben solche Aufgaben ewig "running" (2026-11-16).
                    TASK_DONE.findAll(prompt).forEach { background.remove(it.groupValues[1]) }
                    userText(prompt)?.let { entries += it }
                }
            }
            // Lokale Slash-Befehle (/rename …) und ihre Ausgabe schreibt Claude Code als "system"/"local_command" - ohne diesen
            // Zweig blieb der Befehl im Tab ewig "Queued", obwohl er längst gelaufen war (2026-11-16).
            "system" -> if (o.str("subtype") == "local_command") o.str("content")?.let { userText(it) }?.let { entries += it }
            "user" -> {
                val content = o.getAsJsonObject("message")?.get("content") ?: return
                trackBackground(o, content)
                val text = texts(content).joinToString("\n").trim()
                // Zusammenfassung nach /compact: steht als Nutzer-Nachricht im Protokoll, wird eingeklappt gezeigt.
                if (o.get("isCompactSummary")?.asBoolean == true) { if (text.isNotEmpty()) entries += TranscriptEntry(TranscriptEntry.Kind.SUMMARY, text); return }
                userText(text)?.let { entries += it }
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
                            entries += TranscriptEntry(TranscriptEntry.Kind.TOOL, line)
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

    private companion object { val TASK_DONE = Regex("<task-notification>\\s*<task-id>([^<]+)</task-id>") }

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
        if (text.startsWith("<")) return null
        return TranscriptEntry(TranscriptEntry.Kind.USER, text.removePrefix("The user sent this follow-up prompt from the IDE (Handoff tool window):").trim())
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
    /** Zahl der Prompts im Protokoll, als der älteste vorgemerkte abgeschickt wurde. */
    private var pendingBase = 0
    /** Davon per Ctrl+Enter sofort gesendet. */
    private val sentNow = mutableSetOf<String>()
    /** Aufgeklappte Gruppen von Werkzeug-Schritten (Index des ersten Schritts im Protokoll). */
    private val expanded = mutableSetOf<Int>()

    /** Stil mit den Farben des aktuellen Themes - nach einem Wechsel neu gebaut (die Farben stehen als Text im CSS). */
    private fun styledKit() = HTMLEditorKitBuilder().withWordWrapViewFactory().build().also { kit ->
            val fg = UIUtil.getContextHelpForeground()
            val grey = "#%02x%02x%02x".format(fg.red, fg.green, fg.blue)
            val bg = JBColor.namedColor("EditorTabs.underlinedTabBackground", JBColor(0xEEF2F8, 0x2E3440))
            val userBg = "#%02x%02x%02x".format(bg.red, bg.green, bg.blue)
            kit.styleSheet.addRule("body { margin: ${JBUI.scale(12)}px ${JBUI.scale(24)}px; }")
            kit.styleSheet.addRule("h1 { font-size: 140%; } h2 { font-size: 120%; } h3 { font-size: 108%; }")
            kit.styleSheet.addRule("pre { padding: ${JBUI.scale(6)}px; border: 1px solid $grey; }")
            kit.styleSheet.addRule("td, th { padding: 3px ${JBUI.scale(8)}px 3px 0; text-align: left; vertical-align: top; }")
            kit.styleSheet.addRule(".user { background: $userBg; padding: ${JBUI.scale(6)}px ${JBUI.scale(10)}px; margin-top: ${JBUI.scale(14)}px; }")
            kit.styleSheet.addRule(".tool { color: $grey; margin-top: 2px; margin-bottom: 2px; }")
            kit.styleSheet.addRule(".note { color: $grey; }")
            kit.styleSheet.addRule(".pending { color: $grey; }")
        }

    private val pane = JEditorPane().apply {
        editorKit = styledKit()
        isEditable = false
        addHyperlinkListener { e ->
            if (e.eventType != HyperlinkEvent.EventType.ACTIVATED) return@addHyperlinkListener
            val link = e.description ?: e.url?.toString() ?: return@addHyperlinkListener
            if (link.startsWith(RUN_LINK)) {
                val cmd = java.net.URLDecoder.decode(link.removePrefix(RUN_LINK), Charsets.UTF_8)
                if (file.tab != null && terminalOpen) runCommand(cmd)
                else hint.text = "Slash commands only reach sessions started here (New Claude Session)."
                return@addHyperlinkListener
            }
            if (link.startsWith(PROMPT_LINK)) {
                input.text = java.net.URLDecoder.decode(link.removePrefix(PROMPT_LINK), Charsets.UTF_8)
                input.requestFocusInWindow(); input.caretPosition = input.document.length
                return@addHyperlinkListener
            }
            if (link.startsWith(STEPS_LINK)) {
                link.removePrefix(STEPS_LINK).toIntOrNull()?.let { if (!expanded.remove(it)) expanded += it; renderTranscript(force = false) }
                return@addHyperlinkListener
            }
            SessionLinks.open(project, link, baseDir())
        }
        // Tooltip für vorgeschlagene Slash-Befehle (Swing-HTML zeigt kein title-Attribut an).
        addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
            override fun mouseMoved(e: java.awt.event.MouseEvent) {
                val href = linkAt(e.point)
                val tip = when {
                    href?.startsWith(RUN_LINK) == true -> "Run now"
                    href?.startsWith(PROMPT_LINK) == true -> "Use as prompt"
                    else -> null
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
                val href = linkAt(e.point)?.takeUnless { it.startsWith(STEPS_LINK) || it.startsWith(PROMPT_LINK) || it.startsWith(RUN_LINK) } ?: return
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
            if (w != lastWidth) { lastWidth = w; if (stickToEnd) scrollToEnd() }
            else stickToEnd = bar.value + bar.visibleAmount >= bar.maximum - JBUI.scale(40)
        }
    }

    // Darf in der Breite schrumpfen, nie in der Höhe: Mindesthöhe 0 ließ BoxLayout die Zeile oben anheften und unten
    // abschneiden (2026-11-16, Unterlängen fehlten).
    private val status = object : JBLabel() { override fun getMinimumSize() = java.awt.Dimension(0, preferredSize.height) }
    /** Arbeitsordner der Session; Klick zeigt ihn im Finder bzw. Explorer. */
    private val dirLink = object : ActionLink("", { _: java.awt.event.ActionEvent -> (file.cwd ?: baseDir())?.let { com.intellij.ide.actions.RevealFileAction.openDirectory(File(it)) } }) {
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

    private fun updateLimits(id: String?) {
        val now = System.currentTimeMillis()
        if (now - limitsReadAt < 5000) return
        limitsReadAt = now
        val file = PlanLimits.data
        if (!PlanLimits.isInstalled() || !file.isFile) { limits.text = ""; return }
        val l = PlanLimits.read()
        val o = runCatching { JsonParser.parseString(file.readText()).asJsonObject }.getOrNull()
        val mine = o?.get("session_id")?.takeIf { it.isJsonPrimitive }?.asString == id
        val parts = mutableListOf<String>()
        if (mine) {
            o?.getAsJsonObject("model")?.get("display_name")?.takeIf { it.isJsonPrimitive }?.asString?.let { parts += it }
            o?.getAsJsonObject("context_window")?.get("used_percentage")?.takeIf { it.isJsonPrimitive }?.asInt?.let { parts += "Context $it %" }
        }
        val fmt = java.time.format.DateTimeFormatter.ofPattern("EEE HH:mm").withZone(java.time.ZoneId.systemDefault())
        val tips = mutableListOf<String>()
        l?.fiveHour?.let { w -> parts += "5h ${w.usedPercent.toInt()} %"; w.resetsAt?.let { tips += "5-hour limit resets ${fmt.format(it)}" } }
        l?.sevenDay?.let { w -> parts += "Week ${w.usedPercent.toInt()} %"; w.resetsAt?.let { tips += "Weekly limit resets ${fmt.format(it)}" } }
        limits.text = parts.joinToString("  ·  ")
        limits.toolTipText = tips.joinToString("<br>").takeIf { it.isNotEmpty() }?.let { "<html>$it</html>" }
        // Fast aufgebraucht: auffällig.
        val max = listOfNotNull(l?.fiveHour?.usedPercent, l?.sevenDay?.usedPercent).maxOrNull() ?: 0.0
        limits.foreground = if (max >= 90) JBColor(0xB04079, 0xC86A9A) else UIUtil.getContextHelpForeground()
    }

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
            override fun actionPerformed(e: java.awt.event.ActionEvent) { if (!slash.acceptIfVisible()) send() }
        })
        transferHandler = ImageDropHandler(transferHandler) { path -> attach(path) }
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

    private fun attach(path: String): String {
        attachments.entries.firstOrNull { it.value == path }?.let { return it.key }
        val n = attachments.size + 1
        val token = if (IMAGE.matches(path) || IMAGE.matches("\"$path\"")) "[Image #$n]" else "[File #$n: ${File(path).name}]"
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
        .filter { File(it).isFile }.distinct().toList()

    private fun updateMarkLink() {
        val n = imagesInPrompt().size
        markLink.isVisible = n > 0
        markLink.text = if (n > 1) "Mark Areas in Images…" else "Mark Areas in Image…"
    }

    private val MARK_LINE = Regex("""^- (.+), area x=(\d+) y=(\d+) w=(\d+) h=(\d+) px(?:: (.*))?$""")
    private val MARK_HEAD = "Marked areas (pixels of the original image):"
    private val DRAW_HEAD = "Drawings (copies of the images with my drawing on top - read them):"
    private val DRAW_LINE = Regex("""^- (.+), drawing: (.+\.png)$""")
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
        val drawnBefore = lines.mapNotNull { DRAW_LINE.matchEntire(it.trim()) }.associate { it.groupValues[1] to it.groupValues[2] }
        val drawings = dialog.allShapes.toSortedMap().mapNotNull { (i, list) ->
            val path = paths[i]; val name = File(path).name
            if (list.isEmpty()) { promptShapes.remove(path); return@mapNotNull null }
            // Unverändert: die vorhandene Kopie behalten.
            val file = drawnBefore[name]?.takeIf { promptShapes[path] == list } ?: Drawing.save(path, list) ?: return@mapNotNull null
            promptShapes[path] = list.map { it.copy() }
            "- $name, drawing: $file"
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
        addActionListener { val id = sessionId; if (resumable && id != null) SessionEditors.resume(project, file, id, expanded()) else send() }
    }
    /** Liste der Slash-Befehle: ausführen oder an der Schreibmarke in den Prompt übernehmen. */
    private val commands = SmallButton("/").apply {
        toolTipText = "Slash commands: run one or insert it at the cursor"
        addActionListener { SlashPicker.show(project, this, run = ::runCommand, insert = ::insertCommand) }
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
            else stateKey = null  // Hinweis beim nächsten Durchlauf neu setzen
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
            if (!handled) { dictating = false; hint.text = "Dictation did not start - switch it on in System Settings > Keyboard > Dictation."; return }
            dictating = !stop
        }
        Dictation.toggle(::done)
    }

    private fun runCommand(cmd: String) {
        val tab = file.tab ?: return
        if (terminalOpen && SessionTerminals.send(project, tab, cmd)) {
            // Wie beim Senden gleich als Prompt mit "Queued" zeigen - ohne Rückmeldung wirkte ▶ (etwa bei /compact, das
            // eine Weile braucht) wie kaputt und wurde mehrfach geklickt (2026-11-17).
            if (pending.isEmpty()) pendingBase = transcript?.entries?.count { it.kind == TranscriptEntry.Kind.USER } ?: 0
            pending += cmd; renderTranscript(force = true)
            input.requestFocusInWindow()
        }
    }

    private fun insertCommand(cmd: String) {
        val pos = input.caretPosition.coerceIn(0, input.document.length)
        val before = if (pos > 0 && !input.document.getText(pos - 1, 1).isBlank()) " " else ""
        input.document.insertString(pos, "$before$cmd ", null)
        input.requestFocusInWindow()
    }

    /** Session läuft nicht im Terminal der IDE (z. B. nach einem Neustart): Knopf "Resume" statt "Send". */
    private var resumable = false
    private val hint = JBLabel().apply { foreground = UIUtil.getContextHelpForeground(); font = JBUI.Fonts.smallFont() }

    private val root = JPanel(BorderLayout()).apply {
        add(header, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
        add(JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            border = JBUI.Borders.compound(JBUI.Borders.customLineTop(JBColor.border()), JBUI.Borders.empty(8, 12))
            add(question, BorderLayout.NORTH)
            add(JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
                isOpaque = false
                add(JBScrollPane(input).apply { border = JBUI.Borders.customLine(JBColor.border()) }, BorderLayout.CENTER)
                add(JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                    isOpaque = false
                    // Mikrofon und "/" nebeneinander über Send, zusammen so breit wie Send.
                    add(JPanel(java.awt.GridLayout(1, 0, JBUI.scale(4), 0)).apply { isOpaque = false; add(dictate); add(commands) }, BorderLayout.NORTH)
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

    private val timer = Timer(700) { refresh() }.apply { start() }

    init {
        refresh()
        // Theme gewechselt: Farben im CSS (Prompt-Hintergrund, Grau) und den Hintergrund des Verlaufs neu setzen (2026-11-15:
        // nach Dark -> Light blieben die Prompts dunkelblau).
        com.intellij.openapi.application.ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(com.intellij.ide.ui.LafManagerListener.TOPIC, com.intellij.ide.ui.LafManagerListener {
                SwingUtilities.invokeLater {
                    pane.editorKit = styledKit()
                    conversation.background = pane.background
                    renderTranscript(force = false)
                }
            })
    }

    private val sessionId get() = file.currentSession(project)
    private val terminalOpen get() = file.tab?.let { SessionTerminals.isOpen(project, it) } == true
    /** Vorschläge für Slash-Befehle - nur, wenn sie die Session erreichen (Terminal). */
    private val slash: SlashCompletion = SlashCompletion(project, input, fileToken = { mention(it) }, enabled = { terminalOpen }, workDir = { baseDir() })

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

    private fun send(now: Boolean = false) {
        val shown = input.text.trim().takeIf { it.isNotEmpty() } ?: return
        if (resumeFromTab(shown)) return
        val text = expanded(shown)
        endDictation()
        watchKeys = true
        SwingUtilities.invokeLater { logInputState("after send") }
        // Session läuft nicht mehr (z. B. nach einem IDE-Neustart): fortsetzen und den Prompt gleich mitgeben.
        val id = sessionId
        if (resumable && id != null && !(file.tab != null && terminalOpen) && !(sessions.canReply(id))) {
            SessionEditors.resume(project, file, id, text); return
        }
        val tab = file.tab
        val ok = when {
            tab != null && terminalOpen -> if (now) SessionTerminals.sendNow(project, tab, text) else SessionTerminals.send(project, tab, text)
            else -> sessionId?.let { sessions.reply(it, text) } == true
        }
        if (ok) {
            if (pending.isEmpty()) pendingBase = transcript?.entries?.count { it.kind == TranscriptEntry.Kind.USER } ?: 0
            pending += text; if (now && tab != null && file.busy) sentNow += text; input.text = ""; attachments.clear(); renderTranscript(force = true)
        }
        else hint.text = if (text.startsWith("/")) "Slash commands only reach sessions started here (New Claude Session)." else "Claude is busy - send when it is done, or type in the terminal."
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

    private fun refresh() {
        SessionEditors.needsYou(project, file).let { if (it != needsYou) { needsYou = it; updateTabTitle() } }
        val id = sessionId
        val info = id?.let { sessions.infos[it] }
        // Neue Session im selben Terminal (nach /clear): Protokoll wechseln.
        val path = info?.transcript ?: file.transcript
        if (path != null && file.transcript != path) { file.transcript = path; SessionEditors.save(project) }
        if (path != null && transcript?.path != path) { transcript = Transcript(path); loading = false; renderTranscript(force = true) }
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
        (file.cwd ?: baseDir())?.let { SessionEditors.shortPath(it) }?.takeIf { it != dirLink.text }?.let { dirLink.text = it }
        updateState(id)
        updateSuggestion(id)
        updateLimits(id)
    }

    private fun updateTabTitle() = runCatching {
        com.intellij.openapi.fileEditor.ex.FileEditorManagerEx.getInstanceEx(project).updateFilePresentation(file)
    }

    private fun appendEntry(html: StringBuilder, e: TranscriptEntry) {
        when (e.kind) {
            TranscriptEntry.Kind.USER -> html.append("<div class=\"user\">")
                .append(StringUtil.escapeXmlEntities(e.text).replace("\n", "<br>")).append("</div>")
            TranscriptEntry.Kind.ASSISTANT -> html.append(SessionLinks.linkify(promptLinks(Md.document(e.text)), baseDir()))
            TranscriptEntry.Kind.TOOL -> html.append("<div class=\"tool\">⚙ ").append(StringUtil.escapeXmlEntities(e.text)).append("</div>")
            // Kurze Rückmeldungen lokaler Befehle ("Compacted", "Interrupted") als graue Statuszeile wie Tool-Zeilen -
            // als umrandeter Kasten wirkten sie wie ein Eingabefeld; mehrzeilige Ausgaben (/cost, /context) bleiben Kasten.
            TranscriptEntry.Kind.NOTE -> if ('\n' !in e.text && e.text.length <= 120)
                html.append("<div class=\"tool\">").append(if (e.text == "Interrupted") "⏹ " else "✓ ")
                    .append(StringUtil.escapeXmlEntities(e.text)).append("</div>")
            else html.append("<pre class=\"note\">").append(StringUtil.escapeXmlEntities(e.text)).append("</pre>")
            TranscriptEntry.Kind.SUMMARY -> {}
        }
    }

    /**
     * Slash-Befehle, die Claude vorschlägt (eigener Absatz oder Code-Spanne, die mit einem bekannten Befehl beginnt, z. B.
     * `/compact Behalte nur …` oder `/session-kuerzen`), als Link mit ↗: Klick übernimmt den Befehl ins Eingabefeld.
     */
    private fun promptLinks(html: String): String {
        val names = SlashCommands.all(project).mapTo(HashSet()) { it.name }
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
        val shown = t.entries.takeLast(MAX_ENTRIES)
        val html = StringBuilder("<html><body>")
        if (t.entries.size > shown.size) html.append("<p class=\"note\">${t.entries.size - shown.size} earlier entries not shown.</p>")
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
                    html.append("<div class=\"tool\"><a href=\"$STEPS_LINK$key\">${if (open) "▾" else "▸"} $count steps</a>")
                    if (!open) html.append(" · ").append(StringUtil.escapeXmlEntities(StringUtil.shortenTextWithEllipsis(shown[end].text, 120, 0)))
                    html.append("</div>")
                    if (open) for (k in i..end) html.append("<div class=\"tool\">⚙ ").append(StringUtil.escapeXmlEntities(shown[k].text)).append("</div>")
                    i = end + 1
                    continue
                }
            }
            if (e.kind == TranscriptEntry.Kind.SUMMARY) {
                val key = offset + i
                val open = key in expanded
                html.append("<div class=\"tool\"><a href=\"$STEPS_LINK$key\">${if (open) "▾" else "▸"} Summary of the earlier conversation</a></div>")
                if (open) html.append("<div class=\"user\">").append(StringUtil.escapeXmlEntities(e.text).replace("\n", "<br>")).append("</div>")
                i++
                continue
            }
            i++
            appendEntry(html, e)
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
        for (p in pending) html.append("<div class=\"user\"><span class=\"pending\">").append(StringUtil.escapeXmlEntities(p).replace("\n", "<br>"))
            .append(if (p in sentNow) "<br><i>Sent now - Claude stops its current step and reads it right away.</i></span></div>"
                else "<br><i>Queued - Claude reads it after its current step (Ctrl+Enter sends right away).</i></span></div>")
        // Eine Zeile für alle Hintergrund-Aufgaben: Anzahl und die zuletzt gestartete.
        backgroundTasks().takeIf { it.isNotEmpty() }?.let { tasks ->
            html.append("<div class=\"tool\">⧗ ").append(if (tasks.size == 1) "Running in the background" else "${tasks.size} tasks running in the background")
                .append(" · ").append(StringUtil.escapeXmlEntities(StringUtil.shortenTextWithEllipsis(tasks.last(), 100, 0))).append("</div>")
        }
        html.append("</body></html>")
        val value = bar.value
        pane.text = html.toString()
        if (atEnd) scrollToEnd() else SwingUtilities.invokeLater { bar.value = value }
    }

    /** Hintergrundaufgaben der Session - nur solange sie läuft (mit ihr enden auch die Aufgaben). */
    private fun backgroundTasks(): Collection<String> {
        val id = sessionId ?: return emptyList()
        if (id !in sessions.infos && !terminalOpen) return emptyList()
        return transcript?.background?.values?.toList().orEmpty()
    }

    /** Kopfzeile, Rückfrage und Eingabe nach dem Zustand der Session; nur neu aufbauen, wenn er sich ändert. */
    private fun updateState(id: String?) {
        val waiting = sessions.waiting(id)
        val working = sessions.workingOn(id)
        val tab = file.tab
        val terminal = terminalOpen
        val canSend = terminal || (id != null && sessions.canReply(id))
        // ConcurrentHashMap: `null in set` wirft.
        val wasSent = id != null && id in sessions.sent
        val tasks = backgroundTasks().size
        val key = listOf(id, waiting?.at, waiting?.listening, working?.since, terminal, canSend, wasSent, tasks).joinToString("|")
        if (key == stateKey) return
        stateKey = key

        val (text, color) = when {
            waiting?.finished == true -> "Claude is done · ${time.format(Date(waiting.at))}" to JBColor(0x2A7AA0, 0x5AA8D0)
            waiting != null -> "Claude is waiting for you" to JBColor(0xB04079, 0xC86A9A)
            working != null || wasSent -> "Claude is working…" + (working?.let { " since ${time.format(Date(it.since))}" } ?: "") to JBColor(0x5A9E6F, 0x6FBF85)
            tab != null && !terminal -> "Terminal closed" to UIUtil.getContextHelpForeground()
            id != null && id !in sessions.infos -> "Not running" to UIUtil.getContextHelpForeground()
            id == null && terminal -> "Starting Claude…" to UIUtil.getContextHelpForeground()
            else -> "Ready" to UIUtil.getContextHelpForeground()
        }
        status.text = if (tasks > 0 && working == null && !wasSent) "$text · $tasks background task${if (tasks > 1) "s" else ""} running" else text
        status.foreground = color
        val isBusy = working != null || wasSent
        if (file.busy != isBusy) { file.busy = isBusy; updateTabTitle() }
        if (busy.isVisible != isBusy) { busy.isVisible = isBusy; conversation.revalidate(); if (isBusy) scrollToEnd() }

        headerLinks.removeAll()
        if (tab != null && terminal) {
            if (working != null) headerLinks.add(ActionLink("Interrupt (Esc)") { SessionTerminals.keys(project, tab, "\u001b") })
            headerLinks.add(ActionLink("Show Terminal") { SessionTerminals.show(project, tab) })
        }
        // Session läuft nicht mehr (Terminal zu, IDE neu gestartet): im neuen Terminal fortsetzen.
        // Läuft sie doch noch anderswo (z. B. iTerm), entstünde ein zweiter Strang - deshalb nur, wenn nicht gerade gearbeitet wird.
        resumable = !terminal && id != null && working == null && !wasSent && SessionTerminals.available
        if (resumable) headerLinks.add(ActionLink("Resume in Terminal") { SessionEditors.resume(project, file, id!!) })
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
                if (tab != null && terminal && waiting.notificationType == "permission_prompt" && !asking) {
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
        hint.text = when {
            terminal -> "Enter sends, Ctrl+Enter sends right away while Claude works, Shift+Enter adds a line. Slash commands included."
            canSend -> "Enter sends, Shift+Enter adds a line. Session started outside Handoff: no slash commands."
            resumable -> "Not running in the IDE terminal. Resume continues it there (claude --resume); only if it is not still open elsewhere."
            id == null && tab == null -> "Session not found."
            else -> "Send when Claude is done, or type in the terminal."
        }
    }

    /** Vorschlag von Claude Code für den nächsten Prompt (grau im leeren Feld, Tab übernimmt ihn). */
    private var suggestion: String? = null
    private var baseEmptyText = ""

    private fun applyEmptyText() {
        // Mit Vorschlag zeichnet das Feld selbst (auch mit Fokus); der Platzhalter der IDE bleibt dann leer.
        val text = if (suggestion != null) "" else baseEmptyText
        if (input.emptyText.text != text) { input.emptyText.text = text; input.repaint() }
    }

    /** Nur, wenn Claude fertig ist und im eigenen Terminal läuft - dann steht der Vorschlag hinter "❯". */
    private fun updateSuggestion(id: String?) {
        val tab = file.tab
        val done = sessions.waiting(id)?.finished == true && (id == null || id !in sessions.sent)
        // Vorangestellte Dateien wie "[offene-datei.md]" (Claude Code hängt die offene Editor-Datei als Kontext an) weglassen -
        // sie passen selten zum nächsten Prompt.
        val s = (if (tab != null && done && terminalOpen) SessionTerminals.promptSuggestion(project, tab) else null)
            ?.replace(Regex("""^(?:\[[^\]\n]+]\s*)+"""), "")?.trim()?.takeIf { it.isNotEmpty() }
        if (s != suggestion) { suggestion = s; applyEmptyText() }
    }

    override fun getComponent(): JComponent = root
    override fun getPreferredFocusedComponent(): JComponent = input
    override fun getName() = "Claude Session"
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) {}
    override fun isModified() = false
    override fun isValid() = true
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun dispose() {
        timer.stop()
        slash.hide()
        com.intellij.openapi.util.Disposer.dispose(cards)
        // Nach dem Schließen dieses Reiters (nicht beim Schließen des Projekts) die Liste ohne ihn neu schreiben.
        // Ruht Claude, auch den Terminal-Reiter schließen - sonst sammeln sich dort unzählige "Claude"-Reiter (2026-11-17). Die
        // Session lässt sich per /resume wieder öffnen; arbeitet Claude gerade, bleibt das Terminal offen.
        val tab = file.tab.takeIf { !file.busy && !SessionEditors.isClosing(project) }
        ApplicationManager.getApplication().invokeLater({
            SessionEditors.save(project)
            if (tab != null && FileEditorManager.getInstance(project).openFiles.none { it is SessionFile && it.tab == tab }) SessionTerminals.close(project, tab)
        }, project.disposed)
    }

    companion object {
        /** Mehr Einträge machen den HTML-Viewer träge; der ganze Verlauf steht im Terminal. */
        private const val MAX_ENTRIES = 300
        private const val STEPS_LINK = "handoff-steps:"
        private const val PROMPT_LINK = "handoff-prompt:"
        private const val RUN_LINK = "handoff-run:"
        private val PRE = Regex("(<pre[^>]*>)(.*?)</pre>", RegexOption.DOT_MATCHES_ALL)
        private val SLASH = Regex("""/([\w:.-]+)(?=\s|$)""")
        private val PARAGRAPH = Regex("<p>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)
        private val INLINE_CODE = Regex("<code>([^<]{2,2000})</code>")
        private val TAG = Regex("<[^>]+>")
    }
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
