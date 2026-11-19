package app.heckit.ide.aihandoff

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import java.io.File

/**
 * Welche Claude-Code-Session hat eine Karte geschickt? Der MCP-Aufruf trägt keine Session-Kennung, aber kurz davor läuft der
 * PreToolUse-Hook mit Kennung, Werkzeugname und Eingabe und legt sie als `<id>.call` ab. Passt eine solche Datei zu der
 * Karte (gleiches Werkzeug, der Text der Karte steht in der Eingabe), ist die Session sicher bekannt.
 */
object Callers {
    private const val MAX_AGE_MS = 60_000L

    fun find(p: Proposal): String? {
        val tool = tool(p)
        val probe = probe(p).trim().takeIf { it.isNotEmpty() } ?: return null
        val now = System.currentTimeMillis()
        return WaitingHook.waiting.listFiles { f -> f.name.endsWith(".call") && now - f.lastModified() < MAX_AGE_MS }.orEmpty()
            .sortedByDescending { it.lastModified() }
            .firstNotNullOfOrNull { f ->
                val o = runCatching { JsonParser.parseString(f.readText()).asJsonObject }.getOrNull() ?: return@firstNotNullOfOrNull null
                val name = o.get("tool_name")?.takeIf { it.isJsonPrimitive }?.asString ?: return@firstNotNullOfOrNull null
                if (!name.endsWith("__$tool")) return@firstNotNullOfOrNull null
                if (strings(o.get("tool_input")).none { it.trim() == probe || it.contains(probe) }) return@firstNotNullOfOrNull null
                o.get("session_id")?.takeIf { it.isJsonPrimitive }?.asString ?: f.name.removeSuffix(".call")
            }
    }

    private fun tool(p: Proposal) = when {
        p.review -> "handoff_request_review"
        p.steps.isNotEmpty() -> "handoff_propose_sequence"
        p.images.isNotEmpty() -> "handoff_show_images"
        p.isAsk -> "handoff_ask"
        p.isChecklist -> "handoff_checklist"
        p.secret != null -> "handoff_request_secret"
        else -> "handoff_propose_command"
    }

    /** Text der Karte, der so auch in der Eingabe des Aufrufs steht. */
    private fun probe(p: Proposal) = when {
        p.steps.isNotEmpty() -> p.steps.first().command
        p.isAsk -> p.questions.first().question
        p.isChecklist -> p.checks.first().step
        p.command.isNotEmpty() -> p.command
        else -> p.reason
    }

    private fun strings(e: JsonElement?): List<String> = when {
        e == null || e.isJsonNull -> emptyList()
        e.isJsonPrimitive -> listOf(e.asString)
        e.isJsonArray -> e.asJsonArray.flatMap { strings(it) }
        e.isJsonObject -> e.asJsonObject.entrySet().flatMap { strings(it.value) }
        else -> emptyList()
    }
}
