package app.heckit.ide.aihandoff

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.application.EDT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** Werkzeuge für den MCP-Server der IDE: Claude schlägt Befehle vor, der Nutzer führt sie aus. */
class HandoffToolset : McpToolset {

    @McpTool
    @McpDescription(
        """
        |Hands a shell command over to the user instead of running it yourself. Use it for steps the user wants to run
        |personally: deploys, releases, uploads, logins, secrets, anything outward-facing or hard to undo.
        |The command appears in the IDE's Handoff tool window, where the user can run, edit or dismiss it.
        |Returns an id right away; then call handoff_wait_for_result with it to get exit code and output.
        """
    )
    suspend fun handoff_propose_command(
        @McpDescription("The shell command, exactly as it should run (zsh/bash syntax).")
        command: String,
        @McpDescription("One short sentence for the user: what the command does and why it is needed now.")
        reason: String,
        @McpDescription("Working directory, absolute or relative to the project root. Empty = project root.")
        workingDirectory: String = "",
        @McpDescription("True if the command affects production, publishes something or deletes data.")
        risky: Boolean = false,
        @McpDescription("True if the command needs input from the user (login, password, confirmation prompt). The user types it into the Run window; it is not part of the returned output only if the command hides it (passwords).")
        interactive: Boolean = false,
    ): String {
        val project = currentCoroutineContext().project
        val p = HandoffService.getInstance(project).propose(command.trim(), reason.trim(), workingDir(project, workingDirectory), risky, interactive)
        ApplicationManager.getApplication().invokeLater({
            ToolWindowManager.getInstance(project).getToolWindow("Handoff")?.show()
        }, project.disposed)
        return "Proposed as ${p.id}. The user decides in the IDE. Call handoff_wait_for_result(id=\"${p.id}\") to get the result."
    }

    private fun workingDir(project: com.intellij.openapi.project.Project, workingDirectory: String): String {
        val base = project.basePath ?: System.getProperty("user.home")
        return when {
            workingDirectory.isBlank() -> base
            File(workingDirectory).isAbsolute -> workingDirectory
            else -> File(base, workingDirectory).normalize().path
        }
    }

    @McpTool
    @McpDescription(
        """
        |Hands several shell commands over to the user as one sequence (e.g. verify, tag, push, upload) instead of
        |proposing them one by one. They appear as one card in the IDE's Handoff tool window; the user runs them all in
        |order or step by step and can edit each one. The sequence stops at the first command that fails.
        |Use it when the later commands only make sense after the earlier ones succeeded and you do not need to look at
        |the output in between. Returns an id; call handoff_wait_for_result with it to get exit code and output per step.
        """
    )
    suspend fun handoff_propose_sequence(
        @McpDescription("The shell commands in the order they should run, each exactly as it should run (zsh/bash syntax). Each runs in its own shell: variables and cd do not carry over.")
        commands: List<String>,
        @McpDescription("One short sentence for the user: what the sequence does and why it is needed now.")
        reason: String,
        @McpDescription("Working directory for all commands, absolute or relative to the project root. Empty = project root.")
        workingDirectory: String = "",
        @McpDescription("True if a command affects production, publishes something or deletes data.")
        risky: Boolean = false,
        @McpDescription("True if a command needs input from the user (login, password, confirmation prompt).")
        interactive: Boolean = false,
    ): String {
        val project = currentCoroutineContext().project
        val list = commands.map { it.trim() }.filter { it.isNotEmpty() }
        if (list.isEmpty()) return "No commands given."
        val p = HandoffService.getInstance(project).proposeSequence(list, reason.trim(), workingDir(project, workingDirectory), risky, interactive)
        ApplicationManager.getApplication().invokeLater({
            ToolWindowManager.getInstance(project).getToolWindow("Handoff")?.show()
        }, project.disposed)
        return "Proposed as ${p.id} (${list.size} steps). The user decides in the IDE. Call handoff_wait_for_result(id=\"${p.id}\") to get the result."
    }

    @McpTool
    @McpDescription(
        """
        |Waits until the user has run or dismissed a command proposed with handoff_propose_command and returns the outcome:
        |the command as actually run (the user may have edited it), exit code and the last lines of output, or the user's note
        |when dismissed. Returns "pending" after the timeout; call it again to keep waiting.
        """
    )
    suspend fun handoff_wait_for_result(
        @McpDescription("Id returned by handoff_propose_command.")
        id: String,
        @McpDescription("Maximum seconds to wait (1-50). MCP clients abort longer calls; call again to keep waiting.")
        timeoutSeconds: Int = 50,
    ): String {
        val project = currentCoroutineContext().project
        val p = HandoffService.getInstance(project).find(id) ?: return "Unknown id $id."
        withTimeoutOrNull(timeoutSeconds.coerceIn(1, 50) * 1000L) { p.done.await() }
        return when (p.state) {
            Proposal.State.PENDING if p.steps.any { it.exitCode != null } ->
                "pending: ${p.steps.count { it.exitCode != null }} of ${p.steps.size} steps done, the user has not started the next one yet."
            Proposal.State.PENDING -> "pending: the user has not acted yet."
            Proposal.State.RUNNING if p.steps.isNotEmpty() -> {
                val i = p.steps.indexOfFirst { it.running }
                "running: step ${i + 1} of ${p.steps.size} is still running." + (p.steps.getOrNull(i)?.let { "\nOutput so far:\n${it.outputTail(20)}" } ?: "")
            }
            Proposal.State.RUNNING -> "running: the command is still running.\nOutput so far:\n${p.outputTail(20)}"
            Proposal.State.DISMISSED -> "dismissed by the user." + (p.note?.let { " Note: $it" } ?: "") +
                (p.marksText().takeIf { it.isNotEmpty() }?.let { "\n$it" } ?: "") +
                (if (p.steps.any { it.exitCode != null }) "\nSteps run before:\n" + p.stepsText() else "")
            Proposal.State.FINISHED if p.steps.isNotEmpty() -> buildString {
                val failed = p.steps.indexOfFirst { (it.exitCode ?: 0) != 0 }
                if (failed < 0) append("finished: all ${p.steps.size} steps succeeded.\n")
                else append("failed at step ${failed + 1} of ${p.steps.size} (exit ${p.steps[failed].exitCode}); the steps after it were not run.\n")
                append("Commands as actually run (the user may have edited them), exit code and output tail:\n")
                append(p.stepsText())
            }
            Proposal.State.FINISHED if p.review -> buildString {
                append(if (p.lineNotes == null && p.note == null) "approved by the user: no change requests." else "changes requested by the user.")
                p.note?.let { append("\nNote: $it") }
                p.lineNotes?.let { append("\nLine notes (path:line, the note, then the line it is attached to):\n").append(it) }
            }
            Proposal.State.FINISHED if p.isAsk -> buildString {
                append("answered by the user.")
                p.questions.forEachIndexed { qi, q ->
                    append("\n\nQuestion ${qi + 1}: ${q.question.lineSequence().first().take(120)}")
                    val chosen = q.picked.sorted()
                    if (chosen.isEmpty()) append("\nNo option picked.")
                    else chosen.forEach { append("\nChosen: ${it + 1}. ${q.options[it].label}") }
                    q.own.takeIf { it.isNotBlank() }?.let { append("\nOwn answer: $it") }
                }
            }
            Proposal.State.FINISHED if p.images.isNotEmpty() -> buildString {
                val i = p.chosen ?: 0
                append(if (p.images.size == 1) "approved by the user." else "the user chose image ${i + 1} of ${p.images.size}: ${p.images[i]}.")
                p.note?.let { append(" Note: $it") }
                p.marksText().takeIf { it.isNotEmpty() }?.let { append("\n").append(it) }
            }
            Proposal.State.FINISHED -> buildString {
                append("finished with exit code ${p.exitCode}.\n")
                append("Command run: ${p.command}\n")
                append("Output (tail):\n")
                append(p.outputTail())
            }
        }
    }

    @McpTool
    @McpDescription(
        """
        |Starts a task: creates the VCS changelist "Claude: <title>" and makes it the active one, so every file changed from
        |now on lands there and the user can review and commit the task on its own. Call it before editing files for a new
        |task; call handoff_end_task when the task is done.
        """
    )
    suspend fun handoff_begin_task(
        @McpDescription("Short task title, a few words (e.g. \"Fix login redirect\").")
        title: String,
    ): String {
        val project = currentCoroutineContext().project
        val name = TaskChangelists.getInstance(project).begin(title)
        return "Changelist \"$name\" is now active. Call handoff_end_task when the task is done."
    }

    @McpTool
    @McpDescription(
        """
        |Ends the task started with handoff_begin_task: makes the previously active changelist active again and returns
        |the files changed in the task's changelist.
        """
    )
    suspend fun handoff_end_task(): String {
        val project = currentCoroutineContext().project
        val (name, files) = TaskChangelists.getInstance(project).end()
            ?: return "No task is active."
        return "Task changelist \"$name\" closed, ${files.size} changed file(s)" +
            (if (files.isEmpty()) "." else ":\n" + files.joinToString("\n"))
    }

    @McpTool
    @McpDescription(
        """
        |Shows images to the user in the IDE's Handoff tool window and asks a question about them: one image to review
        |(the user approves it or dismisses it with a note and can mark areas on it), or several variants to choose from (e.g. icon or layout
        |proposals). Prefer this over opening comparison images. Returns an id; call handoff_wait_for_result with it.
        """
    )
    suspend fun handoff_show_images(
        @McpDescription("Image files (PNG, JPEG, GIF), absolute or relative to the project root. One to review, several to choose from.")
        paths: List<String>,
        @McpDescription("Question for the user, one sentence (e.g. \"Which icon variant should we use?\").")
        question: String,
    ): String {
        val project = currentCoroutineContext().project
        val base = project.basePath ?: System.getProperty("user.home")
        val files = paths.map { if (File(it).isAbsolute) File(it) else File(base, it).normalize() }
        files.firstOrNull { !it.isFile }?.let { return "File not found: ${it.path}" }
        if (files.isEmpty()) return "No images given."
        val p = HandoffService.getInstance(project).proposeImages(files.map { it.path }, question.trim())
        ApplicationManager.getApplication().invokeLater({
            ToolWindowManager.getInstance(project).getToolWindow("Handoff")?.show()
        }, project.disposed)
        return "Shown as ${p.id}. Call handoff_wait_for_result(id=\"${p.id}\") to get the user's answer."
    }

    @McpTool
    @McpDescription(
        """
        |Asks the user one or more questions with options to pick from, as a card in the IDE's Handoff tool window - per
        |question one answer or several (multiSelect), plus a free-text field for an own answer. Several questions are
        |shown one after another (Back/Next). Prefer it over asking in the terminal when the questions or options are long.
        |Returns an id; call handoff_wait_for_result with it to get the chosen options and the user's own text per question.
        """
    )
    suspend fun handoff_ask(
        @McpDescription("The questions, shown one after another (Back/Next, Submit on the last). Usually one; several when they belong together.")
        questions: List<AskQuestion>,
        @McpDescription("Optional heading for the card, one short sentence (e.g. \"Which findings should I save?\"). Empty = the first question.")
        title: String = "",
    ): String {
        val project = currentCoroutineContext().project
        val list = questions.map { q -> q.copy(question = q.question.trim(), options = q.options.filter { it.label.isNotBlank() }) }
            .filter { it.options.isNotEmpty() }
        if (list.isEmpty()) return "No questions with options given."
        val p = HandoffService.getInstance(project).proposeAsk(title.trim(), list)
        ApplicationManager.getApplication().invokeLater({
            ToolWindowManager.getInstance(project).getToolWindow("Handoff")?.show()
        }, project.disposed)
        return "Asked as ${p.id}. Call handoff_wait_for_result(id=\"${p.id}\") to get the user's answers."
    }

    @McpTool
    @McpDescription(
        """
        |Asks the user to review the changes you made before you go on (or before you call the task done). The user reads
        |the diff in the IDE, can attach notes to individual lines and then approves or sends the notes back.
        |Reviews the changelist of the current or last task (handoff_begin_task), otherwise all local changes.
        |Returns an id; call handoff_wait_for_result with it to get the approval or the notes (path:line and text).
        """
    )
    suspend fun handoff_request_review(
        @McpDescription("One or two sentences for the user: what was changed and what to look at.")
        summary: String,
    ): String {
        val project = currentCoroutineContext().project
        val p = HandoffService.getInstance(project).proposeReview(summary.trim(), TaskChangelists.getInstance(project).prepareReview())
        ApplicationManager.getApplication().invokeLater({
            ToolWindowManager.getInstance(project).getToolWindow("Handoff")?.show()
        }, project.disposed)
        return "Review requested as ${p.id}. Call handoff_wait_for_result(id=\"${p.id}\") to get the user's answer."
    }

    @McpTool
    @McpDescription(
        """
        |Returns the notes the user attached to lines of code in the IDE (path:line, the note and the line it is attached
        |to) and removes them there. Call it when the user says they left notes or comments for you in the editor or diff.
        """
    )
    suspend fun handoff_get_notes(): String {
        val project = currentCoroutineContext().project
        val notes = withContext(Dispatchers.EDT) { NotesService.getInstance(project).take() }
        return notes?.let { "Notes from the user (path:line, the note, then the line it is attached to):\n$it" } ?: "No open notes."
    }
}
