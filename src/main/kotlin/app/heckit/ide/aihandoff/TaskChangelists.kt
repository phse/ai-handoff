package app.heckit.ide.aihandoff

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Eine Changelist je Claude-Auftrag: Beim Start wird "Claude: <Titel>" zur aktiven Changelist, sodass alle danach
 * geänderten Dateien dort landen; beim Ende wird die vorher aktive wieder aktiv.
 *
 * Claude schreibt an der IDE vorbei auf die Platte. Vor jedem Umschalten deshalb Dateisystem und VCS-Status
 * auffrischen und abwarten - sonst erkennt die IDE Änderungen erst nach dem Umschalten und sortiert sie falsch ein.
 */
@Service(Service.Level.PROJECT)
class TaskChangelists(private val project: Project) {
    private var previousDefault: String? = null
    private var current: String? = null
    private var startedAt = 0L

    suspend fun begin(title: String): String {
        syncChanges()
        return withContext(Dispatchers.EDT) {
            val clm = ChangeListManager.getInstance(project)
            val name = "Claude: " + title.trim().take(60)
            if (current == null) {
                previousDefault = clm.defaultChangeList.name
                startedAt = System.currentTimeMillis() - 1000
            }
            val list = clm.findChangeList(name) ?: clm.addChangeList(name, "Changes made by Claude Code for: ${title.trim()}")
            clm.setDefaultChangeList(list)
            current = name
            name
        }
    }

    /** @return Name der beendeten Changelist und die darin geänderten Dateien (relativ zum Projekt), oder null. */
    suspend fun end(): Pair<String, List<String>>? {
        if (current == null) return null
        syncChanges()
        addNewFiles()
        return withContext(Dispatchers.EDT) {
            val name = current ?: return@withContext null
            val clm = ChangeListManager.getInstance(project)
            val base = project.basePath.orEmpty().trimEnd('/') + "/"
            val files = clm.findChangeList(name)?.changes.orEmpty()
                .mapNotNull { (it.afterRevision ?: it.beforeRevision)?.file?.path?.removePrefix(base) }
                .sorted()
            previousDefault?.let { prev -> clm.findChangeList(prev)?.let { clm.setDefaultChangeList(it) } }
            // Leere Liste selbst entfernen - sonst fragt die IDE per Dialog nach.
            if (files.isEmpty()) clm.findChangeList(name)?.let { if (!it.isDefault) clm.removeChangeList(it) }
            current = null
            previousDefault = null
            name to files
        }
    }

    /**
     * Neue Dateien sind für die IDE "unversioned" und gehören zu keiner Changelist. Was während des Auftrags neu
     * entstanden ist, per `git add` aufnehmen; die IDE sortiert es dann in die noch aktive Claude-Liste ein.
     */
    private suspend fun addNewFiles() {
        val base = project.basePath ?: return
        val paths = ChangeListManager.getInstance(project).unversionedFilesPaths
            .map { it.ioFile }
            .filter { it.isFile && it.lastModified() >= startedAt }
            .map { it.path }
        if (paths.isEmpty()) return
        withContext(Dispatchers.IO) {
            runCatching {
                ProcessBuilder(listOf("git", "add", "--") + paths).directory(java.io.File(base))
                    .redirectErrorStream(true).start().waitFor()
            }
        }
        syncChanges()
    }

    private suspend fun syncChanges() {
        withContext(Dispatchers.IO) {
            project.basePath?.let { LocalFileSystem.getInstance().refreshAndFindFileByPath(it) }
                ?.let { VfsUtil.markDirtyAndRefresh(false, true, true, it) }
        }
        VcsDirtyScopeManager.getInstance(project).markEverythingDirty()
        suspendCancellableCoroutine { c -> ChangeListManager.getInstance(project).invokeAfterUpdate(false) { c.resume(Unit) } }
    }

    companion object {
        fun getInstance(project: Project): TaskChangelists = project.getService(TaskChangelists::class.java)
    }
}
