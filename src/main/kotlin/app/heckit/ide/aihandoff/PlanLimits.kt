package app.heckit.ide.aihandoff

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.time.Instant

/**
 * Plan-Limits (5-hour und 7-day) von Claude Code. Claude Code gibt sie nur an ein Statuszeilen-Skript weiter
 * (`rate_limits` im JSON auf stdin). Unser Skript speichert dieses JSON für die IDE und gibt eine kurze Zeile aus;
 * eine schon eingerichtete Statuszeile ruft es weiterhin auf.
 */
object PlanLimits {
    private val home = File(System.getProperty("user.home"))
    private val dir = File(home, ".claude/handoff")
    val script = File(dir, "statusline.sh")
    val data = File(dir, "statusline.json")
    private val settings = File(home, ".claude/settings.json")
    private const val COMMAND = "~/.claude/handoff/statusline.sh"

    class Window(val usedPercent: Double, val resetsAt: Instant?)
    class Limits(val fiveHour: Window?, val sevenDay: Window?, val updated: Instant)

    fun isInstalled(): Boolean = script.isFile && currentCommand()?.contains("handoff/statusline.sh") == true

    private fun readSettings(): JsonObject =
        runCatching { JsonParser.parseString(settings.readText()).asJsonObject }.getOrNull() ?: JsonObject()

    /** Befehl der eingetragenen Statuszeile, falls vorhanden. */
    fun currentCommand(): String? = readSettings().getAsJsonObject("statusLine")?.get("command")?.takeIf { it.isJsonPrimitive }?.asString

    /** Skript anlegen und als Statuszeile eintragen; eine bisherige Statuszeile wird vom Skript weiter aufgerufen. */
    fun install() {
        val previous = currentCommand()?.takeIf { !it.contains("handoff/statusline.sh") }.orEmpty()
        dir.mkdirs()
        script.writeText(
            """
            |#!/bin/bash
            |# Handoff for Claude Code: keeps Claude Code's status line data (including plan limits) for the IDE plugin.
            |dir="${'$'}HOME/.claude/handoff"
            |input=${'$'}(cat)
            |printf '%s' "${'$'}input" > "${'$'}dir/statusline.json.tmp" && mv "${'$'}dir/statusline.json.tmp" "${'$'}dir/statusline.json"
            |# Status line that was configured before Handoff (empty = none).
            |previous=${shellQuote(previous)}
            |if [ -n "${'$'}previous" ]; then printf '%s' "${'$'}input" | sh -c "${'$'}previous"; exit ${'$'}?; fi
            |pct() { printf '%s' "${'$'}input" | tr -d '\n' | grep -o "\"${'$'}1\"[^}]*" | grep -o '"used_percentage": *[0-9.]*' | grep -o '[0-9.]*${'$'}' | head -1; }
            |five=${'$'}(pct five_hour); week=${'$'}(pct seven_day)
            |[ -n "${'$'}five${'$'}week" ] && LC_ALL=C printf '5h %.0f%% · week %.0f%%' "${'$'}{five:-0}" "${'$'}{week:-0}"
            |""".trimMargin()
        )
        script.setExecutable(true)
        val json = readSettings()
        if (settings.isFile) settings.copyTo(File(settings.parentFile, "settings.json.bak-handoff"), overwrite = true)
        json.add("statusLine", JsonObject().apply { addProperty("type", "command"); addProperty("command", COMMAND) })
        settings.parentFile.mkdirs()
        settings.writeText(GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(json) + "\n")
    }

    private fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    fun read(): Limits? {
        if (!data.isFile) return null
        val o = runCatching { JsonParser.parseString(data.readText()).asJsonObject }.getOrNull() ?: return null
        val rl = o.getAsJsonObject("rate_limits") ?: return Limits(null, null, Instant.ofEpochMilli(data.lastModified()))
        fun window(k: String): Window? {
            val w = rl.getAsJsonObject(k) ?: return null
            val used = w.get("used_percentage")?.takeIf { it.isJsonPrimitive }?.asDouble ?: return null
            return Window(used, w.get("resets_at")?.let(::instant))
        }
        return Limits(window("five_hour"), window("seven_day"), Instant.ofEpochMilli(data.lastModified()))
    }

    /** resets_at als Unix-Zeit (Sekunden oder Millisekunden) oder ISO-Text. */
    private fun instant(e: JsonElement): Instant? = runCatching {
        val p = e.asJsonPrimitive
        if (p.isNumber) p.asLong.let { if (it > 100_000_000_000L) Instant.ofEpochMilli(it) else Instant.ofEpochSecond(it) }
        else Instant.parse(p.asString)
    }.getOrNull()
}
