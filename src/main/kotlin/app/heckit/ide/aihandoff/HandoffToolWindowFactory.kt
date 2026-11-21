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
import javax.swing.JComponent
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
    /** Fenster-Icon per Datei-Adresse, siehe [HandoffIcons]; Größe (neue Oberfläche 16/20 px, alte 13 px) und hell/dunkel wählen wir selbst. */
    private fun iconUrl(path: String) = HandoffIcons.url(path)

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
        val sessionsPanel = SessionsPanel(project)
        toolWindow.contentManager.addContent(ContentFactory.getInstance().createContent(sessionsPanel, "Sessions", false).apply { setDisposer(sessionsPanel) })
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
        val newSession = com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction("Handoff.NewClaudeSession")
        toolWindow.setAdditionalGearActions(com.intellij.openapi.actionSystem.DefaultActionGroup(
            newSession,
            com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction("Handoff.NewClaudeSessionInFolder"),
            com.intellij.openapi.actionSystem.ActionManager.getInstance().getAction("Handoff.ResumeClaudeSession"),
            com.intellij.openapi.actionSystem.Separator.create(),
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
            layoutToggle("Remove Cards on Dismiss", { Layout.removeOnDismiss }, { Layout.removeOnDismiss = it }),
            layoutToggle("Pin Claude Messages to the Bottom", { Layout.pinClaudeMessages }, { Layout.pinClaudeMessages = it }),
            layoutToggle("Newest Cards on Top", { Layout.newestOnTop }, { Layout.newestOnTop = it }),
            com.intellij.openapi.actionSystem.Separator.create(),
        ))
        fun selected() = toolWindow.contentManager.selectedContent?.component
        fun isMemory() = selected() === memory || selected() === usage || selected() === sessionsPanel
        toolWindow.setTitleActions(listOf(
            newSession,
            // Mülleimer als Menü: erledigte Übergaben oder alle „Claude is done/waiting“-Karten entfernen.
            object : com.intellij.openapi.actionSystem.DefaultActionGroup("Clear", true), DumbAware {
                init {
                    templatePresentation.icon = AllIcons.Actions.GC
                    add(object : AnAction("Clear Finished"), DumbAware {
                        override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
                        override fun actionPerformed(e: AnActionEvent) = HandoffService.getInstance(project).clearFinished()
                    })
                    add(object : AnAction("Dismiss All Claude Messages", "Remove every \"Claude is done\" and \"Claude is waiting\" card", null), DumbAware {
                        override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
                        override fun update(e: AnActionEvent) {
                            e.presentation.isEnabled = project.getServiceIfCreated(WaitingSessions::class.java)?.sessions?.isNotEmpty() == true
                        }
                        override fun actionPerformed(e: AnActionEvent) = WaitingSessions.getInstance(project).dismissAll()
                    })
                }
                override fun update(e: AnActionEvent) { e.presentation.isVisible = !isMemory() }
                override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
            },
            object : AnAction("Reload", null, AllIcons.Actions.Refresh), DumbAware {
                override fun update(e: AnActionEvent) { e.presentation.isVisible = isMemory() }
                override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
                override fun actionPerformed(e: AnActionEvent) = when {
                    selected() === usage -> usage.refresh()
                    selected() === sessionsPanel -> sessionsPanel.refresh()
                    else -> memory.refresh()
                }
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
    private const val REMOVE = "app.heckit.ide.aihandoff.removeOnDismiss"
    /** Abgelehnte Karten gleich ausblenden statt als „Dismissed“ stehen zu lassen. */
    var removeOnDismiss: Boolean
        get() = com.intellij.ide.util.PropertiesComponent.getInstance().getBoolean(REMOVE, false)
        set(v) = com.intellij.ide.util.PropertiesComponent.getInstance().setValue(REMOVE, v, false)
    /** „Claude is done/waiting“-Karten fest am unteren Rand statt in der Liste. */
    var pinClaudeMessages: Boolean
        get() = com.intellij.ide.util.PropertiesComponent.getInstance().getBoolean(PIN, true)
        set(v) = com.intellij.ide.util.PropertiesComponent.getInstance().setValue(PIN, v, true)
}

/**
 * Karten-Liste des Commands-Reiters. [compact]: nur wartende Übergaben (Befehle, Fragen, Reviews, Bilder), ohne Leerzustand,
 * Hinweise und Claude-Nachrichten - für den Session-Reiter ([sessionOf]: dessen Session), damit man dort antworten kann, ohne ins Handoff-Fenster zu wechseln.
 */
/** Schlüssel der Karten im Handoff-Fenster (Top-Level: in HandoffPanel wären sie beim ersten rebuild() im init noch null). */
private const val CARD_KEY = "handoff.cardKey"
private const val CARD_GLUE = "handoff.glue"

/** Hauptknopf einer Karte (Approve, Send, Run …) blau hervorheben - "JButton.buttonType" = "default" wirkt im LaF nicht. */
internal fun JButton.primary() = com.intellij.ui.ClientProperty.put(this, com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI.DEFAULT_STYLE_KEY, true)

/** Verwerfen-Knopf schlicht mit Kreuz, damit er sich ohne Lesen vom Hauptknopf unterscheidet. */
internal fun dismissButton(text: String) = JButton(text, AllIcons.Actions.Close)

/**
 * Befehl in die Zwischenablage, falls man ihn lieber selbst ausführt; die Karte bleibt offen (danach etwa mit Notiz
 * verwerfen). [text] liest den Stand beim Klick, also auch Änderungen im Feld.
 */
internal fun copyButton(tooltip: String, text: () -> String) = JButton("Copy", AllIcons.Actions.Copy).apply {
    toolTipText = tooltip
    addActionListener {
        com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(text()))
        this.text = "Copied"
        javax.swing.Timer(1500) { this.text = "Copy" }.apply { isRepeats = false; start() }
    }
}

internal class HandoffPanel(private val project: Project, private val compact: Boolean = false, private val sessionOf: (() -> String?)? = null, private val onNewCard: (() -> Unit)? = null) : JPanel(BorderLayout()), com.intellij.openapi.Disposable {
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
    /** Je Meldung (Datei + Zeit) einmal entschieden: kam sie, während ihr Session-Reiter vorn war, bleibt sie verborgen.
     *  Oben deklariert: rebuild() läuft schon im init (sonst NPE, leeres Handoff-Fenster, 2026-11-18). */
    private val arrival = HashMap<Pair<String, Long>, Boolean>()
    private var lastNewest = 0L

    /** Feste Ablage am unteren Rand für Claudes Nachrichten (siehe [Layout.pinClaudeMessages]). */
    private val pinned = object : JPanel(), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(r: Rectangle, o: Int, d: Int) = JBUI.scale(16)
        override fun getScrollableBlockIncrement(r: Rectangle, o: Int, d: Int) = r.height
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }.apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); border = JBUI.Borders.empty(8, 8, 0, 8) }
    /**
     * Die Ablage scrollt selbst und nimmt höchstens 40 % der Höhe - sonst drückten mehrere "Claude is done"-Karten die
     * übrigen Karten oben auf einen schmalen Streifen zusammen (2026-11-16).
     */
    private val pinnedScroll = object : JBScrollPane(pinned) {
        override fun getPreferredSize(): Dimension {
            val want = pinned.preferredSize.height + insets.top + insets.bottom
            val max = (this@HandoffPanel.height * 0.4).toInt().takeIf { it > 0 } ?: want
            return Dimension(super.getPreferredSize().width, minOf(want, max))
        }
    }.apply { border = JBUI.Borders.customLineTop(JBColor.border()) }
    private val scroll = JBScrollPane(list).apply { border = JBUI.Borders.empty() }

    init {
        // Kompakt steht die Liste im Verlauf des Session-Reiters, der selbst scrollt.
        if (compact) { isOpaque = false; list.isOpaque = false; list.border = JBUI.Borders.empty(4, 0); add(list, BorderLayout.CENTER) }
        else { add(scroll, BorderLayout.CENTER); add(pinnedScroll, BorderLayout.SOUTH) }
        // Nicht in Screenshot-Läufen: Dort gilt die Sandbox als "gerade aktualisiert".
        if (!compact && System.getProperty("handoff.screenshots") == null) Guide.pendingNews()?.let { (version, news) -> add(newsBanner(version, news), BorderLayout.NORTH) }
        service.addListener({ rebuild() }, this)
        // Session-Reiter geöffnet oder geschlossen: Karten wechseln zwischen "Open in Editor" und "Show in Editor".
        if (!compact) project.messageBus.connect(this).subscribe(com.intellij.openapi.fileEditor.FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : com.intellij.openapi.fileEditor.FileEditorManagerListener {
                override fun fileOpened(source: com.intellij.openapi.fileEditor.FileEditorManager, file: com.intellij.openapi.vfs.VirtualFile) { if (file is SessionFile) rebuild() }
                override fun fileClosed(source: com.intellij.openapi.fileEditor.FileEditorManager, file: com.intellij.openapi.vfs.VirtualFile) { if (file is SessionFile) SwingUtilities.invokeLater { rebuild() } }
            })
        rebuild()
    }

    private var lastKey: Any? = null

    private fun rebuildCompact() {
        // Im Session-Reiter nur Karten dieser Session (bzw. ohne sicher bekannte Session) - nicht die Frage der Nachbar-Session.
        val me = sessionOf?.invoke()
        val pending = service.proposals.filter { it.isPending && !it.hidden && (me == null || it.owner == null || it.owner == me) }.sortedBy { it.createdAt }
        // Unverändert: nichts anfassen. Jedes Neu-Aufbauen löste ein Layout des ganzen Verlaufs aus - im Nachbar-Reiter sprang
        // die Scroll-Position, als dort eine fremde (ausgeblendete) Karte kam (2026-11-16); auch ein laufendes Diktat brach ab.
        // Derselbe Schlüssel wie im Tool Window (Auswahl, Prüfliste, …). Geleert wird erst danach: Stand removeAll() davor, baute
        // jede Änderung irgendeiner Session die Karten neu, und ein angefangenes Geheimnis verlor den Fokus ans Eingabefeld
        // (2026-11-19, Fehler seit 1.5.2).
        val key = pending.map { proposalKey(it) }
        if (key == lastKey && list.componentCount > 0 == pending.isNotEmpty()) return
        lastKey = key
        list.removeAll()
        pending.forEach { list.add(hoverClose(card(it), "Dismiss") { service.close(it) }); list.add(Box.createVerticalStrut(JBUI.scale(8))) }
        isVisible = pending.isNotEmpty()
        list.revalidate(); list.repaint()
        revalidate(); parent?.revalidate()
        val newest = pending.maxOfOrNull { it.createdAt } ?: 0L
        // Der Session-Reiter scrollt selbst (im Hintergrund stimmen die Positionen hier nicht, siehe SessionEditor).
        if (newest > lastNewest) onNewCard?.invoke() ?: SwingUtilities.invokeLater { scrollRectToVisible(Rectangle(0, height - 1, 1, 1)) }
        lastNewest = newest
    }

    /** Schlüssel einer Karte: Ändert er sich, wird sie neu gebaut; sonst bleibt die Komponente samt Fokus und Diktat stehen. */
    private fun proposalKey(p: Proposal): Any = listOf("p", p.id, p.state, p.page, p.exitCode, p.note, p.chosen, p.selected,
        p.steps.map { it.exitCode to it.running }, p.marks.mapValues { it.value.size }, p.shapes.keys.toSet(), p.lineNotes,
        p.checks.map { it.result to it.noteOpen })

    /**
     * [container] auf die Karten [items] (Schlüssel → Bauanleitung) bringen, ohne unveränderte anzufassen: Bisher baute jede
     * Statusänderung irgendeiner Session die ganze Liste neu - ein laufendes Diktat in einer Karte brach dabei ab (2026-11-16).
     * Jede Karte steckt in einer Hülle mit 8 px Abstand unten (statt eines eigenen Abstandshalters).
     */
    private fun sync(container: JPanel, items: List<Pair<Any, () -> JComponent>>, glue: Boolean) {
        val current = container.components.filterIsInstance<JComponent>().associateBy { it.getClientProperty(CARD_KEY) }
        val wanted = items.map { (key, make) ->
            current[key] ?: object : JPanel(BorderLayout()) {
                // Höhe der Karte übernehmen (z. B. die begrenzte Befehlskarte) - sonst streckt BoxLayout die Hülle.
                override fun getMaximumSize(): Dimension = (getComponent(0) as JComponent).maximumSize.let {
                    Dimension(Int.MAX_VALUE, if (it.height >= Int.MAX_VALUE - JBUI.scale(8)) Int.MAX_VALUE else it.height + JBUI.scale(8))
                }
            }.apply {
                isOpaque = false; alignmentX = LEFT_ALIGNMENT; border = JBUI.Borders.emptyBottom(8)
                add(make(), BorderLayout.CENTER); putClientProperty(CARD_KEY, key)
            }
        } + if (glue) listOf(current[CARD_GLUE] ?: (Box.createVerticalGlue() as JComponent).apply { putClientProperty(CARD_KEY, CARD_GLUE) }) else emptyList()
        if (container.components.toList() == wanted) return
        val keep = wanted.toSet()
        container.components.filter { it !in keep }.forEach { container.remove(it) }
        wanted.forEachIndexed { i, c -> if (container.componentCount <= i || container.getComponent(i) !== c) container.add(c, i) }
        container.revalidate(); container.repaint()
    }

    private fun rebuild() {
        if (compact) return rebuildCompact()
        val notes = NotesService.getInstance(project).all()
        val waiting = project.getServiceIfCreated(WaitingSessions::class.java)?.sessions.orEmpty().filter { visibleOnArrival(it) }
        val working = project.getServiceIfCreated(WaitingSessions::class.java)?.working.orEmpty()
        val pin = Layout.pinClaudeMessages
        val top = Layout.newestOnTop
        val ws = project.getServiceIfCreated(WaitingSessions::class.java)
        fun waitKey(w: WaitingSession): Any = listOf("w", w.id, w.at, w.finished, w.listening, w.text, SessionEditors.openTab(project, ws?.infos?.get(w.id)?.tab, w.id) != null)
        fun workKey(w: WorkingSession): Any = listOf("k", w.id, w.since, w.prompt, SessionEditors.openTab(project, ws?.infos?.get(w.id)?.tab, w.id) != null)
        val entries = mutableListOf<Pair<Any, () -> JComponent>>()
        val proposals = service.proposals.filter { !it.hidden }
        if (proposals.isEmpty() && notes.isEmpty() && ((waiting.isEmpty() && working.isEmpty()) || pin))
            entries += listOf("empty", Features.missing(project).size, Features.mentioned(project).isEmpty()) to { emptyState() }
        // Chronologisch, Richtung nach Einstellung (Standard: älteste oben, neueste unten wie in einem Chat).
        val items: List<Triple<Long, Any, () -> JComponent>> =
            // Die Liste ist neueste zuerst; umgedreht bleibt die Reihenfolge bei gleicher Millisekunde richtig (stabile Sortierung).
            proposals.reversed().map { p -> Triple(p.createdAt, proposalKey(p)) { hoverClose(card(p), if (p.isPending) "Dismiss" else "Close") { service.close(p) } } } +
                if (pin) emptyList() else waiting.map { w -> Triple(w.at, waitKey(w)) { waitingCard(w) } } +
                    working.map { w -> Triple(w.since, workKey(w)) { workingCard(w) } }
        val sorted = items.sortedBy { it.first }.let { if (top) it.reversed() else it }
        // Notizen ohne wartende Prüfung: eigene Karte, bis Claude sie abholt. Sonst stehen sie in der Prüfkarte.
        val notesEntry: Pair<Any, () -> JComponent>? = if (notes.isNotEmpty() && service.proposals.none { it.review && it.isPending })
            listOf("n", notes.map { it.text to it.highlighter.startOffset }) to { notesCard(notes) } else null
        if (top && notesEntry != null) entries += notesEntry
        sorted.forEach { (_, key, make) -> entries += key to make }
        if (!top && notesEntry != null) entries += notesEntry
        sync(list, entries, glue = true)

        val pinnedItems = if (pin) (waiting.map { Triple(it.at, waitKey(it)) { waitingCard(it) as JComponent } } +
            working.map { Triple(it.since, workKey(it)) { workingCard(it) as JComponent } })
            .sortedBy { it.first }.let { if (top) it.reversed() else it }.map { it.second to it.third } else emptyList()
        sync(pinned, pinnedItems, glue = false)
        val show = pinned.componentCount > 0
        if (pinnedScroll.isVisible != show) { pinnedScroll.isVisible = show; revalidate() }
        if (show) pinnedScroll.revalidate()

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

    /**
     * Hinweis nach einem Update, bis er weggeklickt oder die Anleitung geöffnet wird: oben die Links und ✕, darunter der Text
     * über die volle Breite (neben den Links blieb ihm im schmalen Tool Window nur eine schmale Spalte, 2026-11-19).
     */
    private fun newsBanner(version: String, news: String): JPanel {
        val banner = JPanel(BorderLayout(0, JBUI.scale(4)))
        fun close() { Guide.markNewsSeen(); remove(banner); revalidate(); repaint() }
        banner.border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(6, 10))
        banner.background = JBColor.namedColor("Banner.infoBackground", JBColor(0xF5F8FE, 0x25324D))
        val links = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS); isOpaque = false
            val items = listOfNotNull(
                if (Features.missing(project).isNotEmpty()) com.intellij.ui.components.ActionLink("Update CLAUDE.md…") { showFeatures() } else null,
                com.intellij.ui.components.ActionLink("What's new") { Guide.openNews(project, version); close() },
                com.intellij.ui.components.ActionLink("Guide") { Guide.open(project); close() },
            )
            items.forEachIndexed { i, link -> if (i > 0) add(Box.createHorizontalStrut(JBUI.scale(12))); add(link) }
        }
        banner.add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(links, BorderLayout.WEST)
            add(com.intellij.ui.InplaceButton("Close", AllIcons.Actions.Close) { close() }, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        banner.add(JBLabel("<html><b>New in $version:</b> ${com.intellij.openapi.util.text.StringUtil.escapeXmlEntities(news.replace("**", "").replace("`", ""))}</html>")
            .setAllowAutoWrapping(true), BorderLayout.CENTER)
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
            add(wrapped("Send them to a Claude session, or they go along with the next review.").apply { foreground = UIUtil.getContextHelpForeground() })
        }, BorderLayout.CENTER)
        card.add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            val send = JButton("Send to Claude…").apply { primary() }
            send.addActionListener { chooseSessionForNotes(send) }
            add(send); add(Box.createHorizontalStrut(JBUI.scale(6)))
            add(JButton("Clear Notes").apply { addActionListener { NotesService.getInstance(project).clear() } })
        }, BorderLayout.SOUTH)
        return card
    }

    /**
     * Notizen an eine Session: Liste der laufenden Sessions des Projekts (die Prompts annehmen) und "New Session". Die gewählte
     * bekommt die Notizen als Prompt (Pfad:Zeile, Notiz, Codezeile wie beim Review); danach sind sie erledigt.
     */
    private fun chooseSessionForNotes(anchor: JComponent) {
        val sessions = WaitingSessions.getInstance(project)
        val openTabs = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFiles.filterIsInstance<SessionFile>()
        val running = sessions.infos.values.filter { sessions.canReply(it.id) }.sortedByDescending { it.at }
        fun label(i: SessionInfo): String {
            val title = openTabs.firstOrNull { (i.tab != null && it.tab == i.tab) || it.currentSession(project) == i.id }?.title
            val base = project.basePath.orEmpty().trimEnd('/')
            val dir = if (i.cwd == base) "" else " · " + SessionEditors.shortPath(i.cwd)
            return (title ?: "Session from " + time.format(Date(i.at))) + dir
        }
        val newSession = "New Session"
        val items: List<Any> = running + newSession
        com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
            .createPopupChooserBuilder(items)
            .setTitle("Send Notes to")
            .setRenderer(object : com.intellij.ui.ColoredListCellRenderer<Any?>() {
                override fun customizeCellRenderer(list: javax.swing.JList<out Any?>, value: Any?, index: Int, selected: Boolean, hasFocus: Boolean) {
                    append(if (value is SessionInfo) label(value) else value?.toString().orEmpty())
                }
            })
            .setItemChosenCallback { chosen -> sendNotes(chosen as? SessionInfo) }
            .createPopup()
            .showUnderneathOf(anchor)
    }

    private fun sendNotes(target: SessionInfo?) {
        val notes = NotesService.getInstance(project)
        val text = notes.all().takeIf { it.isNotEmpty() }?.let { notes.describe(it) } ?: return
        val prompt = "Please work through my notes on the code (path:line, note, the line it refers to):\n\n$text"
        if (target != null) {
            if (!WaitingSessions.getInstance(project).reply(target.id, prompt)) {
                Messages.showErrorDialog(project, "The session does not accept prompts right now. Try again when Claude is done.", "Send Notes")
                return
            }
            SessionEditors.open(project, target.tab, target.id, focus = false)
        } else {
            // Neue Session gleich mit dem Prompt starten - über eine Datei, damit Anführungszeichen und Zeilen heil bleiben.
            val file = File(com.intellij.openapi.application.PathManager.getTempPath(), "handoff-notes-${System.currentTimeMillis()}.md")
            file.writeText(prompt)
            if (!SessionEditors.newSession(project, command = "claude \"\$(cat '${file.path}')\"")) return
        }
        notes.clear()
    }

    private fun changes(p: Proposal): List<com.intellij.openapi.vcs.changes.Change> {
        val clm = com.intellij.openapi.vcs.changes.ChangeListManager.getInstance(project)
        val list = p.changelist?.let { clm.findChangeList(it)?.changes }
            ?: clm.allChanges.filter { p.excludeLists.isEmpty() || clm.getChangeList(it)?.name !in p.excludeLists }
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
            // Titel in CENTER, nicht WEST: WEST bekommt immer seine volle Breite und lag bei langen Changelist-Namen über
            // Uhrzeit und ✕ (2026-11-18). CENTER kürzt mit "…", der volle Text steht im Tooltip - so in allen Kartenköpfen.
            add(JBLabel(status).apply { foreground = if (accent == JBColor.border()) UIUtil.getContextHelpForeground() else accent; toolTipText = status; border = JBUI.Borders.emptyRight(8) }, BorderLayout.CENTER)
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
            finish.primary()
            val dismiss = dismissButton("Dismiss").apply {
                toolTipText = "Skip the review; line notes stay"
                addActionListener { service.dismiss(p, note.text) }
            }
            card.add(JPanel(BorderLayout(0, JBUI.scale(6))).apply {
                isOpaque = false
                add(Dictation.withMic(note), BorderLayout.NORTH)
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

    private fun visibleOnArrival(w: WaitingSession): Boolean = arrival.getOrPut(w.file.name to w.at) {
        val tab = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).selectedEditor?.file as? SessionFile
        val inFront = tab != null && com.intellij.openapi.application.ApplicationManager.getApplication().isActive && tab.currentSession(project) == w.id
        !inFront
    }

    /** ✕ oben rechts im Kopf, nur solange die Maus über der Karte ist; die Uhrzeit rückt dafür etwas nach links. */
    private fun <T : JComponent> hoverClose(card: T, tooltip: String, onClose: () -> Unit): T {
        val header = (card.layout as? BorderLayout)?.getLayoutComponent(BorderLayout.NORTH) as? JPanel ?: return card
        val hl = header.layout as? BorderLayout ?: return card
        val close = com.intellij.ui.InplaceButton(com.intellij.openapi.ui.popup.IconButton(tooltip, AllIcons.Actions.Close, AllIcons.Actions.CloseHovered)) { onClose() }.apply { isVisible = false; border = JBUI.Borders.emptyLeft(6) }
        val timeLabel = hl.getLayoutComponent(BorderLayout.EAST)
        if (timeLabel != null) header.remove(timeLabel)
        header.add(JPanel(BorderLayout()).apply { isOpaque = false; timeLabel?.let { add(it, BorderLayout.CENTER) }; add(close, BorderLayout.EAST) }, BorderLayout.EAST)
        // Nicht per MouseListener an jedem Kind: Ein Listener am Kind schluckt dessen Mausereignisse, dann bekam die
        // Bildkachel kein Enter/Moved mehr und ihr Auge flackerte oder fehlte (1.8.0, 2026-11-18). Darum global mithören.
        val listener = java.awt.event.AWTEventListener { ev ->
            val e = ev as? java.awt.event.MouseEvent ?: return@AWTEventListener
            if (e.id != java.awt.event.MouseEvent.MOUSE_MOVED && e.id != java.awt.event.MouseEvent.MOUSE_EXITED && e.id != java.awt.event.MouseEvent.MOUSE_ENTERED) return@AWTEventListener
            if (!card.isShowing || e.component == null) return@AWTEventListener
            val p = SwingUtilities.convertPoint(e.component, e.point, card)
            val inside = p.x >= 0 && p.y >= 0 && p.x < card.width && p.y < card.height &&
                SwingUtilities.getWindowAncestor(e.component) === SwingUtilities.getWindowAncestor(card)
            if (close.isVisible != inside) { close.isVisible = inside; header.revalidate(); header.repaint() }
        }
        val mask = java.awt.AWTEvent.MOUSE_EVENT_MASK or java.awt.AWTEvent.MOUSE_MOTION_EVENT_MASK
        card.addHierarchyListener { e ->
            if (e.changeFlags and java.awt.event.HierarchyEvent.DISPLAYABILITY_CHANGED.toLong() == 0L) return@addHierarchyListener
            java.awt.Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
            if (card.isDisplayable) java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(listener, mask)
            else close.isVisible = false
        }
        if (card.isDisplayable) java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(listener, mask)
        return card
    }

    private fun waitingCard(s: WaitingSession): JPanel {
        val sessions = WaitingSessions.getInstance(project)
        val accent = if (s.finished) JBColor(0x2A7AA0, 0x5AA8D0) else JBColor(0xB04079, 0xC86A9A)
        val title = if (s.finished) "Claude is done" else "Claude is waiting for you"
        val linked = SessionEditors.openTab(project, sessions.infos[s.id]?.tab, s.id) != null
        val card = cardPanel(accent)
        val timeLabel = JBLabel(time.format(Date(s.at))).apply { foreground = UIUtil.getContextHelpForeground() }
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JBLabel(title).apply { foreground = accent; toolTipText = title; border = JBUI.Borders.emptyRight(8) }, BorderLayout.CENTER)
            add(timeLabel, BorderLayout.EAST)
        }
        card.add(header, BorderLayout.NORTH)
        card.add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false
            val (preview, cut) = Md.preview(s.text)
            if (preview.isNotEmpty()) add(markdown(preview))
            // Ohne Session-Reiter: die ganze Nachricht in einem Editor-Reiter - breiter, von oben nach unten lesbar, Markdown gerendert.
            if (!linked && cut) add(link("Open Full Message") { Md.openMessage(project, time.format(Date(s.at)), s.text) })
            add(sessionLink(s.id))
            val base = project.basePath.orEmpty().trimEnd('/')
            if (s.cwd != base) add(grey(if (s.cwd.startsWith("$base/")) s.cwd.removePrefix("$base/") else SessionEditors.shortPath(s.cwd)))
        }, BorderLayout.CENTER)
        val dismiss = dismissButton("Dismiss").apply { addActionListener { sessions.dismiss(s) } }
        if (s.id in sessions.sent) {
            card.add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(grey("Sent to Claude…"), BorderLayout.WEST)
            }, BorderLayout.SOUTH)
        } else if (linked) {
            // Session hat einen Editor-Reiter: dort liest und antwortet man - hier nur der Weg dorthin.
        } else if (s.listening || sessions.canSendKeys(s.id)) {
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
                primary()
                addActionListener { send(s, input) }
            }
            input.inputMap.put(javax.swing.KeyStroke.getKeyStroke("ENTER"), "handoff.send")
            input.inputMap.put(javax.swing.KeyStroke.getKeyStroke("shift ENTER"), "insert-break")
            input.actionMap.put("handoff.send", object : javax.swing.AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent) { send(s, input) }
            })
            card.add(JPanel(BorderLayout(0, JBUI.scale(6))).apply {
                isOpaque = false
                add(Dictation.withMic(JBScrollPane(input).apply { border = JBUI.Borders.customLine(JBColor.border()) }, input), BorderLayout.NORTH)
                add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                    isOpaque = false
                    add(send); add(Box.createHorizontalStrut(JBUI.scale(6))); add(dismiss)
                }, BorderLayout.SOUTH)
            }, BorderLayout.SOUTH)
        } else {
            card.add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { isOpaque = false; add(dismiss) }, BorderLayout.SOUTH)
        }
        return hoverClose(card, "Dismiss") { sessions.dismiss(s) }
    }

    /**
     * Weg zum Session-Reiter, überall gleich benannt: "Show in Editor", wenn es den Reiter schon gibt (holt ihn nach vorn),
     * sonst "Open in Editor" (öffnet einen neuen).
     */
    private fun sessionLink(id: String): JComponent {
        val tab = WaitingSessions.getInstance(project).infos[id]?.tab
        val open = SessionEditors.openTab(project, tab, id) != null
        return link(if (open) "Show in Editor" else "Open in Editor") { SessionEditors.open(project, tab, id) }
    }

    /** Prompt aus einer Karte an die Session; Slash-Befehle gehen nur über ein von Handoff gestartetes Terminal. */
    private fun send(s: WaitingSession, input: JBTextArea) {
        val sessions = WaitingSessions.getInstance(project)
        val text = input.text.trim()
        if (text.startsWith("/") && !sessions.canSendKeys(s.id)) {
            com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().createHtmlTextBalloonBuilder(
                "Slash commands only reach sessions started with <b>New Claude Session</b>. Type it in the terminal.",
                com.intellij.openapi.ui.MessageType.WARNING, null
            ).setFadeoutTime(5000).createBalloon().show(com.intellij.ui.awt.RelativePoint.getNorthWestOf(input), com.intellij.openapi.ui.popup.Balloon.Position.above)
            return
        }
        sessions.reply(s.id, text)
    }

    /** Claude arbeitet an einer Session (Prompt abgeschickt, noch nicht fertig). Bewusst ohne Live-Fortschritt - der steht im Editor-Reiter. */
    private fun workingCard(w: WorkingSession): JPanel {
        val sessions = WaitingSessions.getInstance(project)
        val accent = JBColor(0x5A9E6F, 0x6FBF85)
        val card = cardPanel(accent)
        card.add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JBLabel("Claude is working…").apply { foreground = accent; border = JBUI.Borders.emptyRight(8) }, BorderLayout.CENTER)
            add(JBLabel("since " + time.format(Date(w.since))).apply { foreground = UIUtil.getContextHelpForeground() }, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        card.add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false
            Md.preview(w.prompt.removePrefix("The user sent this follow-up prompt from the IDE (Handoff tool window):").trim(), maxLines = 2, maxChars = 160)
                .first.takeIf { it.isNotEmpty() }?.let { add(grey(Md.plain(it))) }
            add(sessionLink(w.id))
        }, BorderLayout.CENTER)
        card.add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(dismissButton("Dismiss").apply { toolTipText = "Hide this card; Claude keeps working"; addActionListener { sessions.dismiss(w) } })
        }, BorderLayout.SOUTH)
        return hoverClose(card, "Dismiss") { sessions.dismiss(w) }
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
            add(JBLabel(status).apply { foreground = if (accent == JBColor.border()) UIUtil.getContextHelpForeground() else accent; toolTipText = status; border = JBUI.Borders.emptyRight(8) }, BorderLayout.CENTER)
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
        val next = JButton(if (last) "Submit" else "Next").apply { primary() }
        fun updateNext() { next.isEnabled = q.answered }
        body.add(markdown(q.question + if (q.multiSelect) "\n\n*Pick any.*" else ""))
        if (q.question.lines().size > 12) body.add(link("Open Full Question") { Md.openMessage(project, "Question " + time.format(Date(p.createdAt)), q.question) })
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
        // Frage von Claude Code selbst: ablehnen heißt, sie im Terminal zu beantworten (der Hook gibt sie frei).
        val dismiss = dismissButton(if (p.askSession != null) "Answer in Terminal" else "Dismiss").apply { addActionListener { service.dismiss(p, own.text) } }
        card.add(JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = false
            add(Dictation.withMic(own), BorderLayout.NORTH)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                listOf(back, next, dismiss).filter { it.isVisible }.forEachIndexed { i, b -> if (i > 0) add(Box.createHorizontalStrut(JBUI.scale(6))); add(b) }
            }, BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
        return card
    }

    private fun cardHead(card: JPanel, status: String, accent: java.awt.Color, p: Proposal) {
        card.add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JBLabel(status).apply { foreground = if (accent == JBColor.border()) UIUtil.getContextHelpForeground() else accent; toolTipText = status; border = JBUI.Borders.emptyRight(8) }, BorderLayout.CENTER)
            add(JBLabel(time.format(Date(p.createdAt))).apply { foreground = UIUtil.getContextHelpForeground() }, BorderLayout.EAST)
        }, BorderLayout.NORTH)
    }

    /**
     * Prüfliste für einen Handtest: je Schritt ein Häkchen links (geht) und rechts die Sprechblase für eine Notiz. Ein nicht
     * abgehakter Schritt mit Notiz geht als fehlgeschlagen an Claude, ohne Notiz als nicht gemacht. Vorher waren es ✓/✗
     * rechts - umständlich, und ✗ wirkte wie Löschen (2026-11-19).
     */
    private fun checklistCard(p: Proposal): JPanel {
        val pending = p.isPending
        val ok = p.checks.count { it.result == 1 }; val bad = p.checks.count { it.result == 2 }
        val accent = when {
            p.state == Proposal.State.FINISHED && bad == 0 -> JBColor(0x5A9E6F, 0x5A9E6F)
            p.state == Proposal.State.FINISHED -> JBColor(0xC75450, 0xC75450)
            pending -> JBColor(0x2A7AA0, 0x5AA8D0)
            else -> JBColor.border()
        }
        val card = cardPanel(accent)
        val status = when (p.state) {
            Proposal.State.PENDING -> "Test · $ok of ${p.checks.size} checked"
            Proposal.State.FINISHED -> "Tested · $ok passed" + (if (bad > 0) ", $bad failed" else "")
            else -> "Dismissed" + (p.note?.let { ": $it" } ?: "")
        }
        cardHead(card, status, accent, p)
        val body = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false }
        if (p.reason.isNotBlank()) { body.add(markdown(p.reason)); body.add(Box.createVerticalStrut(JBUI.scale(6))) }
        val grey = UIUtil.getContextHelpForeground()
        p.checks.forEachIndexed { i, c ->
            val text = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false
                add(markdown(c.step))
                if (c.expected.isNotBlank()) add(wrapped("Expected: " + Md.plain(c.expected)).apply { foreground = grey })
            }
            val lead: JComponent = if (pending) JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                add(com.intellij.ui.components.JBCheckBox().apply {
                    isSelected = c.result == 1; isOpaque = false; toolTipText = "Works"
                    addActionListener { c.result = if (isSelected) 1 else 0; service.fireChanged() }
                })
                add(JBLabel("${i + 1}").apply { foreground = grey; border = JBUI.Borders.emptyLeft(2) })
            } else {
                val icon = when (c.result) { 1 -> AllIcons.RunConfigurations.TestPassed; 2 -> AllIcons.RunConfigurations.TestFailed; else -> AllIcons.RunConfigurations.TestNotRan }
                JBLabel("${i + 1}", icon, JBLabel.LEFT).apply { verticalAlignment = JBLabel.TOP; foreground = grey }
            }
            body.add(JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
                isOpaque = false; alignmentX = LEFT_ALIGNMENT
                add(lead, BorderLayout.WEST)
                add(text, BorderLayout.CENTER)
                if (pending) add(JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                    isOpaque = false
                    add(com.intellij.ui.InplaceButton(com.intellij.openapi.ui.popup.IconButton("Add a note to this step", HandoffIcons.note())) {
                        // Zuklappen nur ohne Text - sonst ginge die Notiz still verloren.
                        c.noteOpen = !c.noteOpen || c.note.isNotBlank(); c.focusNote = c.noteOpen; service.fireChanged()
                    }.apply { border = JBUI.Borders.empty(2, 4) })
                }, BorderLayout.EAST)
            })
            if (pending && (c.noteOpen || c.note.isNotBlank())) {
                val field = JBTextField(c.note).apply { emptyText.text = "Note on this step, e.g. what happened instead" }
                field.document.addDocumentListener(object : DocumentAdapter() { override fun textChanged(e: DocumentEvent) { c.note = field.text } })
                body.add(Dictation.withMic(field).apply { alignmentX = LEFT_ALIGNMENT; border = JBUI.Borders.emptyLeft(JBUI.scale(28)) })
                if (c.focusNote) { c.focusNote = false; SwingUtilities.invokeLater { field.requestFocusInWindow() } }
            } else if (!pending && c.note.isNotBlank()) body.add(wrapped(c.note).apply { foreground = grey; border = JBUI.Borders.emptyLeft(JBUI.scale(28)) })
            body.add(Box.createVerticalStrut(JBUI.scale(4)))
        }
        card.add(body, BorderLayout.CENTER)
        if (!pending) return card
        val note = JBTextField(p.noteDraft).apply { emptyText.text = "Note for Claude (optional)" }
        note.document.addDocumentListener(object : DocumentAdapter() { override fun textChanged(e: DocumentEvent) { p.noteDraft = note.text } })
        val send = JButton("Send Results").apply {
            primary()
            toolTipText = "Unchecked steps go to Claude as failed if they have a note, otherwise as not done"
            addActionListener {
                p.checks.forEach { if (it.result != 1) it.result = if (it.note.isNotBlank()) 2 else 0 }
                p.note = p.noteDraft.takeIf { it.isNotBlank() }; service.answer(p)
            }
        }
        val dismiss = dismissButton("Dismiss").apply { addActionListener { service.dismiss(p, note.text) } }
        card.add(JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = false
            add(Dictation.withMic(note), BorderLayout.NORTH)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                add(send); add(Box.createHorizontalStrut(JBUI.scale(6))); add(dismiss)
            }, BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)
        return card
    }

    /** Geheimnis eingeben: Das Plugin schreibt den Wert in die Datei, Claude erfährt nur, dass er gesetzt ist. */
    private fun secretCard(p: Proposal): JPanel {
        val r = p.secret!!
        val pending = p.isPending
        val accent = when (p.state) {
            Proposal.State.PENDING -> JBColor(0xB04079, 0xC86A9A)
            Proposal.State.FINISHED -> JBColor(0x5A9E6F, 0x5A9E6F)
            else -> JBColor.border()
        }
        val card = cardPanel(accent)
        cardHead(card, when (p.state) { Proposal.State.PENDING -> "Secret"; Proposal.State.FINISHED -> "Secret saved"; else -> "Dismissed" + (p.note?.let { ": $it" } ?: "") }, accent, p)
        val base = project.basePath.orEmpty().trimEnd('/') + "/"
        val where = r.file.path.removePrefix(base).replace(System.getProperty("user.home"), "~") + (if (r.key.isNotEmpty()) " · ${r.key}" else " (whole file)")
        card.add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false
            add(wrapped(p.reason))
            add(grey(where))
        }, BorderLayout.CENTER)
        if (!pending) return card
        val field = javax.swing.JPasswordField().apply { toolTipText = "Goes straight into the file - Claude never sees it" }
        val save = JButton("Save").apply { primary(); isEnabled = false }
        field.document.addDocumentListener(object : DocumentAdapter() { override fun textChanged(e: DocumentEvent) { save.isEnabled = field.password.isNotEmpty() } })
        fun store() { if (field.password.isNotEmpty()) { val v = field.password; field.text = ""; service.saveSecret(p, v) } }
        save.addActionListener { store() }
        field.addActionListener { store() }
        val dismiss = dismissButton("Dismiss").apply { addActionListener { service.dismiss(p, null) } }
        card.add(JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            isOpaque = false
            add(field, BorderLayout.NORTH)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                add(save); add(Box.createHorizontalStrut(JBUI.scale(6))); add(dismiss)
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
            add(JBLabel(status).apply { foreground = if (accent == JBColor.border()) UIUtil.getContextHelpForeground() else accent; toolTipText = status; border = JBUI.Borders.emptyRight(8) }, BorderLayout.CENTER)
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
            all.primary()
            val one = JButton("Run Step ${next + 1}").apply {
                toolTipText = "Runs only the next step"
                addActionListener { start(false) }
            }
            // Ausstehende Schritte mit && verbunden: Wie beim Ablauf stoppt die Shell beim ersten Fehler.
            val copy = copyButton("Copies the remaining steps, joined with &&, to run them yourself") {
                p.steps.filter { it.exitCode == null }.joinToString(" && \\\n") { s ->
                    fields.firstOrNull { it.first === s }?.second?.text?.trim()?.takeIf { it.isNotEmpty() } ?: s.command
                }
            }
            val dismiss = dismissButton("Dismiss…").apply {
                addActionListener {
                    val note = Messages.showInputDialog(project, "Optional note for Claude:", "Dismiss Commands", null)
                    if (note != null) service.dismiss(p, note)
                }
            }
            card.add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                listOf(all, one, copy, dismiss).forEachIndexed { i, b -> if (i > 0) add(Box.createHorizontalStrut(JBUI.scale(6))); add(b) }
            }, BorderLayout.SOUTH)
        }
        return card
    }

    private fun card(p: Proposal): JPanel {
        if (p.review) return reviewCard(p)
        if (p.steps.isNotEmpty()) return sequenceCard(p)
        if (p.images.isNotEmpty()) return imageCard(p)
        if (p.isAsk) return askCard(p)
        if (p.isChecklist) return checklistCard(p)
        if (p.secret != null) return secretCard(p)
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
            add(JBLabel(status).apply { foreground = if (accent == JBColor.border()) UIUtil.getContextHelpForeground() else accent; toolTipText = status; border = JBUI.Borders.emptyRight(8) }, BorderLayout.CENTER)
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
            run.primary()
            val copy = copyButton("Copies the command to run it yourself") { command.text.trim() }
            val dismiss = dismissButton("Dismiss…").apply {
                addActionListener {
                    val note = Messages.showInputDialog(project, "Optional note for Claude:", "Dismiss Command", null)
                    if (note != null) service.dismiss(p, note)
                }
            }
            card.add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                listOf(run, copy, dismiss).forEachIndexed { i, b -> if (i > 0) add(Box.createHorizontalStrut(JBUI.scale(6))); add(b) }
            }, BorderLayout.SOUTH)
        }
        // Höhe begrenzen (sonst streckt die Liste die Karte), aber nach jeder Breitenänderung neu: Der umbrechende Befehl wird
        // erst mit der echten Breite so hoch, wie er ist - die Höhe beim Aufbau schnitt lange Befehle ab (2026-11-16).
        card.maximumSize = Dimension(Int.MAX_VALUE, card.preferredSize.height)
        card.addComponentListener(object : java.awt.event.ComponentAdapter() {
            // Erst nach dem Auslegen der Kinder (dann kennt das Textfeld seine Breite) - deshalb per invokeLater.
            override fun componentResized(e: java.awt.event.ComponentEvent) = javax.swing.SwingUtilities.invokeLater {
                val h = card.preferredSize.height
                if (card.maximumSize.height != h) { card.maximumSize = Dimension(Int.MAX_VALUE, h); card.parent?.revalidate() }
            }
        })
        return card
    }

    private val thumbs = HashMap<String, Icon?>()

    // Schlüssel mit Änderungszeit und Größe: Claude überschreibt Bilder oft unter gleichem Namen (2026-11-15 alte Vorschau).
    private fun thumb(path: String): Icon? = thumbs.getOrPut(File(path).let { "$path|${it.lastModified()}|${it.length()}" }) {
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
        val dialog = MarkupDialog(project, p.images, i, HashMap(p.marks), editable, HashMap(p.shapes))
        if (dialog.showAndGet() && editable) {
            dialog.allMarks.forEach { (k, v) -> if (v.isEmpty()) p.marks.remove(k) else p.marks[k] = v.toList() }
            dialog.allShapes.forEach { (k, v) ->
                if (v == p.shapes[k].orEmpty()) return@forEach
                if (v.isEmpty()) { p.shapes.remove(k); p.drawn.remove(k) }
                else { p.shapes[k] = v.map { it.copy() }; Drawing.save(p.images[k], v)?.let { p.drawn[k] = it } }
            }
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
            Proposal.State.FINISHED -> if (single) "Approved" else if (p.chosen == Proposal.ALL) "Approved all" else "Chose ${(p.chosen ?: 0) + 1}"
            else -> "Dismissed"
        } + (p.note?.let { ": $it" } ?: "")
        card.add(JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JBLabel(status).apply { foreground = if (accent == JBColor.border()) UIUtil.getContextHelpForeground() else accent; toolTipText = status; border = JBUI.Borders.emptyRight(8) }, BorderLayout.CENTER)
            add(JBLabel(time.format(Date(p.createdAt))).apply { foreground = UIUtil.getContextHelpForeground() }, BorderLayout.EAST)
        }, BorderLayout.NORTH)

        val grid = JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(6), JBUI.scale(6))).apply { isOpaque = false }
        val choose = JButton(if (single) "Approve" else "Choose")
        var paintSelection: () -> Unit = {}
        val tiles = p.images.mapIndexed { i, path ->
            val name = File(path).name
            val count = p.marks[i]?.size ?: 0
            val caption = (if (single) name else "${i + 1} · $name") + (if (count > 0) " · $count marked" else "") +
                (if (!p.shapes[i].isNullOrEmpty()) " · drawn" else "")
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
            choose.primary()
            choose.addActionListener { p.selected?.let { service.choose(p, it, note.text) } }
            val dismiss = dismissButton("Dismiss").apply { addActionListener { service.dismiss(p, note.text) } }
            // Mehrere Bilder sind oft Vorschauen zum Prüfen, nicht zur Wahl: alle auf einmal freigeben (2026-11-18).
            val approveAll = JButton("Approve All").apply {
                toolTipText = "Approve all images instead of choosing one"
                addActionListener { service.choose(p, Proposal.ALL, note.text) }
            }
            card.add(JPanel(BorderLayout(0, JBUI.scale(6))).apply {
                isOpaque = false
                add(Dictation.withMic(note), BorderLayout.NORTH)
                add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                    isOpaque = false; add(choose); add(Box.createHorizontalStrut(JBUI.scale(6)))
                    if (!single) { add(approveAll); add(Box.createHorizontalStrut(JBUI.scale(6))) }
                    add(dismiss)
                }, BorderLayout.SOUTH)
            }, BorderLayout.SOUTH)
        }
        return card
    }

    override fun dispose() {}
}
