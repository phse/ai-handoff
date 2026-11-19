package app.heckit.ide.aihandoff

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware

/**
 * „Open in Handoff“ im Kontextmenü des Terminals: die Claude-Code-Session dieses Reiters im Editor-Reiter zeigen. Ersetzt
 * das Tippen von `/show-in-editor` - das schob einen laufenden Befehl in den Hintergrund und Claude hielt an (2026-11-18).
 * Die Session wird über ihren Prozess gefunden (Hook-Datei `.pid`, Elternprozess = Shell des Reiters).
 */
class OpenInHandoffAction : AnAction(), DumbAware {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && SessionTerminals.available && SessionTerminals.shellPidOf(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val shell = SessionTerminals.shellPidOf(e) ?: return
        val sessions = WaitingSessions.getInstance(project)
        val info = sessions.sessionInShell(shell)
        if (info == null) {
            val why = if (!WaitingHook.isInstalled()) "Switch on \"Show When Claude Is Waiting\" in the Handoff tool window's menu first."
            else "Handoff knows only sessions started after its hooks were set up, in this project's folder."
            com.intellij.openapi.ui.Messages.showInfoMessage(project, "No Claude Code session found in this terminal tab. $why", "Open in Handoff")
            return
        }
        sessions.showInEditor(info)
    }
}
