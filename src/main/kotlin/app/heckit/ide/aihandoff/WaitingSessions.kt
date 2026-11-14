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
    private val EVENTS = listOf(
        Triple("Stop", "wait", null),
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
                    add(JsonObject().apply { addProperty("type", "command"); addProperty("command", "$COMMAND $arg") })
                })
            })
        }
    }

    fun install() {
        dir.mkdirs()
        script.writeText(
            """
            |#!/bin/bash
            |# Handoff for Claude Code: tells the IDE plugin when a Claude Code session is waiting for you.
            |# "wait" keeps the hook data of the session, "clear" removes it. Prints nothing.
            |dir="${'$'}HOME/.claude/handoff/waiting"
            |input=${'$'}(cat)
            |id=${'$'}(printf '%s' "${'$'}input" | tr -d '\n' | grep -o '"session_id" *: *"[A-Za-z0-9_-]*"' | head -1 | sed 's/.*"\([^"]*\)"${'$'}/\1/')
            |[ -n "${'$'}id" ] || exit 0
            |if [ "${'$'}1" = "clear" ]; then
            |  rm -f "${'$'}dir/${'$'}id.json"
            |else
            |  mkdir -p "${'$'}dir" && printf '%s' "${'$'}input" > "${'$'}dir/${'$'}id.tmp" && mv "${'$'}dir/${'$'}id.tmp" "${'$'}dir/${'$'}id.json"
            |fi
            |exit 0
            |""".trimMargin()
        )
        script.setExecutable(true)
        PlanLimits.writeSettings(PlanLimits.readSettings().also(::addTo))
    }

    fun uninstall() {
        PlanLimits.writeSettings(PlanLimits.readSettings().also(::removeFrom))
        script.delete()
        waiting.listFiles()?.forEach { it.delete() }
    }
}

/** Eine Claude-Code-Session, die auf den Nutzer wartet. */
class WaitingSession(val file: File, val at: Long, val cwd: String, val finished: Boolean, val text: String)

@Service(Service.Level.PROJECT)
class WaitingSessions(private val project: Project, private val scope: CoroutineScope) {
    @Volatile var sessions: List<WaitingSession> = emptyList()
        private set
    private val started = AtomicBoolean()

    /** Ordner alle zwei Sekunden ansehen - er liegt außerhalb des Projekts, die IDE meldet dort keine Änderungen. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
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
        val before = sessions.associate { it.file.name to it.at }
        if (found.associate { it.file.name to it.at } == before) return
        sessions = found
        HandoffService.getInstance(project).fireChanged()
        if (notify) found.filter { before[it.file.name] != it.at && now - it.at < 60_000 }.forEach {
            Attention.notify(project, if (it.finished) "Claude is done" else "Claude is waiting for you", it.text.ifBlank { project.name })
        }
    }

    companion object {
        fun getInstance(project: Project): WaitingSessions = project.getService(WaitingSessions::class.java)
    }
}
