package app.heckit.ide.aihandoff

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil

/**
 * Claudes Nachrichten sind Markdown. In Karten reicht eine schlichte Umsetzung (fett, kursiv, Code, Listen, Überschriften);
 * die ganze Nachricht geht in einen Editor-Reiter, dort rendert das Markdown-Plugin.
 */
object Md {
    private val BOLD = Regex("""\*\*(.+?)\*\*|__(.+?)__""")
    private val ITALIC = Regex("""(?<![*\w])\*(?!\s)(.+?)(?<!\s)\*(?![*\w])""")
    private val CODE = Regex("""`([^`]+)`""")
    private val LINK = Regex("""\[([^\]]+)]\(([^)]+)\)""")
    private val HEADING = Regex("""^#{1,6}\s+""")
    private val BULLET = Regex("""^(\s*)[-*+]\s+""")

    /** Ohne Markdown-Zeichen, für Systemmitteilungen. */
    fun plain(md: String): String = md.lines().joinToString("\n") { line ->
        var s = HEADING.replace(line, "")
        s = BULLET.replace(s) { it.groupValues[1] + "• " }
        s = LINK.replace(s) { it.groupValues[1] }
        s = BOLD.replace(s) { it.groupValues[1].ifEmpty { it.groupValues[2] } }
        s = CODE.replace(s) { it.groupValues[1] }
        ITALIC.replace(s) { it.groupValues[1] }
    }

    /** HTML-Körper (ohne `<html>`) für eine Karte. Code wird vor der übrigen Umsetzung herausgenommen. */
    fun html(md: String): String = md.lines().joinToString("<br>") { line ->
        val codes = mutableListOf<String>()
        var s = CODE.replace(line) { codes += it.groupValues[1]; "\u0000${codes.size - 1}\u0000" }
        val heading = HEADING.containsMatchIn(s)
        s = HEADING.replace(s, "")
        s = BULLET.replace(s) { "&nbsp;".repeat(it.groupValues[1].length) + "• " }
        s = StringUtil.escapeXmlEntities(s).replace("&amp;nbsp;", "&nbsp;")
        s = LINK.replace(s) { it.groupValues[1] }
        s = BOLD.replace(s) { "<b>" + it.groupValues[1].ifEmpty { it.groupValues[2] } + "</b>" }
        s = ITALIC.replace(s) { "<i>${it.groupValues[1]}</i>" }
        s = Regex("\u0000(\\d+)\u0000").replace(s) { "<code>" + StringUtil.escapeXmlEntities(codes[it.groupValues[1].toInt()]) + "</code>" }
        if (heading) "<b>$s</b>" else s
    }

    /** Inline-Markdown einer Zeile zu HTML, Links als `<a href>`. */
    fun inline(text: String): String {
        val codes = mutableListOf<String>()
        var s = CODE.replace(text) { codes += it.groupValues[1]; "\u0000${codes.size - 1}\u0000" }
        val links = mutableListOf<Pair<String, String>>()
        s = LINK.replace(s) { links += it.groupValues[1] to it.groupValues[2]; "\u0001${links.size - 1}\u0001" }
        s = StringUtil.escapeXmlEntities(s)
        fun emph(t: String) = ITALIC.replace(BOLD.replace(t) { "<b>" + it.groupValues[1].ifEmpty { it.groupValues[2] } + "</b>" }) { "<i>${it.groupValues[1]}</i>" }
        s = emph(s)
        s = Regex("\u0001(\\d+)\u0001").replace(s) { m ->
            val (label, href) = links[m.groupValues[1].toInt()]
            "<a href=\"${StringUtil.escapeXmlEntities(href)}\">${emph(StringUtil.escapeXmlEntities(label))}</a>"
        }
        return Regex("\u0000(\\d+)\u0000").replace(s) { "<code>" + StringUtil.escapeXmlEntities(codes[it.groupValues[1].toInt()]) + "</code>" }
    }

    /** Sprungmarke einer Überschrift wie auf GitHub. */
    fun slug(heading: String) = plain(heading).lowercase().replace(Regex("[^a-z0-9 -]"), "").trim().replace(' ', '-')

    /**
     * Ganzes Dokument zu HTML für den Viewer: Überschriften (mit Sprungmarke), Absätze, Listen, Codeblöcke, Tabellen.
     * Bewusst eigener kleiner Umsetzer - die Markdown-Bibliothek der Plattform ändert ihre API zwischen den Versionen.
     */
    fun document(md: String): String {
        val out = StringBuilder()
        val lines = md.replace(Regex("(?s)<!--.*?-->"), "").lines()
        var i = 0
        val para = mutableListOf<String>()
        fun flush() { if (para.isNotEmpty()) { out.append("<p>").append(para.joinToString(" ") { inline(it.trim()) }).append("</p>\n"); para.clear() } }
        while (i < lines.size) {
            val l = lines[i]
            when {
                l.trimStart().startsWith("```") -> {
                    flush(); i++
                    val code = mutableListOf<String>()
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) code += lines[i++]
                    out.append("<pre>").append(StringUtil.escapeXmlEntities(code.joinToString("\n"))).append("</pre>\n")
                }
                HEADING.containsMatchIn(l) -> {
                    flush()
                    val level = l.takeWhile { it == '#' }.length
                    val t = HEADING.replace(l, "")
                    out.append("<a name=\"${slug(t)}\"></a><h$level>${inline(t)}</h$level>\n")
                }
                l.trimStart().startsWith("|") -> {
                    flush()
                    out.append("<table>")
                    var head = true
                    while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                        val cells = lines[i].trim().trim('|').split("|").map { it.trim() }
                        if (cells.all { it.matches(Regex(":?-+:?")) }) { i++; continue }
                        val tag = if (head) "th" else "td"
                        out.append("<tr>").append(cells.joinToString("") { "<$tag>${inline(it)}</$tag>" }).append("</tr>")
                        head = false; i++
                    }
                    out.append("</table>\n"); continue
                }
                Regex("""^\s*([-*+]|\d+\.)\s+""").containsMatchIn(l) -> {
                    flush()
                    val ordered = Regex("""^\s*\d+\.""").containsMatchIn(l)
                    out.append(if (ordered) "<ol>" else "<ul>")
                    while (i < lines.size) {
                        val m = Regex("""^\s*([-*+]|\d+\.)\s+(.*)""").find(lines[i]) ?: break
                        val item = mutableListOf(m.groupValues[2])
                        i++
                        // Folgezeilen eines Punkts sind eingerückt.
                        while (i < lines.size && lines[i].startsWith("  ") && !Regex("""^\s*([-*+]|\d+\.)\s+""").containsMatchIn(lines[i])) item += lines[i++].trim()
                        out.append("<li>").append(item.joinToString(" ") { inline(it) }).append("</li>")
                    }
                    out.append(if (ordered) "</ol>" else "</ul>").append("\n"); continue
                }
                l.isBlank() -> flush()
                else -> para += l
            }
            i++
        }
        flush()
        return out.toString()
    }

    /** Anfang einer Nachricht für die Karte: einige nicht leere Zeilen, gekürzt. Zweiter Wert: ob etwas fehlt. */
    fun preview(md: String, maxLines: Int = 4, maxChars: Int = 300): Pair<String, Boolean> {
        val lines = md.lines().filter { it.isNotBlank() }
        var text = lines.take(maxLines).joinToString("\n")
        var cut = lines.size > maxLines
        if (text.length > maxChars) { text = text.take(maxChars).substringBeforeLast(' ') + " …"; cut = true }
        else if (cut) text += " …"
        return text to cut
    }

    private const val PREFIX = "Claude · "

    /** Gerendert in einem Editor-Reiter (eigener Viewer); ein gleichnamiger, schon offener wird nur aktiviert. */
    fun open(project: Project, name: String, text: String, anchor: String? = null) {
        val manager = FileEditorManager.getInstance(project)
        val open = manager.openFiles.firstOrNull { it is MarkdownViewerFile && it.name == name }
        if (open != null && anchor == null) { com.intellij.openapi.fileEditor.OpenFileDescriptor(project, open).navigate(true); return }
        open?.let { manager.closeFile(it) }
        com.intellij.openapi.fileEditor.OpenFileDescriptor(project, MarkdownViewerFile(name, text, anchor)).navigate(true)
    }

    fun openMessage(project: Project, title: String, text: String) = open(project, "$PREFIX$title", text)

    /** Beim Entladen offene Nachrichten schließen, damit kein Editor an unserer Version hängt. */
    fun closeAll(project: Project) {
        val manager = FileEditorManager.getInstance(project)
        manager.openFiles.filter { it is MarkdownViewerFile }.forEach { manager.closeFile(it) }
    }
}
