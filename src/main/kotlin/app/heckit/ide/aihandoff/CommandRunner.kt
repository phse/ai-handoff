package app.heckit.ide.aihandoff

import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.util.Disposer
import com.intellij.terminal.TerminalExecutionConsoleBuilder
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PtyCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import java.nio.charset.StandardCharsets

/** Führt einen vorgeschlagenen Befehl in der Login-Shell des Nutzers aus, Ausgabe im Run-Fenster. */
object CommandRunner {
    fun run(project: Project, p: Proposal) {
        val service = HandoffService.getInstance(project)
        // Interaktive Login-Shell, damit PATH und Variablen aus .zshrc/.bashrc (z. B. JAVA_HOME) gelten. Mit PTY, damit
        // Rückfragen (Login, Passwort, y/n) im Run-Fenster beantwortet werden können und Passwörter verdeckt bleiben.
        val shell = System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/zsh"
        val cmd = PtyCommandLine(listOf(shell, "-l", "-i", "-c", p.command))
            .withWorkDirectory(p.workingDir)
            .withCharset(StandardCharsets.UTF_8)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)

        val handler = try {
            KillableColoredProcessHandler(cmd)
        } catch (e: Exception) {
            p.output.append("Could not start: ${e.message}\n")
            p.exitCode = -1
            p.state = Proposal.State.FINISHED
            p.done.complete(Unit)
            service.fireChanged()
            return
        }
        p.output.setLength(0)
        p.state = Proposal.State.RUNNING
        service.fireChanged()

        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (outputType != ProcessOutputTypes.SYSTEM) p.output.append(event.text)
            }

            override fun processTerminated(event: ProcessEvent) {
                p.exitCode = event.exitCode
                p.state = Proposal.State.FINISHED
                p.done.complete(Unit)
                // Kein Balloon bei Fehlschlag: Die Karte wird rot, das Run-Fenster zeigt die Ausgabe.
                service.fireChanged()
            }
        })

        // Terminal-Konsole statt normaler Konsole: zeigt Farben und Fortschrittsbalken richtig und blendet verdeckte
        // Eingaben (Passwörter) nicht ein - die normale Run-Konsole spiegelt jede Eingabe selbst.
        val console = TerminalExecutionConsoleBuilder(project).build().apply { attachToProcess(handler) }
        val descriptor = RunContentDescriptor(console, handler, console.component, "Handoff: " + p.command.take(40))
        Disposer.register(descriptor, console)
        RunContentManager.getInstance(project).showRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor)
        handler.startNotify()
    }
}
