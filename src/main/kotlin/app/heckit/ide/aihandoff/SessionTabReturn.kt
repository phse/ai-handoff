package app.heckit.ide.aihandoff

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile

/**
 * Datei aus einem Session-Reiter geöffnet (Link in Claudes Antwort) und wieder geschlossen: zurück zum Session-Reiter.
 * Die IDE wählt beim Schließen den zuletzt benutzten Reiter aus ihrem Verlauf, und dort fehlen Session-Reiter - Dateien ohne
 * echten Pfad (LightVirtualFile) nimmt `EditorHistoryManager.isIncludedInHistory` nicht auf. So landete man in irgendeiner
 * anderen Datei, die Session-Reiter kamen erst ganz zuletzt (2026-11-19).
 */
class SessionTabReturn(private val project: Project) : FileEditorManagerListener, FileEditorManagerListener.Before {
    private class State {
        /** Datei → Reiter, der vor ihr ausgewählt war. */
        val cameFrom = HashMap<VirtualFile, VirtualFile>()
        /** Beim Schließen ausgewählt (beforeFileClosed) - nur dann geht es zurück. */
        var closingSelected: VirtualFile? = null
    }

    private val state: State get() = project.getUserData(KEY) ?: State().also { project.putUserData(KEY, it) }

    override fun selectionChanged(event: FileEditorManagerEvent) {
        val new = event.newFile ?: return
        val old = event.oldFile ?: return
        if (new != old) state.cameFrom[new] = old
    }

    override fun beforeFileClosed(source: FileEditorManager, file: VirtualFile) {
        state.closingSelected = file.takeIf { source.selectedFiles.contains(it) }
    }

    override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
        val s = state
        val back = s.cameFrom.remove(file)
        s.cameFrom.values.removeIf { it == file }
        val wasSelected = s.closingSelected == file
        s.closingSelected = null
        if (!wasSelected || back !is SessionFile || !source.isFileOpen(back) || back in source.selectedFiles) return
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater({
            if (!project.isDisposed && source.isFileOpen(back)) com.intellij.openapi.fileEditor.OpenFileDescriptor(project, back).navigate(true)
        }, project.disposed)
    }

    private companion object {
        val KEY = Key.create<State>("handoff.sessionTabReturn")
    }
}
