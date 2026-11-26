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
            SessionEditors.revealCards(project, p)
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
            SessionEditors.revealCards(project, p)
        }, project.disposed)
        return "Proposed as ${p.id} (${list.size} steps). The user decides in the IDE. Call handoff_wait_for_result(id=\"${p.id}\") to get the result."
    }

    @McpTool
    @McpDescription(
        """
        |Waits until the user has run or dismissed a command proposed with handoff_propose_command and returns the outcome:
        |the command as actually run (the user may have edited it), exit code and the last lines of output, or the user's note
        |when dismissed. Works the same for reviews, questions, checklists, images and secrets.
        |Returns "pending" after the timeout: then call it again right away and keep waiting until the result is there -
        |do not end your turn or move on while the user has not answered (they may need minutes, e.g. for manual tests).
        |Only if the user's own instructions (CLAUDE.md) allow moving on without waiting, follow those.
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
        val text = p.resultText()
        if (p.state == Proposal.State.FINISHED || p.state == Proposal.State.DISMISSED) p.fetched = true
        return text
    }

    @McpTool
    @McpDescription(
        """
        |Starts a task: creates the VCS changelist "Claude: <title>" and makes it the active one, so every file changed from
        |now on lands there and the user can review and commit the task on its own. Call it before editing files for a new
        |task; call handoff_end_task when the task is done. The task belongs to your session: other Claude sessions in the
        |same project keep their own tasks, and files you change with Edit/Write go to your changelist.
        """
    )
    suspend fun handoff_begin_task(
        @McpDescription("Short task title, a few words (e.g. \"Fix login redirect\").")
        title: String,
    ): String {
        val project = currentCoroutineContext().project
        val session = runCatching { Callers.session("handoff_begin_task", title.trim()) }.getOrNull()
        val name = TaskChangelists.getInstance(project).begin(title, session)
        return "Changelist \"$name\" is now active. Call handoff_end_task when the task is done."
    }

    @McpTool
    @McpDescription(
        """
        |Ends the task your session started with handoff_begin_task: makes the previously active changelist active again and
        |returns the files changed in the task's changelist. Tasks of other sessions keep running.
        """
    )
    suspend fun handoff_end_task(): String {
        val project = currentCoroutineContext().project
        val session = runCatching { Callers.session("handoff_end_task", null) }.getOrNull()
        val (name, files) = TaskChangelists.getInstance(project).end(session)
            ?: return if (session != null) "No task of this session is active." else "No task is active."
        return "Task changelist \"$name\" closed, ${files.size} changed file(s)" +
            (if (files.isEmpty()) "." else ":\n" + files.joinToString("\n"))
    }

    @McpTool
    @McpDescription(
        """
        |Shows images to the user in the IDE's Handoff tool window and asks a question about them: one image to review
        |(the user approves it or dismisses it with a note and can mark areas on it), or several variants to choose from (e.g. icon or layout
        |proposals). Prefer this over opening comparison images. Returns an id; call handoff_wait_for_result with it.
        |If the user drew on an image, the result names a copy with the drawing on top - read that file to see it.
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
            SessionEditors.revealCards(project, p)
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
            SessionEditors.revealCards(project, p)
        }, project.disposed)
        return "Asked as ${p.id}. Call handoff_wait_for_result(id=\"${p.id}\") to get the user's answers."
    }

    @McpTool
    @McpDescription(
        """
        |Hands the user a checklist for a manual test as a card in the IDE: one step per item, the user marks each as
        |passed or failed and can add a note. Use it instead of a numbered list in the chat whenever the user should try
        |something by hand (after a fix, before a release). Returns an id; call handoff_wait_for_result with it to get
        |the result per step.
        """
    )
    suspend fun handoff_checklist(
        @McpDescription("The steps in order, each one action or check.")
        items: List<CheckItem>,
        @McpDescription("Heading for the card, one short sentence (e.g. \"Test the new export\").")
        title: String = "",
    ): String {
        val project = currentCoroutineContext().project
        val list = items.map { it.copy(step = it.step.trim(), expected = it.expected.trim()) }.filter { it.step.isNotEmpty() }
        if (list.isEmpty()) return "No steps given."
        val p = HandoffService.getInstance(project).proposeChecklist(title.trim(), list)
        ApplicationManager.getApplication().invokeLater({ SessionEditors.revealCards(project, p) }, project.disposed)
        return "Checklist handed over as ${p.id}. Call handoff_wait_for_result(id=\"${p.id}\") to get the results."
    }

    @McpTool
    @McpDescription(
        """
        |Asks the user to type a secret (password, API token, key) into a field in the IDE. The plugin writes it into the
        |file itself - you never see the value. Use it instead of asking for secrets in the chat or telling the user to
        |edit the file. With key, the line KEY=value is replaced or added (.env, .properties, gradle.properties); without
        |key, the value becomes the whole file. Returns an id; call handoff_wait_for_result with it.
        """
    )
    suspend fun handoff_request_secret(
        @McpDescription("Target file, absolute or relative to the project root (e.g. .env).")
        file: String,
        @McpDescription("One sentence for the user: what to enter and where to get it (e.g. \"Your Marketplace token from plugins.jetbrains.com/author/me/tokens\").")
        label: String,
        @McpDescription("Variable name for a KEY=value line (e.g. API_TOKEN). Empty = the value becomes the whole file.")
        key: String = "",
    ): String {
        val project = currentCoroutineContext().project
        val base = project.basePath ?: System.getProperty("user.home")
        val target = (if (File(file).isAbsolute) File(file) else File(base, file)).normalize()
        if (target.isDirectory) return "That is a folder: ${target.path}"
        if (key.isNotEmpty() && !Regex("^[A-Za-z_][A-Za-z0-9_.\\-]*$").matches(key)) return "Invalid key: $key"
        val p = HandoffService.getInstance(project).proposeSecret(label.trim(), target, key.trim())
        ApplicationManager.getApplication().invokeLater({ SessionEditors.revealCards(project, p) }, project.disposed)
        return "Asked as ${p.id}. Call handoff_wait_for_result(id=\"${p.id}\") to learn whether the user entered it."
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
        val session = runCatching { Callers.session("handoff_request_review", summary.trim()) }.getOrNull()
        val tasks = TaskChangelists.getInstance(project)
        val p = HandoffService.getInstance(project).proposeReview(summary.trim(), tasks.prepareReview(session), tasks.otherTaskLists(session))
        ApplicationManager.getApplication().invokeLater({
            SessionEditors.revealCards(project, p)
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
