package app.heckit.ide.aihandoff.screenshot

import app.heckit.ide.aihandoff.HandoffService
import app.heckit.ide.aihandoff.Mark
import app.heckit.ide.aihandoff.MarkupDialog
import app.heckit.ide.aihandoff.MemoryPanel
import app.heckit.ide.aihandoff.UsagePanel
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.ui.JBColor
import java.awt.Window
import java.awt.image.BufferedImage
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.imageio.ImageIO
import javax.swing.JFrame
import javax.swing.RootPaneContainer

/**
 * Erzeugt die Marketplace-Screenshots in der Sandbox-IDE (./gradlew runIde -Pscreenshots=...).
 * Zeichnet Frame und Dialoge selbst in ein Bild, braucht also keine Bildschirmaufnahme-Rechte.
 * Legt Memory und Session-Protokolle des Demo-Projekts unter ~/.claude/projects/-Users-Shared-handoff-demo/ an.
 * Ist nur in Builds mit -Pscreenshots enthalten.
 */
object ScreenshotMaker {
    private const val SCALE = 2.0
    private val log = logger<ScreenshotMaker>()

    @JvmStatic
    fun start(project: Project, dir: String) {
        val out = File(dir).apply { mkdirs() }
        Thread({
            try { run(project, out) } catch (e: Throwable) { log.error("Screenshots failed", e) }
        }, "Handoff screenshots").start()
    }

    private fun run(project: Project, out: File) {
        val base = File(project.basePath ?: error("no base path"))
        writeDemoClaudeData(base)
        val frame = edt { WindowManager.getInstance().getFrame(project) } ?: error("no frame")
        edt {
            frame.extendedState = JFrame.NORMAL
            frame.setBounds(60, 40, 1280, 800)
        }
        Thread.sleep(8000)
        val tw = edt { ToolWindowManager.getInstance(project).getToolWindow("Handoff") } ?: error("no tool window")
        edt { tw.show() }
        Thread.sleep(2000)
        edt { (tw as? ToolWindowEx)?.stretchWidth(520 - tw.component.width) }

        // 1: Befehle und Bildauswahl
        val service = HandoffService.getInstance(project)
        val images = listOf("a-rounded.png", "b-pill.png", "c-square.png").map { File(base, "design/$it").path }
        edt {
            service.propose("npm run deploy:production", "Publishes release 2.4.0 to the live site.", "", true)
            service.propose("npm login", "The registry token expired; log in once so the release can be published.", "", false, interactive = true)
            service.proposeImages(images, "Which checkout button style should we use?").also { p ->
                p.selected = 1
                p.marks[1] = listOf(Mark(110, 420, 680, 70, "Too round next to the square cards"))
                service.fireChanged()
            }
            tw.contentManager.setSelectedContent(tw.contentManager.contents[0])
        }
        Thread.sleep(2500)
        shot(frame, File(out, "01-commands.png"))

        // 2: Viewer mit Rahmen
        val marks = mapOf(1 to listOf(
            Mark(110, 420, 680, 70, "Too round next to the square cards"),
            Mark(640, 340, 160, 40, "Show the total in bold"),
        ))
        var dialog: MarkupDialog? = null
        ApplicationManager.getApplication().invokeLater({
            dialog = MarkupDialog(project, images, 1, marks, true)
            dialog!!.show()
        }, ModalityState.any())
        Thread.sleep(3000)
        // Der Viewer ist größer als der Frame - als eigenes Bild.
        edt {
            val w = dialog!!.window
            val image = BufferedImage((w.width * SCALE).toInt(), (w.height * SCALE).toInt(), BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics(); g.scale(SCALE, SCALE)
            (w as RootPaneContainer).rootPane.paint(g); g.dispose()
            ImageIO.write(image, "png", File(out, "02-viewer.png"))
        }
        edt { dialog?.close(1) }
        Thread.sleep(1000)

        // 3: Memory, Projekt-CLAUDE.md aufgeklappt
        val memory = edt { tw.contentManager.contents[1].component as MemoryPanel }
        edt {
            tw.contentManager.setSelectedContent(tw.contentManager.contents[1])
            memory.refresh()
            memory.expand("f:" + File(base, "CLAUDE.md").path)
        }
        Thread.sleep(3000)
        shot(frame, File(out, "03-memory.png"))

        // 4: Usage
        val usage = edt { tw.contentManager.contents[2].component as UsagePanel }
        edt {
            tw.contentManager.setSelectedContent(tw.contentManager.contents[2])
            usage.refresh()
        }
        Thread.sleep(3000)
        shot(frame, File(out, "04-usage.png"))
        log.warn("Handoff: screenshots written to $out")
    }

    /** Memory mit Index-Lücke und offenem Link, dazu Session-Protokolle der letzten Tage (erfundene, neutrale Titel). */
    private fun writeDemoClaudeData(base: File) {
        val dir = File(System.getProperty("user.home"), ".claude/projects/" + base.absolutePath.replace(Regex("[^A-Za-z0-9]"), "-"))
        val mem = File(dir, "memory").apply { deleteRecursively(); mkdirs() }
        File(mem, "MEMORY.md").writeText(
            "- [Release flow](release-flow.md) — how releases are tagged and deployed\n" +
            "- [Price format](price-format.md) — prices are integers in cents\n"
        )
        File(mem, "release-flow.md").writeText(
            "---\nname: release-flow\ndescription: How releases are tagged and deployed\nmetadata:\n  type: project\n---\n" +
            "Tag on main, the developer deploys. See [[price-format]] and [[staging-setup]].\n"
        )
        File(mem, "price-format.md").writeText(
            "---\nname: price-format\ndescription: Prices are integers in cents\nmetadata:\n  type: project\n---\n" +
            "Never store prices as floats.\n\nAlways run the release script from the project root folder so that the\n" +
            "relative paths in the build configuration resolve correctly every time.\n"
        )
        File(mem, "user-prefers-short-answers.md").writeText(
            "---\nname: user-prefers-short-answers\nmetadata:\n  type: feedback\n---\nKeep answers short.\n"
        )
        dir.listFiles { f -> f.name.endsWith(".jsonl") }?.forEach { it.delete() }
        val rnd = java.util.Random(7)
        val now = Instant.now()
        listOf(
            Triple("Fix checkout total rounding", 0, 3), Triple("Checkout button styles", 0, 1), Triple("Release 2.4.0", 1, 5),
            Triple("Refactor image upload service", 1, 3), Triple("Update dependencies", 2, 4), Triple("Write tests for cart API", 3, 6),
        ).forEach { (title, days, hoursAgo) ->
            var t = now.minus(days.toLong(), ChronoUnit.DAYS).minus(hoursAgo.toLong(), ChronoUnit.HOURS)
            val lines = StringBuilder()
            lines.append("""{"type":"user","message":{"role":"user","content":"$title"},"timestamp":"$t"}""").append('\n')
            repeat(15 + rnd.nextInt(40)) {
                t = t.plusSeconds(20L + rnd.nextInt(120))
                val id = "msg_" + java.lang.Long.toHexString(rnd.nextLong())
                lines.append("""{"type":"assistant","timestamp":"$t","message":{"id":"$id","model":"claude","usage":{"input_tokens":${2 + rnd.nextInt(6)},"cache_creation_input_tokens":${500 + rnd.nextInt(8000)},"cache_read_input_tokens":${20000 + rnd.nextInt(90000)},"output_tokens":${80 + rnd.nextInt(1400)}}}}""").append('\n')
            }
            lines.append("""{"type":"ai-title","aiTitle":"$title"}""").append('\n')
            File(dir, java.util.UUID.nameUUIDFromBytes(title.toByteArray()).toString() + ".jsonl").writeText(lines.toString())
        }
    }

    /** Zeichnet den Frame und alle darüber liegenden Fenster (Dialoge) in ein PNG. */
    private fun shot(frame: JFrame, file: File) = edt {
        val root = frame.rootPane
        val origin = root.locationOnScreen
        val image = BufferedImage((root.width * SCALE).toInt(), (root.height * SCALE).toInt(), BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.scale(SCALE, SCALE)
        root.paint(g)
        for (window in Window.getWindows()) {
            if (window === frame || !window.isShowing || window !is RootPaneContainer || window.owner == null) continue
            val pos = window.locationOnScreen
            val wg = g.create(pos.x - origin.x, pos.y - origin.y, window.width, window.height)
            window.rootPane.paint(wg)
            wg.dispose()
            g.color = JBColor.border()
            g.drawRect(pos.x - origin.x, pos.y - origin.y, window.width - 1, window.height - 1)
        }
        g.dispose()
        ImageIO.write(image, "png", file)
    }

    private fun <T> edt(block: () -> T): T {
        var result: Result<T>? = null
        ApplicationManager.getApplication().invokeAndWait({ result = runCatching(block) }, ModalityState.any())
        return result!!.getOrThrow()
    }
}
