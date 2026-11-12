package app.heckit.ide.aihandoff

import com.intellij.openapi.application.ApplicationManager
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel

class HandoffToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = HandoffPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "Commands", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
        toolWindow.setTitleActions(listOf(object : AnAction("Clear Finished", null, AllIcons.Actions.GC), DumbAware {
            override fun actionPerformed(e: AnActionEvent) = HandoffService.getInstance(project).clearFinished()
        }))
    }
}

private class HandoffPanel(private val project: Project) : JPanel(BorderLayout()), com.intellij.openapi.Disposable {
    private val service = HandoffService.getInstance(project)
    private val list = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); border = JBUI.Borders.empty(8) }
    private val time = SimpleDateFormat("HH:mm")

    init {
        add(JBScrollPane(list).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        service.addListener({ rebuild() }, this)
        rebuild()
    }

    private fun rebuild() {
        list.removeAll()
        if (service.proposals.isEmpty()) {
            list.add(JBLabel(
                "<html><b>No commands yet.</b><br><br>When Claude Code wants you to run a command yourself (deploy, login, release), " +
                    "it shows up here. Requires the IDE's MCP server (Settings | Tools | MCP Server) connected to Claude Code.</html>"
            ).apply { foreground = UIUtil.getContextHelpForeground(); alignmentX = LEFT_ALIGNMENT })
        }
        service.proposals.forEach {
            list.add(card(it))
            list.add(Box.createVerticalStrut(JBUI.scale(8)))
        }
        list.add(Box.createVerticalGlue())
        list.revalidate()
        list.repaint()
    }

    private fun card(p: Proposal): JPanel {
        val accent = when {
            p.state == Proposal.State.FINISHED && p.exitCode == 0 -> JBColor(0x5A9E6F, 0x5A9E6F)
            p.state == Proposal.State.FINISHED -> JBColor(0xC75450, 0xC75450)
            p.risky && p.state == Proposal.State.PENDING -> JBColor(0xB04079, 0xC86A9A)
            else -> JBColor.border()
        }
        val card = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            border = JBUI.Borders.compound(RoundedLineBorder(accent, JBUI.scale(8), JBUI.scale(1)), JBUI.Borders.empty(8, 10))
            alignmentX = LEFT_ALIGNMENT
        }
        val status = when (p.state) {
            Proposal.State.PENDING -> listOfNotNull("Waiting", "risky".takeIf { p.risky }, "needs input".takeIf { p.interactive }).joinToString(" · ")
            Proposal.State.RUNNING -> if (p.interactive) "Running · type input in the Run window" else "Running…"
            Proposal.State.FINISHED -> if (p.exitCode == 0) "Done" else "Failed (exit ${p.exitCode})"
            Proposal.State.DISMISSED -> "Dismissed" + (p.note?.let { ": $it" } ?: "")
        }
        val head = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JBLabel(status).apply { foreground = if (accent == JBColor.border()) UIUtil.getContextHelpForeground() else accent }, BorderLayout.WEST)
            add(JBLabel(time.format(Date(p.createdAt))).apply { foreground = UIUtil.getContextHelpForeground() }, BorderLayout.EAST)
        }
        val reason = JBTextArea(p.reason).apply {
            isEditable = false; lineWrap = true; wrapStyleWord = true; isOpaque = false; border = null
        }
        val editable = p.state == Proposal.State.PENDING
        val command = JBTextArea(p.command).apply {
            font = JBUI.Fonts.create(java.awt.Font.MONOSPACED, UIUtil.getLabelFont().size)
            lineWrap = true; isEditable = editable
            border = JBUI.Borders.empty(6)
            background = JBColor.namedColor("Editor.background", UIUtil.getTextFieldBackground())
        }
        val dir = JBLabel(p.workingDir.replace(System.getProperty("user.home"), "~")).apply {
            foreground = UIUtil.getContextHelpForeground(); font = JBUI.Fonts.smallFont()
        }
        val center = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false
            listOf(reason, command, dir).forEach { it.alignmentX = LEFT_ALIGNMENT; add(it); add(Box.createVerticalStrut(JBUI.scale(4))) }
        }
        card.add(head, BorderLayout.NORTH)
        card.add(center, BorderLayout.CENTER)
        if (editable) {
            // Swing-Listener laufen ohne Write-Intent-Lock; Run-Fenster und Terminal brauchen ihn - also per invokeLater.
            fun start(runner: (Project, Proposal) -> Unit) {
                p.command = command.text.trim()
                ApplicationManager.getApplication().invokeLater({ runner(project, p) }, project.disposed)
            }
            val run = JButton("Run", AllIcons.Actions.Execute).apply {
                toolTipText = "Runs in the Run tool window (type into it if the command asks for input); output goes back to Claude"
                addActionListener { start(CommandRunner::run) }
            }
            run.putClientProperty("JButton.buttonType", "default")
            val dismiss = JButton("Dismiss…").apply {
                addActionListener {
                    val note = Messages.showInputDialog(project, "Optional note for Claude:", "Dismiss Command", null)
                    if (note != null) service.dismiss(p, note)
                }
            }
            card.add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                listOf(run, dismiss).forEachIndexed { i, b -> if (i > 0) add(Box.createHorizontalStrut(JBUI.scale(6))); add(b) }
            }, BorderLayout.SOUTH)
        }
        card.maximumSize = Dimension(Int.MAX_VALUE, card.preferredSize.height)
        return card
    }

    override fun dispose() {}
}
