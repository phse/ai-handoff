package app.heckit.ide.aihandoff

import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Vor dem Entladen (Plugin-Update ohne Neustart) die Reiter des Handoff-Fensters selbst entfernen und damit ihre Listener
 * abmelden. Die IDE hält das alte Fenster-Objekt teils in der Seitenleiste fest und baut es dann nicht ab
 * (Heap-Analyse, 2026-11-13) - so hängen wenigstens unsere Panels und Listener nicht mehr daran.
 */
class UnloadCleanup : DynamicPluginListener {
    override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
        if (pluginDescriptor.pluginId.idString != "app.heckit.ide.aihandoff") return
        for (project in ProjectManager.getInstance().openProjects) {
            ToolWindowManager.getInstance(project).getToolWindow("Handoff")?.contentManager?.removeAllContents(true)
        }
    }
}
