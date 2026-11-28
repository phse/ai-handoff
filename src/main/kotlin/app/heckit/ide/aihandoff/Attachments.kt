package app.heckit.ide.aihandoff

import com.intellij.ide.util.PropertiesComponent
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * Dateien einer Session, je Datei einmal: was Claude angelegt, geändert oder in Antworten verlinkt hat, und was der Nutzer
 * mitgegeben hat (per @, eingefügte Bilder, Pfade im Prompt). Von der IDE automatisch mitgeschickte Dateien (offen,
 * markiert, bearbeitet) zählen nicht dazu.
 */
object Attachments {
    enum class How(val label: String, val byClaude: Boolean) {
        CREATED("written", true), EDITED("edited", true), LINKED("linked", true),
        MENTIONED("@ attached", false), IMAGE("pasted image", false), PROMPT("in prompt", false)
    }
    class Mention(val index: Int, val how: How, val time: Long?)
    class Item(val path: String, val mentions: List<Mention>) {
        val byClaude get() = mentions.any { it.how.byClaude }
        val last get() = mentions.last()
        val isImage get() = mentions.any { it.how == How.IMAGE }
    }

    private val QUOTED = Regex("\"((?:/|~/)[^\"\\n]+)\"")
    private val PLAIN = Regex("(?<![\\w/.])(?:/|~/)[^\\s\"'`\\]\\[()<>*]+")
    private val MD_LINK = Regex("\\]\\(<?([^)>\\s]+)>?\\)")
    private val TOOL = Regex("^(Write|Edit|MultiEdit|NotebookEdit): (.+)$")
    private val tmp = listOfNotNull("/tmp/", "/private/tmp/", "/var/folders/", "/private/var/folders/",
        System.getProperty("java.io.tmpdir")?.trimEnd('/')?.plus("/"))

    /** Temp-Ordner und Scratchpads; eingefügte Bilder (liegen auch im Temp-Ordner) nicht. */
    fun isTemp(i: Item) = !i.isImage && (tmp.any { i.path.startsWith(it) } || "/scratchpad/" in i.path)

    fun collect(entries: List<TranscriptEntry>, base: String?): List<Item> {
        val home = System.getProperty("user.home")
        val byPath = LinkedHashMap<String, MutableList<Mention>>()
        var time: Long? = null
        fun norm(raw: String): String? {
            var p = raw.substringBefore('#').let { if (it.startsWith("file://")) it.removePrefix("file://") else it }
            p = java.net.URLDecoder.decode(p.replace("+", "%2B"), Charsets.UTF_8)
            p = p.replace(Regex(":\\d+(?::\\d+)?$"), "").trimEnd('.', ',', ';', ':')
            if (p.startsWith("~/")) p = home + p.substring(1)
            if (!p.startsWith("/")) { if (base == null || p.contains("://") || p.isBlank()) return null; p = "$base/$p" }
            val f = File(p)
            return if (f.isFile) f.normalize().absolutePath else null
        }
        fun add(raw: String, i: Int, how: How) {
            val p = norm(raw) ?: return
            val list = byPath.getOrPut(p) { mutableListOf() }
            // Mehrfach in einem Eintrag nur einmal.
            if (list.none { it.index == i }) list += Mention(i, how, time)
        }
        entries.forEachIndexed { i, e ->
            if (e.kind == TranscriptEntry.Kind.USER) e.time?.let { time = it }
            when (e.kind) {
                TranscriptEntry.Kind.FILE -> if (e.text == "Attached:") e.path?.let { add(it, i, How.MENTIONED) }
                TranscriptEntry.Kind.IMAGE -> e.path?.let { add(it, i, How.IMAGE) }
                TranscriptEntry.Kind.USER -> (QUOTED.findAll(e.text).map { it.groupValues[1] } + PLAIN.findAll(e.text).map { it.value })
                    .forEach { add(it, i, How.PROMPT) }
                TranscriptEntry.Kind.ASSISTANT -> (MD_LINK.findAll(e.text).map { it.groupValues[1] } + PLAIN.findAll(e.text).map { it.value })
                    .forEach { add(it, i, How.LINKED) }
                TranscriptEntry.Kind.TOOL -> TOOL.find(e.text)?.let { m ->
                    add(m.groupValues[2], i, if (m.groupValues[1] == "Write") How.CREATED else How.EDITED)
                }
                else -> {}
            }
        }
        return byPath.map { (p, m) -> Item(p, m) }.sortedByDescending { it.last.index }
    }
}

/**
 * Seitenleiste im Session-Reiter mit den Dateien der Session als Kacheln (wie die Artefakte in Claude Desktop): Vorschau
 * (Bild, Textanfang oder Dateisymbol), Name, wie und wann. Klick springt zur letzten Erwähnung im Verlauf (weitere Klicks
 * gehen weiter zurück), Doppelklick öffnet die Datei, Rechtsklick bietet mehr.
 */
internal class FilesPanel(
    private val project: Project,
    private val base: () -> String?,
    private val jump: (Int, String) -> Unit,
    private val close: () -> Unit,
) : JPanel(BorderLayout()) {
    override fun getMinimumSize() = java.awt.Dimension(JBUI.scale(120), 0)
    private enum class Filter(val label: String) { ALL("All"), CLAUDE("Claude"), YOU("You") }

    private var items: List<Attachments.Item> = emptyList()
    private val props = PropertiesComponent.getInstance()
    private var filter = runCatching { Filter.valueOf(props.getValue(FILTER_KEY, "ALL")) }.getOrDefault(Filter.ALL)
    private val showTemp = JBCheckBox("Temp", props.getBoolean(TEMP_KEY, false)).apply {
        toolTipText = "Also show files in temp folders and scratchpads"
        isOpaque = false; font = JBUI.Fonts.smallFont()
        addActionListener { props.setValue(TEMP_KEY, isSelected, false); rebuild() }
    }
    private val filterButtons: Map<Filter, Chip> = Filter.entries.associateWith { f ->
        Chip(f.label) { filter = f; props.setValue(FILTER_KEY, f.name); filterButtonsSync(); rebuild() }.apply { selected = f == filter }
    }
    /** Dateiart: bei vielen Dateien z. B. nur Bilder zeigen. */
    private enum class Kind(val label: String) { ANY("Any type"), IMAGES("Images"), TEXT("Text & code"), OTHER("Other") }
    private var kind = runCatching { Kind.valueOf(props.getValue(KIND_KEY, "ANY")) }.getOrDefault(Kind.ANY)
    private fun kindOf(i: Attachments.Item): Kind = when {
        i.isImage || IMAGE_EXT.matches(i.path) || i.path.endsWith(".svg", true) -> Kind.IMAGES
        !FileTypeManager.getInstance().getFileTypeByFileName(File(i.path).name).isBinary -> Kind.TEXT
        else -> Kind.OTHER
    }
    private val kindButtons: Map<Kind, Chip> = Kind.entries.associateWith { k ->
        Chip(k.label) { kind = k; props.setValue(KIND_KEY, k.name); kindButtons.forEach { (x, b) -> b.selected = x == kind; b.repaint(); b.parent?.revalidate() }; rebuild() }
            .apply { selected = k == kind }
    }

    private fun filterButtonsSync() = filterButtons.forEach { (f, b) -> b.selected = f == filter; b.repaint(); b.parent?.revalidate() }

    private val day = SimpleDateFormat("d MMM HH:mm", java.util.Locale.ENGLISH)
    private val clock = SimpleDateFormat("HH:mm")
    private var stepPath: String? = null
    private var stepIndex = 0
    private val info = object : JBLabel() { override fun getMinimumSize() = java.awt.Dimension(0, preferredSize.height) }.apply {
        foreground = UIUtil.getContextHelpForeground(); font = JBUI.Fonts.smallFont(); border = JBUI.Borders.empty(4, 8)
    }
    private val grid = Grid()
    private val empty = JBLabel("", javax.swing.SwingConstants.CENTER).apply { foreground = UIUtil.getContextHelpForeground() }

    /** Vorschauen je Pfad und Änderungszeit, im Hintergrund erzeugt. */
    private val previews = java.util.concurrent.ConcurrentHashMap<String, Preview>()
    private class Preview(val stamp: Long, val image: java.awt.image.BufferedImage?, val lines: List<String>?)

    init {
        border = JBUI.Borders.customLineLeft(JBColor.border())
        // Zwei Zeilen als BoxLayout: alles senkrecht mittig auf einer Linie, rechts bündig ×  bzw. Temp (2026-11-27:
        // mit BorderLayout/FlowLayout saßen × und Temp versetzt und Temp wurde rechts abgeschnitten).
        fun row(lead: javax.swing.JComponent?, chips: List<Chip>, right: javax.swing.JComponent) = OverflowRow(lead, chips, right)
        val closeButton = com.intellij.ui.InplaceButton(com.intellij.openapi.ui.popup.IconButton("Hide files", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) { close() }
        showTemp.border = JBUI.Borders.empty(); showTemp.iconTextGap = JBUI.scale(3)
        add(object : JPanel() { override fun getMinimumSize() = java.awt.Dimension(0, preferredSize.height) }.apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(5, 8))
            add(row(JBLabel("Files").apply { font = JBUI.Fonts.label().asBold(); border = JBUI.Borders.emptyRight(8) }, filterButtons.values.toList(), closeButton))
            add(javax.swing.Box.createVerticalStrut(JBUI.scale(4)))
            add(row(null, kindButtons.values.toList(), showTemp))
        }, BorderLayout.NORTH)
        add(JBScrollPane(grid).apply { border = JBUI.Borders.empty(); horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER }, BorderLayout.CENTER)
        add(info, BorderLayout.SOUTH)
        info.text = "Click: show in conversation · double-click: open"
    }

    private fun act(text: String, run: () -> Unit) = object : DumbAwareAction(text) {
        override fun actionPerformed(e: AnActionEvent) = run()
    }

    private fun open(item: Attachments.Item) {
        if (File(item.path).exists()) SessionLinks.open(project, item.path, base()) else info.text = "${File(item.path).name} is no longer there."
    }

    /** Zur letzten Erwähnung springen, bei erneutem Klick auf dieselbe Datei zur davor (dann wieder von vorn). */
    private fun step(item: Attachments.Item) {
        val ms = item.mentions
        stepIndex = if (stepPath == item.path) (stepIndex - 1).let { if (it < 0) ms.size - 1 else it } else ms.size - 1
        stepPath = item.path
        val m = ms[stepIndex]
        jump(m.index, File(item.path).name)
        info.text = if (ms.size > 1) "Mention ${stepIndex + 1} of ${ms.size} (${m.how.label}) · click again for the one before"
            else m.how.label.replaceFirstChar { it.uppercase() }
        grid.repaint()
    }

    private fun menu(item: Attachments.Item, c: java.awt.Component, x: Int, y: Int) {
        val group = DefaultActionGroup()
        group.add(act("Open in Editor") { open(item) })
        group.add(act("Show Latest Mention") { stepPath = null; step(item) })
        if (item.mentions.size > 1) group.add(act("Show Earlier Mention") { step(item) })
        group.addSeparator()
        group.add(SessionLinks.menu(project, item.path, base()))
        com.intellij.openapi.actionSystem.ActionManager.getInstance().createActionPopupMenu("HandoffFiles", group).component.show(c, x, y)
    }

    /** Für die Kopfzeile: Zahl der Dateien ohne Temp-Dateien (unabhängig vom Filter). */
    val count get() = items.count { showTemp.isSelected || !Attachments.isTemp(it) }

    private fun visible() = items.filter { (showTemp.isSelected || !Attachments.isTemp(it)) &&
        when (filter) { Filter.ALL -> true; Filter.CLAUDE -> it.byClaude; Filter.YOU -> !it.byClaude } &&
        (kind == Kind.ANY || kindOf(it) == kind) }

    fun update(newItems: List<Attachments.Item>) {
        if (newItems.map { it.path to it.mentions.size } == items.map { it.path to it.mentions.size }) return
        items = newItems
        rebuild()
    }

    private fun rebuild() {
        val shown = visible()
        // Anzahl je Dateiart (bei aktuellem Herkunftsfilter) auf den Knöpfen.
        val base = items.filter { (showTemp.isSelected || !Attachments.isTemp(it)) &&
            when (filter) { Filter.ALL -> true; Filter.CLAUDE -> it.byClaude; Filter.YOU -> !it.byClaude } }
        kindButtons.forEach { (k, b) -> b.text = if (k == Kind.ANY) k.label else "${k.label} ${base.count { kindOf(it) == k }}"; b.revalidate() }
        grid.removeAll()
        shown.forEach { grid.add(Card(it)) }
        if (shown.isEmpty()) {
            val hidden = items.size - items.count { !Attachments.isTemp(it) }
            empty.text = if (items.isEmpty()) "No files yet" else if (!showTemp.isSelected && hidden > 0 && filter == Filter.ALL) "Only temp files ($hidden)" else "Nothing here"
            grid.add(empty)
        }
        grid.revalidate(); grid.repaint()
        loadPreviews(shown)
    }

    private fun loadPreviews(list: List<Attachments.Item>) {
        val todo = list.filter { File(it.path).lastModified().let { s -> previews[it.path]?.stamp != s } }
        if (todo.isEmpty()) return
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread {
            todo.forEach { item ->
                val f = File(item.path)
                val p = runCatching {
                    val img = if (IMAGE_EXT.matches(f.name)) javax.imageio.ImageIO.read(f) else null
                    val lines = if (img == null && !FileTypeManager.getInstance().getFileTypeByFileName(f.name).isBinary && f.length() < 5_000_000)
                        f.bufferedReader().useLines { s -> s.take(14).map { it.replace("\t", "  ").take(80) }.toList() } else null
                    Preview(f.lastModified(), img?.let { scaled(it) }, lines)
                }.getOrElse { Preview(f.lastModified(), null, null) }
                previews[item.path] = p
            }
            javax.swing.SwingUtilities.invokeLater { grid.repaint() }
        }
    }

    /** Auf höchstens 2× Kachelgröße verkleinern, damit nicht große Screenshots im Speicher bleiben. */
    private fun scaled(img: java.awt.image.BufferedImage): java.awt.image.BufferedImage {
        val max = JBUI.scale(THUMB_H) * 2.0
        val s = minOf(1.0, max * 1.6 / img.width, max / img.height)
        if (s >= 1.0) return img
        val w = (img.width * s).toInt().coerceAtLeast(1); val h = (img.height * s).toInt().coerceAtLeast(1)
        return java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB).also {
            it.createGraphics().apply {
                setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                drawImage(img, 0, 0, w, h, null); dispose()
            }
        }
    }

    private fun subtitle(o: Attachments.Item): String {
        val hows = o.mentions.map { it.how }.distinct().filter { it.byClaude == o.byClaude }
        val n = o.mentions.size
        val time = o.last.time?.let { if (System.currentTimeMillis() - it < 20 * 3600_000L) clock.format(Date(it)) else day.format(Date(it)) }
        return listOfNotNull(hows.joinToString(", ") { it.label }, "${n}×".takeIf { n > 1 }, time).joinToString(" · ")
    }

    /** Kachel: Vorschau oben, darunter Name und grau wie/wann. */
    private inner class Card(val item: Attachments.Item) : javax.swing.JComponent() {
        private var hover = false
        private var hoverOpen = false
        private val file = File(item.path)
        /** Öffnen-Knopf oben rechts (nur bei Hover sichtbar). */
        // Gleicher Knopf wie auf den Bildkarten im Handoff-Fenster (MoveToWindow, eckig mit Rundung 6).
        private fun openRect(): java.awt.Rectangle {
            val d = AllIcons.Actions.MoveToWindow.iconWidth + 2 * JBUI.scale(4)
            return java.awt.Rectangle(width - d - JBUI.scale(6), JBUI.scale(6), d, d)
        }
        private val name = if (item.isImage && file.parentFile?.name == "handoff-images") "Pasted image" else file.name
        private val sub = subtitle(item)

        init {
            toolTipText = item.path
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseEntered(e: java.awt.event.MouseEvent) { hover = true; repaint() }
                override fun mouseExited(e: java.awt.event.MouseEvent) { hover = false; hoverOpen = false; repaint() }
                override fun mouseMoved(e: java.awt.event.MouseEvent) {
                    val o = file.exists() && openRect().contains(e.point)
                    if (o != hoverOpen) { hoverOpen = o; toolTipText = if (o) "Open in editor" else item.path; repaint() }
                }
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    if (e.button != java.awt.event.MouseEvent.BUTTON1) return
                    if (file.exists() && openRect().contains(e.point)) { if (e.clickCount == 1) open(item); return }
                    if (e.clickCount == 2) open(item) else if (e.clickCount == 1) step(item)
                }
                override fun mousePressed(e: java.awt.event.MouseEvent) = popup(e)
                override fun mouseReleased(e: java.awt.event.MouseEvent) = popup(e)
                private fun popup(e: java.awt.event.MouseEvent) { if (e.isPopupTrigger) { menu(item, this@Card, e.x, e.y); e.consume() } }
            }.also { addMouseMotionListener(it) })
        }

        override fun getPreferredSize() = java.awt.Dimension(JBUI.scale(CARD_W), JBUI.scale(THUMB_H + 44))

        override fun paintComponent(g0: java.awt.Graphics) {
            val g = g0.create() as java.awt.Graphics2D
            try {
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                val arc = JBUI.scale(10)
                val w = width - 1; val h = height - 1
                val bg = UIUtil.getPanelBackground()
                val thumbBg = com.intellij.ui.ColorUtil.mix(bg, UIUtil.getLabelForeground(), 0.05)
                val shape = java.awt.geom.RoundRectangle2D.Float(0f, 0f, w.toFloat(), h.toFloat(), arc.toFloat(), arc.toFloat())
                g.color = if (hover) com.intellij.ui.ColorUtil.mix(bg, UIUtil.getLabelForeground(), 0.04) else bg
                g.fill(shape)
                // Vorschaufläche oben, an die runden Ecken angeschnitten.
                val th = JBUI.scale(THUMB_H)
                val old = g.clip
                g.clip(shape); g.clipRect(0, 0, width, th)
                g.color = thumbBg; g.fillRect(0, 0, width, th)
                val p = previews[item.path]
                when {
                    !file.exists() -> centerIcon(g, AllIcons.General.Warning, th)
                    p?.image != null -> {
                        // Füllend zuschneiden (cover), wie die Vorschauen in Claude Desktop.
                        val img = p.image
                        val s = maxOf(width.toDouble() / img.width, th.toDouble() / img.height)
                        val iw = (img.width * s).toInt(); val ih = (img.height * s).toInt()
                        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                        g.drawImage(img, (width - iw) / 2, (th - ih) / 2, iw, ih, null)
                    }
                    p?.lines != null && p.lines.any { it.isNotBlank() } -> {
                        g.font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, JBUI.scale(8))
                        g.color = com.intellij.ui.ColorUtil.withAlpha(UIUtil.getLabelForeground(), 0.55)
                        val lh = g.fontMetrics.height
                        p.lines.forEachIndexed { i, l -> g.drawString(l, JBUI.scale(8), JBUI.scale(8) + g.fontMetrics.ascent + i * lh) }
                        // Unten weich ausblenden.
                        g.paint = java.awt.GradientPaint(0f, th * 0.55f, com.intellij.ui.ColorUtil.withAlpha(thumbBg, 0.0), 0f, th.toFloat(), thumbBg)
                        g.fillRect(0, 0, width, th)
                    }
                    else -> {
                        val icon = if (item.isImage) AllIcons.FileTypes.Image else FileTypeManager.getInstance().getFileTypeByFileName(file.name).icon
                        centerIcon(g, com.intellij.util.IconUtil.scale(icon ?: AllIcons.FileTypes.Any_type, this, 2f), th)
                    }
                }
                g.clip = old
                g.color = JBColor.border(); g.drawLine(0, th, width, th)
                // Kennung Claude/du als kleiner Punkt oben links.
                if (item.byClaude) {
                    g.color = JBColor(0xD97757, 0xD97757)
                    val d = JBUI.scale(7); g.fillOval(JBUI.scale(7), JBUI.scale(7), d, d)
                }
                // Text: Name (gekürzt), darunter grau.
                val pad = JBUI.scale(8)
                g.font = JBUI.Fonts.label()
                g.color = if (file.exists()) UIUtil.getLabelForeground() else UIUtil.getContextHelpForeground()
                g.drawString(clip(g, name, width - 2 * pad), pad, th + pad + g.fontMetrics.ascent)
                val nameH = g.fontMetrics.height
                g.font = JBUI.Fonts.smallFont()
                g.color = UIUtil.getContextHelpForeground()
                g.drawString(clip(g, sub, width - 2 * pad), pad, th + pad + nameH + g.fontMetrics.ascent)
                // Rahmen; die zuletzt angesprungene Kachel hervorgehoben.
                g.color = if (stepPath == item.path) JBUI.CurrentTheme.Focus.focusColor() else if (hover) UIUtil.getContextHelpForeground() else JBColor.border()
                g.draw(shape)
                if (hover && file.exists()) {
                    val r = openRect()
                    g.color = JBColor.namedColor("Panel.background", UIUtil.getPanelBackground())
                    g.fillRoundRect(r.x, r.y, r.width, r.height, JBUI.scale(6), JBUI.scale(6))
                    g.color = JBColor.border()
                    g.drawRoundRect(r.x, r.y, r.width - 1, r.height - 1, JBUI.scale(6), JBUI.scale(6))
                    val icon = AllIcons.Actions.MoveToWindow
                    icon.paintIcon(this, g, r.x + (r.width - icon.iconWidth) / 2, r.y + (r.height - icon.iconHeight) / 2)
                }
            } finally { g.dispose() }
        }

        private fun centerIcon(g: java.awt.Graphics2D, icon: javax.swing.Icon, th: Int) =
            icon.paintIcon(this, g, (width - icon.iconWidth) / 2, (th - icon.iconHeight) / 2)

        private fun clip(g: java.awt.Graphics2D, s: String, max: Int): String {
            val fm = g.fontMetrics
            if (fm.stringWidth(s) <= max) return s
            var t = s
            while (t.isNotEmpty() && fm.stringWidth("$t…") > max) t = t.dropLast(1)
            return "$t…"
        }
    }

    /** Filter-Knopf: der aktive gefüllt und fett, die anderen nur Text (bei Hover leicht hinterlegt). */
    private class Chip(text: String, val onClick: () -> Unit) : JBLabel(text) {
        var selected = false
        private var hover = false
        init {
            border = JBUI.Borders.empty(2, 9)
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) = onClick()
                override fun mouseEntered(e: java.awt.event.MouseEvent) { hover = true; repaint() }
                override fun mouseExited(e: java.awt.event.MouseEvent) { hover = false; repaint() }
            })
        }
        override fun paintComponent(g0: java.awt.Graphics) {
            val g = g0.create() as java.awt.Graphics2D
            try {
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                val bg = UIUtil.getPanelBackground()
                if (selected || hover) {
                    g.color = if (selected) JBUI.CurrentTheme.ActionButton.pressedBackground() else com.intellij.ui.ColorUtil.mix(bg, UIUtil.getLabelForeground(), 0.06)
                    g.fillRoundRect(0, 0, width, height, height, height)
                }
                font = if (selected) JBUI.Fonts.smallFont().asBold() else JBUI.Fonts.smallFont()
                foreground = if (selected) UIUtil.getLabelForeground() else UIUtil.getContextHelpForeground()
            } finally { g.dispose() }
            super.paintComponent(g0)
        }
        override fun getPreferredSize(): java.awt.Dimension {
            // Breite für fett, damit nichts springt.
            val fm = getFontMetrics(JBUI.Fonts.smallFont().asBold())
            val i = insets
            return java.awt.Dimension(fm.stringWidth(text) + i.left + i.right, fm.height + i.top + i.bottom)
        }
    }

    /**
     * Zeile mit Filter-Chips: Was nicht passt, wandert in ein Menü hinter „More ▾“ (gefüllt, wenn der aktive Filter dort
     * steckt), rechts bleibt [right] immer sichtbar. Alles senkrecht mittig.
     */
    private class OverflowRow(private val lead: javax.swing.JComponent?, private val chips: List<Chip>, private val right: javax.swing.JComponent) : JPanel(null) {
        private val more: Chip = Chip("More ▾") { showMore() }
        private var hidden: List<Chip> = emptyList()
        init {
            isOpaque = false; alignmentX = LEFT_ALIGNMENT
            lead?.let { add(it) }; chips.forEach { add(it) }; add(more); add(right)
        }
        private fun showMore() {
            val menu = com.intellij.openapi.ui.JBPopupMenu()
            hidden.forEach { c ->
                menu.add(javax.swing.JCheckBoxMenuItem(c.text, c.selected).apply { addActionListener { c.onClick(); this@OverflowRow.revalidate(); this@OverflowRow.repaint() } })
            }
            menu.show(more, 0, more.height)
        }
        private fun h() = listOfNotNull(lead, more, right).plus(chips).maxOf { it.preferredSize.height }
        override fun getPreferredSize() = java.awt.Dimension(
            (listOfNotNull(lead, right) + chips).sumOf { it.preferredSize.width } + JBUI.scale(6), h())
        override fun getMinimumSize() = java.awt.Dimension(0, h())
        override fun getMaximumSize() = java.awt.Dimension(Int.MAX_VALUE, h())
        override fun doLayout() {
            val hh = height
            fun place(c: java.awt.Component, x: Int): Int { val d = c.preferredSize; c.setBounds(x, (hh - d.height) / 2, d.width, d.height); c.isVisible = true; return x + d.width }
            val rd = right.preferredSize
            right.setBounds(width - rd.width, (hh - rd.height) / 2, rd.width, rd.height)
            val limit = width - rd.width - JBUI.scale(6)
            var x = lead?.let { place(it, 0) } ?: 0
            val all = chips.sumOf { it.preferredSize.width }
            val fitsAll = x + all <= limit
            val room = if (fitsAll) limit else limit - more.preferredSize.width
            val out = mutableListOf<Chip>()
            for (c in chips) {
                if (out.isEmpty() && x + c.preferredSize.width <= room) x = place(c, x) else { c.isVisible = false; out += c }
            }
            hidden = out
            more.isVisible = out.isNotEmpty()
            if (more.isVisible) { more.selected = out.any { it.selected }; place(more, x) }
        }
    }

    /** Raster, das sich der Breite anpasst: so viele Spalten wie passen, Kacheln füllen die Zeile. */
    private class Grid : JPanel(null), javax.swing.Scrollable {
        private val gap get() = JBUI.scale(8)
        private fun cols(w: Int) = maxOf(1, (w - gap) / (JBUI.scale(CARD_W) + gap))
        override fun doLayout() {
            val cs = components
            if (cs.size == 1 && cs[0] !is Card) { cs[0].setBounds(0, gap * 3, width, cs[0].preferredSize.height); return }
            val n = cols(width)
            val cw = (width - gap * (n + 1)) / n
            cs.forEachIndexed { i, c ->
                val ch = c.preferredSize.height
                c.setBounds(gap + (i % n) * (cw + gap), gap + (i / n) * (ch + gap), cw, ch)
            }
        }
        override fun getPreferredSize(): java.awt.Dimension {
            val w = (parent?.width ?: 0).takeIf { it > 0 } ?: JBUI.scale(CARD_W * 2)
            val cs = components
            if (cs.isEmpty()) return java.awt.Dimension(w, 0)
            val rows = (cs.size + cols(w) - 1) / cols(w)
            return java.awt.Dimension(w, gap + rows * (cs[0].preferredSize.height + gap))
        }
        override fun getPreferredScrollableViewportSize() = preferredSize
        override fun getScrollableUnitIncrement(r: java.awt.Rectangle, o: Int, d: Int) = JBUI.scale(24)
        override fun getScrollableBlockIncrement(r: java.awt.Rectangle, o: Int, d: Int) = r.height
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }

    companion object {
        private const val TEMP_KEY = "handoff.files.showTemp"
        private const val FILTER_KEY = "handoff.files.filter"
        private const val KIND_KEY = "handoff.files.kind"
        private const val CARD_W = 150
        private const val THUMB_H = 92
        private val IMAGE_EXT = Regex("(?i).*\\.(png|jpe?g|gif|bmp|webp)$")
    }
}
