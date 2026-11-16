package app.heckit.ide.aihandoff

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.util.EventDispatcher
import kotlinx.coroutines.CompletableDeferred
import java.util.EventListener
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Ein Schritt eines Ablaufs aus mehreren Befehlen. */
class Step(@Volatile var command: String) {
    @Volatile var exitCode: Int? = null
    @Volatile var running = false
    val output = StringBuffer()

    fun outputTail(maxLines: Int): String {
        val lines = output.toString().trimEnd().lines()
        val tail = lines.takeLast(maxLines)
        return (if (lines.size > tail.size) "… (${lines.size - tail.size} earlier lines omitted)\n" else "") + tail.joinToString("\n")
    }
}

/** Antwortmöglichkeit einer Frage (handoff_ask); auch MCP-Parameter. */
@kotlinx.serialization.Serializable
data class AskOption(
    @com.intellij.mcpserver.annotations.McpDescription("Short label, a few words.")
    val label: String,
    @com.intellij.mcpserver.annotations.McpDescription("Optional one-line explanation shown under the label.")
    val description: String = "",
)

/** Eine Frage von handoff_ask; mehrere Fragen blättert der Nutzer mit Back/Next durch. */
@kotlinx.serialization.Serializable
data class AskQuestion(
    @com.intellij.mcpserver.annotations.McpDescription("The question. Markdown is rendered; it may be long (context, a list of findings).")
    val question: String,
    @com.intellij.mcpserver.annotations.McpDescription("Options to pick from (2-10).")
    val options: List<AskOption>,
    @com.intellij.mcpserver.annotations.McpDescription("True if the user may pick several options.")
    val multiSelect: Boolean = false,
) {
    /** Nur Oberfläche bzw. Ergebnis: angekreuzte Optionen und eigene Antwort. */
    @kotlinx.serialization.Transient val picked: MutableSet<Int> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    @kotlinx.serialization.Transient @Volatile var own: String = ""
    val answered get() = picked.isNotEmpty() || own.isNotBlank()
}

/** Ein von der KI vorgeschlagener Befehl, den der Nutzer selbst ausführt. */
class Proposal(
    val id: String,
    var command: String,
    val reason: String,
    val workingDir: String,
    val risky: Boolean,
    /** Braucht Eingaben (Login, Passwort, Rückfrage) - der Nutzer tippt sie ins Run-Fenster. */
    val interactive: Boolean = false,
    /** Bilder zum Prüfen bzw. zur Auswahl (dann ist [command] leer und [reason] die Frage). */
    val images: List<String> = emptyList(),
    /** Änderungen prüfen: Dann ist [command] leer, [reason] Claudes Zusammenfassung und [changelist] die Changelist der Aufgabe (null = alle Änderungen). */
    val review: Boolean = false,
    val changelist: String? = null,
    /** Ablauf aus mehreren Befehlen (dann ist [command] leer): einzeln oder nacheinander ausführbar, Stopp beim ersten Fehler. */
    val steps: List<Step> = emptyList(),
    /** Fragen mit Auswahl (dann ist [command] leer und [reason] die Überschrift). */
    val questions: List<AskQuestion> = emptyList(),
) {
    enum class State { PENDING, RUNNING, FINISHED, DISMISSED }

    @Volatile var state: State = State.PENDING
    @Volatile var exitCode: Int? = null
    @Volatile var note: String? = null
    /** Gewähltes Bild (Index in [images]). */
    @Volatile var chosen: Int? = null
    /** Nur Oberfläche: markiertes Bild und angefangene Notiz, damit sie ein Neuzeichnen der Liste überstehen. */
    @Volatile var selected: Int? = null
    @Volatile var noteDraft: String = ""
    val isAsk get() = questions.isNotEmpty()
    /** Nur Oberfläche: gerade gezeigte Frage. */
    @Volatile var page = 0
    /** Zeilennotizen, die der Nutzer mit der Prüfung zurückgegeben hat (Text für Claude). */
    @Volatile var lineNotes: String? = null
    val isPending get() = state == State.PENDING
    /** Markierte Bereiche je Bild (Index in [images]). */
    val marks = java.util.concurrent.ConcurrentHashMap<Int, List<Mark>>()
    /** Zeichnungen je Bild und die gespeicherte Bildkopie damit (Pfad). */
    val shapes = java.util.concurrent.ConcurrentHashMap<Int, List<Shape>>()
    val drawn = java.util.concurrent.ConcurrentHashMap<Int, String>()

    /** Ergebnis eines Ablaufs für Claude: je Schritt Befehl, Exit-Code und Ende der Ausgabe. */
    fun stepsText(): String = steps.mapIndexed { i, s ->
        val code = s.exitCode
        when {
            code == null -> "Step ${i + 1} (not run): ${s.command}"
            else -> "Step ${i + 1} (exit $code): ${s.command}" +
                s.outputTail(if (code == 0) 15 else 60).takeIf { it.isNotBlank() }?.let { "\n" + it.prependIndent("  ") }.orEmpty()
        }
    }.joinToString("\n")

    fun marksText(): String = (images.indices.filter { !marks[it].isNullOrEmpty() }.map { i ->
        "Marked areas on image ${i + 1} (${images[i]}), in pixels of the original image:\n" + MarkupDialog.describe(marks[i]!!)
    } + images.indices.mapNotNull { i -> drawn[i]?.let { MarkupDialog.describeDrawing(i, images[i], it) } }).joinToString("\n")
    val output = StringBuffer()
    /** Wird bei FINISHED oder DISMISSED abgeschlossen. */
    val done = CompletableDeferred<Unit>()
    val createdAt: Long = System.currentTimeMillis()

    /** Frage von Claude Code selbst (AskUserQuestion), abgefangen vom PreToolUse-Hook: die Session, an die die Antwort geht. */
    @Volatile var askSession: String? = null
    /** Claude hat das Ergebnis per handoff_wait_for_result abgeholt. */
    @Volatile var fetched = false
    /** Session, die gerade arbeitete, als Claude die Karte schickte - an sie geht das Ergebnis, wenn niemand wartet. */
    @Volatile var session: String? = null
    /** [session] ist eindeutig (genau eine Session arbeitete) - nur dann blenden Session-Reiter fremde Karten aus. */
    @Volatile var sessionSure = false
    /** Session, zu der die Karte gehört, soweit sicher bekannt. */
    val owner get() = askSession ?: session?.takeIf { sessionSure }

    /** Ergebnis für Claude, wie handoff_wait_for_result es liefert. */
    fun resultText(): String {
        return when (state) {
            Proposal.State.PENDING if steps.any { it.exitCode != null } ->
                "pending: ${steps.count { it.exitCode != null }} of ${steps.size} steps done, the user has not started the next one yet."
            Proposal.State.PENDING -> "pending: the user has not acted yet."
            Proposal.State.RUNNING if steps.isNotEmpty() -> {
                val i = steps.indexOfFirst { it.running }
                "running: step ${i + 1} of ${steps.size} is still running." + (steps.getOrNull(i)?.let { "\nOutput so far:\n${it.outputTail(20)}" } ?: "")
            }
            Proposal.State.RUNNING -> "running: the command is still running.\nOutput so far:\n${outputTail(20)}"
            Proposal.State.DISMISSED -> "dismissed by the user." + (note?.let { " Note: $it" } ?: "") +
                (marksText().takeIf { it.isNotEmpty() }?.let { "\n$it" } ?: "") +
                (if (steps.any { it.exitCode != null }) "\nSteps run before:\n" + stepsText() else "")
            Proposal.State.FINISHED if steps.isNotEmpty() -> buildString {
                val failed = steps.indexOfFirst { (it.exitCode ?: 0) != 0 }
                if (failed < 0) append("finished: all ${steps.size} steps succeeded.\n")
                else append("failed at step ${failed + 1} of ${steps.size} (exit ${steps[failed].exitCode}); the steps after it were not run.\n")
                append("Commands as actually run (the user may have edited them), exit code and output tail:\n")
                append(stepsText())
            }
            Proposal.State.FINISHED if review -> buildString {
                append(if (lineNotes == null && note == null) "approved by the user: no change requests." else "changes requested by the user.")
                note?.let { append("\nNote: $it") }
                lineNotes?.let { append("\nLine notes (path:line, the note, then the line it is attached to):\n").append(it) }
            }
            Proposal.State.FINISHED if isAsk -> buildString {
                append("answered by the user.")
                questions.forEachIndexed { qi, q ->
                    append("\n\nQuestion ${qi + 1}: ${q.question.lineSequence().first().take(120)}")
                    val chosen = q.picked.sorted()
                    if (chosen.isEmpty()) append("\nNo option picked.")
                    else chosen.forEach { append("\nChosen: ${it + 1}. ${q.options[it].label}") }
                    q.own.takeIf { it.isNotBlank() }?.let { append("\nOwn answer: $it") }
                }
            }
            Proposal.State.FINISHED if images.isNotEmpty() -> buildString {
                val i = chosen ?: 0
                append(if (images.size == 1) "approved by the user." else "the user chose image ${i + 1} of ${images.size}: ${images[i]}.")
                note?.let { append(" Note: $it") }
                marksText().takeIf { it.isNotEmpty() }?.let { append("\n").append(it) }
            }
            Proposal.State.FINISHED -> buildString {
                append("finished with exit code ${exitCode}.\n")
                append("Command run: ${command}\n")
                append("Output (tail):\n")
                append(outputTail())
            }
        }
    }

    fun outputTail(maxLines: Int = 60): String {
        val lines = output.toString().trimEnd().lines()
        val tail = lines.takeLast(maxLines)
        val skipped = lines.size - tail.size
        return (if (skipped > 0) "… ($skipped earlier lines omitted)\n" else "") + tail.joinToString("\n")
    }
}

@Service(Service.Level.PROJECT)
// Bewusst kein Disposable: Wird der Dienst während eines Plugin-Updates noch einmal angelegt (z. B. von einem späten
// MCP-Aufruf), hielt ihn sonst der Disposer der IDE fest und mit ihm die alte Plugin-Version (Heap-Analyse, 2026-11-13).
class HandoffService(private val project: Project) {
    fun interface Listener : EventListener {
        fun changed()
    }

    private val counter = AtomicInteger()
    val proposals = CopyOnWriteArrayList<Proposal>()
    private val dispatcher = EventDispatcher.create(Listener::class.java)

    fun addListener(listener: Listener, parent: Disposable) = dispatcher.addListener(listener, parent)

    fun fireChanged() {
        ApplicationManager.getApplication().invokeLater({ dispatcher.multicaster.changed() }, project.disposed)
    }

    fun propose(command: String, reason: String, workingDir: String, risky: Boolean, interactive: Boolean = false): Proposal {
        val p = Proposal("h${counter.incrementAndGet()}", command, reason, workingDir, risky || looksRisky(command), interactive)
        add(p)
        Attention.notify(project, if (p.risky) "Claude proposes a risky command" else "Claude proposes a command", reason.ifBlank { command })
        fireChanged()
        return p
    }

    fun proposeSequence(commands: List<String>, reason: String, workingDir: String, risky: Boolean, interactive: Boolean): Proposal {
        val p = Proposal(
            "h${counter.incrementAndGet()}", "", reason, workingDir, risky || commands.any(::looksRisky), interactive,
            steps = commands.map(::Step),
        )
        add(p)
        Attention.notify(project, "Claude proposes ${commands.size} commands", reason)
        fireChanged()
        return p
    }

    fun proposeImages(paths: List<String>, question: String): Proposal {
        val p = Proposal("h${counter.incrementAndGet()}", "", question, "", false, images = paths)
        if (paths.size == 1) p.selected = 0
        add(p)
        Attention.notify(project, if (paths.size == 1) "Claude asks you to review an image" else "Claude asks you to choose an image", question)
        fireChanged()
        return p
    }

    fun proposeAsk(title: String, questions: List<AskQuestion>, askSession: String? = null): Proposal {
        val p = Proposal("h${counter.incrementAndGet()}", "", title, "", false, questions = questions)
        p.askSession = askSession
        add(p)
        Attention.notify(project, if (questions.size == 1) "Claude asks you a question" else "Claude asks you ${questions.size} questions",
            Md.plain(title.ifBlank { questions.first().question }))
        fireChanged()
        return p
    }

    /** Fragen beantworten: Antworten stehen in [AskQuestion.picked] und [AskQuestion.own]. */
    fun answer(p: Proposal) {
        p.state = Proposal.State.FINISHED
        p.done.complete(Unit)
        fireChanged()
    }

    fun proposeReview(summary: String, changelist: String?): Proposal {
        val p = Proposal("h${counter.incrementAndGet()}", "", summary, "", false, review = true, changelist = changelist)
        add(p)
        Attention.notify(project, "Claude asks for a review", summary)
        fireChanged()
        return p
    }

    /** Prüfung abschließen: ohne Notizen eine Freigabe, mit Notizen ein Änderungswunsch. Die Zeilennotizen gehen mit und werden entfernt. */
    fun finishReview(p: Proposal, note: String?) {
        p.lineNotes = NotesService.getInstance(project).take()
        p.note = note?.takeIf { it.isNotBlank() }
        p.state = Proposal.State.FINISHED
        p.done.complete(Unit)
        fireChanged()
    }

    /** Wartende Übergaben plus Claude-Sessions, die auf den Nutzer warten. */
    val pendingCount get() = proposals.count { it.isPending } + (project.getServiceIfCreated(WaitingSessions::class.java)?.sessions?.size ?: 0)

    fun choose(p: Proposal, index: Int, note: String?) {
        p.chosen = index
        p.note = note?.takeIf { it.isNotBlank() }
        p.state = Proposal.State.FINISHED
        p.done.complete(Unit)
        fireChanged()
    }

    /**
     * Neue Karte. Antwortet der Nutzer, nachdem Claude aufgehört hat zu warten (Runde beendet, Warten abgelaufen), holte
     * niemand das Ergebnis ab - es ginge verloren (2026-11-15: Markierungen in einem Bild). Dann geht es als Prompt an die
     * Session, die beim Vorschlag arbeitete (Terminal von Handoff oder wartender Stop-Hook).
     */
    private fun add(p: Proposal) {
        val sessions = WaitingSessions.getInstance(project)
        // Fragen von Claude Code beantwortet der Hook selbst - nichts nachzustellen.
        if (p.askSession == null) {
            p.session = sessions.working.singleOrNull()?.id ?: sessions.working.maxByOrNull { it.since }?.id
            p.sessionSure = sessions.working.size == 1
        }
        proposals.add(0, p)
        p.done.invokeOnCompletion {
            javax.swing.Timer(DELIVER_AFTER_MS) {
                val id = p.session ?: return@Timer
                if (p.fetched || project.isDisposed) return@Timer
                val text = "Result of ${p.id}, which you handed over earlier through Handoff (you were no longer waiting for it):\n" + p.resultText()
                if (sessions.reply(id, text)) p.fetched = true
            }.apply { isRepeats = false; start() }
        }
    }

    fun find(id: String): Proposal? = proposals.firstOrNull { it.id == id }

    /** Karte ohne Ergebnis entfernen (die Frage wurde anderswo beantwortet, z. B. im Terminal). */
    fun drop(p: Proposal) {
        proposals.remove(p)
        p.state = Proposal.State.DISMISSED
        p.fetched = true
        p.done.complete(Unit)
        fireChanged()
    }

    fun dismiss(p: Proposal, note: String?) {
        p.note = note?.takeIf { it.isNotBlank() }
        p.state = Proposal.State.DISMISSED
        p.done.complete(Unit)
        fireChanged()
    }

    fun clearFinished() {
        proposals.removeIf { it.state == Proposal.State.FINISHED || it.state == Proposal.State.DISMISSED }
        fireChanged()
    }

    companion object {
        private val RISKY = Regex(
            """\b(deploy|publish|release|upload|push|rm\s+-r|drop\s+(table|database)|reset\s+--hard|--force|force-push|prod(uction)?)\b""",
            RegexOption.IGNORE_CASE,
        )

        fun looksRisky(command: String) = RISKY.containsMatchIn(command)

        /** So lange warten, ob Claude das Ergebnis selbst abholt (ein laufendes handoff_wait_for_result kehrt sofort zurück). */
        private const val DELIVER_AFTER_MS = 4000
        fun getInstance(project: Project): HandoffService = project.getService(HandoffService::class.java)
    }
}
