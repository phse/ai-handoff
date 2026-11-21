package app.heckit.ide.aihandoff

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Was Handoff außer dem Verlauf aus einem Session-Protokoll braucht: Titel, ob Remote Control an ist, und welche Dateien
 * Claude per Edit/Write geändert hat. Liest jede Datei nur einmal ganz und danach nur neu angehängte Zeilen.
 */
object TranscriptMeta {
    /** Eine Dateiänderung durch Claude (Edit, MultiEdit, Write, NotebookEdit) mit Zeitpunkt laut Protokoll. */
    class Edit(val at: Long, val path: String)

    class Info {
        @Volatile var title: String? = null
        internal var customTitle = false
        /**
         * Remote Control verbunden: Claude Code schreibt beim Verbinden einen Eintrag `bridge-session` mit Kennung und beim
         * Trennen („Disconnect this session“) einen mit leerer `bridgeSessionId` - es gilt der letzte (im Bundle von 2.1.330
         * nachgelesen, 2026-11-21).
         */
        @Volatile var remote = false
        val edits: MutableList<Edit> = java.util.Collections.synchronizedList(ArrayList())
        internal var offset = 0L
        internal var length = -1L
    }

    private val cache = ConcurrentHashMap<String, Info>()
    private val EDIT_TOOLS = setOf("Edit", "MultiEdit", "Write", "NotebookEdit")
    private val EDIT_NAME = Regex("\"name\"\\s*:\\s*\"(?:Edit|MultiEdit|Write|NotebookEdit)\"")

    /** Stand des Protokolls [path]; liest neu angehängte Zeilen. Nicht auf dem EDT aufrufen (erstes Lesen dauert). */
    fun of(path: String): Info {
        val info = cache.getOrPut(path) { Info() }
        synchronized(info) { runCatching { update(File(path), info) } }
        return info
    }

    /** Ohne Lesen: der zuletzt bekannte Stand, oder null. Für den EDT. */
    fun cached(path: String?): Info? = path?.let { cache[it] }

    private fun update(file: File, info: Info) {
        if (!file.isFile) return
        val length = file.length()
        if (length == info.length) return
        if (length < info.offset) { info.offset = 0; info.edits.clear(); info.title = null; info.customTitle = false; info.remote = false }
        RandomAccessFile(file, "r").use { raf ->
            while (info.offset < length) {
                raf.seek(info.offset)
                val bytes = ByteArray((length - info.offset).coerceAtMost(8L * 1024 * 1024).toInt())
                raf.readFully(bytes)
                val end = bytes.lastIndexOf('\n'.code.toByte())
                if (end < 0) break
                info.offset += end + 1
                String(bytes, 0, end, Charsets.UTF_8).lineSequence().forEach { line -> runCatching { parse(line, info) } }
            }
        }
        info.length = length
    }

    /** Zeile enthält `"type":"<t>"` (Claude Code schreibt ohne Leerzeichen, Testdateien manchmal mit). Escaped in Texten zählt nicht. */
    private fun isType(line: String, t: String) = line.contains("\"type\":\"$t\"") || line.contains("\"type\": \"$t\"")

    private fun JsonObject.str(k: String) = get(k)?.takeIf { it.isJsonPrimitive }?.asString

    private fun parse(line: String, info: Info) {
        when {
            isType(line, "custom-title") ->
                JsonParser.parseString(line).asJsonObject.str("customTitle")?.takeIf { it.isNotBlank() }?.let { info.title = it; info.customTitle = true }
            isType(line, "ai-title") ->
                if (!info.customTitle) JsonParser.parseString(line).asJsonObject.str("aiTitle")?.let { info.title = it }
            isType(line, "bridge-session") ->
                info.remote = !JsonParser.parseString(line).asJsonObject.str("bridgeSessionId").isNullOrEmpty()
            isType(line, "tool_use") && isType(line, "assistant") && EDIT_NAME.containsMatchIn(line) -> {
                val o = JsonParser.parseString(line).asJsonObject
                if (o.get("isSidechain")?.asBoolean == true) return
                val at = o.str("timestamp")?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return
                o.getAsJsonObject("message")?.getAsJsonArray("content")?.forEach { c ->
                    val u = c.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                    if (u.str("type") != "tool_use" || u.str("name") !in EDIT_TOOLS) return@forEach
                    val input = u.getAsJsonObject("input") ?: return@forEach
                    (input.str("file_path") ?: input.str("notebook_path"))?.let { info.edits += Edit(at, it) }
                }
            }
        }
    }
}
