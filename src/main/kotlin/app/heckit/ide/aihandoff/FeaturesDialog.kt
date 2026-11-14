package app.heckit.ide.aihandoff

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.ActionEvent
import javax.swing.AbstractAction
import javax.swing.Action
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Fehlende Handoff-Anweisungen auswählen und Claude bitten, sie in die CLAUDE.md zu übernehmen: direkt an eine fertige
 * Session (über den wartenden Stop-Hook) oder als Prompt in die Zwischenablage.
 */
class FeaturesDialog(private val project: Project, private val features: List<Features.Feature>) : DialogWrapper(project) {
    private val boxes = features.map { f ->
        JBCheckBox(f.title, true).apply { toolTipText = "<html><pre>${com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(f.text)}</pre></html>" }
    }
    private val target get() = WaitingSessions.getInstance(project).let { w -> w.sessions.firstOrNull { it.listening && it.id !in w.sent } }

    private val sendAction = object : AbstractAction("Send to Claude") {
        override fun actionPerformed(e: ActionEvent) {
            val s = target ?: return
            WaitingSessions.getInstance(project).reply(s, Features.prompt(selected()))
            close(OK_EXIT_CODE)
        }
    }
    private val copyAction = object : AbstractAction("Copy Prompt") {
        override fun actionPerformed(e: ActionEvent) {
            CopyPasteManager.getInstance().setContents(StringSelection(Features.prompt(selected())))
            close(OK_EXIT_CODE)
        }
    }

    init {
        title = "Add Handoff Instructions to CLAUDE.md"
        boxes.forEach { b -> b.addActionListener { update() } }
        update()
        init()
    }

    private fun selected() = features.filterIndexed { i, _ -> boxes[i].isSelected }

    private fun update() {
        val any = boxes.any { it.isSelected }
        sendAction.isEnabled = any && target != null
        copyAction.isEnabled = any
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
        // Feste Breite im HTML, dann berechnet das Label die Höhe für den Umbruch selbst (eine feste preferredSize schnitt ab).
        add(JBLabel("<html><div width='${JBUI.scale(460)}'>These instructions from the Handoff guide are not in the CLAUDE.md files " +
            "Claude Code loads for this project. Claude checks them against your files and adds the chosen ones.</div></html>"),
            BorderLayout.NORTH)
        add(JBScrollPane(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            boxes.forEach { add(it) }
        }).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        add(JBLabel(if (target != null) "Send to Claude goes to the Claude Code session that is done and waiting in this project."
            else "No Claude Code session is waiting in this project: copy the prompt and paste it into Claude Code.").apply {
            foreground = UIUtil.getContextHelpForeground(); font = JBUI.Fonts.smallFont()
        }, BorderLayout.SOUTH)
    }

    companion object {
        fun showMissing(project: Project) {
            val missing = Features.missing(project)
            if (missing.isEmpty()) com.intellij.openapi.ui.Messages.showInfoMessage(project, "Your CLAUDE.md already covers all Handoff tools.", "Handoff Instructions")
            else FeaturesDialog(project, missing).show()
        }
    }

    override fun createActions(): Array<Action> = arrayOf(sendAction, copyAction, cancelAction).also {
        sendAction.putValue(DEFAULT_ACTION, target != null)
        if (target == null) copyAction.putValue(DEFAULT_ACTION, true)
    }
}
