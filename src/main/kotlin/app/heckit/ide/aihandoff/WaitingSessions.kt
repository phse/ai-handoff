package app.heckit.ide.aihandoff

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * "Claude wartet auf dich": Hooks von Claude Code legen je Session Dateien in `~/.claude/handoff/waiting/` ab:
 * - `<id>.info`: letzte Hook-Daten der Session (Arbeitsordner, Protokoll), bei jedem Ereignis neu; `<id>.tab`: Kennung des
 *   Terminal-Reiters, wenn Handoff die Session gestartet hat (Umgebungsvariable `HANDOFF_TAB`).
 * - `<id>.busy`: Claude arbeitet (UserPromptSubmit), `<id>.json`: fertig (Stop) oder braucht eine Eingabe (Notification).
 * - `<id>.open`: `/show-in-editor` - die IDE soll die Session im Editor-Reiter zeigen.
 * SessionEnd räumt alles weg. Eingerichtet wie die Statuszeile: ein Skript in `~/.claude/handoff/` und Einträge in
 * `~/.claude/settings.json`.
 */
object WaitingHook {
    private val dir = File(System.getProperty("user.home"), ".claude/handoff")
    val script = File(dir, "hook.sh")
    val waiting = File(dir, "waiting")
    /** Befehl `/show-in-editor` für Claude Code; der UserPromptSubmit-Hook fängt ihn ab, bevor Claude ihn liest. */
    val command = File(System.getProperty("user.home"), ".claude/commands/show-in-editor.md")
    private const val COMMAND = "~/.claude/handoff/hook.sh"
    private const val MARK = "handoff/hook.sh"
    const val SHOW_MARK = "HANDOFF_SHOW_IN_EDITOR"
    /** Für Sessions im Handoff-Reiter (SessionStart): Links öffnen sich dort in der IDE - also möglichst viel verlinken. */
    private const val LINK_HINT = "The user reads this session in the Handoff editor tab of their JetBrains IDE, where Markdown links open in the IDE. " +
        "Link as much as possible: files and folders as [name](/absolute/path:line), web pages as plain URLs, settings pages as " +
        "[Plugins](settings:Plugins), IDE actions as [Restart IDE](action:RestartIde) (only action ids you have verified). " +
        "Right-clicking a link offers Reveal in Finder and Open in Terminal."

    /**
     * Ereignis → Argument des Skripts und Filter. Bei Notification nur, was eine Eingabe braucht (idle_prompt deckt Stop schon ab).
     * Stop läuft als asyncRewake-Hook im Hintergrund: Er legt die Datei ab und wartet auf eine Antwort aus der IDE
     * (`<id>.reply`). Kommt eine, gibt er sie auf stderr aus und endet mit Code 2 - Claude Code weckt die Session dann mit
     * diesem Text. Tippt der Nutzer stattdessen im Terminal, löscht UserPromptSubmit die Datei und der Hook endet still.
     */
    private val EVENTS = listOf(
        Triple("SessionStart", "start", null),
        Triple("Stop", "listen", null),
        Triple("Notification", "wait", "permission_prompt|elicitation_dialog|agent_needs_input"),
        Triple("UserPromptSubmit", "busy", null),
        Triple("SessionEnd", "end", null),
    )

    private fun JsonObject.isOurs() = getAsJsonArray("hooks")?.any {
        it.isJsonObject && it.asJsonObject.get("command")?.takeIf { c -> c.isJsonPrimitive }?.asString?.contains(MARK) == true
    } == true

    fun isInstalled(): Boolean = script.isFile && isInstalledIn(PlanLimits.readSettings())

    fun isInstalledIn(settings: JsonObject): Boolean =
        runCatching { settings.getAsJsonObject("hooks")?.getAsJsonArray("Stop")?.any { it.isJsonObject && it.asJsonObject.isOurs() } }.getOrNull() == true

    /** Unsere Einträge aus den Einstellungen nehmen; fremde Hooks bleiben. */
    fun removeFrom(settings: JsonObject) {
        val hooks = settings.get("hooks")?.takeIf { it.isJsonObject }?.asJsonObject ?: return
        for (event in hooks.keySet().toList()) {
            val groups = hooks.get(event).takeIf { it.isJsonArray }?.asJsonArray ?: continue
            val kept = JsonArray().apply { groups.filterNot { it.isJsonObject && it.asJsonObject.isOurs() }.forEach(::add) }
            if (kept.isEmpty) hooks.remove(event) else hooks.add(event, kept)
        }
        if (hooks.size() == 0) settings.remove("hooks")
    }

    fun addTo(settings: JsonObject) {
        removeFrom(settings)
        val hooks = settings.get("hooks")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject().also { settings.add("hooks", it) }
        for ((event, arg, matcher) in EVENTS) {
            val groups = hooks.get(event)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray().also { hooks.add(event, it) }
            groups.add(JsonObject().apply {
                matcher?.let { addProperty("matcher", it) }
                add("hooks", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("type", "command"); addProperty("command", "$COMMAND $arg")
                        if (arg == "listen") { addProperty("asyncRewake", true); addProperty("timeout", LISTEN_SECONDS) }
                    })
                })
            })
        }
    }

    /** So lange wartet ein Stop-Hook auf eine Antwort aus der IDE (danach bleibt nur das Terminal). */
    private const val LISTEN_SECONDS = 86400

    private val SCRIPT = """
        |#!/bin/bash
        |# Handoff for Claude Code: tells the IDE plugin what a Claude Code session is doing.
        |# start/busy/wait/listen keep the hook data of the session, end removes it. "busy" (UserPromptSubmit) marks the
        |# session as working and catches /show-in-editor. "listen" (Stop, asyncRewake) also waits for a prompt sent from
        |# the IDE and hands it to Claude Code (stderr + exit 2 wakes the session).
        |dir="${'$'}HOME/.claude/handoff/waiting"
        |input=${'$'}(cat)
        |id=${'$'}(printf '%s' "${'$'}input" | tr -d '\n' | grep -o '"session_id" *: *"[A-Za-z0-9_-]*"' | head -1 | sed 's/.*"\([^"]*\)"${'$'}/\1/')
        |[ -n "${'$'}id" ] || exit 0
        |f="${'$'}dir/${'$'}id"
        |if [ "${'$'}1" = "end" ] || [ "${'$'}1" = "clear" ]; then
        |  rm -f "${'$'}f.json" "${'$'}f.listen" "${'$'}f.reply" "${'$'}f.busy" "${'$'}f.info" "${'$'}f.tab" "${'$'}f.open"
        |  exit 0
        |fi
        |mkdir -p "${'$'}dir" || exit 0
        |printf '%s' "${'$'}input" > "${'$'}f.itmp" && mv "${'$'}f.itmp" "${'$'}f.info"
        |[ -n "${'$'}HANDOFF_TAB" ] && printf '%s' "${'$'}HANDOFF_TAB" > "${'$'}f.tab"
        |case "${'$'}1" in
        |  start)
        |    # Sessions shown in a Handoff editor tab: tell Claude that links there open in the IDE.
        |    [ -n "${'$'}HANDOFF_TAB" ] && printf '%s' '{"hookSpecificOutput":{"hookEventName":"SessionStart","additionalContext":"$LINK_HINT"}}'
        |    exit 0 ;;
        |  busy)
        |    rm -f "${'$'}f.json" "${'$'}f.reply"
        |    if printf '%s' "${'$'}input" | grep -q -e '$SHOW_MARK' -e '"prompt" *: *"/show-in-editor'; then
        |      # The reference shows up in the terminal; the IDE finds the terminal tab of the session by it.
        |      ref="hk${'$'}(date +%s)${'$'}${'$'}"
        |      printf '%s' "${'$'}ref" > "${'$'}f.open"
        |      printf '{"decision":"block","reason":"Shown in the IDE (Handoff, %s)."}' "${'$'}ref"
        |      exit 0
        |    fi
        |    printf '%s' "${'$'}input" > "${'$'}f.btmp" && mv "${'$'}f.btmp" "${'$'}f.busy"
        |    exit 0 ;;
        |esac
        |printf '%s' "${'$'}input" > "${'$'}f.tmp" && mv "${'$'}f.tmp" "${'$'}f.json"
        |[ "${'$'}1" = "listen" ] || exit 0
        |rm -f "${'$'}f.busy"
        |echo ${'$'}${'$'} > "${'$'}f.listen"
        |end=${'$'}(( ${'$'}(date +%s) + $LISTEN_SECONDS ))
        |while [ ${'$'}(date +%s) -lt ${'$'}end ]; do
        |  # A newer Stop of the same session took over, or the session went on without us.
        |  [ "${'$'}(cat "${'$'}f.listen" 2>/dev/null)" = "${'$'}${'$'}" ] || exit 0
        |  [ -f "${'$'}f.json" ] || { rm -f "${'$'}f.listen"; exit 0; }
        |  if [ -f "${'$'}f.reply" ]; then
        |    cat "${'$'}f.reply" >&2
        |    rm -f "${'$'}f.reply" "${'$'}f.json" "${'$'}f.listen"
        |    exit 2
        |  fi
        |  sleep 1
        |done
        |rm -f "${'$'}f.listen"
        |exit 0
        |""".trimMargin()

    private val COMMAND_TEXT = """
        |---
        |description: Show this session in the IDE (Handoff editor tab)
        |---
        |$SHOW_MARK
        |
        |If you can read this, the Handoff hook did not catch the command. Do nothing else and tell the user in one sentence
        |to switch on "Show When Claude Is Waiting" in the Handoff tool window's menu and to start a new session.
        |""".trimMargin()

    /** Nur anlegen bzw. ersetzen, wenn die Datei fehlt oder von uns stammt - ein eigener Befehl gleichen Namens bleibt. */
    private fun writeCommand() {
        if (command.isFile && !command.readText().contains(SHOW_MARK)) return
        command.parentFile.mkdirs()
        if (!command.isFile || command.readText() != COMMAND_TEXT) command.writeText(COMMAND_TEXT)
    }

    fun install() {
        dir.mkdirs()
        script.writeText(SCRIPT)
        script.setExecutable(true)
        PlanLimits.writeSettings(PlanLimits.readSettings().also(::addTo))
        runCatching { writeCommand() }
    }

    /** Nach einem Plugin-Update: eingerichtete Hooks auf den Stand dieser Version bringen. */
    fun upgradeIfNeeded() {
        if (script.isFile && (script.readText() != SCRIPT || !command.isFile) && isInstalled()) install()
    }

    fun uninstall() {
        PlanLimits.writeSettings(PlanLimits.readSettings().also(::removeFrom))
        script.delete()
        if (command.isFile && command.readText().contains(SHOW_MARK)) command.delete()
        waiting.listFiles()?.forEach { it.delete() }
    }
}

/** Eine Claude-Code-Session, die auf den Nutzer wartet. */
class WaitingSession(val file: File, val at: Long, val cwd: String, val finished: Boolean, val text: String, val notificationType: String? = null) {
    val id get() = file.nameWithoutExtension
    /** Ein Stop-Hook wartet im Hintergrund auf einen weiteren Prompt aus der IDE. */
    val listening get() = finished && File(file.parentFile, "$id.listen").isFile
}

/** Eine Session, an der Claude gerade arbeitet (Prompt abgeschickt, noch kein Stop). */
class WorkingSession(val id: String, val since: Long, val cwd: String, val prompt: String)

/** Was die Hooks zuletzt über eine Session gemeldet haben. [tab]: Kennung des Terminal-Reiters, wenn Handoff sie gestartet hat. */
class SessionInfo(val id: String, val at: Long, val cwd: String, val transcript: String?, val tab: String?)

@Service(Service.Level.PROJECT)
class WaitingSessions(private val project: Project, private val scope: CoroutineScope) {
    @Volatile var sessions: List<WaitingSession> = emptyList()
        private set
    @Volatile var working: List<WorkingSession> = emptyList()
        private set
    @Volatile var infos: Map<String, SessionInfo> = emptyMap()
        private set
    private val started = AtomicBoolean()
    /** Nur Oberfläche: angefangene Prompts je Session und schon abgeschickte (bis der Hook die Datei wegräumt). */
    val drafts = java.util.concurrent.ConcurrentHashMap<String, String>()
    val sent: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Ordner alle zwei Sekunden ansehen - er liegt außerhalb des Projekts, die IDE meldet dort keine Änderungen. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            runCatching { WaitingHook.upgradeIfNeeded() }
            var first = true
            while (true) {
                runCatching { poll(notify = !first) }
                first = false
                delay(if (SessionTerminals.anyOpen(project)) 1000 else 2000)
            }
        }
    }

    fun dismiss(s: WaitingSession) {
        s.file.delete()
        sessions = sessions.filter { it !== s }
        HandoffService.getInstance(project).fireChanged()
    }

    fun dismiss(w: WorkingSession) {
        File(WaitingHook.waiting, "${w.id}.busy").delete()
        working = working.filter { it !== w }
        HandoffService.getInstance(project).fireChanged()
    }

    fun waiting(id: String?) = id?.let { i -> sessions.firstOrNull { it.id == i } }
    fun workingOn(id: String?) = id?.let { i -> working.firstOrNull { it.id == i } }

    /** Neueste Session, die in diesem Terminal-Reiter läuft (nach `/clear` beginnt dort eine neue). */
    fun sessionOfTab(tab: String): SessionInfo? = infos.values.filter { it.tab == tab }.maxByOrNull { it.at }

    /**
     * Weiterer Prompt für eine Session. Läuft sie in einem Terminal-Reiter, den Handoff gestartet hat, geht der Text dorthin -
     * wie getippt, also auch Slash-Befehle wie `/clear`. Sonst reicht ihn der wartende Stop-Hook als Text an Claude Code
     * weiter. Liefert false, wenn nichts gesendet werden konnte.
     */
    fun reply(id: String, prompt: String): Boolean {
        val text = prompt.trim().takeIf { it.isNotEmpty() } ?: return false
        val tab = infos[id]?.tab
        if (tab != null && SessionTerminals.send(project, tab, text)) {
            drafts.remove(id)
            sent += id
            HandoffService.getInstance(project).fireChanged()
            return true
        }
        val s = waiting(id)?.takeIf { it.listening } ?: return false
        val dir = s.file.parentFile
        val tmp = File(dir, "${s.id}.reply.tmp")
        tmp.writeText("The user sent this follow-up prompt from the IDE (Handoff tool window):\n\n$text\n")
        // Gleich als "arbeitet" führen - UserPromptSubmit läuft für die geweckte Session nicht.
        runCatching { s.file.copyTo(File(dir, "${s.id}.busy"), overwrite = true) }
        tmp.renameTo(File(dir, "${s.id}.reply"))
        drafts.remove(s.id)
        sent += s.id
        HandoffService.getInstance(project).fireChanged()
        return true
    }

    /** Ob [reply] für diese Session etwas senden kann, und ob auch Slash-Befehle ankommen (nur über das Terminal). */
    fun canReply(id: String): Boolean = canSendKeys(id) || waiting(id)?.listening == true
    fun canSendKeys(id: String): Boolean = infos[id]?.tab?.let { SessionTerminals.isOpen(project, it) } == true

    private fun inProject(cwd: String?): Boolean {
        val base = project.basePath?.trimEnd('/') ?: return false
        val c = cwd?.trimEnd('/') ?: return false
        return c == base || c.startsWith("$base/")
    }

    private fun read(f: File) = runCatching { JsonParser.parseString(f.readText()).asJsonObject }.getOrNull()
    private fun JsonObject.str(k: String) = get(k)?.takeIf { it.isJsonPrimitive }?.asString

    private fun poll(notify: Boolean) {
        val now = System.currentTimeMillis()
        val files = WaitingHook.waiting.listFiles().orEmpty()
        // Reste abgebrochener Sessions (Terminal geschlossen, ohne SessionEnd) nach einem Tag wegräumen.
        files.filter { now - it.lastModified() > 24 * 3600_000L }.forEach { it.delete() }
        val byName = files.filter { it.isFile }.associateBy { it.name }

        val newInfos = byName.values.filter { it.name.endsWith(".info") }.mapNotNull { f ->
            val o = read(f) ?: return@mapNotNull null
            val cwd = o.str("cwd")?.trimEnd('/')
            if (!inProject(cwd)) return@mapNotNull null
            val id = f.name.removeSuffix(".info")
            SessionInfo(id, f.lastModified(), cwd!!, o.str("transcript_path"), byName["$id.tab"]?.let { runCatching { it.readText().trim() }.getOrNull() })
        }.associateBy { it.id }

        val found = byName.values.filter { it.name.endsWith(".json") }.mapNotNull { f ->
            val at = f.lastModified()
            val o = read(f) ?: return@mapNotNull null
            val cwd = o.str("cwd")?.trimEnd('/') ?: return@mapNotNull null
            if (!inProject(cwd)) return@mapNotNull null
            val finished = o.str("hook_event_name") == "Stop"
            // Nach einer Freigabe im Terminal arbeitet Claude weiter, ohne dass ein Hook die Datei löscht: Das Protokoll
            // der Session ist dann jünger als die Meldung.
            if (!finished && (o.str("transcript_path")?.let { File(it).lastModified() } ?: 0L) > at + 5000) return@mapNotNull null
            WaitingSession(f, at, cwd, finished, (if (finished) o.str("last_assistant_message") else o.str("message")).orEmpty().trim(), o.str("notification_type"))
        }.sortedByDescending { it.at }

        val waitingIds = found.map { it.id }.toSet()
        val newWorking = byName.values.filter { it.name.endsWith(".busy") }.mapNotNull { f ->
            val id = f.name.removeSuffix(".busy")
            if (id in waitingIds) return@mapNotNull null
            val o = read(f) ?: return@mapNotNull null
            val cwd = o.str("cwd")?.trimEnd('/')
            if (!inProject(cwd)) return@mapNotNull null
            // Abgebrochen (Esc im Terminal): kein Stop-Hook. Nach 30 Minuten ohne Bewegung im Protokoll nicht mehr zeigen.
            val last = maxOf(f.lastModified(), o.str("transcript_path")?.let { File(it).lastModified() } ?: 0L)
            if (now - last > 30 * 60_000L) return@mapNotNull null
            WorkingSession(id, f.lastModified(), cwd!!, o.str("prompt").orEmpty())
        }

        // `/show-in-editor`: Session im Editor-Reiter zeigen.
        byName.values.filter { it.name.endsWith(".open") }.forEach { f ->
            val id = f.name.removeSuffix(".open")
            val info = newInfos[id] ?: return@forEach
            val ref = runCatching { f.readText().trim() }.getOrDefault("")
            f.delete()
            infos = infos + (id to info)
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater({
                // Session im Terminal der IDE, aber nicht von Handoff gestartet: Reiter über die Kennung suchen, die der Hook
                // ins Terminal geschrieben hat - dann gehen Eingaben samt Slash-Befehlen auch dorthin.
                if (info.tab == null && ref.isNotEmpty()) SessionTerminals.adopt(project, ref) { tab ->
                    runCatching { File(WaitingHook.waiting, "$id.tab").writeText(tab) }
                    infos = infos + (id to SessionInfo(id, info.at, info.cwd, info.transcript, tab))
                    HandoffService.getInstance(project).fireChanged()
                    SessionEditors.open(project, tab, id, focus = true)
                }
                SessionEditors.open(project, info.tab, id, focus = true)
            }, project.disposed)
        }

        val infosChanged = newInfos.mapValues { it.value.at to it.value.tab } != infos.mapValues { it.value.at to it.value.tab }
        infos = newInfos
        val workingChanged = newWorking.map { it.id to it.since } != working.map { it.id to it.since }
        working = newWorking

        // Die .listen-Datei legt der Hook kurz nach der Meldung an - sie zählt mit, sonst fehlte das Eingabefeld.
        val before = sessions.associate { it.file.name to it.at }
        val listening = sessions.filter { it.listening }.map { it.id }.toSet()
        val waitingChanged = found.associate { it.file.name to it.at } != before || found.filter { it.listening }.map { it.id }.toSet() != listening
        if (!waitingChanged && !workingChanged && !infosChanged) return
        sessions = found
        sent.retainAll(found.map { it.id }.toSet())
        HandoffService.getInstance(project).fireChanged()
        if (notify && waitingChanged) found.filter { before[it.file.name] != it.at && now - it.at < 60_000 }.forEach {
            Attention.notify(project, if (it.finished) "Claude is done" else "Claude is waiting for you", Md.plain(it.text).ifBlank { project.name })
        }
    }

    companion object {
        fun getInstance(project: Project): WaitingSessions = project.getService(WaitingSessions::class.java)
    }
}
