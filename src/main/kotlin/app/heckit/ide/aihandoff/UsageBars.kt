package app.heckit.ide.aihandoff

import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.time.Duration
import java.time.Instant

/** Farben und Balken für Verbrauch (Kontext, Plan-Limits): grün, ab 60 % gelb, ab 85 % rot. */
object UsageBars {
    private val green = JBColor(0x5A9E6F, 0x6FBF85)
    private val amber = JBColor(0xC98A1A, 0xD9A23A)
    private val red = JBColor(0xC7222D, 0xE55765)

    fun color(pct: Double): Color = when { pct >= 85 -> red; pct >= 60 -> amber; else -> green }

    /** Balken als HTML-Tabelle (Swing-HTML kennt kein CSS für Breiten in %, aber td width und bgcolor). */
    fun html(pct: Double, width: Int = 120): String {
        val w = JBUI.scale(width)
        val filled = (w * pct.coerceIn(0.0, 100.0) / 100).toInt()
        val track = ColorUtil.toHtmlColor(ColorUtil.mix(UIUtil.getToolTipBackground(), UIUtil.getContextHelpForeground(), 0.3))
        val cell = { c: String, n: Int -> "<td bgcolor=\"$c\" width=\"$n\" style=\"font-size:4px\">&nbsp;</td>" }
        return "<table cellspacing=\"0\" cellpadding=\"0\" border=\"0\"><tr>" +
            (if (filled > 0) cell(ColorUtil.toHtmlColor(color(pct)), filled) else "") +
            (if (w - filled > 0) cell(track, w - filled) else "") + "</tr></table>"
    }

    /** "in 2 h 10 min" bis zum Zurücksetzen. */
    fun until(at: Instant): String {
        val m = Duration.between(Instant.now(), at).toMinutes().coerceAtLeast(0)
        return when { m < 60 -> "in $m min"; m < 48 * 60 -> "in ${m / 60} h ${m % 60} min"; else -> "in ${m / 1440} days" }
    }

    /** Flacher Balken mit runden Enden in der Farbe des Verbrauchs. */
    class Bar(private val pct: Double) : javax.swing.JComponent() {
        override fun getPreferredSize() = java.awt.Dimension(JBUI.scale(100), JBUI.scale(6))
        override fun paintComponent(g: java.awt.Graphics) {
            val g2 = g.create() as java.awt.Graphics2D
            try {
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                val h = height; val arc = h
                g2.color = ColorUtil.mix(UIUtil.getPanelBackground(), UIUtil.getContextHelpForeground(), 0.25)
                g2.fillRoundRect(0, 0, width, h, arc, arc)
                val w = (width * pct.coerceIn(0.0, 100.0) / 100).toInt()
                if (w > 0) { g2.color = color(pct); g2.fillRoundRect(0, 0, maxOf(w, h), h, arc, arc) }
            } finally { g2.dispose() }
        }
    }
}
