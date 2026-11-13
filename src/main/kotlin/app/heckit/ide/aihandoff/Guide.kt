package app.heckit.ide.aihandoff

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.testFramework.LightVirtualFile
import java.awt.datatransfer.StringSelection

/**
 * Anleitung im Plugin (`/guide/guide.md`): Einrichtung, ein Prompt je Funktion und "What's new". Einzige Quelle für den
 * CLAUDE.md-Baustein und den Update-Hinweis, damit neue Funktionen nur dort nachgetragen werden müssen.
 */
object Guide {
    private const val FILE_NAME = "Handoff Guide.md"
    private const val SEEN_KEY = "app.heckit.ide.aihandoff.seenVersion"

    val text: String by lazy { javaClass.getResourceAsStream("/guide/guide.md")?.use { it.readBytes().decodeToString() }.orEmpty() }

    /** Inhalt des Codeblocks zwischen den snippet-Markern. */
    val snippet: String
        get() = text.substringAfter("<!-- snippet:start -->", "").substringBefore("<!-- snippet:end -->")
            .trim().removePrefix("```").removeSuffix("```").trim() + "\n"

    val version: String? get() = (Guide::class.java.classLoader as? com.intellij.ide.plugins.cl.PluginAwareClassLoader)?.pluginDescriptor?.version

    /** Erste Zeile unter "### <version>" im Abschnitt What's new. */
    fun newsFor(v: String): String? {
        val news = text.substringAfter("## What's new", "").substringBefore("\n## ")
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

    fun markNewsSeen() { version?.substringBefore("-")?.let { PropertiesComponent.getInstance().setValue(SEEN_KEY, it) } }

    fun copySnippet() = CopyPasteManager.getInstance().setContents(StringSelection(snippet))

    fun openMcpSettings(project: Project) = ShowSettingsUtil.getInstance().showSettingsDialog(project, "MCP Server")

    /** Schreibgeschützt im Editor; mit Markdown-Plugin samt Vorschau. Ein schon offener Reiter wird nur aktiviert. */
    fun open(project: Project) {
        val manager = FileEditorManager.getInstance(project)
        val file = manager.openFiles.firstOrNull { it is LightVirtualFile && it.name == FILE_NAME }
            ?: LightVirtualFile(FILE_NAME, text).apply { isWritable = false }
        com.intellij.openapi.fileEditor.OpenFileDescriptor(project, file).navigate(true)
    }

    /** Beim Entladen offene Anleitungen schließen, damit kein Editor an unserer Version hängt. */
    fun closeAll(project: Project) {
        val manager = FileEditorManager.getInstance(project)
        manager.openFiles.filter { it is LightVirtualFile && it.name == FILE_NAME }.forEach { manager.closeFile(it) }
    }
}
