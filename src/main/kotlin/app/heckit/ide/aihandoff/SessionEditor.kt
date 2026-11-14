package app.heckit.ide.aihandoff

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
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
class SessionFile(val tab: String?, val sessionId: String?, restoredTranscript: String? = null, restoredTitle: String? = null) :
    LightVirtualFile("Claude", SessionFileType, "") {
    init { isWritable = false }
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

class SessionTabTitleProvider : EditorTabTitleProvider, DumbAware {
    override fun getEditorTabTitle(project: Project, file: VirtualFile): String? =
        (file as? SessionFile)?.let { f -> "Claude" + (f.title?.let { " · " + StringUtil.shortenTextWithEllipsis(it, 40, 0) } ?: "") }
}

/** Öffnen, Starten und Aufräumen der Session-Reiter. */
object SessionEditors {
    /** Schon offenen Reiter der Session zeigen, sonst einen neuen öffnen. */
    fun open(project: Project, tab: String?, sessionId: String?, focus: Boolean = true) {
        val manager = FileEditorManager.getInstance(project)
        val open = manager.openFiles.filterIsInstance<SessionFile>().firstOrNull { f ->
            (tab != null && f.tab == tab) || (sessionId != null && (f.sessionId == sessionId || f.currentSession(project) == sessionId))
        }
        OpenFileDescriptor(project, open ?: SessionFile(tab, sessionId)).navigate(focus)
        if (focus) com.intellij.ide.impl.ProjectUtil.focusProjectWindow(project, true)
    }

    /** "New Claude Session": Terminal-Reiter mit `claude` im Hintergrund, die Session im Editor-Reiter. */
    fun newSession(project: Project) {
        if (!SessionTerminals.available) {
            Messages.showErrorDialog(project, "This needs the IDE's new terminal (2026.1 or later).", "New Claude Session")
            return
        }
        if (!WaitingHook.isInstalled()) {
            val msg = "Claude sessions in editor tabs need Handoff's hooks for Claude Code (\"Show When Claude Is Waiting\").\n\n" +
                "Handoff will create ~/.claude/handoff/hook.sh, add it as a hook in ~/.claude/settings.json (a backup is kept as " +
                "settings.json.bak-handoff; existing hooks stay) and add the command /show-in-editor to ~/.claude/commands."
            if (Messages.showOkCancelDialog(project, msg, "New Claude Session", "Set Up and Start", "Cancel", null) != Messages.OK) return
            runCatching { WaitingHook.install() }.onFailure {
                Messages.showErrorDialog(project, it.message ?: it.toString(), "New Claude Session"); return
            }
        }
        val tab = SessionTerminals.start(project)
        if (tab == null) {
            Messages.showErrorDialog(project, "Could not open a terminal tab (see idea.log).", "New Claude Session")
            return
        }
        WaitingSessions.getInstance(project).start()
        open(project, tab, null)
    }

    fun closeAll(project: Project) {
        val manager = FileEditorManager.getInstance(project)
        manager.openFiles.filterIsInstance<SessionFile>().forEach { manager.closeFile(it) }
    }

    /** Laufende Session in neuem Terminal fortsetzen (`claude --resume`); der Reiter folgt dann dem Terminal. */
    fun resume(project: Project, file: SessionFile, sessionId: String) {
        val tab = SessionTerminals.start(project, "claude --resume $sessionId") ?: return
        FileEditorManager.getInstance(project).closeFile(file)
        open(project, tab, null)
    }

    private const val KEY = "app.heckit.ide.aihandoff.sessionTabs"
    /** Beim Schließen des Projekts nicht mehr mitschreiben - sonst löschte das Schließen der Reiter die Liste. */
    private val closing: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Offene Session-Reiter (Session, Protokoll, Titel) merken, damit sie nach einem Neustart wieder aufgehen. */
    fun save(project: Project) {
        if (project.isDisposed || project.locationHash in closing) return
        val lines = FileEditorManager.getInstance(project).openFiles.filterIsInstance<SessionFile>().mapNotNull { f ->
            val id = f.currentSession(project) ?: return@mapNotNull null
            listOf(id, f.transcript.orEmpty(), f.title.orEmpty().replace('\t', ' ').replace('\n', ' ')).joinToString("\t")
        }.distinct()
        com.intellij.ide.util.PropertiesComponent.getInstance(project).setList(KEY, lines.ifEmpty { null })
    }

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
                OpenFileDescriptor(project, SessionFile(null, id, parts.getOrNull(1)?.ifBlank { null }, parts.getOrNull(2)?.ifBlank { null })).navigate(false)
            }
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
private class ImageDropHandler(private val delegate: javax.swing.TransferHandler) : javax.swing.TransferHandler() {
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
        val text = paths.joinToString(" ") { if (it.contains(' ')) "\"$it\"" else it }
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
            else -> if (!openFile(project, link.removePrefix("file://"), base)) BrowserUtil.browse(link)
        }
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
    private val URL = Regex("""https?://[^\s<>"'`)\]]+[^\s<>"'`)\].,;:!?]""")
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
                text = URL.replace(text) { u -> a(StringUtil.unescapeXmlEntities(u.value), u.value) }
                // Pfade nur außerhalb der eben gesetzten Links.
                text = text.split(Regex("(?=<a )|(?<=</a>)")).joinToString("") { part ->
                    if (part.startsWith("<a ")) part
                    else PATH.replace(part) { p -> val raw = StringUtil.unescapeXmlEntities(p.value); if (resolve(raw, base) != null || File(raw.replaceFirst(Regex("^~(?=/)"), System.getProperty("user.home"))).isDirectory) a(raw, p.value) else p.value }
                }
            }
            out.append(text)
        }
        // Code-Spannen danach (sie stehen nicht in Links).
        return CODE.replace(out.toString()) { m ->
            val raw = StringUtil.unescapeXmlEntities(m.groupValues[1])
            if (raw.contains(' ') || !raw.contains('.') && !raw.contains('/')) return@replace m.value
            if (resolve(raw, base) == null) m.value else a(raw, m.value)
        }
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
    enum class Kind { USER, ASSISTANT, TOOL, NOTE }
}

/**
 * Liest das Protokoll einer Session (JSONL von Claude Code) nach und nach: Nur neue, vollständige Zeilen werden gelesen.
 * Seitenpfade (Subagenten), Meta-Einträge und Tool-Ergebnisse bleiben weg.
 */
class Transcript(val path: String) {
    val entries = mutableListOf<TranscriptEntry>()
    var title: String? = null
        private set
    private var offset = 0L

    /** Neue Zeilen einlesen; true, wenn sich etwas geändert hat. */
    fun update(): Boolean {
        val file = File(path)
        if (!file.isFile) return false
        val length = file.length()
        if (length < offset) { offset = 0; entries.clear() }
        if (length == offset) return false
        val bytes = RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            ByteArray((length - offset).coerceAtMost(32L * 1024 * 1024).toInt()).also { raf.readFully(it) }
        }
        val end = bytes.lastIndexOf('\n'.code.toByte())
        if (end < 0) return false
        offset += end + 1
        val before = entries.size to title
        String(bytes, 0, end, Charsets.UTF_8).lineSequence().forEach { line -> if (line.isNotBlank()) runCatching { parse(JsonParser.parseString(line).asJsonObject) } }
        return (entries.size to title) != before
    }

    private fun JsonObject.str(k: String) = get(k)?.takeIf { it.isJsonPrimitive }?.asString

    private fun parse(o: JsonObject) {
        if (o.get("isSidechain")?.asBoolean == true || o.get("isMeta")?.asBoolean == true) return
        when (o.str("type")) {
            "ai-title" -> title = o.str("aiTitle") ?: title
            // Während Claude arbeitet abgeschickte Prompts stehen nur als Anhang im Protokoll.
            "attachment" -> {
                val a = o.getAsJsonObject("attachment") ?: return
                if (a.str("type") == "queued_command") a.str("prompt")?.trim()?.let { userText(it) }?.let { entries += it }
            }
            "user" -> {
                val content = o.getAsJsonObject("message")?.get("content") ?: return
                val text = texts(content).joinToString("\n").trim()
                userText(text)?.let { entries += it }
            }
            "assistant" -> {
                val content = o.getAsJsonObject("message")?.get("content")?.takeIf { it.isJsonArray } ?: return
                content.asJsonArray.forEach { item ->
                    val c = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                    when (c.str("type")) {
                        "text" -> c.str("text")?.trim()?.takeIf { it.isNotEmpty() }?.let { entries += TranscriptEntry(TranscriptEntry.Kind.ASSISTANT, it) }
                        "tool_use" -> entries += TranscriptEntry(TranscriptEntry.Kind.TOOL, toolLine(c.str("name").orEmpty(), c.getAsJsonObject("input")))
                    }
                }
            }
        }
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
    private fun userText(text: String): TranscriptEntry? {
        if (text.isEmpty()) return null
        Regex("<command-name>(.*?)</command-name>").find(text)?.let { m ->
            val args = Regex("(?s)<command-args>(.*?)</command-args>").find(text)?.groupValues?.get(1)?.trim().orEmpty()
            return TranscriptEntry(TranscriptEntry.Kind.USER, (m.groupValues[1].trim() + " " + args).trim())
        }
        Regex("(?s)<local-command-stdout>(.*?)</local-command-stdout>").find(text)?.let { m ->
            val out = m.groupValues[1].replace(Regex("\u001B\\[[0-9;]*m"), "").trim()
            return if (out.isEmpty()) null else TranscriptEntry(TranscriptEntry.Kind.NOTE, out)
        }
        if (text.startsWith("<")) return null
        return TranscriptEntry(TranscriptEntry.Kind.USER, text.removePrefix("The user sent this follow-up prompt from the IDE (Handoff tool window):").trim())
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

    private val pane = JEditorPane().apply {
        editorKit = HTMLEditorKitBuilder().withWordWrapViewFactory().build().also { kit ->
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
        isEditable = false
        addHyperlinkListener { e ->
            if (e.eventType != HyperlinkEvent.EventType.ACTIVATED) return@addHyperlinkListener
            val link = e.description ?: e.url?.toString() ?: return@addHyperlinkListener
            SessionLinks.open(project, link, baseDir())
        }
        // Rechtsklick auf einen Link: Aktionen je nach Ziel.
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mousePressed(e: java.awt.event.MouseEvent) = popup(e)
            override fun mouseReleased(e: java.awt.event.MouseEvent) = popup(e)
            private fun popup(e: java.awt.event.MouseEvent) {
                if (!e.isPopupTrigger) return
                val href = linkAt(e.point) ?: return
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
    private val cards = HandoffPanel(project, compact = true).apply { border = JBUI.Borders.empty(0, JBUI.scale(24) - 8, JBUI.scale(12), JBUI.scale(24) - 8) }

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
    private val scroll = JBScrollPane(conversation).apply { border = JBUI.Borders.empty() }

    private val status = JBLabel()
    private val header = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(6, 12))
        add(status, BorderLayout.WEST)
    }
    private val headerLinks = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(12), 0)).apply { isOpaque = false }.also { header.add(it, BorderLayout.EAST) }
    /** Plan-Limits (5 Stunden, Woche) und - für diese Session - Modell und Kontext, aus den Daten der Statuszeile. */
    private val limits = JBLabel().apply {
        foreground = UIUtil.getContextHelpForeground()
        horizontalAlignment = javax.swing.SwingConstants.CENTER
    }.also { header.add(it, BorderLayout.CENTER) }
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


    private val input = JBTextArea(3, 0).apply {
        lineWrap = true; wrapStyleWord = true; font = UIUtil.getLabelFont()
        border = JBUI.Borders.empty(6, 8)
        inputMap.put(KeyStroke.getKeyStroke("ENTER"), "handoff.send")
        inputMap.put(KeyStroke.getKeyStroke("shift ENTER"), "insert-break")
        actionMap.put("handoff.send", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) { if (!slash.acceptIfVisible()) send() }
        })
        transferHandler = ImageDropHandler(transferHandler)
        document.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) { SwingUtilities.invokeLater { updateMarkLink() } }
        })
    }

    /** Bilder im Prompt: Bereiche markieren wie beim Prüfen von Bildern (Rahmen mit Notiz). */
    private val markLink = ActionLink("Mark Areas in Image…") { markImages() }.apply { isVisible = false }
    private val IMAGE = Regex("""(?:"([^"]+\.(?:png|jpe?g|gif|webp))"|(\S+\.(?:png|jpe?g|gif|webp)))""", RegexOption.IGNORE_CASE)

    private fun imagesInPrompt(): List<String> = IMAGE.findAll(input.text).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }
        .filter { File(it).isFile }.distinct().toList()

    private fun updateMarkLink() {
        val n = imagesInPrompt().size
        markLink.isVisible = n > 0
        markLink.text = if (n > 1) "Mark Areas in Images…" else "Mark Areas in Image…"
    }

    private val MARK_LINE = Regex("""^- (.+), area x=(\d+) y=(\d+) w=(\d+) h=(\d+) px(?:: (.*))?$""")
    private val MARK_HEAD = "Marked areas (pixels of the original image):"

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
        val dialog = MarkupDialog(project, paths, 0, initial, true)
        if (!dialog.showAndGet()) return
        val marks = dialog.allMarks.toSortedMap().flatMap { (i, list) ->
            list.map { m -> "- ${File(paths[i]).name}, area x=${m.x} y=${m.y} w=${m.w} h=${m.h} px" + (if (m.note.isNotBlank()) ": ${m.note.trim()}" else "") }
        }
        // Alte Blöcke raus, ein neuer ans Ende.
        val kept = lines.filterNot { it.trim() == MARK_HEAD || MARK_LINE.matches(it.trim()) }.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trimEnd()
        input.text = if (marks.isEmpty()) kept else kept + (if (kept.isEmpty()) "" else "\n\n") + MARK_HEAD + "\n" + marks.joinToString("\n") + "\n"
        input.requestFocusInWindow()
    }
    private val send = JButton("Send").apply {
        putClientProperty("JButton.buttonType", "default")
        addActionListener { val id = sessionId; if (resumable && id != null) SessionEditors.resume(project, file, id) else send() }
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
                add(JPanel(BorderLayout()).apply { isOpaque = false; add(send, BorderLayout.SOUTH) }, BorderLayout.EAST)
            }, BorderLayout.CENTER)
            add(JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
                isOpaque = false
                add(hint, BorderLayout.CENTER)
                add(markLink, BorderLayout.EAST)
            }, BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
    }

    private val timer = Timer(700) { refresh() }.apply { start() }

    init { refresh() }

    private val sessionId get() = file.currentSession(project)
    private val terminalOpen get() = file.tab?.let { SessionTerminals.isOpen(project, it) } == true
    /** Vorschläge für Slash-Befehle - nur, wenn sie die Session erreichen (Terminal). */
    private val slash: SlashCompletion = SlashCompletion(project, input) { terminalOpen }

    private fun send() {
        val text = input.text.trim().takeIf { it.isNotEmpty() } ?: return
        val tab = file.tab
        val ok = when {
            tab != null && terminalOpen -> SessionTerminals.send(project, tab, text)
            else -> sessionId?.let { sessions.reply(it, text) } == true
        }
        if (ok) {
            if (pending.isEmpty()) pendingBase = transcript?.entries?.count { it.kind == TranscriptEntry.Kind.USER } ?: 0
            pending += text; input.text = ""; renderTranscript(force = true)
        }
        else hint.text = if (text.startsWith("/")) "Slash commands only reach sessions started here (New Claude Session)." else "Claude is busy - send when it is done, or type in the terminal."
    }

    /** Arbeitsordner der Session für relative Pfade, sonst der Projektordner. */
    private fun baseDir(): String? = sessionId?.let { sessions.infos[it]?.cwd } ?: project.basePath

    private fun scrollToEnd() = SwingUtilities.invokeLater { scroll.verticalScrollBar.let { it.value = it.maximum } }

    private fun refresh() {
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
        updateState(id)
        updateLimits(id)
    }

    private fun updateTabTitle() = runCatching {
        com.intellij.openapi.fileEditor.ex.FileEditorManagerEx.getInstanceEx(project).updateFilePresentation(file)
    }

    private fun renderTranscript(force: Boolean) {
        val t = transcript ?: return
        val bar = scroll.verticalScrollBar
        val atEnd = force || bar.value + bar.visibleAmount >= bar.maximum - JBUI.scale(40)
        val shown = t.entries.takeLast(MAX_ENTRIES)
        val html = StringBuilder("<html><body>")
        if (t.entries.size > shown.size) html.append("<p class=\"note\">${t.entries.size - shown.size} earlier entries not shown.</p>")
        if (shown.isEmpty()) html.append("<p class=\"note\">No messages yet.</p>")
        for (e in shown) when (e.kind) {
            TranscriptEntry.Kind.USER -> html.append("<div class=\"user\">")
                .append(StringUtil.escapeXmlEntities(e.text).replace("\n", "<br>")).append("</div>")
            TranscriptEntry.Kind.ASSISTANT -> html.append(SessionLinks.linkify(Md.document(e.text), baseDir()))
            TranscriptEntry.Kind.TOOL -> html.append("<div class=\"tool\">⚙ ").append(StringUtil.escapeXmlEntities(e.text)).append("</div>")
            TranscriptEntry.Kind.NOTE -> html.append("<pre class=\"note\">").append(StringUtil.escapeXmlEntities(e.text)).append("</pre>")
        }
        // Abgeschickt, aber noch nicht im Protokoll (Claude arbeitet noch und hat ihn nur vorgemerkt): grau zeigen.
        // Claude Code speichert Prompts teils verändert (Bildpfade als [Image #1], eingefügter Text gekürzt) - der Text passt
        // dann nie. Deshalb auch zählen: Jeder neue Prompt im Protokoll seit dem Absenden löst den ältesten vorgemerkten ab.
        val users = t.entries.count { it.kind == TranscriptEntry.Kind.USER }
        if (users > pendingBase && pending.isNotEmpty()) { repeat(minOf(users - pendingBase, pending.size)) { pending.removeAt(0) }; pendingBase = users }
        // Swing-HTML kennt nur eine Klasse je Element - grau per innerem span.
        for (p in pending) html.append("<div class=\"user\"><span class=\"pending\">").append(StringUtil.escapeXmlEntities(p).replace("\n", "<br>"))
            .append("<br><i>Queued - Claude reads it after its current step and keeps working on the task with it.</i></span></div>")
        html.append("</body></html>")
        val value = bar.value
        pane.text = html.toString()
        if (atEnd) scrollToEnd() else SwingUtilities.invokeLater { bar.value = value }
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
        val key = listOf(id, waiting?.at, waiting?.listening, working?.since, terminal, canSend, wasSent).joinToString("|")
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
        status.text = text
        status.foreground = color
        val isBusy = working != null || wasSent
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
                if (tab != null && terminal && waiting.notificationType == "permission_prompt") {
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

        input.isEnabled = canSend
        send.isEnabled = canSend || resumable
        send.text = if (!canSend && resumable) "Resume" else "Send"
        input.emptyText.text = when {
            terminal -> "Prompt or /command for Claude"
            canSend -> "Next prompt for Claude"
            else -> "Claude is not waiting for a prompt"
        }
        hint.text = when {
            terminal -> "Enter sends, Shift+Enter adds a line. Goes to the terminal as if typed, slash commands included."
            canSend -> "Enter sends, Shift+Enter adds a line. Session started outside Handoff: no slash commands."
            resumable -> "Not running in the IDE terminal. Resume continues it there (claude --resume); only if it is not still open elsewhere."
            id == null && tab == null -> "Session not found."
            else -> "Send when Claude is done, or type in the terminal."
        }
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
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater({ SessionEditors.save(project) }, project.disposed)
    }

    companion object {
        /** Mehr Einträge machen den HTML-Viewer träge; der ganze Verlauf steht im Terminal. */
        private const val MAX_ENTRIES = 300
    }
}
