// Die Terminal-API ist experimentell/intern markiert. Diese Datei ist bewusst die einzige Stelle, die sie anfasst (Wrapper
// SessionTerminals → TerminalBridge, Fehler werden in guard() abgefangen) - die Warnungen dazu hier gesammelt unterdrücken,
// statt sie an jedem Aufruf zu sehen. Der Plugin Verifier listet die Nutzung weiterhin.
@file:Suppress("UnstableApiUsage")

package app.heckit.ide.aihandoff

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Claude-Code-Sessions, die Handoff im Terminal der IDE startet. Jeder Reiter bekommt eine Kennung als Umgebungsvariable
 * `HANDOFF_TAB`; Claude Code reicht sie an die Hooks weiter, die sie neben die Session-Daten legen (`<id>.tab`). So weiß
 * Handoff, in welchem Reiter eine Session läuft, und kann ihr Eingaben schicken - wie getippt, also auch Slash-Befehle.
 *
 * Die Terminal-API ([TerminalToolWindowTabsManager], neues Terminal) ist als experimentell markiert; alle Aufrufe laufen
 * über [TerminalBridge], damit ein Fehlen oder eine Änderung nur diese Funktion abschaltet.
 */
object SessionTerminals {
    private val log = logger<SessionTerminals>()

    private fun bridge(project: Project) = project.getService(TerminalBridge::class.java)

    /** Ob das Terminal der IDE die nötige API hat (neues Terminal, ab 2026.1). */
    val available: Boolean by lazy {
        runCatching { Class.forName("com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager"); true }.getOrDefault(false)
    }

    /** Neuer Terminal-Reiter mit `claude` in [dir] (sonst im Projektordner); liefert die Kennung des Reiters oder null. */
    fun start(project: Project, command: String = "claude", dir: String? = null): String? =
        guard("start") { bridge(project).start(command, dir ?: project.basePath) }

    fun isOpen(project: Project, tab: String): Boolean = guard("isOpen") { bridge(project).isOpen(tab) } ?: false

    /** Irgendein von Handoff gestarteter Reiter offen (ohne EDT, nur für die Abfragehäufigkeit). */
    fun anyOpen(project: Project): Boolean = available && project.getServiceIfCreated(TerminalBridge::class.java)?.hasTabs() == true

    /** Prompt schicken und abschicken (Enter). Zeilenumbrüche als Esc+Enter, damit Claude Code die Zeilen nicht einzeln abschickt. */
    fun send(project: Project, tab: String, text: String): Boolean = guard("send") { bridge(project).send(tab, text, execute = true) } ?: false

    /**
     * Wie [send], aber sofort statt nach dem laufenden Schritt (Ctrl+Enter in Claude Code, `chat:sendNow`): Claude bricht den
     * laufenden Werkzeugaufruf ab und liest den Prompt gleich. Gesendet als Akkord Ctrl+X Ctrl+S, den Claude Code ebenfalls
     * an `chat:sendNow` bindet - Ctrl+Enter selbst kommt im Terminal nicht eindeutig an (per PTY mit 2.1.325 geprüft, 2026-11-16).
     */
    fun sendNow(project: Project, tab: String, text: String): Boolean = guard("sendNow") { bridge(project).send(tab, text, execute = true, enter = "\u0018\u0013") } ?: false

    /** Einzelne Tasten ohne Enter, z. B. "1" für eine Auswahl oder Esc. */
    fun keys(project: Project, tab: String, keys: String): Boolean = guard("keys") { bridge(project).send(tab, keys, execute = false) } ?: false

    /**
     * Einen Terminal-Reiter übernehmen, den Handoff nicht selbst gestartet hat: den, dessen Ausgabe [ref] enthält. Die Ausgabe
     * erscheint erst kurz nach dem Hook - deshalb einige Versuche. [found] bekommt die neue Kennung des Reiters.
     */
    fun adopt(project: Project, ref: String, tries: Int = 8, found: (String) -> Unit) {
        val id = guard("adopt") { bridge(project).adopt(ref) }
        if (id != null) { found(id); return }
        if (tries > 1) javax.swing.Timer(500) { adopt(project, ref, tries - 1, found) }.apply { isRepeats = false; start() }
    }

    /** Normales Terminal (ohne Claude) in einem Ordner. */
    fun shell(project: Project, dir: String) { guard("shell") { bridge(project).shell(dir) } }

    /** Terminal-Reiter schließen (beendet Claude dort; die Session lässt sich später fortsetzen). */
    fun closeOrphans(project: Project) { if (available) guard("orphans") { bridge(project).closeOrphans() } }

    fun close(project: Project, tab: String) { guard("close") { bridge(project).close(tab) } }

    /** Terminal-Reiter nach der Session benennen statt "Claude" - sonst sind viele Reiter nicht zu unterscheiden. */
    fun rename(project: Project, tab: String, name: String) { guard("rename") { bridge(project).rename(tab, name) } }

    fun show(project: Project, tab: String) { guard("show") { bridge(project).show(tab) } }

    /**
     * Vorschlag für den nächsten Prompt, den Claude Code nach einer Antwort grau ins Eingabefeld schreibt (Tab übernimmt ihn
     * dort). Steht nirgends gespeichert - nur auf dem Bildschirm, in der Zeile hinter "❯". Getippter Text steht dort ebenso,
     * aber in der normalen Schriftfarbe - der zählt nicht als Vorschlag (siehe [typedInput]).
     */
    fun promptSuggestion(project: Project, tab: String): String? =
        guard("suggestion") { bridge(project).promptLine(tab)?.takeUnless { it.second }?.first }

    /**
     * Text, der schon im Eingabefeld des Terminals steht (direkt getippt oder per „Send to Claude Code“ als `@datei`
     * eingefügt). Ein Prompt aus dem Handoff-Reiter hinge sich hinten an und ginge mit ab (2026-11-17).
     */
    fun typedInput(project: Project, tab: String): String? =
        guard("typedInput") { bridge(project).promptLine(tab)?.takeIf { it.second }?.first }

    private fun <T> guard(what: String, block: () -> T): T? {
        if (!available) return null
        return try { block() } catch (e: LinkageError) { log.warn("Handoff: terminal API not usable ($what)", e); null }
        catch (e: RuntimeException) { log.warn("Handoff: terminal call failed ($what)", e); null }
    }
}

/**
 * Alles, was Handoff mit Terminal-Reitern macht, ohne die Terminal-API selbst anzufassen - das tut nur [TerminalApi].
 * Wird erst geladen, wenn [SessionTerminals.available] stimmt.
 */
@Service(Service.Level.PROJECT)
class TerminalBridge(private val project: Project) {
    /** Kennung → Reiter (ein `TerminalToolWindowTab`, hier bewusst als Any - siehe [TerminalApi]). */
    private val tabs = ConcurrentHashMap<String, Any>()

    fun hasTabs() = tabs.isNotEmpty()
    fun owns(id: String) = tabs.containsKey(id)

    companion object {
        private const val ENTER_DELAY_MS = 250
        /** Namenskonvention für Handoffs Terminal-Reiter - nur solche räumt [closeOrphans] weg, nie "Claude" o. Ä. des Nutzers. */
        const val TAB_NAME = "Claude (Handoff)"
    }

    /**
     * Verwaiste Claude-Reiter schließen: Nach einem IDE-Neustart stellt das Terminal unsere Reiter ("Claude (Handoff) …") als
     * leere Shells wieder her - Claude läuft darin nicht mehr, [Resume] startet einen neuen Reiter, die alten blieben stehen.
     * Nur Reiter mit unserem Namen, die wir in dieser Sitzung nicht selbst geöffnet haben.
     */
    fun closeOrphans() {
        val ours = tabs.values.toSet()
        TerminalApi.tabs(project).filter { t -> t !in ours && TerminalApi.content(t).displayName.let { it == TAB_NAME || it.startsWith("$TAB_NAME ") } }
            .forEach { TerminalApi.close(project, it) }
    }

    fun start(command: String, dir: String?): String {
        closeOrphans()
        // Kennung mit der Prozessnummer der IDE: Die Hook-Daten liegen für alle IDEs im selben Ordner - so erkennt eine
        // andere IDE (z. B. die Sandbox neben der normalen), dass die Session nicht zu ihr gehört (2026-11-18).
        val id = "p${ProcessHandle.current().pid()}-" + UUID.randomUUID()
        // Sonst startet die Shell erst, wenn jemand das Terminal-Fenster öffnet - es soll im Hintergrund bleiben.
        val tab = TerminalApi.create(project, dir, mapOf("HANDOFF_TAB" to id), TAB_NAME, background = true)
        tabs[id] = tab
        TerminalApi.send(tab, command, execute = true)
        return id
    }

    fun adopt(ref: String): String? {
        // Nur das Ende der Ausgabe - die Kennung ist gerade erst erschienen.
        val tab = TerminalApi.tabs(project).firstOrNull { t -> TerminalApi.outputTails(t, 20_000L).any { it.text.contains(ref) } } ?: return null
        tabs.entries.firstOrNull { it.value == tab }?.let { return it.key }
        val id = UUID.randomUUID().toString()
        tabs[id] = tab
        return id
    }

    /** Muss auf dem EDT laufen (Liste der Reiter). Geschlossene Reiter fallen dabei aus der Liste. */
    fun isOpen(id: String): Boolean {
        val tab = tabs[id] ?: return false
        if (tab in TerminalApi.tabs(project)) return true
        tabs.remove(id)
        return false
    }

    fun send(id: String, text: String, execute: Boolean, enter: String = "\r"): Boolean {
        if (!isOpen(id)) return false
        val tab = tabs[id]!!
        // Mehrzeilig nicht als Einfügen (bracketed paste): Claude Code schließt Eingefügtes in <pasted_content id="…"> ein, und
        // Remote Control zeigt die Marker an. Esc+Enter ist der Zeilenumbruch von Claude Code (wie Shift+Enter) - so kommt der
        // Text als ein getippter Prompt an (2026-11-15 per PTY mit Claude Code 2.1.325 geprüft, auch ohne Pausen).
        TerminalApi.send(tab, text.replace("\r\n", "\n").replace("\n", "\u001b\r"), execute = false)
        // Enter getrennt und etwas später: Kommt "Text\r" in einem Stück, hält Claude Code es für eingefügten Text und macht
        // aus dem \r einen Zeilenumbruch (Sandbox 2026-11-14, nicht jedes Mal) - der Prompt bliebe ungesendet im Feld stehen.
        if (execute) javax.swing.Timer(ENTER_DELAY_MS) { if (isOpen(id)) TerminalApi.send(tab, enter, execute = false) }.apply { isRepeats = false; start() }
        return true
    }

    /**
     * Text hinter "❯" und ob er getippt ist: Bei Vorschlag und Platzhalter (grau) steht der Cursor direkt hinter "❯ ", bei
     * Getipptem dahinter (per PTY mit Claude Code 2.1.325 geprüft, 2026-11-17). Die Schriftfarbe wäre eindeutiger, ist aber
     * nur über interne API zu lesen.
     */
    fun promptLine(id: String): Pair<String, Boolean>? {
        if (!isOpen(id)) return null
        // Alternativer Bildschirm zuerst - dort läuft Claude Code.
        for (tail in TerminalApi.outputTails(tabs[id]!!, 6_000L).reversed()) {
            val all = tail.text
            val lineStart = all.lines().let { ls ->
                val i = ls.indexOfLast { it.trimStart().startsWith("❯") }
                if (i < 0) -1 else ls.take(i).sumOf { it.length + 1 }
            }
            if (lineStart < 0) continue
            val line = all.substring(lineStart).substringBefore('\n')
            val text = line.trimStart().removePrefix("❯").replace(' ', ' ').trim().trim('│').trim().takeIf { it.isNotEmpty() } ?: return null
            val inputAt = lineStart + line.indexOf('❯') + 2
            return text to (tail.cursor != null && tail.cursor > inputAt)
        }
        return null
    }

    fun shell(dir: String) {
        TerminalApi.create(project, dir, emptyMap(), null, background = false)
        ToolWindowManager.getInstance(project).getToolWindow("Terminal")?.activate(null)
    }

    fun close(id: String) {
        val tab = tabs[id]?.takeIf { isOpen(id) } ?: return
        tabs.remove(id)
        TerminalApi.close(project, tab)
    }

    fun rename(id: String, name: String) {
        val content = tabs[id]?.takeIf { isOpen(id) }?.let { TerminalApi.content(it) } ?: return
        if (content.displayName != name) content.displayName = name
    }

    fun show(id: String) {
        val content = tabs[id]?.takeIf { isOpen(id) }?.let { TerminalApi.content(it) } ?: return
        val window = ToolWindowManager.getInstance(project).getToolWindow("Terminal") ?: return
        window.activate { window.contentManager.setSelectedContent(content, true) }
    }
}

/** Ende einer Terminal-Ausgabe: Text und Position des Cursors darin (null, wenn er davor steht). */
class TerminalTail(val text: String, val cursor: Int?)

/**
 * Die einzige Stelle, die die experimentelle Terminal-API anfasst. Der Marketplace (Plugin Verifier) zählt jede Kombination
 * aus API-Element und aufrufender Methode einzeln - verteilt über [TerminalBridge] waren es 91 Meldungen (1.6.2). Hier nutzt
 * jede Methode nur ihre eigenen Elemente, Reiter laufen nach außen als Any.
 */
private object TerminalApi {
    private fun manager(project: Project) = TerminalToolWindowTabsManager.getInstance(project)

    fun tabs(project: Project): List<Any> = manager(project).tabs

    fun close(project: Project, tab: Any) = manager(project).closeTab(tab as TerminalToolWindowTab)

    fun create(project: Project, dir: String?, env: Map<String, String>, name: String?, background: Boolean): Any {
        var b = manager(project).createTabBuilder().workingDirectory(dir)
        if (env.isNotEmpty()) b = b.envVariables(env)
        if (name != null) b = b.tabName(name)
        if (background) b = b.requestFocus(false).deferSessionStartUntilUiShown(false)
        return b.createTab()
    }

    fun content(tab: Any): com.intellij.ui.content.Content = (tab as TerminalToolWindowTab).content

    fun send(tab: Any, text: String, execute: Boolean) {
        val builder = (tab as TerminalToolWindowTab).view.createSendTextBuilder()
        (if (execute) builder.shouldExecute() else builder).send(text)
    }

    /** Die letzten [chars] Zeichen der normalen und der alternativen Ausgabe (in dieser Reihenfolge). */
    fun outputTails(tab: Any, chars: Long): List<TerminalTail> {
        val models = (tab as TerminalToolWindowTab).view.outputModels
        return listOf(models.regular, models.alternative).map { m ->
            val start = maxOf(m.startOffset, m.endOffset.minus(chars))
            TerminalTail(m.getText(start, m.endOffset).toString(), (m.cursorOffset - start).takeIf { it >= 0 }?.toInt())
        }
    }
}
