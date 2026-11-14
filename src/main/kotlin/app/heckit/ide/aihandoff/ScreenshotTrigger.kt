package app.heckit.ide.aihandoff

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/** Startet den Screenshot-Helfer, nur in Builds mit -Pscreenshots enthalten (siehe build.gradle.kts); im Release fehlt die Klasse. */
class ScreenshotTrigger : ProjectActivity {
    override suspend fun execute(project: Project) {
        WaitingSessions.getInstance(project).start()
        val dir = System.getProperty("handoff.screenshots") ?: return
        runCatching {
            Class.forName("app.heckit.ide.aihandoff.screenshot.ScreenshotMaker")
                .getMethod("start", Project::class.java, String::class.java)
                .invoke(null, project, dir)
        }.onFailure { logger<ScreenshotTrigger>().warn("Handoff: screenshot helper not available", it) }
    }
}
