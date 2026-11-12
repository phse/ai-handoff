package app.heckit.ide.aihandoff

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.wm.ToolWindowManager
import kotlinx.coroutines.currentCoroutineContext
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
        val base = project.basePath ?: System.getProperty("user.home")
        val dir = when {
            workingDirectory.isBlank() -> base
            File(workingDirectory).isAbsolute -> workingDirectory
            else -> File(base, workingDirectory).normalize().path
        }
        val p = HandoffService.getInstance(project).propose(command.trim(), reason.trim(), dir, risky, interactive)
        ApplicationManager.getApplication().invokeLater({
            ToolWindowManager.getInstance(project).getToolWindow("Handoff")?.show()
        }, project.disposed)
        return "Proposed as ${p.id}. The user decides in the IDE. Call handoff_wait_for_result(id=\"${p.id}\") to get the result."
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
            Proposal.State.PENDING -> "pending: the user has not acted yet."
            Proposal.State.RUNNING -> "running: the command is still running.\nOutput so far:\n${p.outputTail(20)}"
            Proposal.State.DISMISSED -> "dismissed by the user." + (p.note?.let { " Note: $it" } ?: "")
            Proposal.State.FINISHED -> buildString {
                append("finished with exit code ${p.exitCode}.\n")
                append("Command run: ${p.command}\n")
                append("Output (tail):\n")
                append(p.outputTail())
            }
        }
    }
}
