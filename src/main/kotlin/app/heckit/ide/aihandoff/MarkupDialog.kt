package app.heckit.ide.aihandoff

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import com.intellij.ui.DocumentAdapter
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Ein markierter Bereich in Pixeln des Originalbilds, mit Notiz. */
data class Mark(val x: Int, val y: Int, val w: Int, val h: Int, var note: String = "")

/** Bild groß zeigen; der Nutzer zieht Rahmen auf und schreibt je Rahmen eine Notiz. */
class MarkupDialog(project: Project, title: String, private val image: BufferedImage, initial: List<Mark>) :
    DialogWrapper(project) {

    val marks = initial.map { it.copy() }.toMutableList()
    private val accent = JBColor(0xC75450, 0xE06C68)
    private val notes = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

    /** Maßstab Anzeige / Original: Bild passt in ca. 70 % des Bildschirms, wird aber nie vergrößert. */
    private val scale: Double = run {
        val screen = Toolkit.getDefaultToolkit().screenSize
        minOf(1.0, screen.width * 0.6 / image.width, screen.height * 0.65 / image.height)
    }

    private val canvas = object : JComponent() {
        var dragStart: Point? = null
        var dragNow: Point? = null

        init {
            preferredSize = Dimension((image.width * scale).roundToInt(), (image.height * scale).roundToInt())
            cursor = Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR)
            val mouse = object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) { dragStart = e.point; dragNow = e.point }
                override fun mouseDragged(e: MouseEvent) { dragNow = clamp(e.point); repaint() }
                override fun mouseReleased(e: MouseEvent) {
                    val a = dragStart ?: return
                    val b = clamp(e.point)
                    dragStart = null; dragNow = null
                    if (abs(a.x - b.x) >= 6 && abs(a.y - b.y) >= 6) {
                        marks.add(Mark(
                            (min(a.x, b.x) / scale).roundToInt(), (min(a.y, b.y) / scale).roundToInt(),
                            (abs(a.x - b.x) / scale).roundToInt(), (abs(a.y - b.y) / scale).roundToInt(),
                        ))
                        rebuildNotes(focusLast = true)
                    }
                    repaint()
                }
            }
            addMouseListener(mouse); addMouseMotionListener(mouse)
        }

        fun clamp(p: Point) = Point(p.x.coerceIn(0, width - 1), p.y.coerceIn(0, height - 1))

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g2.drawImage(image, 0, 0, width, height, null)
            g2.stroke = BasicStroke(JBUI.scale(2).toFloat())
            marks.forEachIndexed { i, m ->
                val r = Rectangle((m.x * scale).roundToInt(), (m.y * scale).roundToInt(), (m.w * scale).roundToInt(), (m.h * scale).roundToInt())
                g2.color = accent
                g2.drawRect(r.x, r.y, r.width, r.height)
                badge(g2, i + 1, r.x, r.y)
            }
            val a = dragStart; val b = dragNow
            if (a != null && b != null) {
                g2.color = accent
                g2.drawRect(min(a.x, b.x), min(a.y, b.y), abs(a.x - b.x), abs(a.y - b.y))
            }
            g2.dispose()
        }
    }

    private fun badge(g: Graphics2D, n: Int, x: Int, y: Int) {
        val d = JBUI.scale(18)
        g.color = accent
        g.fillOval(x - d / 2, y - d / 2, d, d)
        g.color = JBColor.WHITE
        g.font = UIUtil.getLabelFont().deriveFont(Font.BOLD, JBUI.scaleFontSize(11f).toFloat())
        val fm = g.fontMetrics
        val s = n.toString()
        g.drawString(s, x - fm.stringWidth(s) / 2, y + (fm.ascent - fm.descent) / 2)
    }

    private fun rebuildNotes(focusLast: Boolean = false) {
        notes.removeAll()
        var last: JBTextField? = null
        if (marks.isEmpty()) {
            notes.add(JBLabel("Drag on the image to mark an area.").apply { foreground = UIUtil.getContextHelpForeground() })
        }
        marks.forEachIndexed { i, m ->
            val field = JBTextField(m.note, 24).apply {
                emptyText.text = "What about this area?"
                document.addDocumentListener(object : DocumentAdapter() {
                    override fun textChanged(e: DocumentEvent) { m.note = text }
                })
            }
            val remove = JButton(AllIcons.Actions.Close).apply {
                toolTipText = "Remove mark"
                putClientProperty("JButton.buttonType", "square")
                addActionListener { marks.removeAt(i); rebuildNotes(); canvas.repaint() }
            }
            notes.add(JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
                border = JBUI.Borders.emptyBottom(6)
                add(JBLabel("${i + 1}").apply { foreground = accent; font = font.deriveFont(Font.BOLD) }, BorderLayout.WEST)
                add(field, BorderLayout.CENTER)
                add(remove, BorderLayout.EAST)
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            })
            last = field
        }
        notes.revalidate(); notes.repaint()
        if (focusLast) last?.requestFocusInWindow()
    }

    init {
        this.title = title
        setOKButtonText("Save Marks")
        rebuildNotes()
        init()
    }

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
        add(JBScrollPane(canvas).apply { border = JBUI.Borders.customLine(JBColor.border()) }, BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            preferredSize = Dimension(JBUI.scale(280), 0)
            add(JBLabel("Marks").apply { font = font.deriveFont(Font.BOLD); border = JBUI.Borders.emptyBottom(6) }, BorderLayout.NORTH)
            add(JBScrollPane(notes).apply { border = null }, BorderLayout.CENTER)
        }, BorderLayout.EAST)
    }

    companion object {
        fun describe(marks: List<Mark>): String = marks.mapIndexed { i, m ->
            "  ${i + 1}. x=${m.x}, y=${m.y}, w=${m.w}, h=${m.h}" + (m.note.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
        }.joinToString("\n")
    }
}
