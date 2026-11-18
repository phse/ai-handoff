package app.heckit.ide.aihandoff

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import java.awt.Point
import javax.swing.Icon

/**
 * "+" im Zeilenrand beim Überfahren einer Zeile: Ein Klick legt eine Notiz für Claude an (wie bei Code-Reviews).
 * Technisch ein gewöhnliches Randsymbol an der Zeile unter der Maus, an der Stelle, wo danach die Sprechblase steht.
 * Im Diff immer; in normalen Editoren, solange das Notiz-Werkzeug in Handoff Tools… erlaubt ist.
 */
object NoteGutter {
    private val KEY = Key.create<Hover>("handoff.noteGutter")

    /**
     * Echtes Dokument hinter dem Editor. Der einseitige Diff (Unified, Standard bei geänderten Dateien) zeigt ein eigenes,
     * zusammengesetztes Dokument; das der aktuellen Fassung hängt als ORIGINAL_DOCUMENT daran (UnifiedDiffViewer) - ohne
     * das gab es das "+" nur bei neuen Dateien (2026-11-18).
     */
    private fun document(editor: Editor): com.intellij.openapi.editor.Document {
        editor.document.getUserData(com.intellij.openapi.command.undo.UndoManager.ORIGINAL_DOCUMENT)?.let { return it }
        // Nur gesetzt, wenn die aktuelle Fassung bearbeitbar ist - sonst über die Anfrage des Viewers (rechte Seite).
        val viewer = unified(editor) ?: return editor.document
        val request = viewer.request as? com.intellij.diff.requests.ContentDiffRequest ?: return editor.document
        return (request.contents.getOrNull(1) as? com.intellij.diff.contents.DocumentContent)?.document ?: editor.document
    }

    private fun unified(editor: Editor): com.intellij.diff.tools.fragmented.UnifiedDiffViewer? =
        if (editor.editorKind != EditorKind.DIFF) null
        else (com.intellij.ide.DataManager.getInstance().getDataContext(editor.component)
            .getData(com.intellij.diff.tools.util.DiffDataKeys.DIFF_VIEWER) as? com.intellij.diff.tools.fragmented.UnifiedDiffViewer)
            ?.takeIf { it.editor === editor }

    private fun file(editor: Editor): VirtualFile? =
        FileDocumentManager.getInstance().getFile(document(editor))?.takeIf { it.isInLocalFileSystem }

    /** Zeile im echten Dokument; -1, wenn die Zeile im einseitigen Diff nur in der alten Fassung steht (gelöscht). */
    fun realLine(editor: Editor, line: Int): Int {
        if (document(editor) === editor.document) return line
        val viewer = unified(editor) ?: return -1
        return viewer.transferLineFromOnesideStrict(com.intellij.diff.util.Side.RIGHT, line)
    }

    /**
     * "+" auch in normalen Editoren - immer, solange das Werkzeug für Notizen (handoff_get_notes) in **Handoff Tools…** erlaubt
     * ist (2026-11-18; vorher nur bei wartendem Review oder vorhandenen Notizen, das wirkte zufällig). Die Einstellung steht in
     * ~/.claude/settings.json - darum nur alle paar Sekunden gelesen.
     */
    private fun reviewing(project: com.intellij.openapi.project.Project): Boolean = notesAllowed()

    @Volatile private var allowedAt = 0L
    @Volatile private var allowed = true
    private fun notesAllowed(): Boolean {
        val now = System.currentTimeMillis()
        if (now - allowedAt > 5000) { allowedAt = now; allowed = runCatching { "handoff_get_notes" !in Permissions.denied() }.getOrDefault(true) }
        return allowed
    }

    fun install(editor: Editor) {
        val project = editor.project ?: return
        if (project.isDisposed || editor !is EditorEx) return
        if (editor.editorKind != EditorKind.DIFF && editor.editorKind != EditorKind.MAIN_EDITOR) return
        // Im Diff die Datei erst beim Überfahren prüfen: Beim Anlegen des Editors hängt das Dokument der aktuellen Fassung
        // im einseitigen Diff noch nicht am zusammengesetzten Dokument - geänderte Dateien bekamen so nie ein "+" (2026-11-18).
        if (editor.getUserData(KEY) != null || (editor.editorKind != EditorKind.DIFF && file(editor) == null)) return
        val hover = Hover(editor)
        editor.addEditorMouseMotionListener(hover, hover)
        editor.addEditorMouseListener(hover, hover)
        editor.putUserData(KEY, hover)
    }

    fun uninstall(editor: Editor) {
        val hover = editor.getUserData(KEY) ?: return
        editor.putUserData(KEY, null)
        Disposer.dispose(hover)
    }

    /** Für Editoren, die beim Laden des Plugins schon offen sind. */
    fun installAll() = EditorFactory.getInstance().allEditors.forEach { install(it) }

    fun uninstallAll() = EditorFactory.getInstance().allEditors.forEach { uninstall(it) }

    private class Hover(private val editor: EditorEx) : EditorMouseMotionListener, EditorMouseListener, Disposable {
        private var line = -1
        private var highlighter: RangeHighlighter? = null

        /** "+" weg (nach dem Klick) - sonst stünde es nach dem Speichern neben der Sprechblase, bis die Maus die Zeile verlässt. */
        fun hide() = show(-1)

        private fun show(newLine: Int) {
            if (newLine == line) return
            line = newLine
            highlighter?.let { if (it.isValid) it.dispose() }
            highlighter = null
            val project = editor.project ?: return
            val file = file(editor) ?: return
            val document = editor.document
            if (editor.editorKind != EditorKind.DIFF && !reviewing(project)) return
            // Kein "+" an Zeilen mit Notiz - dort steht die Sprechblase.
            if (newLine < 0 || newLine >= document.lineCount) return
            val real = realLine(editor, newLine)
            if (real < 0 || NotesService.getInstance(project).at(file, real) != null) return
            highlighter = editor.markupModel.addRangeHighlighter(
                null, document.getLineStartOffset(newLine), document.getLineStartOffset(newLine), HighlighterLayer.LAST, HighlighterTargetArea.LINES_IN_RANGE,
            ).apply { gutterIconRenderer = Plus(editor, newLine) }
        }

        override fun mouseMoved(e: EditorMouseEvent) {
            val y = e.mouseEvent.y
            show(if (y >= editor.contentComponent.height) -1 else editor.xyToLogicalPosition(Point(0, y)).line)
        }

        override fun mouseExited(e: EditorMouseEvent) {
            // Vom Text in den Zeilenrand (oder zurück) ist kein Verlassen.
            val to = e.mouseEvent.locationOnScreen
            val inside = listOf(editor.contentComponent, editor.gutterComponentEx).any { c ->
                c.isShowing && java.awt.Rectangle(c.locationOnScreen, c.size).contains(to)
            }
            if (!inside) show(-1)
        }

        override fun dispose() = show(-1)
    }

    private class Plus(private val editor: EditorEx, private val line: Int) : GutterIconRenderer() {
        override fun getIcon(): Icon = AllIcons.General.InlineAdd
        override fun getTooltipText() = "Add note for Claude"
        override fun getAlignment() = Alignment.RIGHT
        override fun isNavigateAction() = true
        override fun getClickAction(): AnAction = object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) {
                val project = editor.project ?: return
                val real = realLine(editor, line).takeIf { it >= 0 } ?: return
                val doc = document(editor)
                editor.getUserData(KEY)?.hide()
                NotePopup.show(project, editor, line, null, target = if (doc === editor.document) null else doc to real)
            }
        }
        override fun equals(other: Any?) = other is Plus && other.editor === editor && other.line == line
        override fun hashCode() = line
    }
}

/** Hängt das "+" an neu geöffnete Editoren. */
class NoteGutterInstaller : EditorFactoryListener {
    override fun editorCreated(event: EditorFactoryEvent) = NoteGutter.install(event.editor)
    override fun editorReleased(event: EditorFactoryEvent) = NoteGutter.uninstall(event.editor)
}
