package app.heckit.ide.aihandoff

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import java.awt.datatransfer.StringSelection

/**
 * Anleitung im Plugin (`/guide/guide.md`): Einrichtung, ein Prompt je Funktion und "Version history". Einzige Quelle für den
 * CLAUDE.md-Baustein und den Update-Hinweis, damit neue Funktionen nur dort nachgetragen werden müssen.
 */
object Guide {
    private const val FILE_NAME = "Handoff Guide"
    private const val SEEN_KEY = "app.heckit.ide.aihandoff.seenVersion"

    val text: String by lazy { javaClass.getResourceAsStream("/guide/guide.md")?.use { it.readBytes().decodeToString() }.orEmpty() }

    /** Inhalt des Codeblocks zwischen den snippet-Markern. */
    val snippet: String
        get() = text.substringAfter("<!-- snippet:start -->", "").substringBefore("<!-- snippet:end -->")
            .trim().removePrefix("```").removeSuffix("```").trim() + "\n"

    val version: String? get() = (Guide::class.java.classLoader as? com.intellij.ide.plugins.cl.PluginAwareClassLoader)?.pluginDescriptor?.version

    /** Erste Zeile unter "### <version>" im Abschnitt Version history. */
    fun newsFor(v: String): String? {
        val news = text.substringAfter("## Version history", "").substringBefore("\n## ")
        val section = news.substringAfter("\n### $v\n", "").takeIf { it.isNotEmpty() } ?: return null
        return section.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
    }

    /**
     * Hinweis nach einem Update, solange er nicht weggeklickt ist. Bei einer Neuinstallation keiner - dann zeigt der leere
     * Reiter die Einrichtung. Dev-Builds (`1.0.1-dev…`) zählen wie ihre Version.
     */
    fun pendingNews(): Pair<String, String>? {
        val v = version?.substringBefore("-") ?: return null
        val props = PropertiesComponent.getInstance()
        val seen = props.getValue(SEEN_KEY)
        if (seen == null) { props.setValue(SEEN_KEY, v); return null }
        if (seen == v) return null
        return newsFor(v)?.let { v to it } ?: run { props.setValue(SEEN_KEY, v); null }
    }

    private const val INSTALL_KEY = "app.heckit.ide.aihandoff.guideShownOnInstall"

    /**
     * Nach der ersten Installation einmal die Anleitung öffnen. Wer von einer älteren Version aktualisiert, hat schon eine
     * gesehene Version gespeichert und bekommt stattdessen den Update-Hinweis. Nicht in Screenshot-Läufen.
     */
    fun openOnFirstInstall(project: Project) {
        if (System.getProperty("handoff.screenshots") != null) return
        val props = PropertiesComponent.getInstance()
        if (props.isValueSet(INSTALL_KEY)) return
        props.setValue(INSTALL_KEY, true)
        if (props.getValue(SEEN_KEY) != null) return
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater({ open(project) }, project.disposed)
    }

    fun markNewsSeen() { version?.substringBefore("-")?.let { PropertiesComponent.getInstance().setValue(SEEN_KEY, it) } }

    fun copySnippet() = CopyPasteManager.getInstance().setContents(StringSelection(snippet))

    fun openMcpSettings(project: Project) = ShowSettingsUtil.getInstance().showSettingsDialog(project, "MCP Server")

    /** Im eigenen Viewer (gerendert, Links auf Plugin-Aktionen); ein schon offener Reiter wird nur aktiviert. */
    fun open(project: Project) {
        Md.open(project, FILE_NAME, text)
    }

    /** Anleitung beim Eintrag der Version in der Version history (Editor springt dorthin, die Vorschau folgt). */
    fun openNews(project: Project, version: String) {
        Md.open(project, FILE_NAME, text, anchor = version.replace(".", ""))
    }

    /** Beim Entladen offene Anleitungen schließen, damit kein Editor an unserer Version hängt. */
    fun closeAll(project: Project) {
        val manager = FileEditorManager.getInstance(project)
        manager.openFiles.filter { it is MarkdownViewerFile && it.name == FILE_NAME }.forEach { manager.closeFile(it) }
    }
}
