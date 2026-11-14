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
 * "Claude wartet auf dich": Hooks von Claude Code (Stop, Notification) legen je Session eine Datei in
 * `~/.claude/handoff/waiting/` ab, UserPromptSubmit und SessionEnd löschen sie wieder. Eingerichtet wie die Statuszeile:
 * ein Skript in `~/.claude/handoff/` und Einträge in `~/.claude/settings.json`.
 */
object WaitingHook {
    private val dir = File(System.getProperty("user.home"), ".claude/handoff")
    val script = File(dir, "hook.sh")
    val waiting = File(dir, "waiting")
    private const val COMMAND = "~/.claude/handoff/hook.sh"
    private const val MARK = "handoff/hook.sh"

    /** Ereignis → Argument des Skripts und Filter. Bei Notification nur, was eine Eingabe braucht (idle_prompt deckt Stop schon ab). */
    /**
     * Stop läuft als asyncRewake-Hook im Hintergrund: Er legt die Datei ab und wartet auf eine Antwort aus der IDE
     * (`<id>.reply`). Kommt eine, gibt er sie auf stderr aus und endet mit Code 2 - Claude Code weckt die Session dann mit
     * diesem Text. Tippt der Nutzer stattdessen im Terminal, löscht UserPromptSubmit die Datei und der Hook endet still.
     */
    private val EVENTS = listOf(
        Triple("Stop", "listen", null),
        Triple("Notification", "wait", "permission_prompt|elicitation_dialog|agent_needs_input"),
        Triple("UserPromptSubmit", "clear", null),
        Triple("SessionEnd", "clear", null),
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
        |# Handoff for Claude Code: tells the IDE plugin when a Claude Code session is waiting for you.
        |# "wait" keeps the hook data of the session, "clear" removes it. "listen" (Stop, asyncRewake) also waits for a
        |# prompt sent from the IDE and hands it to Claude Code (stderr + exit 2 wakes the session).
        |dir="${'$'}HOME/.claude/handoff/waiting"
        |input=${'$'}(cat)
        |id=${'$'}(printf '%s' "${'$'}input" | tr -d '\n' | grep -o '"session_id" *: *"[A-Za-z0-9_-]*"' | head -1 | sed 's/.*"\([^"]*\)"${'$'}/\1/')
        |[ -n "${'$'}id" ] || exit 0
        |f="${'$'}dir/${'$'}id"
        |if [ "${'$'}1" = "clear" ]; then
        |  rm -f "${'$'}f.json" "${'$'}f.listen" "${'$'}f.reply"
        |  exit 0
        |fi
        |mkdir -p "${'$'}dir" && printf '%s' "${'$'}input" > "${'$'}f.tmp" && mv "${'$'}f.tmp" "${'$'}f.json"
        |[ "${'$'}1" = "listen" ] || exit 0
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

    fun install() {
        dir.mkdirs()
        script.writeText(SCRIPT)
        script.setExecutable(true)
        PlanLimits.writeSettings(PlanLimits.readSettings().also(::addTo))
    }

    /** Nach einem Plugin-Update: eingerichtete Hooks auf den Stand dieser Version bringen. */
    fun upgradeIfNeeded() {
        if (script.isFile && script.readText() != SCRIPT && isInstalled()) install()
    }

    fun uninstall() {
        PlanLimits.writeSettings(PlanLimits.readSettings().also(::removeFrom))
        script.delete()
        waiting.listFiles()?.forEach { it.delete() }
    }
}

/** Eine Claude-Code-Session, die auf den Nutzer wartet. */
class WaitingSession(val file: File, val at: Long, val cwd: String, val finished: Boolean, val text: String) {
    val id get() = file.nameWithoutExtension
    /** Ein Stop-Hook wartet im Hintergrund auf einen weiteren Prompt aus der IDE. */
    val listening get() = finished && File(file.parentFile, "$id.listen").isFile
}

@Service(Service.Level.PROJECT)
class WaitingSessions(private val project: Project, private val scope: CoroutineScope) {
    @Volatile var sessions: List<WaitingSession> = emptyList()
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
                delay(2000)
            }
        }
    }

    fun dismiss(s: WaitingSession) {
        s.file.delete()
        sessions = sessions.filter { it !== s }
        HandoffService.getInstance(project).fireChanged()
    }

    /** Weiterer Prompt für eine fertige Session: Der wartende Stop-Hook reicht ihn an Claude Code weiter. */
    fun reply(s: WaitingSession, prompt: String) {
        val text = prompt.trim().takeIf { it.isNotEmpty() } ?: return
        val dir = s.file.parentFile
        val tmp = File(dir, "${s.id}.reply.tmp")
        tmp.writeText("The user sent this follow-up prompt from the IDE (Handoff tool window):\n\n$text\n")
        tmp.renameTo(File(dir, "${s.id}.reply"))
        drafts.remove(s.id)
        sent += s.id
        HandoffService.getInstance(project).fireChanged()
    }

    private fun poll(notify: Boolean) {
        val base = project.basePath?.trimEnd('/') ?: return
        val now = System.currentTimeMillis()
        val found = WaitingHook.waiting.listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { f ->
            val at = f.lastModified()
            // Reste abgebrochener Sessions (Terminal geschlossen, ohne SessionEnd) nach einem Tag wegräumen.
            if (now - at > 24 * 3600_000L) { f.delete(); return@mapNotNull null }
            val o = runCatching { JsonParser.parseString(f.readText()).asJsonObject }.getOrNull() ?: return@mapNotNull null
            fun str(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asString
            val cwd = str("cwd")?.trimEnd('/') ?: return@mapNotNull null
            if (cwd != base && !cwd.startsWith("$base/")) return@mapNotNull null
            val finished = str("hook_event_name") == "Stop"
            // Nach einer Freigabe im Terminal arbeitet Claude weiter, ohne dass ein Hook die Datei löscht: Das Protokoll
            // der Session ist dann jünger als die Meldung.
            if (!finished && (str("transcript_path")?.let { File(it).lastModified() } ?: 0L) > at + 5000) return@mapNotNull null
            WaitingSession(f, at, cwd, finished, (if (finished) str("last_assistant_message") else str("message")).orEmpty().trim())
        }.sortedByDescending { it.at }
        // Die .listen-Datei legt der Hook kurz nach der Meldung an - sie zählt mit, sonst fehlte das Eingabefeld.
        val before = sessions.associate { it.file.name to it.at }
        val listening = sessions.filter { it.listening }.map { it.id }.toSet()
        if (found.associate { it.file.name to it.at } == before && found.filter { it.listening }.map { it.id }.toSet() == listening) return
        sessions = found
        sent.retainAll(found.map { it.id }.toSet())
        HandoffService.getInstance(project).fireChanged()
        if (notify) found.filter { before[it.file.name] != it.at && now - it.at < 60_000 }.forEach {
            Attention.notify(project, if (it.finished) "Claude is done" else "Claude is waiting for you", Md.plain(it.text).ifBlank { project.name })
        }
    }

    companion object {
        fun getInstance(project: Project): WaitingSessions = project.getService(WaitingSessions::class.java)
    }
}
