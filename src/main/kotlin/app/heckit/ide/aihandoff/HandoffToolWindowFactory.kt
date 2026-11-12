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
import javax.swing.Scrollable
import javax.swing.SwingUtilities
import java.awt.Rectangle
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon
import javax.swing.ImageIcon
import javax.swing.event.DocumentEvent
import javax.imageio.ImageIO
import java.awt.Image
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.WrapLayout

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
    // Folgt der Breite des Fensters, damit Karten (und die Bildreihen darin) umbrechen statt rechts abgeschnitten zu werden.
    private val list = object : JPanel(), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(r: Rectangle, o: Int, d: Int) = JBUI.scale(16)
        override fun getScrollableBlockIncrement(r: Rectangle, o: Int, d: Int) = r.height
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }.apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); border = JBUI.Borders.empty(8) }
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
        if (p.images.isNotEmpty()) return imageCard(p)
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
            font = UIUtil.getLabelFont()
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

    private val thumbs = HashMap<String, Icon?>()

    private fun thumb(path: String): Icon? = thumbs.getOrPut(path) {
        runCatching { ImageIO.read(File(path)) }.getOrNull()?.let { img ->
            val w = JBUI.scale(150)
            val h = (img.height * w.toDouble() / img.width).toInt().coerceAtMost(JBUI.scale(150))
            val sw = (img.width * h.toDouble() / img.height).toInt()
            ImageIcon(img.getScaledInstance(sw, h, Image.SCALE_SMOOTH))
        }
    }

    /** Bilder groß zeigen (Pfeiltasten blättern); solange die Karte offen ist, mit Rahmen und Notizen. */
    private fun openInEditor(path: String) {
        // Aus dem MouseListener ohne Write-Intent-Lock - deshalb per invokeLater.
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath(path) ?: return@invokeLater
            com.intellij.openapi.fileEditor.OpenFileDescriptor(project, vf).navigate(true)
        }
    }

    private fun viewer(p: Proposal, i: Int) {
        val editable = p.state == Proposal.State.PENDING
        val dialog = MarkupDialog(project, p.images, i, HashMap(p.marks), editable)
        if (dialog.showAndGet() && editable) {
            dialog.allMarks.forEach { (k, v) -> if (v.isEmpty()) p.marks.remove(k) else p.marks[k] = v.toList() }
            rebuild()
        }
    }

    /** Karte mit einem Bild zum Prüfen oder mehreren zur Auswahl. */
    private fun imageCard(p: Proposal): JPanel {
        val pending = p.state == Proposal.State.PENDING
        val accent = when (p.state) {
            Proposal.State.FINISHED -> JBColor(0x5A9E6F, 0x5A9E6F)
            Proposal.State.PENDING -> JBColor(0x5A5A8C, 0x8C8CC0)
            else -> JBColor.border()
        }
        val selectedColor = JBColor(0x2A7AA0, 0x5AA8D0)
        // Höhe hängt von der Breite ab (Bildreihen brechen um) - deshalb bei jedem Layout neu statt einmal festlegen.
        val card = object : JPanel(BorderLayout(0, JBUI.scale(6))) {
            override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply {
            border = JBUI.Borders.compound(RoundedLineBorder(accent, JBUI.scale(8), JBUI.scale(1)), JBUI.Borders.empty(8, 10))
            alignmentX = LEFT_ALIGNMENT
        }
        val single = p.images.size == 1
        val status = when (p.state) {
            Proposal.State.PENDING -> if (single) "Review image" else "Choose one of ${p.images.size}"
            Proposal.State.FINISHED -> if (single) "Approved" else "Chose ${(p.chosen ?: 0) + 1}"
            else -> "Dismissed"
        } + (p.note?.let { ": $it" } ?: "")
        card.add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JBLabel(status).apply { foreground = if (accent == JBColor.border()) UIUtil.getContextHelpForeground() else accent }, BorderLayout.WEST)
            add(JBLabel(time.format(Date(p.createdAt))).apply { foreground = UIUtil.getContextHelpForeground() }, BorderLayout.EAST)
        }, BorderLayout.NORTH)

        val grid = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(6))).apply { isOpaque = false }
        val choose = JButton(if (single) "Approve" else "Choose")
        var paintSelection: () -> Unit = {}
        val tiles = p.images.mapIndexed { i, path ->
            val name = File(path).name
            val count = p.marks[i]?.size ?: 0
            val caption = (if (single) name else "${i + 1} · $name") + (if (count > 0) " · $count marked" else "")
            val label = JBLabel(caption, thumb(path), JBLabel.CENTER).apply {
                verticalTextPosition = JBLabel.BOTTOM; horizontalTextPosition = JBLabel.CENTER
                font = JBUI.Fonts.smallFont()
            }
            // Beim Darüberfahren zeichnet die Kachel oben rechts ein Auge; ein Klick darauf öffnet das Bild groß im
            // Editor, ein Klick daneben wählt es aus. Selbst gezeichnet statt als Kind-Knopf: dessen Ein- und Ausblenden
            // per Enter/Exit blieb hängen (mehrere Augen gleichzeitig sichtbar).
            val eyeIcon = AllIcons.General.InspectionsEye
            val openIcon = AllIcons.Actions.MoveToWindow
            val tile = object : JPanel(BorderLayout()) {
                var hovered = false
                fun eyeBounds(): Rectangle {
                    val size = eyeIcon.iconWidth + 2 * JBUI.scale(4)
                    return Rectangle(width - insets.right - size - JBUI.scale(2), insets.top + JBUI.scale(2), size, size)
                }
                /** Links neben dem Auge: Bild im Editor öffnen. */
                fun openBounds(): Rectangle = eyeBounds().let { Rectangle(it.x - it.width - JBUI.scale(3), it.y, it.width, it.height) }
                private fun button(g2: Graphics2D, r: Rectangle, icon: Icon) {
                    g2.color = JBColor.namedColor("Panel.background", UIUtil.getPanelBackground())
                    g2.fillRoundRect(r.x, r.y, r.width, r.height, JBUI.scale(6), JBUI.scale(6))
                    g2.color = JBColor.border()
                    g2.drawRoundRect(r.x, r.y, r.width - 1, r.height - 1, JBUI.scale(6), JBUI.scale(6))
                    icon.paintIcon(this, g2, r.x + (r.width - icon.iconWidth) / 2, r.y + (r.height - icon.iconHeight) / 2)
                }
                override fun paintChildren(g: Graphics) {
                    super.paintChildren(g)
                    if (!hovered) return
                    val g2 = g.create() as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    button(g2, eyeBounds(), eyeIcon)
                    button(g2, openBounds(), openIcon)
                    g2.dispose()
                }
            }.apply {
                isOpaque = false
                add(label, BorderLayout.CENTER)
                border = JBUI.Borders.empty(4)
            }
            val mouse = object : MouseAdapter() {
                fun hover(on: Boolean) { if (tile.hovered != on) { tile.hovered = on; tile.repaint() } }
                override fun mouseEntered(e: MouseEvent) = hover(true)
                override fun mouseExited(e: MouseEvent) = hover(false)
                override fun mouseMoved(e: MouseEvent) {
                    hover(true)
                    tile.toolTipText = if (tile.openBounds().contains(e.point)) "Open in editor"
                        else if (!tile.eyeBounds().contains(e.point)) null
                        else if (pending) "View full size, mark areas for Claude (← → to switch images)" else "View full size"
                }
                override fun mouseClicked(e: MouseEvent) {
                    if (tile.openBounds().contains(e.point)) openInEditor(p.images[i])
                    else if (tile.eyeBounds().contains(e.point)) viewer(p, i)
                    else if (pending) { p.selected = i; paintSelection(); choose.isEnabled = true }
                }
            }
            tile.addMouseListener(mouse); tile.addMouseMotionListener(mouse)
            grid.add(tile)
            tile
        }
        paintSelection = {
            tiles.forEachIndexed { i, t ->
                val on = i == (if (pending) p.selected else p.chosen) && !(single && pending)
                t.border = if (on) JBUI.Borders.compound(RoundedLineBorder(selectedColor, JBUI.scale(6), JBUI.scale(2)), JBUI.Borders.empty(2))
                           else JBUI.Borders.empty(4)
                t.revalidate(); t.repaint()
            }
        }
        paintSelection()

        val center = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false
            val q = JBTextArea(p.reason).apply { font = UIUtil.getLabelFont(); isEditable = false; lineWrap = true; wrapStyleWord = true; isOpaque = false; border = null }
            listOf(q, grid).forEach { it.alignmentX = LEFT_ALIGNMENT; add(it); add(Box.createVerticalStrut(JBUI.scale(4))) }
        }
        card.add(center, BorderLayout.CENTER)

        if (pending) {
            val note = JBTextField(p.noteDraft).apply {
                emptyText.text = "Optional note for Claude"
                document.addDocumentListener(object : DocumentAdapter() {
                    override fun textChanged(e: DocumentEvent) { p.noteDraft = text }
                })
            }
            choose.isEnabled = p.selected != null
            choose.putClientProperty("JButton.buttonType", "default")
            choose.addActionListener { p.selected?.let { service.choose(p, it, note.text) } }
            val dismiss = JButton("Dismiss").apply { addActionListener { service.dismiss(p, note.text) } }
            card.add(JPanel(BorderLayout(0, JBUI.scale(6))).apply {
                isOpaque = false
                add(note, BorderLayout.NORTH)
                add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                    isOpaque = false; add(choose); add(Box.createHorizontalStrut(JBUI.scale(6))); add(dismiss)
                }, BorderLayout.SOUTH)
            }, BorderLayout.SOUTH)
        }
        return card
    }

    override fun dispose() {}
}
