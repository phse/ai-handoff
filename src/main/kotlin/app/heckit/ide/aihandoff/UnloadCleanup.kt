package app.heckit.ide.aihandoff

import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Vor dem Entladen (Plugin-Update ohne Neustart) die Reiter des Handoff-Fensters selbst entfernen und damit ihre Listener
 * abmelden. Die IDE hält das alte Fenster-Objekt teils in der Seitenleiste fest und baut es dann nicht ab
 * (Heap-Analyse, 2026-11-13, IJPL-6052) - so hängen wenigstens unsere Panels und Listener nicht mehr daran.
 */
class UnloadCleanup : DynamicPluginListener {
    /** Installation ohne Neustart: Projekt-Aktivitäten laufen dann nicht - die Anleitung im vordersten Projekt öffnen. */
    override fun pluginLoaded(pluginDescriptor: IdeaPluginDescriptor) {
        if (pluginDescriptor.pluginId.idString != "app.heckit.ide.aihandoff") return
        val project = com.intellij.openapi.wm.IdeFocusManager.getGlobalInstance().lastFocusedFrame?.project
            ?: ProjectManager.getInstance().openProjects.firstOrNull() ?: return
        Guide.openOnFirstInstall(project)
    }

    override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
        if (pluginDescriptor.pluginId.idString != "app.heckit.ide.aihandoff") return
        NoteGutter.uninstallAll()
        for (project in ProjectManager.getInstance().openProjects) {
            Guide.closeAll(project)
            Md.closeAll(project)
            // Randsymbole der Notizen hängen als Highlighter an den Dokumenten der IDE.
            project.getServiceIfCreated(NotesService::class.java)?.clear()
            ToolWindowManager.getInstance(project).getToolWindow("Handoff")?.contentManager?.removeAllContents(true)
        }
    }
}
