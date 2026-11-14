package app.heckit.ide.aihandoff

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorLinePainter
import com.intellij.openapi.editor.LineExtensionInfo
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ex.EditorGutterComponentEx
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.event.KeyEvent
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.KeyStroke

/**
 * Notiz des Nutzers an einer Zeile (oder einem Zeilenbereich) für Claude. Die Stelle hängt als Highlighter am Dokument
 * und wandert beim Bearbeiten mit; dadurch erscheint das Randsymbol in jedem Editor der Datei, auch im Diff.
 */
class LineNote(val file: VirtualFile, val highlighter: RangeHighlighter, @Volatile var text: String) {
    val isValid get() = highlighter.isValid && file.isValid
    /** Erste und letzte Zeile, ab 0. */
    val lines: IntRange
        get() {
            val d = highlighter.document
            val start = d.getLineNumber(highlighter.startOffset.coerceIn(0, d.textLength))
            return start..maxOf(start, d.getLineNumber(highlighter.endOffset.coerceIn(0, d.textLength)))
        }
}

/** Offene Notizen eines Projekts. Nur im Speicher - sie sind für die laufende Übergabe gedacht, nicht als Ablage. */
@Service(Service.Level.PROJECT)
class NotesService(private val project: Project) {
    private val notes = CopyOnWriteArrayList<LineNote>()

    /** Gültige Notizen in Datei- und Zeilenfolge; Notizen, deren Stelle gelöscht wurde, fallen weg. */
    fun all(): List<LineNote> {
        notes.filter { !it.isValid }.forEach { remove(it) }
        return notes.sortedWith(compareBy({ it.file.path }, { it.lines.first }))
    }

    val hasNotes get() = notes.isNotEmpty()

    fun at(file: VirtualFile, line: Int): LineNote? = notes.firstOrNull { it.isValid && it.file == file && line in it.lines }

    /** Notiz, deren erste Zeile [line] ist (dort steht der Text am Zeilenende). */
    fun startingAt(file: VirtualFile, line: Int): LineNote? = notes.firstOrNull { it.isValid && it.file == file && it.lines.first == line }

    fun add(document: Document, file: VirtualFile, startLine: Int, endLine: Int, text: String): LineNote {
        val h = DocumentMarkupModel.forDocument(document, project, true).addRangeHighlighter(
            null, document.getLineStartOffset(startLine), document.getLineEndOffset(endLine),
            HighlighterLayer.LAST, HighlighterTargetArea.LINES_IN_RANGE,
        )
        val note = LineNote(file, h, text)
        h.gutterIconRenderer = NoteRenderer(project, note)
        h.setErrorStripeMarkColor(COLOR)
        h.errorStripeTooltip = text
        notes += note
        changed()
        return note
    }

    fun update(note: LineNote, text: String) {
        note.text = text
        if (note.highlighter.isValid) note.highlighter.errorStripeTooltip = text
        changed()
    }

    fun remove(note: LineNote) {
        notes -= note
        if (note.highlighter.isValid) note.highlighter.dispose()
        changed()
    }

    fun clear() {
        val old = notes.toList()
        notes.clear()
        old.forEach { if (it.highlighter.isValid) it.highlighter.dispose() }
        changed()
    }

    /** Text für Claude: je Notiz Pfad (relativ zum Projekt), Zeilen ab 1, Notiz und die Zeile, an der sie hängt. */
    fun describe(list: List<LineNote> = all()): String {
        val base = project.basePath.orEmpty().trimEnd('/') + "/"
        return list.joinToString("\n") { n ->
            val l = n.lines
            val where = n.file.path.removePrefix(base) + ":" + (l.first + 1) + (if (l.last > l.first) "-${l.last + 1}" else "")
            val d = n.highlighter.document
            val code = d.getText(com.intellij.openapi.util.TextRange(d.getLineStartOffset(l.first), d.getLineEndOffset(l.first))).trim().take(120)
            "- $where: ${n.text.trim().replace("\n", "\n  ")}\n  > $code"
        }
    }

    /** Notizen an Claude übergeben: Text liefern und die Notizen entfernen. */
    fun take(): String? = all().takeIf { it.isNotEmpty() }?.let { list -> describe(list).also { clear() } }

    fun navigate(note: LineNote) {
        if (note.isValid) OpenFileDescriptor(project, note.file, note.lines.first, 0).navigate(true)
    }

    private fun changed() {
        HandoffService.getInstance(project).fireChanged()
        // Text am Zeilenende neu zeichnen.
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater({
            com.intellij.openapi.editor.EditorFactory.getInstance().allEditors.forEach { if (it.project == project) it.contentComponent.repaint() }
        }, project.disposed)
    }

    companion object {
        val COLOR = JBColor(0x5A5A8C, 0x8C8CC0)
        fun getInstance(project: Project): NotesService = project.getService(NotesService::class.java)
    }
}

private class NoteRenderer(private val project: Project, private val note: LineNote) : GutterIconRenderer() {
    override fun getIcon(): Icon = AllIcons.General.Balloon
    override fun getTooltipText() = "Note for Claude: " + note.text
    override fun isNavigateAction() = true
    override fun getAlignment() = Alignment.RIGHT
    override fun getClickAction(): AnAction = object : DumbAwareAction() {
        override fun actionPerformed(e: AnActionEvent) {
            val editor = e.getData(CommonDataKeys.EDITOR) ?: return
            NotePopup.show(project, editor, note.lines.first, note)
        }
    }
    override fun equals(other: Any?) = other is NoteRenderer && other.note === note
    override fun hashCode() = System.identityHashCode(note)
}

/** Kurzer Notiztext grau am Ende der ersten Zeile, wie die Inline-Werte des Debuggers. */
class NoteLinePainter : EditorLinePainter() {
    override fun getLineExtensions(project: Project, file: VirtualFile, lineNumber: Int): Collection<LineExtensionInfo>? {
        val note = NotesService.getInstance(project).startingAt(file, lineNumber) ?: return null
        val text = note.text.lineSequence().first().let { if (it.length > 90) it.take(90) + "…" else it }
        return listOf(LineExtensionInfo("    ◂ $text", TextAttributes(NotesService.COLOR, null, null, null, Font.ITALIC)))
    }
}

/** "Add Note for Claude…" im Kontextmenü von Editor, Diff und Zeilenrand. */
class AddNoteAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    private fun file(editor: Editor?): VirtualFile? =
        editor?.let { FileDocumentManager.getInstance().getFile(it.document) }?.takeIf { it.isInLocalFileSystem }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = file(editor)
        e.presentation.isEnabledAndVisible = project != null && file != null
        if (project != null && editor != null && file != null) {
            val line = e.getData(EditorGutterComponentEx.LOGICAL_LINE_AT_CURSOR) ?: editor.caretModel.logicalPosition.line
            e.presentation.text = if (NotesService.getInstance(project).at(file, line) != null) "Edit Note for Claude…" else "Add Note for Claude…"
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = file(editor) ?: return
        val gutterLine = e.getData(EditorGutterComponentEx.LOGICAL_LINE_AT_CURSOR)
        val selection = editor.selectionModel
        val (start, end) = when {
            gutterLine != null -> gutterLine to gutterLine
            selection.hasSelection() -> {
                val d = editor.document
                // Endet die Auswahl am Zeilenanfang, gehört diese Zeile nicht mehr dazu.
                val last = d.getLineNumber(maxOf(selection.selectionStart, selection.selectionEnd - 1))
                d.getLineNumber(selection.selectionStart) to last
            }
            else -> editor.caretModel.logicalPosition.line.let { it to it }
        }
        if (start >= editor.document.lineCount) return
        NotePopup.show(project, editor, start, NotesService.getInstance(project).at(file, start), end)
    }
}

object NotePopup {
    fun show(project: Project, editor: Editor, line: Int, existing: LineNote?, endLine: Int = line) {
        val service = NotesService.getInstance(project)
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val area = JBTextArea(existing?.text.orEmpty()).apply {
            lineWrap = true; wrapStyleWord = true; border = JBUI.Borders.empty(6)
            emptyText.text = "What should Claude change here?"
            inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, java.awt.event.InputEvent.SHIFT_DOWN_MASK), javax.swing.text.DefaultEditorKit.insertBreakAction)
        }
        val panel = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            border = JBUI.Borders.empty(6)
            add(JBScrollPane(area).apply { preferredSize = Dimension(JBUI.scale(380), JBUI.scale(90)) }, BorderLayout.CENTER)
        }
        val popup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, area)
            .setTitle(if (existing == null) "Note for Claude" else "Edit Note for Claude")
            .setRequestFocus(true).setFocusable(true).setMovable(true).setResizable(true)
            .setCancelOnClickOutside(false).setCancelKeyEnabled(true)
            .createPopup()
        fun save() {
            val text = area.text.trim()
            when {
                text.isEmpty() -> existing?.let { service.remove(it) }
                existing != null -> service.update(existing, text)
                else -> service.add(editor.document, file, line, endLine.coerceAtMost(editor.document.lineCount - 1), text)
            }
            popup.closeOk(null)
        }
        panel.add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JBLabel("Enter saves · Shift+Enter new line · Esc cancels").apply {
                foreground = UIUtil.getContextHelpForeground(); font = JBUI.Fonts.smallFont()
            }, BorderLayout.WEST)
            if (existing != null) add(ActionLink("Delete") { service.remove(existing); popup.cancel() }, BorderLayout.EAST)
        }, BorderLayout.SOUTH)
        object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) = save()
        }.registerCustomShortcutSet(CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)), area)
        val at = editor.logicalPositionToXY(LogicalPosition(line + 1, 0))
        popup.show(RelativePoint(editor.contentComponent, at))
    }
}
