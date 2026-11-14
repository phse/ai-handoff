package app.heckit.ide.aihandoff

import com.intellij.openapi.project.Project
import java.io.File

/**
 * Welche Handoff-Anweisungen stehen schon in den CLAUDE.md-Dateien, die Claude Code für das Projekt lädt? Grundlage ist
 * der Baustein der Anleitung: je Aufzählungspunkt eine Funktion, erkannt an den darin genannten Werkzeugen.
 */
object Features {
    class Feature(val text: String, val tools: List<String>) {
        /** Erste Zeile ohne Spiegelstrich, als Beschriftung. */
        val title get() = text.lineSequence().first().removePrefix("- ").trim().let { if (it.length > 70) it.take(70).substringBeforeLast(' ') + " …" else it }
    }

    private val TOOL = Regex("""handoff_[a-z_]+""")

    /** Alle Werkzeuge des Plugins, aus den MCP-Annotationen. */
    val allTools: List<String> by lazy {
        HandoffToolset::class.java.declaredMethods
            .filter { m -> m.annotations.any { it.annotationClass.simpleName == "McpTool" } }
            .map { it.name }.filter { it.startsWith("handoff_") }.distinct().sorted()
    }

    /** Aufzählungspunkte des Bausteins (Folgezeilen eingerückt). */
    val all: List<Feature> by lazy {
        val bullets = mutableListOf<String>()
        Guide.snippet.lines().forEach { l ->
            when {
                l.startsWith("- ") -> bullets += l
                l.startsWith("  ") && bullets.isNotEmpty() -> bullets[bullets.size - 1] += "\n" + l
            }
        }
        bullets.map { b -> Feature(b, TOOL.findAll(b).map { it.value }.distinct().toList()) }.filter { it.tools.isNotEmpty() }
    }

    /** Text aller geladenen Anweisungsdateien: global, Projekt und Elternordner, samt @-Importen (eine Ebene). */
    fun instructions(project: Project): String {
        val home = File(System.getProperty("user.home"))
        val global = File(home, ".claude/CLAUDE.md")
        val files = mutableListOf(global)
        project.basePath?.let { File(it) }?.let { base ->
            generateSequence(base) { it.parentFile }.takeWhile { it != home.parentFile }.forEach { d ->
                listOf("CLAUDE.md", ".claude/CLAUDE.md", "CLAUDE.local.md").map { File(d, it) }.filter { it != global }.forEach(files::add)
            }
        }
        val texts = files.filter { it.isFile }.map { f ->
            val t = runCatching { f.readText() }.getOrDefault("")
            val imports = Regex("""(?m)(?:^|\s)@(~?[\w./-]+\.md)""").findAll(t).mapNotNull { m ->
                val p = m.groupValues[1]
                val file = if (p.startsWith("~/")) File(home, p.drop(2)) else File(f.parentFile, p)
                file.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
            }
            (sequenceOf(t) + imports).joinToString("\n")
        }
        return texts.joinToString("\n")
    }

    fun mentioned(project: Project): Set<String> = TOOL.findAll(instructions(project)).map { it.value }.toSet()

    /** Funktionen, deren Werkzeuge noch nicht alle in den Anweisungen vorkommen. */
    fun missing(project: Project): List<Feature> {
        val known = mentioned(project)
        return all.filter { f -> !known.containsAll(f.tools) }
    }

    /** Prompt für Claude, die gewählten Anweisungen zu übernehmen. */
    fun prompt(selected: List<Feature>): String = buildString {
        append("Handoff (IDE plugin) has instructions that are not in my CLAUDE.md yet. Check which of the following are ")
        append("missing in the CLAUDE.md files loaded for this project (global ~/.claude/CLAUDE.md or the project's) and add ")
        append("them to the existing Handoff section, or create one in the global file. Keep the file's style and language, ")
        append("don't duplicate what is already there, and show me what you changed.\n\n")
        selected.forEach { append(it.text).append("\n") }
    }
}
