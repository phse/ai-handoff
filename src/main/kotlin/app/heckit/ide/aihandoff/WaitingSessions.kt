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
        "Right-clicking a link offers Reveal in Finder and Open in Terminal. " +
        // 2026-11-16: Aktionen wurden selten verlinkt, weil Claude die IDs mühsam in den JARs suchen musste.
        "Before naming an IDE action, look up its id with the MCP tool search_ide_actions (load it via ToolSearch if deferred) " +
        "and link it; write names of actions and settings pages in bold - the tab links exact matches by itself. " +
        // 2026-11-15: Ein gerendertes 3D-Bild ging per `open` an Vorschau statt an Handoff.
        "Show images the user should look at, approve or choose from (renders, screenshots, variants) with the MCP tool " +
        "handoff_show_images (load it via ToolSearch if deferred), not with open or another app."

    /**
     * Regeln für die Karten - nur für Werkzeuge, die nicht per `permissions.deny` gesperrt sind. Stehen sie nur in einer langen
     * CLAUDE.md, schreibt Claude Befehle doch als Textzeile (2026-11-16, `git push` zweimal); die Werkzeuge sind oft
     * deferred und fallen beim Antworten ohne Schema nicht ein. Keine Anführungszeichen (steht in JSON in einem printf).
     */
    private fun startHint(): String {
        val denied = runCatching { Permissions.denied() }.getOrDefault(emptySet())
        fun ok(vararg tools: String) = tools.none { it in denied } && "handoff_wait_for_result" !in denied
        val parts = mutableListOf(LINK_HINT)
        if (ok("handoff_propose_command")) parts += "Hand over commands the user should run themselves (push, deploy, release, upload, login, " +
            "anything outward-facing) as a card with handoff_propose_command" +
            (if (ok("handoff_propose_sequence")) " - several in a row as one card with handoff_propose_sequence -" else "") +
            " instead of writing them as a text line or asking whether to run them, and wait for the outcome with handoff_wait_for_result."
        if (ok("handoff_ask")) parts += "Ask questions with options (which variant, which findings to save) with handoff_ask instead of in the text."
        if (ok("handoff_request_review")) parts += "When a task with file changes is done, ask the user to review it with handoff_request_review."
        parts += "These handoff tools are often deferred: load them via ToolSearch (query handoff) before the first call. " +
            "Put slash commands for the user (e.g. /compact with instructions, skills) on their own line or in backticks: the tab offers them to run or insert."
        return parts.joinToString(" ")
    }

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
        // Fragen von Claude Code (AskUserQuestion) in Sessions im Handoff-Reiter: im Reiter beantworten statt im Terminal.
        Triple("PreToolUse", "ask", "AskUserQuestion"),
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
                        if (arg == "ask") addProperty("timeout", ASK_SECONDS)
                    })
                })
            })
        }
    }

    /** So lange wartet der Frage-Hook auf die Antwort aus dem Reiter; danach fragt Claude Code im Terminal. */
    private const val ASK_SECONDS = 3600

    /** So lange wartet ein Stop-Hook auf eine Antwort aus der IDE (danach bleibt nur das Terminal). */
    private const val LISTEN_SECONDS = 86400

    private val SCRIPT get() = """
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
        |  rm -f "${'$'}f.json" "${'$'}f.listen" "${'$'}f.reply" "${'$'}f.busy" "${'$'}f.info" "${'$'}f.tab" "${'$'}f.open" "${'$'}f.ask" "${'$'}f.answer"
        |  exit 0
        |fi
        |mkdir -p "${'$'}dir" || exit 0
        |printf '%s' "${'$'}input" > "${'$'}f.itmp" && mv "${'$'}f.itmp" "${'$'}f.info"
        |[ -n "${'$'}HANDOFF_TAB" ] && printf '%s' "${'$'}HANDOFF_TAB" > "${'$'}f.tab"
        |case "${'$'}1" in
        |  start)
        |    # Sessions shown in a Handoff editor tab: tell Claude that links there open in the IDE.
        |    [ -n "${'$'}HANDOFF_TAB" ] && printf '%s' '{"hookSpecificOutput":{"hookEventName":"SessionStart","additionalContext":"${startHint()}"}}'
        |    exit 0 ;;
        |  ask)
        |    # AskUserQuestion: only for sessions shown in a Handoff tab, and only while the IDE is running (it touches .alive).
        |    # The IDE shows the question as a card and writes the hook output (allow + answers) to .answer; deleting .ask
        |    # means "answer in the terminal" - then Claude Code shows its own dialog as usual.
        |    [ -n "${'$'}HANDOFF_TAB" ] || [ -f "${'$'}f.tab" ] || exit 0
        |    alive() { [ -f "${'$'}dir/.alive" ] && [ ${'$'}(( ${'$'}(date +%s) - ${'$'}(date -r "${'$'}dir/.alive" +%s) )) -lt 15 ]; }
        |    alive || exit 0
        |    rm -f "${'$'}f.answer"
        |    trap 'rm -f "${'$'}f.ask"' EXIT
        |    printf '%s' "${'$'}input" > "${'$'}f.qtmp" && mv "${'$'}f.qtmp" "${'$'}f.ask"
        |    end=${'$'}(( ${'$'}(date +%s) + $ASK_SECONDS - 30 ))
        |    while [ ${'$'}(date +%s) -lt ${'$'}end ]; do
        |      if [ -f "${'$'}f.answer" ]; then cat "${'$'}f.answer"; rm -f "${'$'}f.answer"; exit 0; fi
        |      [ -f "${'$'}f.ask" ] || exit 0
        |      alive || exit 0
        |      sleep 0.5
        |    done
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
        val c = cwd?.trimEnd('/') ?: return false
        return dirs().any { c == it || c.startsWith("$it/") }
    }

    /** Projektordner und die Ordner außerhalb, in denen aus diesem Projekt Sessions gestartet wurden. */
    fun dirs(): List<String> = listOfNotNull(project.basePath?.trimEnd('/')) + extraDirs()

    private fun extraDirs(): List<String> = com.intellij.ide.util.PropertiesComponent.getInstance(project).getList(DIRS_KEY).orEmpty()

    /** Session in einem Ordner außerhalb des Projekts gestartet: ihre Hook-Daten ab jetzt auch hier zeigen. */
    fun addDir(dir: String) {
        val d = dir.trimEnd('/')
        if (dirs().any { d == it || d.startsWith("$it/") }) return
        com.intellij.ide.util.PropertiesComponent.getInstance(project).setList(DIRS_KEY, (extraDirs() + d).takeLast(20))
    }

    /** Letzte Nutzer-Nachricht im Protokoll ist ein Abbruch, jünger als [since]. Liest nur das Ende der Datei. */
    private fun interrupted(transcript: String?, since: Long): Boolean = runCatching {
        val file = File(transcript ?: return false)
        if (file.lastModified() < since) return false
        java.io.RandomAccessFile(file, "r").use { raf ->
            val len = raf.length(); val from = maxOf(0L, len - 32_000)
            val buf = ByteArray((len - from).toInt()); raf.seek(from); raf.readFully(buf)
            val last = String(buf, Charsets.UTF_8).lines().lastOrNull { it.contains("\"type\":\"user\"") || it.contains("\"type\":\"assistant\"") }
            last != null && last.contains("\"type\":\"user\"") && last.contains("[Request interrupted by user")
        }
    }.getOrDefault(false)

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
            // Abgebrochen (Esc): kein Stop-Hook, aber Claude Code schreibt "[Request interrupted by user…]" ins Protokoll.
            if (interrupted(o.str("transcript_path"), f.lastModified())) { f.delete(); sent.remove(id); return@mapNotNull null }
            // Sonst nach 30 Minuten ohne Bewegung im Protokoll nicht mehr zeigen.
            val last = maxOf(f.lastModified(), o.str("transcript_path")?.let { File(it).lastModified() } ?: 0L)
            if (now - last > 30 * 60_000L) return@mapNotNull null
            WorkingSession(id, f.lastModified(), cwd!!, o.str("prompt").orEmpty())
        }

        // Lebenszeichen für den Frage-Hook: Ohne laufende IDE fragt Claude Code gleich im Terminal.
        runCatching { File(WaitingHook.waiting, ".alive").let { if (!it.exists()) it.createNewFile(); it.setLastModified(now) } }
        pollAsks(byName, newInfos)

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

    /** Offene Fragen von Claude Code je Session: Karte und die Eingabe des Werkzeugs (für die Antwort). */
    private val asks = java.util.concurrent.ConcurrentHashMap<String, Pair<Proposal, JsonObject>>()

    private fun pollAsks(byName: Map<String, File>, infos: Map<String, SessionInfo>) {
        val service = HandoffService.getInstance(project)
        val open = byName.values.filter { it.name.endsWith(".ask") }.associateBy { it.name.removeSuffix(".ask") }
        // Weg (im Terminal beantwortet, abgebrochen): Karte entfernen.
        asks.keys.filter { it !in open }.forEach { id -> asks.remove(id)?.first?.takeIf { it.isPending }?.let(service::drop) }
        for ((id, f) in open) {
            if (asks.containsKey(id) || id !in infos) continue
            val input = read(f)?.getAsJsonObject("tool_input") ?: continue
            val questions = runCatching {
                input.getAsJsonArray("questions").map { q ->
                    val o = q.asJsonObject
                    val header = o.str("header")?.takeIf { it.isNotBlank() }
                    AskQuestion(
                        (header?.let { "**$it** · " } ?: "") + o.str("question").orEmpty(),
                        o.getAsJsonArray("options").map { op -> op.asJsonObject.let { AskOption(it.str("label").orEmpty(), it.str("description").orEmpty()) } },
                        o.get("multiSelect")?.asBoolean == true,
                    )
                }
            }.getOrNull()?.takeIf { it.isNotEmpty() } ?: continue
            val placeholder = Proposal("", "", "", "", false)
            asks[id] = placeholder to input
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater({
                val p = service.proposeAsk("Claude Code asks", questions, askSession = id)
                if (asks[id]?.first === placeholder) asks[id] = p to input else { service.drop(p); return@invokeLater }
                p.done.invokeOnCompletion {
                    if (p.state == Proposal.State.FINISHED) answerAsk(id, input, p) else if (asks[id]?.first === p) File(WaitingHook.waiting, "$id.ask").delete()
                    asks.remove(id)
                }
                SessionEditors.revealCards(project, p)
            }, project.disposed)
        }
    }

    /** Antwort für den Hook: Werkzeug freigeben, mit den Antworten in der Eingabe (wie die Agent-SDK es vorsieht). */
    private fun answerAsk(id: String, input: JsonObject, p: Proposal) {
        val answers = JsonObject()
        val raw = input.getAsJsonArray("questions")
        p.questions.forEachIndexed { i, q ->
            val text = raw.get(i).asJsonObject.str("question") ?: return@forEachIndexed
            val picked = q.picked.sorted().map { q.options[it].label } + listOfNotNull(q.own.trim().takeIf { it.isNotEmpty() })
            answers.addProperty(text, picked.joinToString(", "))
        }
        val updated = input.deepCopy().apply { add("answers", answers) }
        val out = JsonObject().apply {
            add("hookSpecificOutput", JsonObject().apply {
                addProperty("hookEventName", "PreToolUse"); addProperty("permissionDecision", "allow")
                addProperty("permissionDecisionReason", "Answered in the IDE (Handoff)"); add("updatedInput", updated)
            })
        }
        val tmp = File(WaitingHook.waiting, "$id.atmp")
        tmp.writeText(out.toString())
        tmp.renameTo(File(WaitingHook.waiting, "$id.answer"))
    }

    companion object {
        private const val DIRS_KEY = "app.heckit.ide.aihandoff.sessionDirs"
        fun getInstance(project: Project): WaitingSessions = project.getService(WaitingSessions::class.java)
    }
}
