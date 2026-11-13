package app.heckit.ide.aihandoff

import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeModel

/**
 * Baum mit einer Kennzahl je Zeile fest am rechten Rand des sichtbaren Bereichs (Tokens o. Ä.). Gezeichnet über dem Text,
 * mit Hintergrund dahinter, damit lange Texte darunter verschwinden statt waagerecht zu scrollen.
 * [meta] liefert zum userObject einer Zeile den Text und ob er kräftig (Kennzahl) oder grau (Nebensache) erscheint.
 */
class MetaTree(model: TreeModel, private val meta: (Any?) -> Pair<String, Boolean>?) : Tree(model) {
    init {
        isRootVisible = false; showsRootHandles = true
        // Doppelklick öffnet nur; auf- und zuklappen über den Pfeil (bzw. ← →).
        toggleClickCount = 0
        // Keine aufgeklappte Vollzeile beim Darüberfahren - sie liefe unter den Kennzahlen weiter.
        setExpandableItemsEnabled(false)
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        val vis = visibleRect
        val pad = JBUI.scale(8)
        for (row in 0 until rowCount) {
            val b = getRowBounds(row) ?: continue
            if (b.y > vis.y + vis.height || b.y + b.height < vis.y) continue
            val (text, strong) = meta((getPathForRow(row).lastPathComponent as? DefaultMutableTreeNode)?.userObject) ?: continue
            if (text.isEmpty()) continue
            g2.font = if (strong) JBUI.Fonts.smallFont().deriveFont(Font.BOLD) else JBUI.Fonts.smallFont()
            val fm = g2.fontMetrics
            val w = fm.stringWidth(text)
            val x = vis.x + vis.width - w - pad
            val selected = isRowSelected(row)
            g2.color = if (selected) UIUtil.getTreeSelectionBackground(hasFocus()) else background
            g2.fillRect(x - pad, b.y, w + 2 * pad, b.height)
            g2.color = if (selected) UIUtil.getTreeSelectionForeground(hasFocus()) else if (strong) UIUtil.getLabelForeground() else UIUtil.getContextHelpForeground()
            g2.drawString(text, x, b.y + (b.height + fm.ascent - fm.descent) / 2)
        }
        g2.dispose()
    }
}
