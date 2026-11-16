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
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import com.intellij.ui.DocumentAdapter
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Ein markierter Bereich in Pixeln des Originalbilds, mit Notiz. */
data class Mark(var x: Int, var y: Int, var w: Int, var h: Int, var note: String = "")

/**
 * Bilder groß zeigen, bei mehreren per Pfeiltasten (bzw. Knöpfen) blättern. Ist [editable], zieht der Nutzer Rahmen auf
 * und schreibt je Rahmen eine Notiz.
 */
class MarkupDialog(
    project: Project,
    private val paths: List<String>,
    start: Int,
    initial: Map<Int, List<Mark>>,
    private val editable: Boolean,
    initialShapes: Map<Int, List<Shape>> = emptyMap(),
) : DialogWrapper(project) {

    /** Rahmen je Bild (Index in [paths]). */
    val allMarks: MutableMap<Int, MutableList<Mark>> =
        paths.indices.associateWith { i -> initial[i].orEmpty().map { it.copy() }.toMutableList() }.toMutableMap()
    /** Stand beim Öffnen - "Save" nur, wenn sich davon etwas unterscheidet. */
    private val original: Map<Int, List<Mark>> = allMarks.mapValues { (_, l) -> l.map { it.copy() } }
    /** Zeichnungen je Bild (Index in [paths]) - gehen als Bildkopie an Claude, nicht als Mark. */
    val allShapes: MutableMap<Int, MutableList<Shape>> =
        paths.indices.associateWith { i -> initialShapes[i].orEmpty().map { it.copy() }.toMutableList() }.toMutableMap()
    private val originalShapes: Map<Int, List<Shape>> = allShapes.mapValues { (_, l) -> l.map { it.copy() } }
    private val shapes: MutableList<Shape> get() = allShapes.getValue(index)
    private fun changed() = allMarks != original || allShapes != originalShapes
    /** Gewähltes Werkzeug: null = Bereiche markieren (Standard), sonst eine Zeichenart. */
    private var tool: ShapeKind? = null
    private var color = Drawing.COLORS[0].second
    private var strokeWidth = Drawing.WIDTHS[1].second
    /** Strich, der gerade gezogen wird. */
    private var drawing: Shape? = null
    private var index = start
    /** Stift-Werkzeug: für genau einen neuen Rahmen; endet nach dem Ziehen oder per Esc. */
    private var markMode = false
    /** Ausgewählter Rahmen (Griffe zum Verschieben/Größe ändern), Index in [marks]. */
    private var selected: Int? = null
    private val images = HashMap<Int, BufferedImage?>()
    private fun imageAt(i: Int) = images.getOrPut(i) { runCatching { javax.imageio.ImageIO.read(java.io.File(paths[i])) }.getOrNull() }
    private val image: BufferedImage get() = imageAt(index) ?: BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
    private val marks: MutableList<Mark> get() = allMarks.getValue(index)
    private val position = JBLabel().apply { font = font.deriveFont(Font.BOLD) }
    // IDE-Blau mit weißer Kontur, in beiden Themes gleich (Variante C, 07-rahmen-farben.png).
    private val accent = java.awt.Color(0x3574F0)
    private val onAccent = java.awt.Color.WHITE
    /** Platz fürs Bild: ca. 60 x 65 % des Bildschirms; Bilder werden darin eingepasst, aber nie vergrößert. */
    private val box = Toolkit.getDefaultToolkit().screenSize.let { Dimension((it.width * 0.6).toInt(), (it.height * 0.65).toInt()) }
    /** null = einpassen (Standard), sonst fester Faktor (1.0 = 1:1). */
    private var zoom: Double? = null
    private val viewport get() = canvasScroll.viewport.extentSize.takeIf { it.width > 0 } ?: box
    private val fitScale: Double get() = minOf(1.0, viewport.width.toDouble() / image.width, viewport.height.toDouble() / image.height)
    private val scale: Double get() = zoom ?: fitScale

    // Skaliertes Bild zwischenspeichern - bikubisch bei jedem Neuzeichnen (Ziehen eines Rahmens) ließ es stottern.
    private var scaledKey: Triple<Int, Int, Int>? = null
    private var scaled: java.awt.Image? = null
    private fun scaledImage(w: Int, h: Int): java.awt.Image {
        val key = Triple(index, w, h)
        if (key != scaledKey) {
            // Bilinear statt getScaledInstance(SMOOTH): das brauchte bei jeder Größenänderung des Fensters spürbar lange.
            scaled = if (w == image.width && h == image.height) image else BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).also {
                it.createGraphics().apply {
                    setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                    setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                    drawImage(image, 0, 0, w, h, null); dispose()
                }
            }
            scaledKey = key
        }
        return scaled!!
    }

    private val canvas = object : JComponent(), javax.swing.Scrollable {
        override fun getPreferredScrollableViewportSize() = box
        override fun getScrollableUnitIncrement(r: Rectangle, o: Int, d: Int) = JBUI.scale(24)
        override fun getScrollableBlockIncrement(r: Rectangle, o: Int, d: Int) = if (o == javax.swing.SwingConstants.VERTICAL) r.height else r.width
        // Einpassen: Fläche folgt dem sichtbaren Bereich; Zoom: Fläche so groß wie das Bild (mindestens Sichtbereich).
        override fun getScrollableTracksViewportWidth() = zoom == null || (parent?.width ?: 0) > preferredSize.width
        override fun getScrollableTracksViewportHeight() = zoom == null || (parent?.height ?: 0) > preferredSize.height
        var dragStart: Point? = null
        var dragNow: Point? = null

        init {
            layout = null
            isFocusable = true
            // Ziehen auf freier Fläche zeichnet einen Rahmen; Klick auf einen Rahmen wählt ihn aus, an den Eckgriffen ändert Ziehen
            // die Größe, verschoben wird über das Nummernfeld. Einen Stift-Knopf braucht es dafür nicht mehr.
            val mouse = object : MouseAdapter() {
                var grab: Point? = null      // Mauspunkt beim Drücken (Bildschirm-Pixel im Bild)
                var startMark: Mark? = null  // Rahmen beim Drücken
                var handle = -1              // -1 = verschieben, 0..3 = Ecke (oben links, oben rechts, unten rechts, unten links)

                override fun mousePressed(e: MouseEvent) {
                    requestFocusInWindow()
                    if (tool != null && editable) {
                        selected = null
                        val p = imagePoint(e.point)
                        // Strichstärke wie auf dem Bildschirm gezeigt, gespeichert in Bildpixeln (folgt dem Zoom).
                        drawing = Shape(tool!!, color, (com.intellij.ui.scale.JBUIScale.scale(strokeWidth) / scale).toFloat(),
                            mutableListOf(java.awt.geom.Point2D.Float((p.x / scale).toFloat(), (p.y / scale).toFloat())))
                        shapes.add(drawing!!)
                        repaint(); return
                    }
                    if (markMode) { dragStart = imagePoint(e.point); dragNow = dragStart; return }
                    if (!editable) return
                    val hit = hitTest(e.point)
                    // Freie Fläche: gleich einen neuen Rahmen aufziehen (ein Klick ohne Ziehen hebt nur die Auswahl auf).
                    if (hit == null) { selected = null; grab = null; startMark = null; dragStart = imagePoint(e.point); dragNow = dragStart; repaint(); return }
                    selected = hit?.first
                    handle = hit?.second ?: -1
                    // Verschieben geht über das Nummernfeld; im Rahmen selbst nur auswählen, an den Ecken skalieren.
                    grab = if (hit != null && handle >= 0) rawPoint(e.point) else null
                    startMark = hit?.let { marks[it.first].copy() }
                    repaint()
                }
                override fun mouseMoved(e: MouseEvent) {
                    if (tool != null && editable) { cursor = Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR); return }
                    if (markMode || !editable) return
                    val hit = hitTest(e.point)
                    cursor = Cursor.getPredefinedCursor(when (hit?.second) {
                        null -> Cursor.CROSSHAIR_CURSOR
                        -1 -> Cursor.DEFAULT_CURSOR
                        0 -> Cursor.NW_RESIZE_CURSOR; 1 -> Cursor.NE_RESIZE_CURSOR
                        2 -> Cursor.SE_RESIZE_CURSOR; else -> Cursor.SW_RESIZE_CURSOR
                    })
                }
                override fun mouseDragged(e: MouseEvent) {
                    drawing?.let { d ->
                        val p = imagePoint(e.point)
                        val q = java.awt.geom.Point2D.Float((p.x / scale).toFloat(), (p.y / scale).toFloat())
                        // Pfeile: nur Anfang und Ende - das Ende folgt der Maus.
                        if (d.kind != ShapeKind.PEN) { if (d.points.size > 1) d.points[1] = q else d.points.add(q); repaint(); return }
                        val last = d.points.last()
                        if (abs(last.x - q.x) * scale >= 1 || abs(last.y - q.y) * scale >= 1) { d.points.add(q); repaint() }
                        return
                    }
                    val a = dragStart
                    if (a == null) {
                        val g = grab ?: return; val s0 = startMark ?: return; val i = selected ?: return
                        val p = rawPoint(e.point)
                        val dx = ((p.x - g.x) / scale).roundToInt(); val dy = ((p.y - g.y) / scale).roundToInt()
                        val m = marks[i]
                        val min = (8 / scale).roundToInt().coerceAtLeast(2)
                        var l = s0.x; var t = s0.y; var rr = s0.x + s0.w; var b = s0.y + s0.h
                        when (handle) {
                            -1 -> { l += dx; rr += dx; t += dy; b += dy
                                val sx = (-l).coerceAtLeast(0) - (rr - image.width).coerceAtLeast(0)
                                val sy = (-t).coerceAtLeast(0) - (b - image.height).coerceAtLeast(0)
                                l += sx; rr += sx; t += sy; b += sy }
                            0 -> { l = (l + dx).coerceIn(0, rr - min); t = (t + dy).coerceIn(0, b - min) }
                            1 -> { rr = (rr + dx).coerceIn(l + min, image.width); t = (t + dy).coerceIn(0, b - min) }
                            2 -> { rr = (rr + dx).coerceIn(l + min, image.width); b = (b + dy).coerceIn(t + min, image.height) }
                            3 -> { l = (l + dx).coerceIn(0, rr - min); b = (b + dy).coerceIn(t + min, image.height) }
                        }
                        m.x = l; m.y = t; m.w = rr - l; m.h = b - t
                        revalidate(); repaint()
                        return
                    }
                    // Nur den Bereich des alten und neuen Rahmens neu zeichnen - nicht das ganze Bild.
                    val o = origin(); val pad = JBUI.scale(4)
                    val before = dragNow ?: a
                    dragNow = imagePoint(e.point)
                    val area = Rectangle(min(a.x, before.x), min(a.y, before.y), abs(a.x - before.x), abs(a.y - before.y))
                        .union(Rectangle(min(a.x, dragNow!!.x), min(a.y, dragNow!!.y), abs(a.x - dragNow!!.x), abs(a.y - dragNow!!.y)))
                    repaint(area.x + o.x - pad, area.y + o.y - pad, area.width + 2 * pad, area.height + 2 * pad)
                }
                override fun mouseReleased(e: MouseEvent) {
                    if (drawing != null) {
                        // Pfeil ohne Ziehen (nur geklickt) ergibt nichts Sichtbares - verwerfen statt als leere Zeichnung zählen.
                        drawing?.takeIf { it.kind != ShapeKind.PEN && it.points.size < 2 }?.let { shapes.remove(it); repaint() }
                        drawing = null; updateOkText(); return
                    }
                    if (grab != null) { grab = null; startMark = null; updateOkText(); return }
                    val a = dragStart ?: return
                    val b = imagePoint(e.point)
                    dragStart = null; dragNow = null
                    if (abs(a.x - b.x) >= 6 && abs(a.y - b.y) >= 6) {
                        marks.add(Mark(
                            (min(a.x, b.x) / scale).roundToInt(), (min(a.y, b.y) / scale).roundToInt(),
                            (abs(a.x - b.x) / scale).roundToInt(), (abs(a.y - b.y) / scale).roundToInt(),
                        ))
                        selected = marks.lastIndex
                        endMarkMode()
                        rebuildNotes(focusLast = true)
                    }
                    repaint()
                }
            }
            addMouseListener(mouse); addMouseMotionListener(mouse)
        }

        /** Notizzeilen unten links an ihren Rahmen setzen (in Bildschirm-Pixeln, folgt Zoom und Fenstergröße). */
        override fun doLayout() {
            val o = origin()
            rows.forEachIndexed { i, row ->
                val m = marks.getOrNull(i) ?: return@forEachIndexed
                val pref = row.preferredSize
                val x = (o.x + (m.x * scale).roundToInt() - JBUI.scale(1)).coerceIn(0, maxOf(0, width - pref.width))
                val y = (o.y + ((m.y + m.h) * scale).roundToInt() - JBUI.scale(1)).coerceIn(0, maxOf(0, height - pref.height))
                row.setBounds(x, y, pref.width, pref.height)
            }
        }

        override fun getPreferredSize(): Dimension =
            if (zoom == null) box else Dimension((image.width * scale).roundToInt(), (image.height * scale).roundToInt())

        /** Bild mittig in der Fläche; Mauspunkte werden auf Bildkoordinaten (angezeigte Größe) umgerechnet. */
        fun origin() = Point(maxOf(0, (width - (image.width * scale).roundToInt()) / 2), maxOf(0, (height - (image.height * scale).roundToInt()) / 2))
        /** Mauspunkt relativ zum Bild (angezeigte Größe), ohne Begrenzung. */
        fun rawPoint(p: Point): Point = origin().let { Point(p.x - it.x, p.y - it.y) }
        fun screenRect(m: Mark) = Rectangle((m.x * scale).roundToInt(), (m.y * scale).roundToInt(), (m.w * scale).roundToInt(), (m.h * scale).roundToInt())
        fun corners(r: Rectangle) = listOf(Point(r.x, r.y), Point(r.x + r.width, r.y), Point(r.x + r.width, r.y + r.height), Point(r.x, r.y + r.height))
        /** Treffer: (Index, Griff); Griffe nur am ausgewählten Rahmen, sonst Innenfläche (-1). Oberster Rahmen zuerst. */
        fun hitTest(p: Point): Pair<Int, Int>? {
            val q = rawPoint(p); val tol = JBUI.scale(6)
            selected?.let { i -> marks.getOrNull(i)?.let { m ->
                corners(screenRect(m)).forEachIndexed { c, pt -> if (abs(pt.x - q.x) <= tol && abs(pt.y - q.y) <= tol) return i to c }
            } }
            for (i in marks.indices.reversed()) if (screenRect(marks[i]).apply { grow(tol / 2, tol / 2) }.contains(q)) return i to -1
            return null
        }
        fun imagePoint(p: Point): Point {
            val o = origin()
            return Point((p.x - o.x).coerceIn(0, (image.width * scale).roundToInt() - 1), (p.y - o.y).coerceIn(0, (image.height * scale).roundToInt() - 1))
        }

        /** Eckgriffe nach den Notizzeilen zeichnen - sonst verdeckt das Nummernfeld den Griff unten links. */
        override fun paintChildren(g: Graphics) {
            super.paintChildren(g)
            val i = selected ?: return
            val m = marks.getOrNull(i) ?: return
            if (!editable) return
            val g2 = g.create() as Graphics2D
            val o = origin(); g2.translate(o.x, o.y)
            g2.stroke = BasicStroke(JBUI.scale(1).toFloat())
            val hs = JBUI.scale(8)
            corners(screenRect(m)).forEach { pt ->
                g2.color = JBColor.WHITE; g2.fillRect(pt.x - hs / 2, pt.y - hs / 2, hs, hs)
                g2.color = accent; g2.drawRect(pt.x - hs / 2, pt.y - hs / 2, hs, hs)
            }
            g2.dispose()
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            val o = origin()
            g2.translate(o.x, o.y)
            g2.drawImage(scaledImage((image.width * scale).roundToInt(), (image.height * scale).roundToInt()), 0, 0, null)
            shapes.forEach { Drawing.paint(g2, it, scale) }
            g2.stroke = BasicStroke(JBUI.scale(2).toFloat())
            marks.forEachIndexed { i, m ->
                val r = Rectangle((m.x * scale).roundToInt(), (m.y * scale).roundToInt(), (m.w * scale).roundToInt(), (m.h * scale).roundToInt())
                val label = rows.getOrNull(i)?.bounds?.let { Rectangle(it.x - o.x, it.y - o.y, it.width, it.height) }
                outlined(g2, r, label)
            }
            val a = dragStart; val b = dragNow
            if (a != null && b != null) {
                outlined(g2, Rectangle(min(a.x, b.x), min(a.y, b.y), abs(a.x - b.x), abs(a.y - b.y)))
            }
            g2.dispose()
        }
    }

    /** Rahmen in Akzentfarbe mit heller Kontur - bleibt auch auf dunklem, rotem oder buntem Grund sichtbar. */
    /** Die helle Kontur umschließt Rahmen und Etikett gemeinsam (Etikett schmiegt sich unten links an). */
    private fun outlined(g2: Graphics2D, r: Rectangle, label: Rectangle? = null) {
        // Als gefüllte, pixelgenaue Flächen ohne Antialiasing - eine Linie mit Antialiasing franst halbe Pixel aus und
        // passt dann nicht zum harten Etikett. Rahmen außen = r ± 1, Stärke 2; Etikett setzt an der Außenkante an.
        val t = JBUI.scale(2); val hw = JBUI.scale(1)
        fun grow(x: Rectangle, d: Int) = Rectangle(x.x - d, x.y - d, x.width + 2 * d, x.height + 2 * d)
        val outer = grow(r, t / 2)
        val inner = grow(outer, -t)
        val blue = java.awt.geom.Area(outer).apply { subtract(java.awt.geom.Area(inner)); label?.let { add(java.awt.geom.Area(it)) } }
        val halo = java.awt.geom.Area(grow(outer, hw)).apply {
            label?.let { add(java.awt.geom.Area(grow(it, hw))) }
            subtract(java.awt.geom.Area(grow(inner, -hw)))
            subtract(blue)
        }
        val g = g2.create() as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
        g.color = java.awt.Color(255, 255, 255, 200); g.fill(halo)
        g.color = accent; g.fill(blue)
        g.dispose()
    }

    /** Etiketten der Rahmen (siehe [rebuildNotes]). */
    private val rows = mutableListOf<JComponent>()

    /**
     * Je Rahmen ein Etikett unten links: Nummer, Notiz, Stift (bzw. × zum Leeren während der Eingabe) und Mülleimer.
     * Ohne Fokus ist die Notiz nur Text, und das ganze Etikett verschiebt den Rahmen; der Stift öffnet die Eingabe.
     */
    /** Icons auf dem Etikett weiß einfärben - die grauen Originale gehen auf der Akzentfarbe unter; in der Eingabe dunkel. */
    private fun darkIcon(icon: javax.swing.Icon) = tint(icon, onAccent)
    private fun fieldIcon(icon: javax.swing.Icon) = tint(icon, java.awt.Color(0x6C707E))

    /** Icon vollflächig in einer Farbe (Form aus dem Alpha) - IconUtil.colorize mischte nur und blieb grau. */
    private fun tint(icon: javax.swing.Icon, color: java.awt.Color) = object : javax.swing.Icon {
        override fun getIconWidth() = icon.iconWidth
        override fun getIconHeight() = icon.iconHeight
        override fun paintIcon(c: java.awt.Component?, g: Graphics, x: Int, y: Int) {
            val img = com.intellij.util.ui.ImageUtil.createImage(g, iconWidth, iconHeight, BufferedImage.TYPE_INT_ARGB)
            val ig = img.createGraphics()
            icon.paintIcon(c, ig, 0, 0)
            ig.composite = java.awt.AlphaComposite.SrcIn
            ig.color = color
            ig.fillRect(0, 0, iconWidth, iconHeight)
            ig.dispose()
            com.intellij.util.ui.StartupUiUtil.drawImage(g, img, x, y, null)
        }
    }

    private fun rebuildNotes(focusLast: Boolean = false) {
        rows.forEach { canvas.remove(it) }
        rows.clear()
        var lastEdit: (() -> Unit)? = null
        val h = JBUI.scale(24)
        val black = onAccent
        marks.forEachIndexed { i, m ->
            val number = JBLabel("${i + 1}", javax.swing.SwingConstants.CENTER).apply {
                foreground = black; font = font.deriveFont(Font.BOLD); preferredSize = Dimension(h, h)
            }
            // Innenabstand: die weiße Eingabe sitzt mit Rand im Etikett; helle Kontur wie beim Rahmen.
            // Eigener Malursprung: Neuzeichnen der Eingabe (Cursor-Blinken) läuft über die Zeile, die danach den Eckgriff
            // unten links wieder darüberlegt - sonst übermalt das Feld den Griff, den die Fläche zeichnet.
            val row = object : JPanel(BorderLayout()) {
                override fun isPaintingOrigin() = true
                override fun paintChildren(g: Graphics) {
                    super.paintChildren(g)
                    if (selected != i || !editable) return
                    val r = canvas.screenRect(m); val o = canvas.origin()
                    val hs = JBUI.scale(8)
                    val cx = o.x + r.x - x; val cy = o.y + r.y + r.height - y
                    val g2 = g.create() as Graphics2D
                    g2.color = JBColor.WHITE; g2.fillRect(cx - hs / 2, cy - hs / 2, hs, hs)
                    g2.color = accent; g2.drawRect(cx - hs / 2, cy - hs / 2, hs, hs)
                    g2.dispose()
                }
            }.apply {
                isOpaque = true; background = accent; add(number, BorderLayout.WEST)
                border = JBUI.Borders.empty(2)
            }
            if (!editable && m.note.isBlank()) { rows.add(row); canvas.add(row); return@forEachIndexed }

            val field = object : JBTextField(m.note) {
                // Ohne Eingabe fest schwarz - der LaF hellt den Text nicht editierbarer Felder sonst auf.
                override fun getForeground(): java.awt.Color? = if (isEditable) super.getForeground() else onAccent
                override fun getPreferredSize(): Dimension = super.getPreferredSize().let {
                    Dimension(it.width.coerceIn(JBUI.scale(160), JBUI.scale(420)), h)
                }
            }
            val clearOrEdit = JBLabel().apply {
                preferredSize = Dimension(h, h); isOpaque = true
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            }
            fun placeholder(c: java.awt.Color) {
                field.emptyText.clear().appendText("What about this area?",
                    com.intellij.ui.SimpleTextAttributes(com.intellij.ui.SimpleTextAttributes.STYLE_PLAIN, c))
            }
            fun idle() {
                field.isEditable = false; field.isFocusable = false
                field.isOpaque = false; field.border = JBUI.Borders.empty(0, 6)
                field.cursor = Cursor.getPredefinedCursor(if (editable) Cursor.MOVE_CURSOR else Cursor.DEFAULT_CURSOR)
                placeholder(java.awt.Color(255, 255, 255, 150))
                clearOrEdit.icon = darkIcon(AllIcons.Actions.Edit); clearOrEdit.toolTipText = "Edit note"; clearOrEdit.background = accent
                row.repaint()
            }
            fun edit() {
                field.isEditable = true; field.isFocusable = true
                field.isOpaque = true; field.background = java.awt.Color.WHITE
                field.foreground = java.awt.Color(0x1E1F22); field.caretColor = java.awt.Color(0x1E1F22)
                placeholder(java.awt.Color(0, 0, 0, 110))
                field.border = JBUI.Borders.empty(0, 6)
                field.cursor = Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
                // Das × sitzt in der Eingabe (gleicher Hintergrund) und leert den Text.
                clearOrEdit.icon = fieldIcon(AllIcons.Actions.Close); clearOrEdit.toolTipText = "Clear note"
                clearOrEdit.background = java.awt.Color.WHITE
                field.requestFocusInWindow()
                field.caretPosition = field.text.length
                row.repaint()
            }
            field.addFocusListener(object : java.awt.event.FocusAdapter() {
                override fun focusLost(e: java.awt.event.FocusEvent) { idle() }
            })
            field.document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) { m.note = field.text; updateOkText(); canvas.revalidate(); canvas.repaint() }
            })
            idle()
            row.add(field, BorderLayout.CENTER)

            if (editable) {
                clearOrEdit.addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) { if (field.isEditable) { field.text = ""; field.requestFocusInWindow() } else edit() }
                })
                val trash = JBLabel(darkIcon(AllIcons.Actions.GC)).apply {
                    toolTipText = "Remove mark"; preferredSize = Dimension(h, h)
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent) {
                            marks.removeAt(i); selected = null; rebuildNotes(); canvas.repaint(); canvas.requestFocusInWindow()
                        }
                    })
                }
                row.add(JPanel(BorderLayout()).apply { isOpaque = false; add(clearOrEdit, BorderLayout.WEST); add(trash, BorderLayout.EAST) }, BorderLayout.EAST)

                // Ziehen auf dem Etikett (Nummer oder Notiz ohne Eingabe) verschiebt den Rahmen.
                val drag = object : MouseAdapter() {
                    var from: Point? = null; var start: Mark? = null
                    override fun mousePressed(e: MouseEvent) {
                        if (field.isEditable && e.component == field) return
                        from = e.locationOnScreen; start = m.copy()
                        selected = i; canvas.repaint(); canvas.requestFocusInWindow()
                    }
                    override fun mouseDragged(e: MouseEvent) {
                        val f = from ?: return; val s0 = start ?: return
                        m.x = (s0.x + ((e.locationOnScreen.x - f.x) / scale).roundToInt()).coerceIn(0, maxOf(0, image.width - s0.w))
                        m.y = (s0.y + ((e.locationOnScreen.y - f.y) / scale).roundToInt()).coerceIn(0, maxOf(0, image.height - s0.h))
                        canvas.revalidate(); canvas.repaint()
                    }
                    override fun mouseReleased(e: MouseEvent) { if (from != null) { from = null; start = null; updateOkText() } }
                    // Doppelklick auf die Notiz: bearbeiten wie mit dem Stift.
                    override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 2 && !field.isEditable) edit() }
                }
                number.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                for (c in listOf(number, field, row)) { c.addMouseListener(drag); c.addMouseMotionListener(drag) }
                lastEdit = ::edit
            }
            rows.add(row)
            canvas.add(row)
        }
        canvas.revalidate(); canvas.repaint()
        updateOkText()
        if (focusLast) lastEdit?.let { e -> javax.swing.SwingUtilities.invokeLater { e() } }
    }

    /** "Save" nur bei Änderungen gegenüber dem Stand beim Öffnen, sonst "Close". */
    private fun updateOkText() {
        setOKButtonText(if (editable && changed()) "Save" else "Close")
        undo.isEnabled = shapes.isNotEmpty(); clear.isEnabled = shapes.isNotEmpty()
    }

    // Aktives Werkzeug deutlich gedrückt zeigen - der LaF zeichnet einen gewählten Umschaltknopf kaum anders.
    private val pen = object : javax.swing.JToggleButton(AllIcons.Actions.Edit) {
        // Nach dem LaF zeichnen (der malt seinen Hintergrund deckend und hat die Fläche vorher überdeckt).
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            if (isSelected) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = JBUI.CurrentTheme.Focus.focusColor().let { java.awt.Color(it.red, it.green, it.blue, 70) }
                val arc = JBUI.scale(8)
                g2.fillRoundRect(JBUI.scale(2), JBUI.scale(2), width - JBUI.scale(4), height - JBUI.scale(4), arc, arc)
                g2.color = JBUI.CurrentTheme.Focus.focusColor()
                g2.drawRoundRect(JBUI.scale(2), JBUI.scale(2), width - JBUI.scale(4) - 1, height - JBUI.scale(4) - 1, arc, arc)
                g2.dispose()
            }
        }
    }.apply {
        toolTipText = "Mark an area (drag on the image, Esc cancels)"
        isSelected = markMode
        addActionListener { setMarkMode(isSelected) }
    }

    private fun setMarkMode(on: Boolean) {
        markMode = on
        pen.isSelected = on
        canvas.cursor = Cursor.getPredefinedCursor(if (on) Cursor.CROSSHAIR_CURSOR else Cursor.DEFAULT_CURSOR)
        canvas.requestFocusInWindow()
    }

    private fun endMarkMode() {
        markMode = false
        pen.isSelected = false
        canvas.cursor = Cursor.getDefaultCursor()
    }

    /** Umschaltknopf mit deutlich gezeichnetem Gewählt-Zustand (der LaF zeigt ihn kaum). */
    private open inner class ToolToggle(icon: javax.swing.Icon?, tip: String) : javax.swing.JToggleButton(icon) {
        init { toolTipText = tip; isFocusable = false }
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            if (!isSelected) return
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = JBUI.CurrentTheme.Focus.focusColor().let { java.awt.Color(it.red, it.green, it.blue, 70) }
            val arc = JBUI.scale(8)
            g2.fillRoundRect(JBUI.scale(2), JBUI.scale(2), width - JBUI.scale(4), height - JBUI.scale(4), arc, arc)
            g2.color = JBUI.CurrentTheme.Focus.focusColor()
            g2.drawRoundRect(JBUI.scale(2), JBUI.scale(2), width - JBUI.scale(4) - 1, height - JBUI.scale(4) - 1, arc, arc)
            g2.dispose()
        }
    }

    /** Icon "Rechteck" für das Markier-Werkzeug. */
    private val markIcon = object : javax.swing.Icon {
        override fun getIconWidth() = JBUI.scale(16)
        override fun getIconHeight() = JBUI.scale(16)
        override fun paintIcon(c: java.awt.Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            g2.color = UIUtil.getLabelForeground(); g2.stroke = BasicStroke(com.intellij.ui.scale.JBUIScale.scale(1.5f))
            g2.drawRect(x + JBUI.scale(2), y + JBUI.scale(3), JBUI.scale(12), JBUI.scale(10))
            g2.dispose()
        }
    }

    private val markTool = ToolToggle(markIcon, "Mark areas with a note (drag on the image)").apply { addActionListener { selectTool(null) } }
    private val penTool = ToolToggle(AllIcons.Actions.Edit, "Pen: draw on the image (Esc back to marking)").apply { addActionListener { selectTool(ShapeKind.PEN) } }

    /** Pfeil-Icon für die Werkzeugleiste, mit Spitze an einem oder beiden Enden. */
    private fun arrowIcon(double: Boolean) = object : javax.swing.Icon {
        override fun getIconWidth() = JBUI.scale(16)
        override fun getIconHeight() = JBUI.scale(16)
        override fun paintIcon(c: java.awt.Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            g2.translate(x, y)
            Drawing.paint(g2, Shape(if (double) ShapeKind.DOUBLE_ARROW else ShapeKind.ARROW, UIUtil.getLabelForeground(), 1.5f,
                mutableListOf(java.awt.geom.Point2D.Float(2f, 14f), java.awt.geom.Point2D.Float(14f, 2f))), com.intellij.ui.scale.JBUIScale.scale(1f).toDouble())
            g2.dispose()
        }
    }
    private val arrowTool = ToolToggle(arrowIcon(false), "Arrow: drag from the tail to the tip (Esc back to marking)").apply { addActionListener { selectTool(ShapeKind.ARROW) } }
    private val doubleArrowTool = ToolToggle(arrowIcon(true), "Double arrow: drag from one end to the other (Esc back to marking)").apply { addActionListener { selectTool(ShapeKind.DOUBLE_ARROW) } }

    /** Farb- oder Stärkenwahl: kleiner Knopf, der sein Muster selbst zeichnet. */
    private inner class Swatch(tip: String, private val paintIt: (Graphics2D, Int, Int) -> Unit, private val isOn: () -> Boolean, onClick: () -> Unit) : JComponent() {
        init {
            toolTipText = tip; cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            preferredSize = Dimension(JBUI.scale(22), JBUI.scale(22))
            addMouseListener(object : MouseAdapter() { override fun mouseClicked(e: MouseEvent) { onClick(); swatches.forEach { it.repaint() } } })
        }
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            if (isOn()) {
                g2.color = JBUI.CurrentTheme.Focus.focusColor(); g2.stroke = BasicStroke(com.intellij.ui.scale.JBUIScale.scale(2f))
                g2.drawRoundRect(JBUI.scale(1), JBUI.scale(1), width - JBUI.scale(3), height - JBUI.scale(3), JBUI.scale(6), JBUI.scale(6))
            }
            paintIt(g2, width / 2, height / 2)
            g2.dispose()
        }
    }

    private val swatches = mutableListOf<Swatch>()
    private val colorSwatches = Drawing.COLORS.map { (name, c) ->
        Swatch(name, { g, cx, cy ->
            val r = JBUI.scale(7)
            g.color = c; g.fillOval(cx - r, cy - r, 2 * r, 2 * r)
            g.color = JBColor.border(); g.stroke = BasicStroke(1f); g.drawOval(cx - r, cy - r, 2 * r, 2 * r)
        }, { color == c }, { color = c; selectTool(tool ?: ShapeKind.PEN) }).also { swatches += it }
    }
    private val widthSwatches = Drawing.WIDTHS.map { (name, w) ->
        Swatch("$name line", { g, cx, cy ->
            val d = com.intellij.ui.scale.JBUIScale.scale(w).roundToInt().coerceAtLeast(2)
            g.color = UIUtil.getLabelForeground(); g.fillOval(cx - d / 2, cy - d / 2, d, d)
        }, { strokeWidth == w }, { strokeWidth = w; selectTool(tool ?: ShapeKind.PEN) }).also { swatches += it }
    }
    private val undo = JButton(AllIcons.Actions.Undo).apply { toolTipText = "Undo the last drawing (⌘Z)"; isFocusable = false; addActionListener { undoShape() } }
    private val clear = JButton(AllIcons.Actions.GC).apply {
        toolTipText = "Remove all drawings on this image"; isFocusable = false
        addActionListener { shapes.clear(); updateOkText(); canvas.repaint() }
    }
    /** Farbe und Stärke nur zeigen, solange gezeichnet wird. */
    private val penOptions = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, JBUI.scale(2), 0)).apply {
        add(javax.swing.Box.createHorizontalStrut(JBUI.scale(6)))
        colorSwatches.forEach { add(it) }
        add(javax.swing.Box.createHorizontalStrut(JBUI.scale(8)))
        widthSwatches.forEach { add(it) }
    }

    private fun selectTool(kind: ShapeKind?) {
        tool = kind
        markTool.isSelected = kind == null; penTool.isSelected = kind == ShapeKind.PEN
        arrowTool.isSelected = kind == ShapeKind.ARROW; doubleArrowTool.isSelected = kind == ShapeKind.DOUBLE_ARROW
        penOptions.isVisible = kind != null
        if (kind != null) { selected = null; endMarkMode() }
        canvas.cursor = Cursor.getPredefinedCursor(if (kind != null) Cursor.CROSSHAIR_CURSOR else Cursor.DEFAULT_CURSOR)
        swatches.forEach { it.repaint() }
        canvas.repaint(); canvas.requestFocusInWindow()
    }

    private fun undoShape() {
        if (shapes.isEmpty()) return
        shapes.removeAt(shapes.lastIndex); updateOkText(); canvas.repaint()
    }

    private val canvasScroll = JBScrollPane(canvas).apply { border = JBUI.Borders.customLine(JBColor.border()) }

    private fun zoomButton(icon: javax.swing.Icon, tip: String, action: () -> Unit) = JButton(icon).apply {
        toolTipText = tip; addActionListener { action(); applyZoom() }
    }
    private val zoomOut = zoomButton(AllIcons.General.ZoomOut, "Zoom out (−)") { zoom = maxOf(0.1, scale / 1.25) }
    private val zoomIn = zoomButton(AllIcons.General.ZoomIn, "Zoom in (+)") { zoom = minOf(8.0, scale * 1.25) }
    private val actual = zoomButton(AllIcons.General.ActualZoom, "Actual size 1:1 (1)") { zoom = 1.0 }
    private val fit = zoomButton(AllIcons.General.FitContent, "Fit (0)") { zoom = null }

    private fun applyZoom() {
        canvas.revalidate(); canvas.repaint()
        canvas.requestFocusInWindow()
    }

    private val prev = JButton(AllIcons.Actions.Back).apply { toolTipText = "Previous image (←)"; addActionListener { go(-1) } }
    private val next = JButton(AllIcons.Actions.Forward).apply { toolTipText = "Next image (→)"; addActionListener { go(1) } }

    private fun go(delta: Int) {
        if (paths.size < 2) return
        index = (index + delta + paths.size) % paths.size
        selected = null
        zoom = null; canvas.revalidate()
        showCurrent()
    }

    private fun showCurrent() {
        title = java.io.File(paths[index]).name
        position.text = if (paths.size > 1) "${index + 1} / ${paths.size} · ${java.io.File(paths[index]).name}" else java.io.File(paths[index]).name
        rebuildNotes()
        canvas.repaint()
    }

    init {
        init()
        selectTool(null)
        showCurrent()
        // Pfeiltasten per KeyEventDispatcher: Eine Belegung am rootPane kam nicht an, weil die Scrollpane um das Bild
        // ← → selbst belegt. Steht der Fokus in einem Textfeld, bleiben die Tasten beim Cursor.
        val arrows = java.awt.KeyEventDispatcher { e ->
            val owner = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
            // ⌘Z / Ctrl+Z: letzten Strich zurücknehmen (nicht im Notizfeld - dort gilt das Rückgängig des Felds).
            if (e.id == java.awt.event.KeyEvent.KEY_PRESSED && e.keyCode == java.awt.event.KeyEvent.VK_Z && editable &&
                (e.modifiersEx == java.awt.event.InputEvent.META_DOWN_MASK || e.modifiersEx == java.awt.event.InputEvent.CTRL_DOWN_MASK) &&
                owner != null && owner !is javax.swing.text.JTextComponent && javax.swing.SwingUtilities.getWindowAncestor(owner) == window
            ) { undoShape(); return@KeyEventDispatcher true }
            if (e.id != java.awt.event.KeyEvent.KEY_PRESSED || e.modifiersEx != 0 || owner == null ||
                javax.swing.SwingUtilities.getWindowAncestor(owner) != window
            ) return@KeyEventDispatcher false
            // Esc im Notizfeld nimmt nur den Fokus weg (dann gehen ← → wieder ans Blättern), schließt nicht.
            if (owner is javax.swing.text.JTextComponent) {
                // Esc und Enter beenden nur die Eingabe (Enter schließt sonst per Standardknopf den Dialog).
                if (e.keyCode != java.awt.event.KeyEvent.VK_ESCAPE && e.keyCode != java.awt.event.KeyEvent.VK_ENTER) return@KeyEventDispatcher false
                canvas.requestFocusInWindow(); return@KeyEventDispatcher true
            }
            when (e.keyCode) {
                java.awt.event.KeyEvent.VK_ESCAPE -> if (tool != null) { selectTool(null); true } else if (markMode) { endMarkMode(); true }
                    else if (selected != null) { selected = null; canvas.repaint(); true } else false
                java.awt.event.KeyEvent.VK_DELETE, java.awt.event.KeyEvent.VK_BACK_SPACE -> selected?.let { i ->
                    if (!editable) return@let false
                    marks.removeAt(i); selected = null; rebuildNotes(); canvas.repaint(); true
                } ?: false
                java.awt.event.KeyEvent.VK_PLUS, java.awt.event.KeyEvent.VK_ADD, java.awt.event.KeyEvent.VK_EQUALS -> { zoomIn.doClick(); true }
                java.awt.event.KeyEvent.VK_MINUS, java.awt.event.KeyEvent.VK_SUBTRACT -> { zoomOut.doClick(); true }
                java.awt.event.KeyEvent.VK_1 -> { actual.doClick(); true }
                java.awt.event.KeyEvent.VK_0 -> { fit.doClick(); true }
                java.awt.event.KeyEvent.VK_LEFT -> { go(-1); true }
                java.awt.event.KeyEvent.VK_RIGHT -> { go(1); true }
                else -> false
            }
        }
        java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(arrows)
        com.intellij.openapi.util.Disposer.register(disposable) {
            java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(arrows)
        }
    }

    // Esc schließt immer (Cancel-Aktion bleibt aktiv, auch wenn ihr Knopf fehlt); ungespeicherte Rahmen verwirft es.
    override fun createActions() = if (editable) arrayOf(okAction, cancelAction) else arrayOf(okAction)

    /** Esc/Cancel mit ungespeicherten Änderungen: nachfragen (Speichern / Verwerfen / Abbrechen). */
    override fun doCancelAction() {
        if (!editable || !changed()) return super.doCancelAction()
        when (com.intellij.openapi.ui.Messages.showYesNoCancelDialog(
            contentPane, "Save the changes to the marks and drawings?", "Unsaved Changes", "Save", "Discard", "Cancel",
            com.intellij.openapi.ui.Messages.getQuestionIcon(),
        )) {
            com.intellij.openapi.ui.Messages.YES -> doOKAction()
            com.intellij.openapi.ui.Messages.NO -> super.doCancelAction()
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = canvas

    override fun createCenterPanel(): JComponent = JPanel(BorderLayout(JBUI.scale(12), JBUI.scale(8))).apply {
        add(JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            add(position, BorderLayout.CENTER)
            if (editable) add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, JBUI.scale(2), 0)).apply {
                add(markTool); add(penTool); add(arrowTool); add(doubleArrowTool); add(penOptions)
                add(javax.swing.Box.createHorizontalStrut(JBUI.scale(8))); add(undo); add(clear)
                add(javax.swing.Box.createHorizontalStrut(JBUI.scale(12)))
            }, BorderLayout.WEST)
            add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, JBUI.scale(4), 0)).apply {
                add(zoomOut); add(zoomIn); add(actual); add(fit)
                add(javax.swing.Box.createHorizontalStrut(JBUI.scale(8)))
                if (paths.size > 1) { add(prev); add(next) }
            }, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(canvasScroll, BorderLayout.CENTER)
    }

    companion object {
        fun describeDrawing(i: Int, original: String, drawn: String) =
            "The user drew on image ${i + 1} ($original). Copy with the drawing on top (read it to see the drawing): $drawn"

        fun describe(marks: List<Mark>): String = marks.mapIndexed { i, m ->
            "  ${i + 1}. x=${m.x}, y=${m.y}, w=${m.w}, h=${m.h}" + (m.note.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
        }.joinToString("\n")
    }
}
