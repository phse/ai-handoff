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
class HandoffService(private val project: Project) : Disposable {
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
        fireChanged()
        return p
    }

    fun proposeImages(paths: List<String>, question: String): Proposal {
        val p = Proposal("h${counter.incrementAndGet()}", "", question, "", false, images = paths)
        if (paths.size == 1) p.selected = 0
        proposals.add(0, p)
        fireChanged()
        return p
    }

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

    override fun dispose() {
        proposals.forEach { it.done.complete(Unit) }
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
