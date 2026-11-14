package app.heckit.ide.aihandoff

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.AppIcon
import com.intellij.ui.SystemNotifications

/**
 * Aufmerksamkeit holen, wenn eine Übergabe wartet und die IDE nicht im Vordergrund ist: Mitteilung des Betriebssystems
 * und Hinweis am Dock- bzw. Taskleisten-Symbol. Ist die IDE aktiv, reicht das aufgehende Handoff-Fenster.
 */
object Attention {
    fun notify(project: Project, title: String, text: String) {
        val app = ApplicationManager.getApplication()
        if (app.isUnitTestMode || app.isHeadlessEnvironment) return
        app.invokeLater({
            if (app.isActive) return@invokeLater
            SystemNotifications.getInstance().notify("Handoff", title, text.lineSequence().first().take(140))
            AppIcon.getInstance().requestAttention(project, false)
        }, project.disposed)
    }
}
