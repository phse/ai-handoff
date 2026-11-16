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

    fun show(project: Project, tab: String) { guard("show") { bridge(project).show(tab) } }

    /**
     * Vorschlag für den nächsten Prompt, den Claude Code nach einer Antwort grau ins Eingabefeld schreibt (Tab übernimmt ihn
     * dort). Steht nirgends gespeichert - nur auf dem Bildschirm, in der Zeile hinter "❯". Handoff tippt nie ins Feld des
     * Terminals, also ist Text dort der Vorschlag (oder etwas, das jemand direkt im Terminal tippt).
     */
    fun promptSuggestion(project: Project, tab: String): String? = guard("suggestion") { bridge(project).promptSuggestion(tab) }

    private fun <T> guard(what: String, block: () -> T): T? {
        if (!available) return null
        return try { block() } catch (e: LinkageError) { log.warn("Handoff: terminal API not usable ($what)", e); null }
        catch (e: RuntimeException) { log.warn("Handoff: terminal call failed ($what)", e); null }
    }
}

/** Alles, was die Terminal-API direkt anfasst. Wird erst geladen, wenn [SessionTerminals.available] stimmt. */
@Service(Service.Level.PROJECT)
class TerminalBridge(private val project: Project) {
    private val tabs = ConcurrentHashMap<String, TerminalToolWindowTab>()

    fun hasTabs() = tabs.isNotEmpty()

    private companion object { const val ENTER_DELAY_MS = 250 }

    fun start(command: String, dir: String?): String {
        val id = UUID.randomUUID().toString()
        val tab = TerminalToolWindowTabsManager.getInstance(project).createTabBuilder()
            .workingDirectory(dir)
            .envVariables(mapOf("HANDOFF_TAB" to id))
            .tabName("Claude")
            .requestFocus(false)
            // Sonst startet die Shell erst, wenn jemand das Terminal-Fenster öffnet - es soll im Hintergrund bleiben.
            .deferSessionStartUntilUiShown(false)
            .createTab()
        tabs[id] = tab
        tab.view.createSendTextBuilder().shouldExecute().send(command)
        return id
    }

    fun adopt(ref: String): String? {
        val manager = TerminalToolWindowTabsManager.getInstance(project)
        val tab = manager.tabs.firstOrNull { t ->
            val models = t.view.outputModels
            listOf(models.regular, models.alternative).any { m ->
                // Nur das Ende der Ausgabe - die Kennung ist gerade erst erschienen.
                val start = maxOf(m.startOffset, m.endOffset.minus(20_000L))
                m.getText(start, m.endOffset).contains(ref)
            }
        } ?: return null
        tabs.entries.firstOrNull { it.value == tab }?.let { return it.key }
        val id = UUID.randomUUID().toString()
        tabs[id] = tab
        return id
    }

    /** Muss auf dem EDT laufen ([TerminalToolWindowTabsManager.tabs]). Geschlossene Reiter fallen dabei aus der Liste. */
    fun isOpen(id: String): Boolean {
        val tab = tabs[id] ?: return false
        if (tab in TerminalToolWindowTabsManager.getInstance(project).tabs) return true
        tabs.remove(id)
        return false
    }

    fun send(id: String, text: String, execute: Boolean, enter: String = "\r"): Boolean {
        if (!isOpen(id)) return false
        val view = tabs[id]!!.view
        // Mehrzeilig nicht als Einfügen (bracketed paste): Claude Code schließt Eingefügtes in <pasted_content id="…"> ein, und
        // Remote Control zeigt die Marker an. Esc+Enter ist der Zeilenumbruch von Claude Code (wie Shift+Enter) - so kommt der
        // Text als ein getippter Prompt an (2026-11-15 per PTY mit Claude Code 2.1.325 geprüft, auch ohne Pausen).
        view.createSendTextBuilder().send(text.replace("\r\n", "\n").replace("\n", "\u001b\r"))
        // Enter getrennt und etwas später: Kommt "Text\r" in einem Stück, hält Claude Code es für eingefügten Text und macht
        // aus dem \r einen Zeilenumbruch (Sandbox 2026-11-14, nicht jedes Mal) - der Prompt bliebe ungesendet im Feld stehen.
        if (execute) javax.swing.Timer(ENTER_DELAY_MS) { if (isOpen(id)) view.sendText(enter) }.apply { isRepeats = false; start() }
        return true
    }

    fun promptSuggestion(id: String): String? {
        if (!isOpen(id)) return null
        val models = tabs[id]!!.view.outputModels
        for (m in listOf(models.alternative, models.regular)) {
            val start = maxOf(m.startOffset, m.endOffset.minus(6_000L))
            val line = m.getText(start, m.endOffset).lines().lastOrNull { it.trimStart().startsWith("❯") } ?: continue
            return line.trimStart().removePrefix("❯").replace('\u00a0', ' ').trim().trim('│').trim().takeIf { it.isNotEmpty() }
        }
        return null
    }

    fun shell(dir: String) {
        TerminalToolWindowTabsManager.getInstance(project).createTabBuilder().workingDirectory(dir).createTab()
        ToolWindowManager.getInstance(project).getToolWindow("Terminal")?.activate(null)
    }

    fun show(id: String) {
        val tab = tabs[id]?.takeIf { isOpen(id) } ?: return
        val window = ToolWindowManager.getInstance(project).getToolWindow("Terminal") ?: return
        window.activate {
            window.contentManager.setSelectedContent(tab.content, true)
        }
    }
}
