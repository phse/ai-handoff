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
        // Ansicht wie nach der Installation (die Sandbox behält, was man dort umgestellt hat).
        app.heckit.ide.aihandoff.Layout.newestOnTop = false
        app.heckit.ide.aihandoff.Layout.pinClaudeMessages = true
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

        // 6: Ablauf aus mehreren Befehlen und eine wartende Claude-Session (vor Szene 5, die den Diff öffnet)
        val waitingFile = File(app.heckit.ide.aihandoff.WaitingHook.waiting.apply { mkdirs() }, "handoff-demo.json")
        waitingFile.writeText(com.google.gson.JsonObject().apply {
            addProperty("session_id", "handoff-demo"); addProperty("cwd", base.path); addProperty("hook_event_name", "Stop")
            addProperty("last_assistant_message", "The checkout button now uses the **pill style**. Tests pass; the release steps are ready for you.")
        }.toString())
        // Ein wartender Stop-Hook: Die Karte bekommt das Feld für den nächsten Prompt.
        val listenFile = File(waitingFile.parentFile, "handoff-demo.listen").apply { writeText("0") }
        app.heckit.ide.aihandoff.WaitingSessions.getInstance(project).drafts["handoff-demo"] = "Also update the button in the mobile layout"
        edt {
            service.proposals.clear()
            service.proposeSequence(
                listOf("npm test", "npm version minor", "git push --follow-tags", "npm publish"),
                "Release 2.5.0: run the tests, bump the version, push the tag and publish the package.", base.path, true, false,
            ).also { p -> p.steps[0].exitCode = 0; service.fireChanged() }
            tw.contentManager.setSelectedContent(tw.contentManager.contents[0])
        }
        Thread.sleep(4000)
        shot(frame, File(out, "06-sequence.png"))
        waitingFile.delete(); listenFile.delete()
        Thread.sleep(2500)

        // 7: Frage mit Auswahl (zwei Fragen, die erste beantwortet)
        edt {
            service.proposals.clear()
            service.proposeAsk("Before the release", listOf(
                app.heckit.ide.aihandoff.AskQuestion(
                    "Which **version number** should release 2.5.0 get? The changes add a feature and fix two bugs.",
                    listOf(
                        app.heckit.ide.aihandoff.AskOption("2.5.0", "New feature: free shipping threshold"),
                        app.heckit.ide.aihandoff.AskOption("2.4.1", "Only if the threshold waits for the next release"),
                        app.heckit.ide.aihandoff.AskOption("3.0.0", "Breaking change in the cart API"),
                    ),
                ),
                app.heckit.ide.aihandoff.AskQuestion(
                    "Which channels should announce it?",
                    listOf(app.heckit.ide.aihandoff.AskOption("Changelog"), app.heckit.ide.aihandoff.AskOption("Newsletter"), app.heckit.ide.aihandoff.AskOption("Blog post")),
                    multiSelect = true,
                ),
            )).also { p -> p.questions[0].picked += 0; service.fireChanged() }
            tw.contentManager.setSelectedContent(tw.contentManager.contents[0])
        }
        Thread.sleep(2500)
        shot(frame, File(out, "07-ask.png"))

        // 8: Handoff Tools (Dialog als eigenes Bild)
        var tools: app.heckit.ide.aihandoff.ToolsDialog? = null
        ApplicationManager.getApplication().invokeLater({
            tools = app.heckit.ide.aihandoff.ToolsDialog(project, mapOf(
                "handoff_propose_command" to 14, "handoff_wait_for_result" to 31, "handoff_begin_task" to 6, "handoff_end_task" to 6,
                "handoff_request_review" to 5, "handoff_show_images" to 2, "handoff_ask" to 3,
            ), "7 days")
            tools!!.show()
        }, ModalityState.any())
        Thread.sleep(3000)
        edt {
            val w = tools!!.window
            val image = BufferedImage((w.width * SCALE).toInt(), (w.height * SCALE).toInt(), BufferedImage.TYPE_INT_ARGB)
            val g = image.createGraphics(); g.scale(SCALE, SCALE)
            (w as RootPaneContainer).rootPane.paint(g); g.dispose()
            ImageIO.write(image, "png", File(out, "08-tools.png"))
        }
        edt { tools?.close(1) }
        Thread.sleep(1000)

        // 9: Session im Editor-Reiter - Verlauf, Links, eine Frage als Karte im Gespräch, Feld für den nächsten Prompt
        sessionScene(project, base, out, tw, frame)

        // 5: Änderungen prüfen - Diff mit Zeilennotizen
        val cart = File(base, "src/cart.js")
        cart.writeText(CART_CHANGED)
        val vf = edt { com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(cart) } ?: error("no cart.js")
        write {
            val vcs = com.intellij.openapi.vcs.ProjectLevelVcsManager.getInstance(project)
            if (vcs.directoryMappings.isEmpty()) vcs.directoryMappings = listOf(com.intellij.openapi.vcs.VcsDirectoryMapping("", "Git"))
            vf.refresh(false, false)
            com.intellij.openapi.vcs.changes.VcsDirtyScopeManager.getInstance(project).markEverythingDirty()
            service.clearFinished()
            service.proposals.clear()
        }
        Thread.sleep(6000)
        edt {
            val document = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getDocument(vf)!!
            val notes = app.heckit.ide.aihandoff.NotesService.getInstance(project)
            fun line(text: String) = document.text.lines().indexOfFirst { it.contains(text) }
            notes.add(document, vf, line("const SHIPPING"), line("const SHIPPING"), "Read this from the shop settings")
            notes.add(document, vf, line("if (total <"), line("if (total <"), "Compare before the discount, not after")
            service.proposeReview("Added a discount and a free-shipping threshold to the cart total.", null)
            tw.contentManager.setSelectedContent(tw.contentManager.contents[0])
            // Platz für den Diff: Projektbaum zu.
            ToolWindowManager.getInstance(project).getToolWindow("Project")?.hide()
            val changes = com.intellij.openapi.vcs.changes.ChangeListManager.getInstance(project).allChanges.toList()
            log.warn("Handoff: review screenshot with ${changes.size} change(s)")
            com.intellij.openapi.vcs.changes.actions.diff.ShowDiffAction.showDiffForChange(project, changes)
        }
        Thread.sleep(5000)
        // Maus über eine Zeile im Diff: dort erscheint das "+" anstelle der Zeilennummer.
        edt {
            com.intellij.openapi.editor.EditorFactory.getInstance().allEditors
                .firstOrNull { it.editorKind == com.intellij.openapi.editor.EditorKind.DIFF && it.document.text == CART_CHANGED }
                ?.let { e ->
                    val y = e.logicalPositionToXY(com.intellij.openapi.editor.LogicalPosition(10, 0)).y + e.lineHeight / 2
                    e.contentComponent.dispatchEvent(java.awt.event.MouseEvent(e.contentComponent, java.awt.event.MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, 40, y, 0, false))
                } ?: log.warn("Handoff: no diff editor for the hover")
        }
        Thread.sleep(1000)
        shot(frame, File(out, "05-review.png"))
        log.warn("Handoff: screenshots written to $out")
    }

    private fun sessionScene(project: Project, base: File, out: File, tw: com.intellij.openapi.wm.ToolWindow, frame: JFrame) {
        val id = "handoff-demo-session"
        val dir = File(System.getProperty("user.home"), ".claude/projects/" + base.absolutePath.replace(Regex("[^A-Za-z0-9]"), "-")).apply { mkdirs() }
        val transcript = File(dir, "$id.jsonl")
        fun str(t: String) = com.google.gson.JsonPrimitive(t).toString()
        fun user(t: String) = """{"type":"user","message":{"role":"user","content":${str(t)}}}"""
        fun text(t: String) = """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":${str(t)}}]}}"""
        fun tool(name: String, key: String, value: String) = """{"type":"assistant","message":{"role":"assistant","content":[{"type":"tool_use","name":"$name","input":{"$key":${str(value)}}}]}}"""
        transcript.writeText(listOf(
            user("Add a free shipping threshold to the cart total"),
            tool("Read", "file_path", "${base.path}/src/cart.js"),
            tool("Edit", "file_path", "${base.path}/src/cart.js"),
            tool("Bash", "command", "npm test"),
            text("Done. Orders from **50 €** now ship for free:\n\n- `src/cart.js:3` holds the threshold `FREE_SHIPPING_FROM`\n" +
                "- the shipping fee is added only below it\n- all 24 tests pass\n\nThe docs for the rule: https://example.com/docs/shipping"),
            user("Should the threshold apply before or after the discount?"),
            tool("mcp__phpstorm__handoff_ask", "title", "Free shipping and discounts"),
            """{"type":"ai-title","aiTitle":"Free shipping threshold"}""",
        ).joinToString("\n") + "\n")
        // Session "fertig", Stop-Hook wartet: Feld für den nächsten Prompt ist frei.
        val waiting = app.heckit.ide.aihandoff.WaitingHook.waiting.apply { mkdirs() }
        val hook = com.google.gson.JsonObject().apply {
            addProperty("session_id", id); addProperty("cwd", base.path); addProperty("transcript_path", transcript.path)
            addProperty("hook_event_name", "Stop"); addProperty("last_assistant_message", "Asked about the discount.")
        }.toString()
        val files = listOf(File(waiting, "$id.info").apply { writeText(hook) }, File(waiting, "$id.json").apply { writeText(hook) },
            File(waiting, "$id.listen").apply { writeText("0") })
        val service = HandoffService.getInstance(project)
        edt {
            service.proposals.clear()
            service.proposeAsk("Free shipping and discounts", listOf(app.heckit.ide.aihandoff.AskQuestion(
                "Should the **50 €** threshold be checked before or after the discount?",
                listOf(
                    app.heckit.ide.aihandoff.AskOption("After the discount", "What the customer actually pays counts"),
                    app.heckit.ide.aihandoff.AskOption("Before the discount", "Discounts never cost free shipping"),
                ),
            )))
            tw.hide()
            ToolWindowManager.getInstance(project).getToolWindow("Project")?.hide()
            // Nur der Session-Reiter (die Sandbox öffnet z. B. "What's New").
            com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).let { m -> m.openFiles.forEach(m::closeFile) }
            com.intellij.openapi.fileEditor.OpenFileDescriptor(project, app.heckit.ide.aihandoff.SessionFile(null, id, transcript.path, "Free shipping threshold")).navigate(true)
        }
        Thread.sleep(4000)
        // Echte Plan-Limits des Rechners nicht ins Bild.
        edt {
            com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedEditor?.component?.let { root ->
                com.intellij.util.ui.UIUtil.findComponentsOfType(root, com.intellij.ui.components.JBLabel::class.java)
                    .filter { it.text.orEmpty().contains("Week") }.forEach { it.text = "5h 12 %  ·  Week 34 %" }
                // Wie eine Session aus "New Claude Session" (in der Sandbox läuft keine): ohne Resume, Hinweis fürs Terminal.
                com.intellij.util.ui.UIUtil.findComponentsOfType(root, com.intellij.ui.components.ActionLink::class.java)
                    .filter { it.text.orEmpty().startsWith("Resume") }.forEach { it.isVisible = false }
                com.intellij.util.ui.UIUtil.findComponentsOfType(root, com.intellij.ui.components.JBLabel::class.java)
                    .filter { it.text.orEmpty().startsWith("Enter sends") }
                    .forEach { it.text = "Enter sends, Shift+Enter adds a line. Goes to the terminal as if typed, slash commands included." }
            }
            // Hinweise der Sandbox (z. B. "Cannot update plugin") nicht ins Bild.
            com.intellij.notification.NotificationsManager.getNotificationsManager()
                .getNotificationsOfType(com.intellij.notification.Notification::class.java, project).forEach { it.expire() }
        }
        Thread.sleep(500)
        shot(frame, File(out, "09-session.png"))
        edt {
            app.heckit.ide.aihandoff.SessionEditors.closeAll(project)
            service.proposals.clear(); service.fireChanged()
            tw.show()
        }
        files.forEach { it.delete() }
        transcript.delete()
        Thread.sleep(1500)
    }

    private val CART_CHANGED = """
        // Cart totals. Prices are integers in cents.

        const FREE_SHIPPING_FROM = 5000;
        const SHIPPING = 490;

        export function cartTotal(items, discountPercent = 0) {
          let total = 0;
          for (const item of items) {
            total += item.price * item.quantity;
          }
          total = Math.round(total * (1 - discountPercent / 100));
          if (total < FREE_SHIPPING_FROM) {
            total += SHIPPING;
          }
          return total;
        }

        export function formatPrice(cents) {
          return (cents / 100).toFixed(2) + ' €';
        }
    """.trimIndent() + "\n"

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

    /** Wie [edt], aber in schreibsicherem Kontext (VFS- und VCS-Änderungen). */
    private fun write(block: () -> Unit) = ApplicationManager.getApplication().invokeAndWait(block, ModalityState.nonModal())

    private fun <T> edt(block: () -> T): T {
        var result: Result<T>? = null
        ApplicationManager.getApplication().invokeAndWait({ result = runCatching(block) }, ModalityState.any())
        return result!!.getOrThrow()
    }
}
