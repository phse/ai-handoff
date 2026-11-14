package app.heckit.ide.aihandoff

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.beans.PropertyChangeListener
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.event.HyperlinkEvent

/**
 * Eigener Viewer für Markdown (Anleitung, Claudes Nachrichten, lange Fragen): ein Editor-Reiter, der nur das
 * gerenderte Ergebnis zeigt. Anders als die Markdown-Vorschau behandelt er Links selbst - Sprungmarken (#…) und
 * Aktionen des Plugins (`handoff:…`), z. B. die MCP-Einstellungen öffnen.
 */
class MarkdownViewerFile(title: String, val markdown: String, val anchor: String? = null) :
    LightVirtualFile(title, MarkdownViewerFileType, markdown) {
    init { isWritable = false }
}

object MarkdownViewerFileType : FileType {
    override fun getName() = "Handoff Viewer"
    override fun getDescription() = "Handoff guide and messages"
    override fun getDefaultExtension() = ""
    override fun getIcon(): Icon = com.intellij.icons.AllIcons.Actions.Help
    override fun isBinary() = false
    override fun isReadOnly() = true
}

class MarkdownViewerProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file is MarkdownViewerFile
    override fun acceptRequiresReadAction() = false
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = MarkdownViewer(project, file as MarkdownViewerFile)
    override fun getEditorTypeId() = "handoff-markdown-viewer"
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

private class MarkdownViewer(private val project: Project, private val file: MarkdownViewerFile) : UserDataHolderBase(), FileEditor {
    private val pane = JEditorPane().apply {
        editorKit = HTMLEditorKitBuilder().withWordWrapViewFactory().build().also { kit ->
            val fg = UIUtil.getContextHelpForeground()
            val grey = "#%02x%02x%02x".format(fg.red, fg.green, fg.blue)
            kit.styleSheet.addRule("body { margin: ${JBUI.scale(16)}px ${JBUI.scale(24)}px; }")
            kit.styleSheet.addRule("h1 { font-size: 160%; margin-top: 0; }")
            kit.styleSheet.addRule("h2 { font-size: 130%; margin-top: ${JBUI.scale(18)}px; }")
            kit.styleSheet.addRule("h3 { font-size: 112%; margin-top: ${JBUI.scale(12)}px; }")
            kit.styleSheet.addRule("pre { padding: ${JBUI.scale(8)}px; border: 1px solid $grey; }")
            kit.styleSheet.addRule("td, th { padding: 3px ${JBUI.scale(8)}px 3px 0; text-align: left; vertical-align: top; }")
        }
        isEditable = false
        text = "<html><body>${Md.document(file.markdown)}</body></html>"
        caretPosition = 0
        addHyperlinkListener { e -> if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) open(e.description ?: e.url?.toString() ?: return@addHyperlinkListener) }
    }
    private val scroll = JBScrollPane(pane).apply { border = JBUI.Borders.empty() }

    init { file.anchor?.let { a -> javax.swing.SwingUtilities.invokeLater { pane.scrollToReference(a) } } }

    private fun open(link: String) {
        when {
            link.startsWith("#") -> pane.scrollToReference(link.drop(1))
            link.startsWith("handoff:") -> Actions.run(project, link.removePrefix("handoff:"))
            else -> BrowserUtil.browse(link)
        }
    }

    override fun getComponent(): JComponent = scroll
    override fun getPreferredFocusedComponent(): JComponent = pane
    override fun getName() = "Viewer"
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) {}
    override fun isModified() = false
    override fun isValid() = true
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun dispose() {}
}

/** Aktionen hinter `handoff:…`-Links im Viewer. */
object Actions {
    fun run(project: Project, action: String) {
        when (action) {
            "mcp-settings" -> Guide.openMcpSettings(project)
            "update-claude-md" -> FeaturesDialog.showMissing(project)
            "copy-snippet" -> Guide.copySnippet()
            "tool-window" -> com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("Handoff")?.show()
        }
    }
}
