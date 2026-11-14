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
    /** Zeilennotizen, die der Nutzer mit der Prüfung zurückgegeben hat (Text für Claude). */
    @Volatile var lineNotes: String? = null
    val isPending get() = state == State.PENDING
    /** Markierte Bereiche je Bild (Index in [images]). */
    val marks = java.util.concurrent.ConcurrentHashMap<Int, List<Mark>>()

    /** Ergebnis eines Ablaufs für Claude: je Schritt Befehl, Exit-Code und Ende der Ausgabe. */
    fun stepsText(): String = steps.mapIndexed { i, s ->
        val code = s.exitCode
        when {
            code == null -> "Step ${i + 1} (not run): ${s.command}"
            else -> "Step ${i + 1} (exit $code): ${s.command}" +
                s.outputTail(if (code == 0) 15 else 60).takeIf { it.isNotBlank() }?.let { "\n" + it.prependIndent("  ") }.orEmpty()
        }
    }.joinToString("\n")

    fun marksText(): String = images.indices.filter { !marks[it].isNullOrEmpty() }.joinToString("\n") { i ->
        "Marked areas on image ${i + 1} (${images[i]}), in pixels of the original image:\n" + MarkupDialog.describe(marks[i]!!)
    }
    val output = StringBuffer()
    /** Wird bei FINISHED oder DISMISSED abgeschlossen. */
    val done = CompletableDeferred<Unit>()
    val createdAt: Long = System.currentTimeMillis()

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
        proposals.add(0, p)
        Attention.notify(project, if (p.risky) "Claude proposes a risky command" else "Claude proposes a command", reason.ifBlank { command })
        fireChanged()
        return p
    }

    fun proposeSequence(commands: List<String>, reason: String, workingDir: String, risky: Boolean, interactive: Boolean): Proposal {
        val p = Proposal(
            "h${counter.incrementAndGet()}", "", reason, workingDir, risky || commands.any(::looksRisky), interactive,
            steps = commands.map(::Step),
        )
        proposals.add(0, p)
        Attention.notify(project, "Claude proposes ${commands.size} commands", reason)
        fireChanged()
        return p
    }

    fun proposeImages(paths: List<String>, question: String): Proposal {
        val p = Proposal("h${counter.incrementAndGet()}", "", question, "", false, images = paths)
        if (paths.size == 1) p.selected = 0
        proposals.add(0, p)
        Attention.notify(project, if (paths.size == 1) "Claude asks you to review an image" else "Claude asks you to choose an image", question)
        fireChanged()
        return p
    }

    fun proposeReview(summary: String, changelist: String?): Proposal {
        val p = Proposal("h${counter.incrementAndGet()}", "", summary, "", false, review = true, changelist = changelist)
        proposals.add(0, p)
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

    fun find(id: String): Proposal? = proposals.firstOrNull { it.id == id }

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

        fun getInstance(project: Project): HandoffService = project.getService(HandoffService::class.java)
    }
}
