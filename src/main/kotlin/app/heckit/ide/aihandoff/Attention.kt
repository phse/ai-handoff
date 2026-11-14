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
            val body = text.lineSequence().first().take(140)
            // Klick auf die Mitteilung: das Fenster des Projekts nach vorn holen, nicht irgendein Fenster der IDE
            // (mehrere Projekte in einer IDE). Die Variante mit Rückruf gibt es erst ab 2026.2 - per Reflexion, sonst ohne.
            val onClick = Runnable {
                app.invokeLater({
                    com.intellij.ide.impl.ProjectUtil.focusProjectWindow(project, true)
                    com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("Handoff")?.show()
                }, project.disposed)
            }
            val notifications = SystemNotifications.getInstance()
            val withCallback = runCatching {
                notifications.javaClass.getMethod("notify", String::class.java, String::class.java, String::class.java, Runnable::class.java)
            }.getOrNull()
            if (withCallback != null) withCallback.invoke(notifications, "Handoff", title, body, onClick)
            else notifications.notify("Handoff", title, body)
            AppIcon.getInstance().requestAttention(project, false)
        }, project.disposed)
    }
}
