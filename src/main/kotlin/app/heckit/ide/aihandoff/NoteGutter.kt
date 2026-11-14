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
 * Im Diff immer; in normalen Editoren nur, solange eine Prüfung wartet oder schon Notizen da sind - sonst stünde
 * das "+" dauerhaft in jeder Datei.
 */
object NoteGutter {
    private val KEY = Key.create<Hover>("handoff.noteGutter")

    private fun file(editor: Editor): VirtualFile? =
        FileDocumentManager.getInstance().getFile(editor.document)?.takeIf { it.isInLocalFileSystem }

    private fun reviewing(project: com.intellij.openapi.project.Project): Boolean =
        project.getServiceIfCreated(HandoffService::class.java)?.proposals?.any { it.review && it.isPending } == true ||
            project.getServiceIfCreated(NotesService::class.java)?.hasNotes == true

    fun install(editor: Editor) {
        val project = editor.project ?: return
        if (project.isDisposed || editor !is EditorEx) return
        if (editor.editorKind != EditorKind.DIFF && editor.editorKind != EditorKind.MAIN_EDITOR) return
        if (editor.getUserData(KEY) != null || file(editor) == null) return
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
            if (newLine < 0 || newLine >= document.lineCount || NotesService.getInstance(project).at(file, newLine) != null) return
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
                NotePopup.show(project, editor, line, null)
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
