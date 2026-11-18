package app.heckit.ide.aihandoff

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

/** Protokoll (`.jsonl`) einer Session: aus dem Reiter oder, solange die Hook-Daten da sind, aus der laufenden Session. */
object SessionLog {
    fun path(project: Project, f: SessionFile): String? {
        val id = f.currentSession(project)
        return id?.let { WaitingSessions.getInstance(project).infos[it]?.transcript } ?: f.transcript
    }

    /**
     * Protokoll einer Claude-Code-Session: `.jsonl` unter `~/.claude/projects` oder, woanders (kopiert, Testdaten), mit
     * `sessionId` in der ersten Zeile.
     */
    fun isLog(file: VirtualFile): Boolean =
        file.isInLocalFileSystem && file.extension == "jsonl" &&
            (file.path.startsWith(File(System.getProperty("user.home"), ".claude/projects").path + "/") || sessionId(file) != null)

    /** `sessionId` aus der ersten Zeile; der Dateiname ist sie nur im Original-Ordner sicher. */
    fun sessionId(file: VirtualFile): String? = runCatching {
        val first = File(file.path).bufferedReader().use { r -> CharArray(16384).let { b -> String(b, 0, r.read(b).coerceAtLeast(0)) } }.lineSequence().first()
        com.google.gson.JsonParser.parseString(first).asJsonObject.get("sessionId")?.asString
    }.getOrNull()
}

/** Kontextmenü des Session-Reiters: das Protokoll der Session als Datei öffnen. */
class OpenSessionLogAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    private fun log(e: AnActionEvent): String? {
        val project = e.project ?: return null
        val f = e.getData(CommonDataKeys.VIRTUAL_FILE) as? SessionFile ?: return null
        return SessionLog.path(project, f)?.takeIf { File(it).isFile }
    }

    override fun update(e: AnActionEvent) {
        val isSession = e.getData(CommonDataKeys.VIRTUAL_FILE) is SessionFile
        e.presentation.isVisible = isSession
        e.presentation.isEnabled = isSession && log(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(log(e) ?: return) ?: return
        OpenFileDescriptor(project, file).navigate(true)
    }
}

/**
 * `.jsonl` unter `~/.claude/projects` zeigt standardmäßig die Session-Ansicht (Umschalter "Claude Session | Text" unten
 * im Editor). Eine Eingabe dort setzt die Session per `claude --resume` in einem neuen Reiter fort.
 */
class SessionLogEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = SessionLog.isLog(file)
    override fun acceptRequiresReadAction() = false
    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        val session = SessionFile(null, SessionLog.sessionId(file) ?: file.nameWithoutExtension, file.path, restoredCwd = SessionEditors.startDir(file.path)).apply { logOnly = true }
        return SessionLogEditor(createSessionEditor(project, session), file)
    }
    override fun getEditorTypeId() = "handoff-session-log"
    override fun getPolicy() = FileEditorPolicy.PLACE_BEFORE_DEFAULT_EDITOR
}

/** Die Session-Ansicht, aber mit der `.jsonl` als Datei - so gehören Reiter, Pfad und Finder-Aktionen zur echten Datei. */
private class SessionLogEditor(private val delegate: FileEditor, private val log: VirtualFile) : FileEditor by delegate {
    override fun getFile(): VirtualFile = log
}

/** Kontextmenü des Session-Reiters (und einer `.jsonl`): die Session als Markdown speichern. */
class ExportSessionMarkdownAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    private fun log(e: AnActionEvent): String? {
        val project = e.project ?: return null
        return when (val f = e.getData(CommonDataKeys.VIRTUAL_FILE)) {
            is SessionFile -> SessionLog.path(project, f)
            null -> null
            else -> f.path.takeIf { SessionLog.isLog(f) }
        }?.takeIf { File(it).isFile }
    }

    override fun update(e: AnActionEvent) {
        val f = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val relevant = f is SessionFile || (f != null && f.extension == "jsonl")
        e.presentation.isVisible = relevant
        e.presentation.isEnabled = relevant && log(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val path = log(e) ?: return
        val t = Transcript(path)
        while (t.update()) { /* Protokolle über 32 MB kommen in mehreren Stücken */ }
        val name = (t.title ?: File(path).nameWithoutExtension).replace(Regex("[^\\w .-]+"), "").trim().ifEmpty { "session" }
        val descriptor = com.intellij.openapi.fileChooser.FileSaverDescriptor("Export Session as Markdown", "Save the conversation as a Markdown file", "md")
        val base = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        val target = com.intellij.openapi.fileChooser.FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project).save(base, "$name.md") ?: return
        target.file.writeText(SessionMarkdown.of(t, File(path).nameWithoutExtension))
        target.virtualFile?.let { OpenFileDescriptor(project, it).navigate(true) }
    }
}

object SessionMarkdown {
    fun of(t: Transcript, id: String): String = buildString {
        append("# ").append(t.title ?: "Claude Code session").append("\n\n")
        append("Session `").append(id).append("`\n")
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm")
        for (e in t.entries) {
            when (e.kind) {
                TranscriptEntry.Kind.USER -> {
                    append("\n## You").append(e.time?.let { " (" + fmt.format(java.util.Date(it)) + ")" } ?: "").append("\n\n")
                    append(e.text.lines().joinToString("\n") { "> $it" }).append("\n\n")
                }
                TranscriptEntry.Kind.ASSISTANT -> append(e.text).append("\n\n")
                TranscriptEntry.Kind.TOOL -> append("- `").append(e.text.replace("`", "'")).append("`")
                    .append(e.error?.let { " - **failed:** $it" } ?: "").append("\n")
                TranscriptEntry.Kind.NOTE, TranscriptEntry.Kind.INFO -> append("\n_").append(e.text.replace("\n", " ")).append("_\n\n")
                TranscriptEntry.Kind.DIVIDER -> append("\n---\n\n_").append(e.text).append("_\n\n")
                TranscriptEntry.Kind.FILE -> append("- ").append(e.text).append(" [").append(File(e.path.orEmpty()).name).append("](")
                    .append(e.path).append(")\n")
                TranscriptEntry.Kind.SUMMARY -> append("\n<details><summary>Summary of the earlier conversation</summary>\n\n").append(e.text).append("\n\n</details>\n\n")
                TranscriptEntry.Kind.IMAGE -> append("\n_[image]_\n\n")
            }
        }
    }
}
