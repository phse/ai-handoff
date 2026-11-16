package app.heckit.ide.aihandoff

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.geom.Point2D
import java.awt.image.BufferedImage
import java.io.File

/**
 * Zeichenwerkzeuge im Bild-Dialog: Stift und Pfeil (einfach, beidseitig; Punkte = Anfang, Ende). Vorgesehen sind Rechteck
 * und Kreis (gefüllt oder Rahmen), Linie und Text - jeweils als eigene Art mit Punkten wie der Stift (Anfang/Ende bzw. Ankerpunkt).
 * Zeichnungen sind kein Mark: Sie gehen als Bildkopie an Claude, Marks als Koordinaten mit Notiz.
 */
enum class ShapeKind { PEN, ARROW, DOUBLE_ARROW }

/** Eine Zeichnung in Pixeln des Originalbilds: Punkte, Farbe, Strichstärke (Bildpixel). */
data class Shape(
    val kind: ShapeKind,
    val color: Color,
    val width: Float,
    val points: MutableList<Point2D.Float> = mutableListOf(),
    val filled: Boolean = false,
    val text: String = "",
) {
    fun copy(): Shape = Shape(kind, color, width, points.map { Point2D.Float(it.x, it.y) }.toMutableList(), filled, text)
}

object Drawing {
    /** Kleine Palette: kräftige Farben, die auf hellen wie dunklen Screenshots auffallen. */
    val COLORS = listOf(
        "Red" to Color(0xE5, 0x39, 0x35), "Yellow" to Color(0xFD, 0xD8, 0x35), "Green" to Color(0x43, 0xA0, 0x47),
        "Blue" to Color(0x1E, 0x88, 0xE5), "Black" to Color.BLACK, "White" to Color.WHITE,
    )
    /** Strichstärken in Bildschirmpixeln beim Zeichnen - umgerechnet auf Bildpixel, damit der Strich so dick wirkt wie gezeigt. */
    val WIDTHS = listOf("Thin" to 2f, "Medium" to 4f, "Thick" to 8f)

    /** [shape] zeichnen; [scale] = Bildschirmpixel je Bildpixel. */
    fun paint(g: Graphics2D, shape: Shape, scale: Double) {
        val pts = shape.points.takeIf { it.isNotEmpty() } ?: return
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g2.color = shape.color
        val w = (shape.width * scale).toFloat().coerceAtLeast(1f)
        when (shape.kind) {
            ShapeKind.PEN -> {
                if (pts.size == 1) {
                    val p = pts[0]
                    g2.fill(java.awt.geom.Ellipse2D.Double(p.x * scale - w / 2, p.y * scale - w / 2, w.toDouble(), w.toDouble()))
                } else {
                    g2.stroke = BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    g2.draw(Path2D.Double().apply {
                        moveTo(pts[0].x * scale, pts[0].y * scale)
                        for (i in 1 until pts.size) lineTo(pts[i].x * scale, pts[i].y * scale)
                    })
                }
            }
            ShapeKind.ARROW, ShapeKind.DOUBLE_ARROW -> {
                val a = pts.first(); val b = pts.last()
                val x1 = a.x * scale; val y1 = a.y * scale; val x2 = b.x * scale; val y2 = b.y * scale
                val len = Math.hypot(x2 - x1, y2 - y1)
                if (len < 1) { g2.dispose(); return }
                g2.stroke = BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                // Spitze wächst mit der Strichstärke, höchstens halb so lang wie der Pfeil; der Schaft endet vor der Spitze.
                val head = minOf(w * 4.0 + 8, len / 2)
                val ux = (x2 - x1) / len; val uy = (y2 - y1) / len
                val double = shape.kind == ShapeKind.DOUBLE_ARROW
                val sx = if (double) x1 + ux * head * 0.8 else x1; val sy = if (double) y1 + uy * head * 0.8 else y1
                g2.draw(java.awt.geom.Line2D.Double(sx, sy, x2 - ux * head * 0.8, y2 - uy * head * 0.8))
                fun tip(tx: Double, ty: Double, dx: Double, dy: Double) {
                    val bx = tx - dx * head; val by = ty - dy * head; val half = head * 0.5
                    val p = Path2D.Double().apply { moveTo(tx, ty); lineTo(bx - dy * half, by + dx * half); lineTo(bx + dy * half, by - dx * half); closePath() }
                    g2.fill(p); g2.stroke = BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); g2.draw(p)
                }
                tip(x2, y2, ux, uy)
                if (double) tip(x1, y1, -ux, -uy)
            }
        }
        g2.dispose()
    }

    /** Kopie des Bilds mit den Zeichnungen darauf (ohne Marks - die würden Bereiche verdecken). */
    fun render(image: BufferedImage, shapes: List<Shape>): BufferedImage {
        val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.drawImage(image, 0, 0, null)
        shapes.forEach { paint(g, it, 1.0) }
        g.dispose()
        return out
    }

    /**
     * Zeichnung als PNG im Temp-Ordner der IDE ablegen (das Original bleibt unverändert); liefert den Pfad. Jede Fassung
     * bekommt einen eigenen Namen, damit Claude nicht eine ältere aus dem Zwischenspeicher liest.
     */
    fun save(original: String, shapes: List<Shape>): String? = runCatching {
        val image = javax.imageio.ImageIO.read(File(original)) ?: return null
        val dir = File(com.intellij.openapi.application.PathManager.getTempPath(), "handoff-drawings").apply { mkdirs() }
        val file = File(dir, File(original).nameWithoutExtension + "-drawn-" + System.currentTimeMillis() + ".png")
        javax.imageio.ImageIO.write(render(image, shapes), "png", file)
        file.absolutePath
    }.getOrNull()
}
