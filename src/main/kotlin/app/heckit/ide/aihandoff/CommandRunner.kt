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
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import java.nio.charset.StandardCharsets

/** Führt vorgeschlagene Befehle in der Login-Shell des Nutzers aus, Ausgabe im Run-Fenster. */
object CommandRunner {
    fun run(project: Project, p: Proposal) {
        if (p.steps.isNotEmpty()) return runSteps(project, p, all = true)
        val service = HandoffService.getInstance(project)
        exec(project, p.command, p.workingDir, "Handoff: " + p.command.take(40), p.output,
            started = { p.state = Proposal.State.RUNNING; service.fireChanged() },
            // Kein Balloon bei Fehlschlag: Die Karte wird rot, das Run-Fenster zeigt die Ausgabe.
            done = { code -> p.exitCode = code; p.state = Proposal.State.FINISHED; p.done.complete(Unit); service.fireChanged() })
    }

    /** Nächsten offenen Schritt eines Ablaufs ausführen; mit [all] danach die übrigen, bis einer fehlschlägt. */
    fun runSteps(project: Project, p: Proposal, all: Boolean) {
        val service = HandoffService.getInstance(project)
        val i = p.steps.indexOfFirst { it.exitCode == null }
        if (i < 0 || p.steps.any { it.running }) return
        val step = p.steps[i]
        exec(project, step.command, p.workingDir, "Handoff ${i + 1}/${p.steps.size}: " + step.command.take(40), step.output,
            started = { step.running = true; p.state = Proposal.State.RUNNING; service.fireChanged() },
            done = { code ->
                step.running = false
                step.exitCode = code
                when {
                    code != 0 || i == p.steps.lastIndex -> {
                        p.exitCode = code; p.state = Proposal.State.FINISHED; p.done.complete(Unit); service.fireChanged()
                    }
                    // Das Run-Fenster braucht den EDT; das Prozessende kommt aus einem Hintergrund-Thread.
                    all -> ApplicationManager.getApplication().invokeLater({ runSteps(project, p, true) }, project.disposed)
                    else -> { p.state = Proposal.State.PENDING; service.fireChanged() }
                }
            })
    }

    private fun exec(project: Project, command: String, workingDir: String, title: String, output: StringBuffer, started: () -> Unit, done: (Int) -> Unit) {
        // Interaktive Login-Shell, damit PATH und Variablen aus .zshrc/.bashrc (z. B. JAVA_HOME) gelten. Mit PTY, damit
        // Rückfragen (Login, Passwort, y/n) im Run-Fenster beantwortet werden können und Passwörter verdeckt bleiben.
        val shell = System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/zsh"
        val cmd = PtyCommandLine(listOf(shell, "-l", "-i", "-c", command))
            .withWorkDirectory(workingDir)
            .withCharset(StandardCharsets.UTF_8)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)

        output.setLength(0)
        val handler = try {
            KillableColoredProcessHandler(cmd)
        } catch (e: Exception) {
            output.append("Could not start: ${e.message}\n")
            done(-1)
            return
        }
        started()

        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (outputType != ProcessOutputTypes.SYSTEM) output.append(event.text)
            }

            override fun processTerminated(event: ProcessEvent) = done(event.exitCode)
        })

        // Terminal-Konsole statt normaler Konsole: zeigt Farben und Fortschrittsbalken richtig und blendet verdeckte
        // Eingaben (Passwörter) nicht ein - die normale Run-Konsole spiegelt jede Eingabe selbst.
        val console = TerminalExecutionConsoleBuilder(project).build().apply { attachToProcess(handler) }
        val descriptor = RunContentDescriptor(console, handler, console.component, title)
        Disposer.register(descriptor, console)
        RunContentManager.getInstance(project).showRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor)
        handler.startNotify()
    }
}
