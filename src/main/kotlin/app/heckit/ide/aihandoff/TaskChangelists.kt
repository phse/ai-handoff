package app.heckit.ide.aihandoff

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Eine Changelist je Claude-Auftrag und Session: Beim Start wird "Claude: <Titel>" zur aktiven Changelist, sodass danach
 * geänderte Dateien dort landen; beim Ende wird die vorher aktive wieder aktiv.
 *
 * Mehrere Sessions im selben Projekt: Aufgaben gehören der Session, die `handoff_begin_task` aufruft (PreToolUse-Hook, siehe
 * [Callers]). Aktiv ist immer nur eine Changelist - was eine andere Session ändert, landete früher dort, und ihr
 * `handoff_end_task` beendete die fremde Aufgabe (SCAD Sphere, 2026-11-19). Darum sortiert [sortEdits] die Dateien nach dem
 * Protokoll der Sessions (Edit/Write mit Pfad) in die Liste der Session, die sie geändert hat.
 *
 * Claude schreibt an der IDE vorbei auf die Platte. Vor jedem Umschalten deshalb Dateisystem und VCS-Status
 * auffrischen und abwarten - sonst erkennt die IDE Änderungen erst nach dem Umschalten und sortiert sie falsch ein.
 */
@Service(Service.Level.PROJECT)
class TaskChangelists(private val project: Project, private val scope: CoroutineScope) {
    /** Laufende Aufgabe: Changelist, Session (null ohne Hook-Daten) und Beginn. */
    class Task(val name: String, val session: String?, val startedAt: Long)

    /** Laufende Aufgaben je Session ("" = Session unbekannt), in der Reihenfolge ihres Beginns. */
    private val tasks = LinkedHashMap<String, Task>()
    /** Zuletzt beendete Aufgabe je Session - für eine Prüfung nach dem Ende. */
    private val last = HashMap<String, String>()
    /** Changelist, die vor der ersten laufenden Aufgabe aktiv war - wird wieder aktiv, wenn keine mehr läuft. */
    private var restoreTo: String? = null
    private val mutex = Mutex()
    private var sorter: Job? = null

    private fun key(session: String?) = session ?: ""

    /** Laufende Aufgabe einer Session (für die Sessions-Übersicht). */
    fun taskOf(session: String?): Task? = synchronized(tasks) { tasks[key(session)] }

    /**
     * Changelists laufender Aufgaben anderer Sessions: Bittet eine Session ohne eigene Aufgabe um Prüfung, gehören die nicht
     * dazu (im Test zeigte die Karte sonst alle Änderungen des Projekts, 2026-11-22). Ohne bekannte Session: keine.
     */
    fun otherTaskLists(session: String?): Set<String> = if (session == null) emptySet() else
        synchronized(tasks) { tasks.filterKeys { it != key(session) }.values.map { it.name }.toSet() }

    /** Ohne bekannte Session: die einzige laufende Aufgabe, sonst die zuletzt begonnene (Verhalten vor 1.11.0). */
    private fun resolve(session: String?): Task? = synchronized(tasks) {
        tasks[key(session)] ?: tasks.values.singleOrNull()?.takeIf { session == null || it.session == null }
            ?: if (session == null) tasks.values.lastOrNull() else null
    }

    /**
     * Changelist für eine Prüfung: die der laufenden, sonst der zuletzt beendeten Aufgabe der Session (falls es sie noch
     * gibt). Vorher den Stand der Platte einlesen und neue Dateien der laufenden Aufgabe aufnehmen - sonst fehlen sie im Diff.
     */
    suspend fun prepareReview(session: String?): String? = mutex.withLock {
        syncChanges()
        sortEditsLocked()
        val t = resolve(session)
        if (t != null) addNewFiles(t)
        t?.name ?: (last[key(session)] ?: if (session == null) last.values.lastOrNull() else null)
            ?.takeIf { ChangeListManager.getInstance(project).findChangeList(it) != null }
    }

    suspend fun begin(title: String, session: String?): String = mutex.withLock {
        syncChanges()
        sortEditsLocked()
        val name = withContext(Dispatchers.EDT) {
            val clm = ChangeListManager.getInstance(project)
            val name = "Claude: " + title.trim().take(60)
            synchronized(tasks) {
                if (tasks.isEmpty()) restoreTo = clm.defaultChangeList.name
                val before = tasks.remove(key(session))
                tasks[key(session)] = Task(name, session, before?.takeIf { it.name == name }?.startedAt ?: (System.currentTimeMillis() - 1000))
            }
            val list = clm.findChangeList(name) ?: clm.addChangeList(name, "Changes made by Claude Code for: ${title.trim()}")
            clm.setDefaultChangeList(list)
            name
        }
        startSorter()
        HandoffService.getInstance(project).fireChanged()
        name
    }

    /** @return Name der beendeten Changelist und die darin geänderten Dateien (relativ zum Projekt), oder null. */
    suspend fun end(session: String?): Pair<String, List<String>>? = mutex.withLock {
        val task = resolve(session) ?: return@withLock null
        syncChanges()
        sortEditsLocked()
        addNewFiles(task)
        val result = withContext(Dispatchers.EDT) {
            val clm = ChangeListManager.getInstance(project)
            val base = project.basePath.orEmpty().trimEnd('/') + "/"
            val files = clm.findChangeList(task.name)?.changes.orEmpty()
                .mapNotNull { (it.afterRevision ?: it.beforeRevision)?.file?.path?.removePrefix(base) }
                .sorted()
            val next = synchronized(tasks) {
                tasks.entries.removeIf { it.value === task }
                last[key(task.session)] = task.name
                tasks.values.lastOrNull()?.name ?: restoreTo.also { restoreTo = null }
            }
            // Nur umschalten, wenn die beendete Liste aktiv ist - sonst hat der Nutzer oder eine andere Aufgabe umgeschaltet.
            if (clm.defaultChangeList.name == task.name) next?.let { clm.findChangeList(it) }?.let { clm.setDefaultChangeList(it) }
            // Leere Liste selbst entfernen - sonst fragt die IDE per Dialog nach.
            if (files.isEmpty()) clm.findChangeList(task.name)?.let { if (!it.isDefault) clm.removeChangeList(it) }
            task.name to files
        }
        HandoffService.getInstance(project).fireChanged()
        result
    }

    /** Solange Aufgaben laufen, alle paar Sekunden einsortieren - die Changelists stimmen dann auch beim Hinsehen. */
    private fun startSorter() {
        if (sorter?.isActive == true) return
        sorter = scope.launch {
            while (synchronized(tasks) { tasks.isNotEmpty() }) {
                delay(SORT_EVERY_MS)
                runCatching { if (mutex.tryLock()) try { sortEditsLocked(refresh = true) } finally { mutex.unlock() } }
                    .onFailure { logger<TaskChangelists>().info("Handoff: sorting task changes failed", it) }
            }
        }
    }

    /**
     * Geänderte Dateien in die Changelist der Session schieben, die sie zuletzt per Edit/Write geändert hat (laut Protokoll,
     * seit Beginn ihrer Aufgabe). Hat eine Session ohne Aufgabe die Datei zuletzt geändert, geht sie aus einer Aufgabenliste
     * zurück in die vorher aktive Liste. Änderungen per Bash (sed, Skripte) stehen nicht im Protokoll und bleiben, wo die IDE
     * sie einsortiert hat - in der aktiven Liste.
     */
    private suspend fun sortEditsLocked(refresh: Boolean = false) {
        val (running, home) = synchronized(tasks) { tasks.values.toList() to restoreTo }
        if (running.none { it.session != null }) return
        val infos = project.getServiceIfCreated(WaitingSessions::class.java)?.infos.orEmpty()
        val since = running.minOf { it.startedAt }
        val base = project.basePath?.trimEnd('/') ?: return
        // Pfad → (Zeitpunkt, Ziel-Changelist) der letzten Änderung.
        val target = HashMap<String, Pair<Long, String?>>()
        withContext(Dispatchers.IO) {
            for (info in infos.values) {
                val transcript = info.transcript ?: continue
                val task = running.firstOrNull { it.session == info.id }
                val from = task?.startedAt ?: since
                val all = TranscriptMeta.of(transcript).edits
                val edits = synchronized(all) { all.filter { it.at >= from } }
                for (e in edits) {
                    if (!e.path.startsWith("$base/")) continue
                    val prev = target[e.path]
                    if (prev == null || prev.first <= e.at) target[e.path] = e.at to (task?.name ?: home)
                }
            }
        }
        if (target.isEmpty()) return
        val taskNames = running.map { it.name }.toSet()
        if (refresh) {
            val files = withContext(Dispatchers.IO) { target.keys.mapNotNull { LocalFileSystem.getInstance().refreshAndFindFileByPath(it) } }
            files.forEach { VcsDirtyScopeManager.getInstance(project).fileDirty(it) }
            waitForChanges()
        }
        // Neu angelegte Dateien sind "unversioned" und in keiner Liste: per `git add` aufnehmen, dann sortieren.
        val fresh = withContext(Dispatchers.EDT) {
            val clm = ChangeListManager.getInstance(project)
            target.filter { it.value.second in taskNames }.keys.filter { p ->
                LocalFileSystem.getInstance().findFileByPath(p)?.let { clm.isUnversioned(it) } == true
            }
        }
        if (fresh.isNotEmpty()) { gitAdd(fresh); syncChanges() }
        withContext(Dispatchers.EDT) {
            val clm = ChangeListManager.getInstance(project)
            for ((path, t) in target) {
                val name = t.second ?: continue
                val vf = LocalFileSystem.getInstance().findFileByPath(path) ?: continue
                val change = clm.getChange(vf) ?: continue
                val now = clm.getChangeList(change) ?: continue
                if (now.name == name) continue
                // In die vorher aktive Liste nur aus einer Aufgabenliste zurück - nie Dateien aus anderen eigenen Listen holen.
                if (name !in taskNames && now.name !in taskNames) continue
                val list = clm.findChangeList(name) ?: continue
                clm.moveChangesTo(list, change)
            }
        }
    }

    /**
     * Neue Dateien sind für die IDE "unversioned" und gehören zu keiner Changelist. Was während des Auftrags neu
     * entstanden ist, per `git add` aufnehmen; die IDE sortiert es dann in die noch aktive Claude-Liste ein.
     * Laufen mehrere Aufgaben, nur Dateien, die laut Protokoll diese Session angelegt hat (das erledigt [sortEditsLocked]).
     */
    private suspend fun addNewFiles(task: Task) {
        if (synchronized(tasks) { tasks.size } > 1) return
        val paths = ChangeListManager.getInstance(project).unversionedFilesPaths
            .map { it.ioFile }
            .filter { it.isFile && it.lastModified() >= task.startedAt }
            .map { it.path }
        if (paths.isEmpty()) return
        gitAdd(paths)
        syncChanges()
    }

    private suspend fun gitAdd(paths: List<String>) {
        val base = project.basePath ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                ProcessBuilder(listOf("git", "add", "--") + paths).directory(File(base))
                    .redirectErrorStream(true).start().waitFor()
            }
        }
    }

    private suspend fun syncChanges() {
        withContext(Dispatchers.IO) {
            project.basePath?.let { LocalFileSystem.getInstance().refreshAndFindFileByPath(it) }
                ?.let { VfsUtil.markDirtyAndRefresh(false, true, true, it) }
        }
        VcsDirtyScopeManager.getInstance(project).markEverythingDirty()
        waitForChanges()
    }

    private suspend fun waitForChanges() {
        suspendCancellableCoroutine { c -> ChangeListManager.getInstance(project).invokeAfterUpdate(false) { c.resume(Unit) } }
    }

    companion object {
        private const val SORT_EVERY_MS = 5000L
        fun getInstance(project: Project): TaskChangelists = project.getService(TaskChangelists::class.java)
    }
}
