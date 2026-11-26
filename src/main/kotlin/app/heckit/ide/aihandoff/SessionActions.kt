package app.heckit.ide.aihandoff

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DoubleClickListener
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.event.MouseEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.ListSelectionModel

/** "New Claude Session in Folder…": wie "New Claude Session", aber in einem frei gewählten Ordner. */
class NewSessionInFolderAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabled = e.project != null }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val start = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Start Claude Session In")
        val dir = FileChooser.chooseFile(descriptor, project, start) ?: return
        SessionEditors.newSession(project, dir.path.takeIf { it.trimEnd('/') != project.basePath?.trimEnd('/') })
    }
}

/** Knopf im Start-Dialog: das Welcome-Projekt hat keine eigenen Sessions - deshalb die neuesten aus allen Ordnern. */
class WelcomeResumeSessionAction : ResumeSessionAction() {
    override fun update(e: AnActionEvent) { super.update(e); e.presentation.icon = com.intellij.icons.AllIcons.Vcs.History }
    override fun dirs(project: Project): List<String>? = null
}

/**
 * Knopf im Start-Dialog (siehe handoff-welcome.xml): Das Fenster dahinter ist ein leeres Welcome-Projekt, sein Ordner taugt
 * nicht als Arbeitsordner - deshalb immer einen Ordner wählen lassen (Start im Home-Ordner).
 */
class WelcomeNewSessionAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
        e.presentation.icon = HandoffIcons.editor()
    }
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val start = LocalFileSystem.getInstance().findFileByPath(System.getProperty("user.home"))
        val descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor().withTitle("Start Claude Session In")
        val dir = FileChooser.chooseFile(descriptor, project, start) ?: return
        SessionEditors.newSession(project, dir.path)
    }
}

/** "Resume Claude Session…": frühere Sessions des Projekts (und der Ordner, in denen es Sessions gestartet hat) zur Auswahl. */
open class ResumeSessionAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun update(e: AnActionEvent) { e.presentation.isEnabled = e.project != null }
    /** Ordner, deren Sessions zur Wahl stehen; null = alle. */
    protected open fun dirs(project: Project): List<String>? = WaitingSessions.getInstance(project).dirs()
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val dirs = dirs(project)
        val found = ProgressManager.getInstance().runProcessWithProgressSynchronously<List<PastSession>, RuntimeException>(
            { PastSessions.list(dirs) }, "Reading Claude Sessions", true, project) ?: return
        if (found.isEmpty()) {
            Messages.showInfoMessage(project, if (dirs == null) "No Claude Code sessions found." else "No Claude Code sessions found for this project.", "Resume Claude Session")
            return
        }
        val dialog = ResumeDialog(project, found)
        if (dialog.showAndGet()) dialog.selected?.let { SessionEditors.resumeOld(project, it.id, it.cwd) }
    }
}

class PastSession(val id: String, val cwd: String, val at: Long, val title: String?, val firstPrompt: String?, val prompts: Int)

/** Protokolle früherer Sessions unter `~/.claude/projects/<Ordner mit - statt Sonderzeichen>/<id>.jsonl`. */
object PastSessions {
    private const val MAX = 80

    private fun encode(path: String) = path.trimEnd('/').replace(Regex("[^A-Za-z0-9]"), "-")

    fun list(dirs: List<String>?): List<PastSession> {
        val root = File(System.getProperty("user.home"), ".claude/projects")
        val prefixes = dirs?.map(::encode)
        // Auch Unterordner (eigener Protokollordner je cwd); ob die cwd wirklich darunter liegt, prüft das Protokoll selbst.
        val folders = root.listFiles().orEmpty().filter { f -> f.isDirectory && (prefixes == null || prefixes.any { f.name == it || f.name.startsWith("$it-") }) }
        val files = folders.flatMap { it.listFiles().orEmpty().filter { f -> f.isFile && f.name.endsWith(".jsonl") } }
            .sortedByDescending { it.lastModified() }.take(MAX)
        return files.mapNotNull { f ->
            ProgressManager.checkCanceled()
            runCatching { read(f) }.getOrNull()?.takeIf { s -> s.prompts > 0 && (dirs == null || dirs.any { s.cwd == it || s.cwd.startsWith("$it/") }) }
        }
    }

    private fun read(f: File): PastSession? {
        var cwd: String? = null
        var title: String? = null
        var first: String? = null
        var prompts = 0
        var custom = false
        f.bufferedReader().useLines { lines ->
            for (line in lines) {
                // Nur Zeilen parsen, die etwas Gesuchtes enthalten - Protokolle werden schnell mehrere MB groß.
                val isTitle = line.contains("\"ai-title\"") || line.contains("\"custom-title\"")
                val isUser = line.contains("\"type\":\"user\"")
                if (!isTitle && !isUser && cwd != null) continue
                val o = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: continue
                if (cwd == null) cwd = o.str("cwd")
                if (isTitle) o.str("customTitle")?.let { title = it; custom = true } ?: o.str("aiTitle")?.takeIf { !custom }?.let { title = it }
                if (isUser && o.str("type") == "user" && o.get("isMeta")?.asBoolean != true && o.get("isSidechain")?.asBoolean != true) {
                    val text = promptText(o) ?: continue
                    prompts++
                    if (first == null) first = text
                }
            }
        }
        return PastSession(f.nameWithoutExtension, cwd?.trimEnd('/') ?: return null, f.lastModified(), title, first, prompts)
    }

    /** Getippter Prompt; Werkzeug-Ergebnisse und Befehls-Markup (`<command-name>` …) zählen nicht. */
    private fun promptText(o: JsonObject): String? {
        val content = o.getAsJsonObject("message")?.get("content") ?: return null
        val text = when {
            content.isJsonPrimitive -> content.asString
            content.isJsonArray -> content.asJsonArray.mapNotNull { c ->
                c.takeIf { it.isJsonObject }?.asJsonObject?.takeIf { it.str("type") == "text" }?.str("text")
            }.joinToString("\n")
            else -> return null
        }.let(::unwrapPasted).trim()
        return text.takeIf { it.isNotEmpty() && !it.startsWith("<") }
    }

    private fun JsonObject.str(k: String) = get(k)?.takeIf { it.isJsonPrimitive }?.asString
}

private class ResumeDialog(private val project: Project, private val sessions: List<PastSession>) : DialogWrapper(project) {
    private val time = SimpleDateFormat("dd.MM. HH:mm")
    private val model = com.intellij.ui.CollectionListModel(sessions)
    /** Filter wie in den Dialogen der IDE: tippen grenzt ein, Pfeiltasten und Enter wirken weiter auf die Liste. */
    private val filter = com.intellij.ui.SearchTextField(false).apply {
        textEditor.emptyText.text = "Filter by title, prompt or folder"
        addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) = applyFilter()
        })
    }
    private val list = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        selectedIndex = 0
        cellRenderer = object : ColoredListCellRenderer<PastSession>() {
            override fun customizeCellRenderer(list: JList<out PastSession>, s: PastSession, index: Int, selected: Boolean, focus: Boolean) {
                val label = (s.title ?: s.firstPrompt ?: s.id).replace('\n', ' ')
                append(StringUtil.shortenTextWithEllipsis(label, 80, 0))
                append("   " + time.format(Date(s.at)), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                append("  ·  ${s.prompts} prompt" + (if (s.prompts == 1) "" else "s"), SimpleTextAttributes.GRAYED_ATTRIBUTES)
                val base = project.basePath?.trimEnd('/')
                if (s.cwd != base) append("  ·  " + if (base != null && s.cwd.startsWith("$base/")) s.cwd.removePrefix("$base/") else SessionEditors.shortPath(s.cwd),
                    SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                // Titel und erster Prompt unterscheiden sich oft - der Prompt zeigt, worum es ging.
                toolTipText = s.firstPrompt?.let { StringUtil.escapeXmlEntities(StringUtil.shortenTextWithEllipsis(it, 400, 0)).replace("\n", "<br>") }
                    ?.let { "<html>$it</html>" }
            }
        }
    }
    val selected: PastSession? get() = list.selectedValue

    private fun applyFilter() {
        val words = filter.text.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        model.replaceAll(sessions.filter { s ->
            val text = listOfNotNull(s.title, s.firstPrompt, s.cwd).joinToString(" ").lowercase()
            words.all { it in text }
        })
        if (model.size > 0) list.selectedIndex = 0
        isOKActionEnabled = model.size > 0
    }

    init {
        title = "Resume Claude Session"
        setOKButtonText("Resume")
        object : DoubleClickListener() {
            override fun onDoubleClick(event: MouseEvent): Boolean { if (selected != null) doOKAction(); return true }
        }.installOn(list)
        // Pfeiltasten im Filterfeld bewegen die Auswahl in der Liste.
        filter.textEditor.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) {
                if (e.keyCode != java.awt.event.KeyEvent.VK_UP && e.keyCode != java.awt.event.KeyEvent.VK_DOWN || model.size == 0) return
                val i = (list.selectedIndex + if (e.keyCode == java.awt.event.KeyEvent.VK_UP) -1 else 1).coerceIn(0, model.size - 1)
                list.selectedIndex = i; list.ensureIndexIsVisible(i); e.consume()
            }
        })
        init()
    }

    override fun createCenterPanel(): JComponent = javax.swing.JPanel(java.awt.BorderLayout(0, JBUI.scale(6))).apply {
        add(filter, java.awt.BorderLayout.NORTH)
        add(JBScrollPane(list).apply { preferredSize = JBUI.size(720, 420) }, java.awt.BorderLayout.CENTER)
    }
    override fun getPreferredFocusedComponent(): JComponent = filter.textEditor
}
