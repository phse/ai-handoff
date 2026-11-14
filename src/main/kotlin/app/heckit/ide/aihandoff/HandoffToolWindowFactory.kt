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
    /**
     * Fenster-Icon per Datei-Adresse statt per Pfad in der plugin.xml. Ein pfadbasiertes Icon hängt an unserem Classloader,
     * und die IDE behält es nach einem Plugin-Update bis zu 10 min im Speicher für ausgegraute Icons (iconToDisabledIcon wird
     * beim Entladen nicht geleert, IJPL-252580, behoben ab 2027.1) - dann bleibt die alte Plugin-Version geladen. Ohne Classloader findet die IDE aber keine
     * Varianten mehr; deshalb wählen wir Größe (neue Oberfläche 16/20 px, alte 13 px) und hell/dunkel selbst.
     */
    /**
     * Icon als Datei im Temp-Ordner der IDE: Eine jar-URL lieferte nach einem Hot-Reload kein Bild mehr (die JAR wird unter
     * gleichem Namen ersetzt, der Zip-Cache der IDE kennt noch die alte). Die Datei-URL hängt weder an JAR noch Classloader.
     */
    private fun iconUrl(path: String): java.net.URL? = runCatching {
        val bytes = javaClass.getResourceAsStream(path)?.use { it.readBytes() } ?: return null
        val dir = java.io.File(com.intellij.openapi.application.PathManager.getTempPath(), "handoff-icons").apply { mkdirs() }
        // Name aus Inhalt, damit eine geänderte Grafik nicht aus einem Cache der alten Datei kommt.
        val file = java.io.File(dir, path.substringAfterLast('/').removeSuffix(".svg") + "-" + bytes.contentHashCode().toUInt().toString(16) + ".svg")
        if (!file.isFile) file.writeBytes(bytes)
        file.toURI().toURL()
    }.getOrNull()

    override fun init(toolWindow: ToolWindow) {
        fun update() {
            // Theme über den LafManager - JBColor.isBright() ist beim frühen init() noch nicht gesetzt (lieferte "dunkel").
            val isDark = com.intellij.ide.ui.LafManager.getInstance().currentUIThemeLookAndFeel?.isDark ?: !JBColor.isBright()
            val dark = if (isDark) "_dark" else ""
            val path = if (com.intellij.ui.NewUI.isEnabled()) {
                val size = JBUI.CurrentTheme.Toolbar.stripeToolbarButtonIconSize()
                if (size >= 20) "/icons/expui/toolwindow/handoff@20x20$dark.svg" else "/icons/expui/toolwindow/handoff$dark.svg"
            } else "/icons/handoff$dark.svg"
            val waiting = toolWindow.project.getServiceIfCreated(HandoffService::class.java)?.pendingCount ?: 0
            iconUrl(path)?.let { url -> com.intellij.openapi.util.IconLoader.findIcon(url, false) }?.let { icon ->
                // Punkt am Symbol, solange eine Übergabe auf den Nutzer wartet.
                toolWindow.setIcon(if (waiting > 0) com.intellij.ui.IconManager.getInstance().withIconBadge(icon, JBColor(0xB04079, 0xC86A9A)) else icon)
            }
        }
        update()
        HandoffService.getInstance(toolWindow.project).addListener({ update() }, toolWindow.disposable)
        NoteGutter.installAll()
        // Auch hier, falls das Plugin ohne Neustart geladen wurde (dann läuft die Startaktivität nicht).
        WaitingSessions.getInstance(toolWindow.project).start()
        val bus = ApplicationManager.getApplication().messageBus.connect(toolWindow.disposable)
        bus.subscribe(com.intellij.ide.ui.LafManagerListener.TOPIC, com.intellij.ide.ui.LafManagerListener { update() })
        bus.subscribe(com.intellij.ide.ui.UISettingsListener.TOPIC, com.intellij.ide.ui.UISettingsListener { update() })
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = HandoffPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "Commands", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
        // Zahl der wartenden Übergaben im Reiternamen.
        HandoffService.getInstance(project).addListener({
            val n = HandoffService.getInstance(project).pendingCount
            content.displayName = if (n > 0) "Commands ($n)" else "Commands"
        }, panel)
        val memory = MemoryPanel(project)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(memory, "Memory", false).apply { setDisposer(memory) })
        val usage = UsagePanel(project)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(usage, "Usage", false).apply { setDisposer(usage) })
        // Je Reiter passende Kopfzeilen-Aktion: Aufräumen bei den Befehlen, Neu einlesen beim Memory.
        // Usage beim Öffnen des Reiters neu einlesen (neue Antworten seit dem letzten Blick).
        toolWindow.contentManager.addContentManagerListener(object : com.intellij.ui.content.ContentManagerListener {
            override fun selectionChanged(event: com.intellij.ui.content.ContentManagerEvent) {
                if (event.operation == com.intellij.ui.content.ContentManagerEvent.ContentOperation.add && event.content.component === usage) usage.refresh()
            }
        })
        fun layoutToggle(text: String, get: () -> Boolean, set: (Boolean) -> Unit) =
            object : com.intellij.openapi.actionSystem.ToggleAction(text), DumbAware {
                override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.BGT
                override fun isSelected(e: AnActionEvent) = get()
                override fun setSelected(e: AnActionEvent, state: Boolean) { set(state); HandoffService.getInstance(project).fireChanged() }
            }
        toolWindow.setAdditionalGearActions(com.intellij.openapi.actionSystem.DefaultActionGroup(
            object : AnAction("Guide", "Setup, a CLAUDE.md prompt per feature and what's new", AllIcons.Actions.Help), DumbAware {
                override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) = Guide.open(project)
            },
            object : AnAction("Handoff Tools…", "Which tools Claude uses; switch the CLAUDE.md instruction or the permission per tool", AllIcons.General.Settings), DumbAware {
                override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) = usage.manageTools()
            },
            object : AnAction("Update CLAUDE.md Instructions…", "Lets Claude add the Handoff instructions your CLAUDE.md does not have yet", AllIcons.Ide.External_link_arrow), DumbAware {
                override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) = FeaturesDialog.showMissing(project)
            },
            com.intellij.openapi.actionSystem.Separator.create(),
            object : com.intellij.openapi.actionSystem.ToggleAction("Show When Claude Is Waiting", "Adds hooks to Claude Code that tell the IDE when a session is done or needs your input", null), DumbAware {
                override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.BGT
                override fun isSelected(e: AnActionEvent) = WaitingHook.isInstalled()
                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    if (state) {
                        val msg = "Handoff will create ~/.claude/handoff/hook.sh and add it as a hook for Stop, Notification, UserPromptSubmit and " +
                            "SessionEnd in ~/.claude/settings.json (a backup is kept as settings.json.bak-handoff). Existing hooks stay.\n\n" +
                            "When a Claude Code session in this project is done or needs your input, the Commands tab shows a card and, " +
                            "if the IDE is in the background, you get a system notification. Applies to sessions started afterwards."
                        if (Messages.showOkCancelDialog(project, msg, "Show When Claude Is Waiting", "Set Up", "Cancel", null) != Messages.OK) return
                    }
                    runCatching { if (state) WaitingHook.install() else WaitingHook.uninstall() }.onFailure {
                        Messages.showErrorDialog(project, it.message ?: it.toString(), "Show When Claude Is Waiting")
                    }
                }
            },
            layoutToggle("Pin Claude Messages to the Bottom", { Layout.pinClaudeMessages }, { Layout.pinClaudeMessages = it }),
            layoutToggle("Newest Cards on Top", { Layout.newestOnTop }, { Layout.newestOnTop = it }),
            com.intellij.openapi.actionSystem.Separator.create(),
        ))
        fun selected() = toolWindow.contentManager.selectedContent?.component
        fun isMemory() = selected() === memory || selected() === usage
        toolWindow.setTitleActions(listOf(
            object : AnAction("Clear Finished", null, AllIcons.Actions.GC), DumbAware {
                override fun update(e: AnActionEvent) { e.presentation.isVisible = !isMemory() }
                override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) = HandoffService.getInstance(project).clearFinished()
            },
            object : AnAction("Reload", null, AllIcons.Actions.Refresh), DumbAware {
                override fun update(e: AnActionEvent) { e.presentation.isVisible = isMemory() }
                override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) = if (selected() === usage) usage.refresh() else memory.refresh()
            },
        ))
    }
}

/** Anordnung im Commands-Reiter (für alle Projekte). */
object Layout {
    private const val NEWEST_TOP = "app.heckit.ide.aihandoff.newestOnTop"
    private const val PIN = "app.heckit.ide.aihandoff.pinClaudeMessages"
    var newestOnTop: Boolean
        get() = com.intellij.ide.util.PropertiesComponent.getInstance().getBoolean(NEWEST_TOP, false)
        set(v) = com.intellij.ide.util.PropertiesComponent.getInstance().setValue(NEWEST_TOP, v, false)
    /** „Claude is done/waiting“-Karten fest am unteren Rand statt in der Liste. */
    var pinClaudeMessages: Boolean
        get() = com.intellij.ide.util.PropertiesComponent.getInstance().getBoolean(PIN, true)
        set(v) = com.intellij.ide.util.PropertiesComponent.getInstance().setValue(PIN, v, true)
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
    private var lastNewest = 0L

    /** Feste Ablage am unteren Rand für Claudes Nachrichten (siehe [Layout.pinClaudeMessages]). */
    private val pinned = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.compound(JBUI.Borders.customLineTop(JBColor.border()), JBUI.Borders.empty(8, 8, 0, 8))
    }
    private val scroll = JBScrollPane(list).apply { border = JBUI.Borders.empty() }

    init {
        add(scroll, BorderLayout.CENTER)
        add(pinned, BorderLayout.SOUTH)
        // Nicht in Screenshot-Läufen: Dort gilt die Sandbox als "gerade aktualisiert".
        if (System.getProperty("handoff.screenshots") == null) Guide.pendingNews()?.let { (version, news) -> add(newsBanner(version, news), BorderLayout.NORTH) }
        service.addListener({ rebuild() }, this)
        rebuild()
    }

    private fun rebuild() {
        list.removeAll()
        val notes = NotesService.getInstance(project).all()
        val waiting = project.getServiceIfCreated(WaitingSessions::class.java)?.sessions.orEmpty()
        val pin = Layout.pinClaudeMessages
        val top = Layout.newestOnTop
        if (service.proposals.isEmpty() && notes.isEmpty() && (waiting.isEmpty() || pin)) list.add(emptyState())
        // Chronologisch, Richtung nach Einstellung (Standard: älteste oben, neueste unten wie in einem Chat).
        val items: List<Pair<Long, () -> JPanel>> =
            // Die Liste ist neueste zuerst; umgedreht bleibt die Reihenfolge bei gleicher Millisekunde richtig (stabile Sortierung).
            service.proposals.reversed().map { p -> p.createdAt to { card(p) } } + if (pin) emptyList() else waiting.map { w -> w.at to { waitingCard(w) } }
        val sorted = items.sortedBy { it.first }.let { if (top) it.reversed() else it }
        // Notizen ohne wartende Prüfung: eigene Karte, bis Claude sie abholt. Sonst stehen sie in der Prüfkarte.
        val notesCard = if (notes.isNotEmpty() && service.proposals.none { it.review && it.isPending }) notesCard(notes) else null
        if (top && notesCard != null) { list.add(notesCard); list.add(Box.createVerticalStrut(JBUI.scale(8))) }
        sorted.forEach { (_, make) ->
            list.add(make())
            list.add(Box.createVerticalStrut(JBUI.scale(8)))
        }
        if (!top && notesCard != null) { list.add(notesCard); list.add(Box.createVerticalStrut(JBUI.scale(8))) }
        list.add(Box.createVerticalGlue())
        list.revalidate()
        list.repaint()

        pinned.removeAll()
        if (pin) waiting.sortedBy { it.at }.let { if (top) it.reversed() else it }.forEach {
            pinned.add(waitingCard(it)); pinned.add(Box.createVerticalStrut(JBUI.scale(8)))
        }
        pinned.isVisible = pinned.componentCount > 0
        pinned.revalidate(); pinned.repaint()

        // Neue Karte: dorthin scrollen, wo die neuesten stehen.
        val newest = items.maxOfOrNull { it.first } ?: 0L
        if (newest > lastNewest) SwingUtilities.invokeLater {
            list.scrollRectToVisible(if (top) Rectangle(0, 0, 1, 1) else Rectangle(0, list.height - 1, 1, 1))
        }
        lastNewest = newest
    }

    private fun link(text: String, action: () -> Unit) =
        com.intellij.ui.components.ActionLink(text) { action() }.apply { alignmentX = LEFT_ALIGNMENT }

    /** Einrichtung, solange keine Karte da ist - so sieht man sie gleich nach der Installation. */
    private fun showFeatures() = FeaturesDialog.showMissing(project)

    /**
     * Leerer Reiter. Ist Handoff schon eingerichtet (die CLAUDE.md nennt Handoff-Werkzeuge), nur eine Zeile - die
     * Einrichtung braucht man dann nicht mehr dauernd zu sehen; fehlende neue Anweisungen als Link.
     */
    private fun emptyState(): JPanel {
        if (Features.mentioned(project).isEmpty()) return setupState()
        val missing = Features.missing(project)
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false; alignmentX = LEFT_ALIGNMENT
            add(grey("Nothing waiting.").apply { font = UIUtil.getLabelFont() })
            if (missing.isNotEmpty()) {
                add(Box.createVerticalStrut(JBUI.scale(6)))
                add(link(if (missing.size == 1) "1 new instruction for your CLAUDE.md…" else "${missing.size} new instructions for your CLAUDE.md…") { showFeatures() })
            }
        }
    }

    private fun setupState() = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false; alignmentX = LEFT_ALIGNMENT
        fun text(html: String) = JBLabel("<html>$html</html>").apply { alignmentX = LEFT_ALIGNMENT }
        add(text("<b>No commands yet.</b>"))
        add(Box.createVerticalStrut(JBUI.scale(6)))
        add(text("When Claude Code wants you to run a command yourself (deploy, login, release) or to review images, it shows up here.")
            .apply { foreground = UIUtil.getContextHelpForeground() })
        add(Box.createVerticalStrut(JBUI.scale(12)))
        add(text("<b>Setup</b>"))
        add(Box.createVerticalStrut(JBUI.scale(4)))
        add(text("1. Enable the IDE's MCP server."))
        add(link("Open MCP Server settings") { Guide.openMcpSettings(project) }.apply { border = JBUI.Borders.emptyLeft(14) })
        add(Box.createVerticalStrut(JBUI.scale(4)))
        add(text("2. Connect Claude Code: <i>Auto-Configure</i> on the same page, or <code>/mcp</code> in Claude Code."))
        add(Box.createVerticalStrut(JBUI.scale(4)))
        add(text("3. Tell Claude when to use Handoff: let Claude add the instructions to your CLAUDE.md."))
        add(link("Add Instructions to CLAUDE.md…") { showFeatures() }.apply { border = JBUI.Borders.emptyLeft(14) })
        add(link("Or copy the snippet and paste it yourself") {
            Guide.copySnippet()
            com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().createHtmlTextBalloonBuilder(
                "Copied. Paste it into ~/.claude/CLAUDE.md or the project's CLAUDE.md.", com.intellij.openapi.ui.MessageType.INFO, null
            ).setFadeoutTime(3000).createBalloon().show(
                com.intellij.ui.awt.RelativePoint.getSouthOf(this), com.intellij.openapi.ui.popup.Balloon.Position.below)
        }.apply { border = JBUI.Borders.emptyLeft(14) })
        add(Box.createVerticalStrut(JBUI.scale(12)))
        add(link("Show Full Guide") { Guide.open(project) })
    }

    /** Einzeiliger Hinweis nach einem Update, bis er weggeklickt oder die Anleitung geöffnet wird. */
    private fun newsBanner(version: String, news: String): JPanel {
        val banner = JPanel(BorderLayout(JBUI.scale(8), 0))
        fun close() { Guide.markNewsSeen(); remove(banner); revalidate(); repaint() }
        banner.border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(6, 10))
        banner.background = JBColor.namedColor("Banner.infoBackground", JBColor(0xF5F8FE, 0x25324D))
        banner.add(JBLabel("<html><b>New in $version:</b> ${com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(news.replace("**", "").replace("`", ""))}</html>"), BorderLayout.CENTER)
        banner.add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(8), 0)).apply {
            isOpaque = false
            if (Features.missing(project).isNotEmpty()) add(com.intellij.ui.components.ActionLink("Update CLAUDE.md…") { showFeatures() })
            add(com.intellij.ui.components.ActionLink("What's new") { Guide.openNews(project, version); close() })
            add(com.intellij.ui.components.ActionLink("Guide") { Guide.open(project); close() })
            add(com.intellij.ui.InplaceButton("Close", AllIcons.Actions.Close) { close() })
        }, BorderLayout.EAST)
        return banner
    }

    private fun cardPanel(accent: java.awt.Color) = object : JPanel(BorderLayout(0, JBUI.scale(6))) {
        override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)
    }.apply {
        border = JBUI.Borders.compound(RoundedLineBorder(accent, JBUI.scale(8), JBUI.scale(1)), JBUI.Borders.empty(8, 10))
        alignmentX = LEFT_ALIGNMENT
    }

    private fun wrapped(text: String) = JBTextArea(text).apply {
        font = UIUtil.getLabelFont(); isEditable = false; lineWrap = true; wrapStyleWord = true; isOpaque = false; border = null
        alignmentX = LEFT_ALIGNMENT
    }

    private fun grey(text: String) = JBLabel(text).apply {
        foreground = UIUtil.getContextHelpForeground(); font = JBUI.Fonts.smallFont(); alignmentX = LEFT_ALIGNMENT
    }

    /** Zeilennotizen als Liste: Klick springt zur Stelle. */
    private fun notesList(notes: List<LineNote>) = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false; alignmentX = LEFT_ALIGNMENT
        val base = project.basePath.orEmpty().trimEnd('/') + "/"
        notes.forEach { n ->
            val l = n.lines
            val where = n.file.path.removePrefix(base).substringAfterLast('/') + ":" + (l.first + 1)
            add(JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
                isOpaque = false; alignmentX = LEFT_ALIGNMENT
                add(link(where) { NotesService.getInstance(project).navigate(n) }.apply { toolTipText = n.file.path.removePrefix(base) }, BorderLayout.WEST)
                add(JBLabel(n.text.lineSequence().first()).apply { toolTipText = n.text }, BorderLayout.CENTER)
                maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            })
        }
    }

    /** Notizen, die noch niemand abgeholt hat. */
    private fun notesCard(notes: List<LineNote>): JPanel {
        val card = cardPanel(NotesService.COLOR)
        card.add(JBLabel(if (notes.size == 1) "1 note for Claude" else "${notes.size} notes for Claude").apply { foreground = NotesService.COLOR }, BorderLayout.NORTH)
        card.add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false
            add(notesList(notes))
            add(Box.createVerticalStrut(JBUI.scale(6)))
            add(wrapped("Tell Claude to read your notes; they also go along with the next review.").apply { foreground = UIUtil.getContextHelpForeground() })
        }, BorderLayout.CENTER)
        card.add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(JButton("Clear Notes").apply { addActionListener { NotesService.getInstance(project).clear() } })
        }, BorderLayout.SOUTH)
        return card
    }

    private fun changes(p: Proposal): List<com.intellij.openapi.vcs.changes.Change> {
        val clm = com.intellij.openapi.vcs.changes.ChangeListManager.getInstance(project)
        val list = p.changelist?.let { clm.findChangeList(it)?.changes } ?: clm.allChanges
        return list.sortedBy { (it.afterRevision ?: it.beforeRevision)?.file?.path.orEmpty() }
    }

    private fun showDiff(changes: List<com.intellij.openapi.vcs.changes.Change>, index: Int) {
        if (changes.isEmpty()) return
        // Aus dem Swing-Listener ohne Write-Intent-Lock - deshalb per invokeLater.
        ApplicationManager.getApplication().invokeLater({
            com.intellij.openapi.vcs.changes.actions.diff.ShowDiffAction.showDiffForChange(project, changes, index)
        }, project.disposed)
    }

    /** Karte „Änderungen prüfen“: Dateien der Aufgabe, Diff, Zeilennotizen, dann Freigabe oder Notizen zurück. */
    private fun reviewCard(p: Proposal): JPanel {
        val pending = p.isPending
        val notes = if (pending) NotesService.getInstance(project).all() else emptyList()
        val requested = p.lineNotes != null || p.note != null
        val accent = when (p.state) {
            Proposal.State.FINISHED -> if (requested) JBColor(0xC27D2A, 0xD9A05B) else JBColor(0x5A9E6F, 0x5A9E6F)
            Proposal.State.PENDING -> NotesService.COLOR
            else -> JBColor.border()
        }
        val card = cardPanel(accent)
        val status = when (p.state) {
            Proposal.State.PENDING -> "Review changes" + (p.changelist?.let { " · $it" } ?: "")
            Proposal.State.FINISHED -> if (requested) "Changes requested" else "Approved"
            else -> "Dismissed"
        } + (p.note?.let { ": $it" } ?: "")
        card.add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JBLabel(status).apply { foreground = if (accent == JBColor.border()) UIUtil.getContextHelpForeground() else accent }, BorderLayout.WEST)
            add(JBLabel(time.format(Date(p.createdAt))).apply { foreground = UIUtil.getContextHelpForeground() }, BorderLayout.EAST)
        }, BorderLayout.NORTH)

        val changes = if (pending) changes(p) else emptyList()
        val base = project.basePath.orEmpty().trimEnd('/') + "/"
        card.add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false
            add(wrapped(p.reason))
            if (pending) {
                add(Box.createVerticalStrut(JBUI.scale(6)))
                if (changes.isEmpty()) add(grey("No local changes found."))
                changes.forEachIndexed { i, c ->
                    val path = (c.afterRevision ?: c.beforeRevision)?.file?.path.orEmpty().removePrefix(base)
                    add(link(path) { showDiff(changes, i) }.apply { toolTipText = "Show diff" })
                }
                add(Box.createVerticalStrut(JBUI.scale(6)))
                if (notes.isEmpty()) add(wrapped("To request a change, hover a line in the diff or editor and click the + next to its line number.")
                    .apply { foreground = UIUtil.getContextHelpForeground() })
                else { add(grey(if (notes.size == 1) "1 line note" else "${notes.size} line notes")); add(notesList(notes)) }
            } else p.lineNotes?.let {
                add(Box.createVerticalStrut(JBUI.scale(4)))
                add(wrapped(it).apply { foreground = UIUtil.getContextHelpForeground() })
            }
        }, BorderLayout.CENTER)

        if (pending) {
            val note = JBTextField(p.noteDraft).apply {
                emptyText.text = "Optional note for Claude"
                document.addDocumentListener(object : DocumentAdapter() {
                    override fun textChanged(e: DocumentEvent) { p.noteDraft = text }
                })
            }
            val diff = JButton("Show Diff").apply { isEnabled = changes.isNotEmpty(); addActionListener { showDiff(changes, 0) } }
            // Mit Notizen (an Zeilen oder im Feld) ist es ein Änderungswunsch, sonst eine Freigabe.
            val finish = JButton(if (notes.isEmpty()) "Approve" else if (notes.size == 1) "Send 1 Note" else "Send ${notes.size} Notes").apply {
                toolTipText = "A note in the field below turns an approval into a change request"
                addActionListener { service.finishReview(p, note.text) }
            }
            finish.putClientProperty("JButton.buttonType", "default")
            val dismiss = JButton("Dismiss").apply {
                toolTipText = "Skip the review; line notes stay"
                addActionListener { service.dismiss(p, note.text) }
            }
            card.add(JPanel(BorderLayout(0, JBUI.scale(6))).apply {
                isOpaque = false
                add(note, BorderLayout.NORTH)
                add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                    isOpaque = false
                    listOf(diff, finish, dismiss).forEachIndexed { i, b -> if (i > 0) add(Box.createHorizontalStrut(JBUI.scale(6))); add(b) }
                }, BorderLayout.SOUTH)
            }, BorderLayout.SOUTH)
        }
        return card
    }

    /** Markdown als HTML, umbrechend in der schmalen Liste. */
    private fun markdown(md: String) = javax.swing.JEditorPane().apply {
        editorKit = com.intellij.util.ui.HTMLEditorKitBuilder().build()
        text = "<html><body>${Md.html(md)}</body></html>"
        isEditable = false; isOpaque = false; border = null; alignmentX = LEFT_ALIGNMENT
        putClientProperty(javax.swing.JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        font = UIUtil.getLabelFont()
    }

    /** Eine Claude-Session wartet: fertig (Stop) oder braucht eine Eingabe (Notification). */
    private fun waitingCard(s: WaitingSession): JPanel {
        val sessions = WaitingSessions.getInstance(project)
        val accent = if (s.finished) JBColor(0x2A7AA0, 0x5AA8D0) else JBColor(0xB04079, 0xC86A9A)
        val title = if (s.finished) "Claude is done" else "Claude is waiting for you"
        val card = cardPanel(accent)
        card.add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JBLabel(title).apply { foreground = accent }, BorderLayout.WEST)
            add(JBLabel(time.format(Date(s.at))).apply { foreground = UIUtil.getContextHelpForeground() }, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        card.add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false
            val (preview, cut) = Md.preview(s.text)
            if (preview.isNotEmpty()) add(markdown(preview))
            // Die ganze Nachricht in einem Editor-Reiter: breiter, von oben nach unten lesbar, Markdown gerendert.
            if (cut) add(link("Open Full Message") { Md.openMessage(project, time.format(Date(s.at)), s.text) })
            val base = project.basePath.orEmpty().trimEnd('/')
            if (s.cwd != base) add(grey(s.cwd.removePrefix("$base/")))
        }, BorderLayout.CENTER)
        val dismiss = JButton("Dismiss").apply { addActionListener { sessions.dismiss(s) } }
        if (s.id in sessions.sent) {
            card.add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(grey("Sent to Claude…"), BorderLayout.WEST)
            }, BorderLayout.SOUTH)
        } else if (s.listening) {
            // Weiterer Prompt: Enter schickt ab, Umschalt+Enter macht eine neue Zeile.
            val input = JBTextArea(sessions.drafts[s.id].orEmpty(), 2, 0).apply {
                lineWrap = true; wrapStyleWord = true; font = UIUtil.getLabelFont()
                emptyText.text = "Next prompt for Claude"
                border = JBUI.Borders.empty(4, 6)
                document.addDocumentListener(object : DocumentAdapter() {
                    override fun textChanged(e: DocumentEvent) { sessions.drafts[s.id] = text }
                })
            }
            val send = JButton("Send").apply {
                putClientProperty("JButton.buttonType", "default")
                addActionListener { sessions.reply(s, input.text) }
            }
            input.inputMap.put(javax.swing.KeyStroke.getKeyStroke("ENTER"), "handoff.send")
            input.inputMap.put(javax.swing.KeyStroke.getKeyStroke("shift ENTER"), "insert-break")
            input.actionMap.put("handoff.send", object : javax.swing.AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent) { sessions.reply(s, input.text) }
            })
            card.add(JPanel(BorderLayout(0, JBUI.scale(6))).apply {
                isOpaque = false
                add(JBScrollPane(input).apply { border = JBUI.Borders.customLine(JBColor.border()) }, BorderLayout.NORTH)
                add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                    isOpaque = false
                    add(send); add(Box.createHorizontalStrut(JBUI.scale(6))); add(dismiss)
                }, BorderLayout.SOUTH)
            }, BorderLayout.SOUTH)
        } else {
            card.add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { isOpaque = false; add(dismiss) }, BorderLayout.SOUTH)
        }
        return card
    }

    /** Fragen mit Auswahl (handoff_ask): je Frage eine oder mehrere Antworten und eigener Text; mehrere Fragen mit Back/Next. */
    private fun askCard(p: Proposal): JPanel {
        val pending = p.isPending
        val n = p.questions.size
        val accent = when (p.state) {
            Proposal.State.PENDING -> JBColor(0x2A7AA0, 0x5AA8D0)
            Proposal.State.FINISHED -> JBColor(0x5A9E6F, 0x5A9E6F)
            else -> JBColor.border()
        }
        val card = cardPanel(accent)
        val status = when (p.state) {
            Proposal.State.PENDING -> if (n == 1) "Question" else "Question ${p.page + 1} of $n"
            Proposal.State.FINISHED -> "Answered"
            else -> "Dismissed" + (p.note?.let { ": $it" } ?: "")
        }
        card.add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JBLabel(status).apply { foreground = if (accent == JBColor.border()) UIUtil.getContextHelpForeground() else accent }, BorderLayout.WEST)
            add(JBLabel(time.format(Date(p.createdAt))).apply { foreground = UIUtil.getContextHelpForeground() }, BorderLayout.EAST)
        }, BorderLayout.NORTH)

        val body = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false }
        if (p.reason.isNotBlank()) { body.add(markdown(p.reason)); body.add(Box.createVerticalStrut(JBUI.scale(6))) }
        if (!pending) {
            // Erledigt: kurz je Frage, was gewählt wurde.
            p.questions.forEach { q ->
                val answer = (q.picked.sorted().map { q.options[it].label } + listOfNotNull(q.own.takeIf { it.isNotBlank() })).joinToString(", ")
                body.add(wrapped(Md.plain(q.question.lineSequence().first()) + (if (answer.isNotEmpty()) " → $answer" else "")).apply {
                    foreground = UIUtil.getContextHelpForeground()
                })
            }
            card.add(body, BorderLayout.CENTER)
            return card
        }

        val q = p.questions[p.page.coerceIn(0, n - 1)]
        val last = p.page >= n - 1
        val own = JBTextField(q.own).apply { emptyText.text = "Own answer (optional)" }
        val next = JButton(if (last) "Submit" else "Next").apply { putClientProperty("JButton.buttonType", "default") }
        fun updateNext() { next.isEnabled = q.answered }
        body.add(markdown(q.question + if (q.multiSelect) "\n\n*Pick any.*" else ""))
        if (q.question.lines().size > 12) body.add(link("Open in Editor") { Md.openMessage(project, "Question " + time.format(Date(p.createdAt)), q.question) })
        body.add(Box.createVerticalStrut(JBUI.scale(6)))
        val group = javax.swing.ButtonGroup()
        q.options.forEachIndexed { i, o ->
            val box: javax.swing.JToggleButton =
                if (q.multiSelect) javax.swing.JCheckBox(o.label) else javax.swing.JRadioButton(o.label).also(group::add)
            box.apply {
                isOpaque = false; alignmentX = LEFT_ALIGNMENT; isSelected = i in q.picked
                addActionListener {
                    if (!q.multiSelect) q.picked.clear()
                    if (isSelected) q.picked += i else q.picked -= i
                    updateNext()
                }
            }
            body.add(box)
            if (o.description.isNotEmpty()) body.add(JBLabel("<html>${com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(o.description)}</html>").apply {
                foreground = UIUtil.getContextHelpForeground(); font = JBUI.Fonts.smallFont(); alignmentX = LEFT_ALIGNMENT
                border = JBUI.Borders.emptyLeft(24)
            })
        }
        card.add(body, BorderLayout.CENTER)

        own.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) { q.own = own.text; updateNext() }
        })
        fun advance() { if (last) service.answer(p) else { p.page++; service.fireChanged() } }
        own.addActionListener { if (next.isEnabled) advance() }
        next.addActionListener { advance() }
        updateNext()
        val back = JButton("Back").apply { isVisible = p.page > 0; addActionListener { p.page--; service.fireChanged() } }
        val dismiss = JButton("Dismiss").apply { addActionListener { service.dismiss(p, own.text) } }
        card.add(JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = false
            add(own, BorderLayout.NORTH)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                listOf(back, next, dismiss).filter { it.isVisible }.forEachIndexed { i, b -> if (i > 0) add(Box.createHorizontalStrut(JBUI.scale(6))); add(b) }
            }, BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
        return card
    }

    /** Ablauf aus mehreren Befehlen: alle nacheinander oder Schritt für Schritt, Stopp beim ersten Fehler. */
    private fun sequenceCard(p: Proposal): JPanel {
        val n = p.steps.size
        val failed = p.steps.indexOfFirst { (it.exitCode ?: 0) != 0 }
        val next = p.steps.indexOfFirst { it.exitCode == null }
        val pending = p.isPending
        val accent = when {
            p.state == Proposal.State.FINISHED && failed < 0 -> JBColor(0x5A9E6F, 0x5A9E6F)
            p.state == Proposal.State.FINISHED -> JBColor(0xC75450, 0xC75450)
            p.risky && pending -> JBColor(0xB04079, 0xC86A9A)
            else -> JBColor.border()
        }
        val card = cardPanel(accent)
        val status = when (p.state) {
            Proposal.State.PENDING -> listOfNotNull(
                if (next > 0) "$next of $n steps done" else "Waiting · $n steps", "risky".takeIf { p.risky }, "needs input".takeIf { p.interactive },
            ).joinToString(" · ")
            Proposal.State.RUNNING -> "Running step ${p.steps.indexOfFirst { it.running } + 1} of $n" + if (p.interactive) " · type input in the Run window" else "…"
            Proposal.State.FINISHED -> if (failed < 0) "Done · $n steps" else "Failed at step ${failed + 1} of $n (exit ${p.steps[failed].exitCode})"
            Proposal.State.DISMISSED -> "Dismissed" + (p.note?.let { ": $it" } ?: "")
        }
        card.add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JBLabel(status).apply { foreground = if (accent == JBColor.border()) UIUtil.getContextHelpForeground() else accent }, BorderLayout.WEST)
            add(JBLabel(time.format(Date(p.createdAt))).apply { foreground = UIUtil.getContextHelpForeground() }, BorderLayout.EAST)
        }, BorderLayout.NORTH)

        val fields = ArrayList<Pair<Step, JBTextArea>>()
        card.add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false
            add(wrapped(p.reason))
            add(Box.createVerticalStrut(JBUI.scale(4)))
            p.steps.forEachIndexed { i, s ->
                val code = s.exitCode
                val icon = when {
                    s.running -> com.intellij.ui.AnimatedIcon.Default()
                    code == null -> AllIcons.RunConfigurations.TestNotRan
                    code == 0 -> AllIcons.RunConfigurations.TestPassed
                    else -> AllIcons.RunConfigurations.TestFailed
                }
                val field = JBTextArea(s.command).apply {
                    font = JBUI.Fonts.create(java.awt.Font.MONOSPACED, UIUtil.getLabelFont().size)
                    lineWrap = true; isEditable = pending && code == null
                    border = JBUI.Borders.empty(4, 6)
                    background = JBColor.namedColor("Editor.background", UIUtil.getTextFieldBackground())
                }
                if (field.isEditable) fields.add(s to field)
                add(JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
                    isOpaque = false; alignmentX = LEFT_ALIGNMENT
                    add(JBLabel("${i + 1}", icon, JBLabel.LEFT).apply {
                        verticalAlignment = JBLabel.TOP; border = JBUI.Borders.emptyTop(4)
                        foreground = UIUtil.getContextHelpForeground()
                        toolTipText = when { s.running -> "Running"; code == null -> "Not run yet"; code == 0 -> "Done"; else -> "Failed (exit $code)" }
                    }, BorderLayout.WEST)
                    add(field, BorderLayout.CENTER)
                })
                add(Box.createVerticalStrut(JBUI.scale(4)))
            }
            add(grey(p.workingDir.replace(System.getProperty("user.home"), "~")))
        }, BorderLayout.CENTER)

        if (pending) {
            // Swing-Listener laufen ohne Write-Intent-Lock; das Run-Fenster braucht ihn - also per invokeLater.
            fun start(all: Boolean) {
                fields.forEach { (s, f) -> f.text.trim().takeIf { it.isNotEmpty() }?.let { s.command = it } }
                ApplicationManager.getApplication().invokeLater({ CommandRunner.runSteps(project, p, all) }, project.disposed)
            }
            val all = JButton(if (next > 0) "Run Remaining" else "Run All", AllIcons.Actions.Execute).apply {
                toolTipText = "Runs the steps one after another in the Run tool window and stops at the first one that fails"
                addActionListener { start(true) }
            }
            all.putClientProperty("JButton.buttonType", "default")
            val one = JButton("Run Step ${next + 1}").apply {
                toolTipText = "Runs only the next step"
                addActionListener { start(false) }
            }
            val dismiss = JButton("Dismiss…").apply {
                addActionListener {
                    val note = Messages.showInputDialog(project, "Optional note for Claude:", "Dismiss Commands", null)
                    if (note != null) service.dismiss(p, note)
                }
            }
            card.add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                listOf(all, one, dismiss).forEachIndexed { i, b -> if (i > 0) add(Box.createHorizontalStrut(JBUI.scale(6))); add(b) }
            }, BorderLayout.SOUTH)
        }
        return card
    }

    private fun card(p: Proposal): JPanel {
        if (p.review) return reviewCard(p)
        if (p.steps.isNotEmpty()) return sequenceCard(p)
        if (p.images.isNotEmpty()) return imageCard(p)
        if (p.isAsk) return askCard(p)
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
