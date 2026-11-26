package app.heckit.ide.aihandoff

import com.intellij.database.DatabaseDataKeys
import com.intellij.database.datagrid.DataGrid
import com.intellij.database.datagrid.GridUtil
import com.intellij.database.datagrid.ModelIndexSet
import com.intellij.database.run.ui.DataAccessType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.text.StringUtil
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Ergebnis einer Datenbank-Abfrage an eine Claude-Session (Kontextmenü im Ergebnis): markierte Zeilen - oder alle geladenen,
 * wenn nur eine Zelle markiert ist - als TSV unter `~/.claude/handoff/data/`, im Eingabefeld der Session als `@`-Marke.
 * Zwei Einträge: "Send to Claude Session <Titel>" an den zuletzt angezeigten Session-Reiter des Projekts, und
 * "Send to Claude Session…" mit Auswahl aller laufenden Sessions - auch in anderen Projekten oder IDEs (z. B. aus DataGrip);
 * die bekommen die Datei über `~/.claude/handoff/waiting/<id>.mention` (2026-11-26).
 */
class GridToClaudeAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val target = project?.let(::lastTab)
        e.presentation.isEnabledAndVisible = target != null && e.getData(DatabaseDataKeys.DATA_GRID_KEY) != null
        if (target != null) e.presentation.text = "Send to Claude Session " +
            StringUtil.shortenTextWithEllipsis(target.title ?: "New Session", 40, 0).replace("_", "__")
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val grid = e.getData(DatabaseDataKeys.DATA_GRID_KEY) ?: return
        val tab = lastTab(project) ?: return
        val (file, label) = GridExport.write(grid) ?: return
        SessionEditors.insertMention(project, tab, file.path, label)
    }

    private fun lastTab(project: Project): SessionFile? =
        SessionEditors.lastSelected[project]?.takeIf { f -> FileEditorManager.getInstance(project).isFileOpen(f) && !f.logOnly }
}

/** "Send to Claude Session…": alle laufenden Sessions in einem Handoff-Reiter, in jedem Projekt und jeder IDE. */
class GridToClaudeChooseAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.getData(DatabaseDataKeys.DATA_GRID_KEY) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project
        val grid = e.getData(DatabaseDataKeys.DATA_GRID_KEY) ?: return
        val targets = LiveSessions.list()
        if (targets.isEmpty()) {
            Messages.showInfoMessage(project, "No Claude session is running in a Handoff tab (in any IDE).", "Send to Claude Session")
            return
        }
        com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
            .createPopupChooserBuilder(targets)
            .setTitle("Send to Claude Session")
            .setRenderer(object : com.intellij.ui.ColoredListCellRenderer<LiveSession>() {
                override fun customizeCellRenderer(list: javax.swing.JList<out LiveSession>, value: LiveSession?, index: Int, selected: Boolean, hasFocus: Boolean) {
                    value ?: return
                    append(StringUtil.shortenTextWithEllipsis(value.title, 60, 0))
                    append("   " + SessionEditors.shortPath(value.cwd), com.intellij.ui.SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            })
            .setNamerForFiltering { it.title + " " + it.cwd }
            .setItemChosenCallback { target ->
                val (file, label) = GridExport.write(grid) ?: return@setItemChosenCallback
                send(target, file, label)
            }
            .createPopup()
            .showInBestPositionFor(e.dataContext)
    }

    /** Offen in einem Projekt dieser IDE: direkt einfügen; sonst über die Ablage für die IDE, die den Reiter hat. */
    private fun send(target: LiveSession, file: File, label: String) {
        for (p in com.intellij.openapi.project.ProjectManager.getInstance().openProjects) {
            val tab = FileEditorManager.getInstance(p).openFiles.filterIsInstance<SessionFile>().firstOrNull { it.currentSession(p) == target.id }
            if (tab != null) { SessionEditors.insertMention(p, tab, file.path, label); com.intellij.ide.impl.ProjectUtil.focusProjectWindow(p, true); return }
        }
        val out = com.google.gson.JsonObject().apply { addProperty("path", file.path); addProperty("label", label) }
        val tmp = File(WaitingHook.waiting, "${target.id}.mtmp")
        tmp.writeText(out.toString())
        tmp.renameTo(File(WaitingHook.waiting, "${target.id}.mention"))
    }
}

/** Eine laufende Claude-Code-Session in einem Handoff-Reiter (irgendeiner IDE). */
class LiveSession(val id: String, val cwd: String, val title: String, val at: Long)

object LiveSessions {
    /** Aus `~/.claude/handoff/waiting`: Sessions mit Reiter (.tab), deren Claude-Prozess noch lebt; neueste zuerst. */
    fun list(): List<LiveSession> {
        val dir = WaitingHook.waiting
        return dir.listFiles().orEmpty().filter { it.name.endsWith(".info") }.mapNotNull { info ->
            val id = info.name.removeSuffix(".info")
            if (!File(dir, "$id.tab").isFile) return@mapNotNull null
            val pid = runCatching { File(dir, "$id.pid").readText().trim().toLong() }.getOrNull()
            if (pid != null && !ProcessHandle.of(pid).map { it.isAlive }.orElse(false)) return@mapNotNull null
            val o = runCatching { com.google.gson.JsonParser.parseString(info.readText()).asJsonObject }.getOrNull() ?: return@mapNotNull null
            val cwd = o.get("cwd")?.asString ?: return@mapNotNull null
            val past = o.get("transcript_path")?.asString?.let { File(it) }?.takeIf { it.isFile }?.let { runCatching { PastSessions.read(it) }.getOrNull() }
            LiveSession(id, cwd, past?.title ?: past?.firstPrompt?.replace('\n', ' ') ?: "Session in ${File(cwd).name}", info.lastModified())
        }.sortedByDescending { it.at }
    }
}

/** Export der Rasterdaten als Datei. */
object GridExport {
    /**
     * Die markierten Zeilen (ohne Markierung alle geladenen) und sichtbaren Spalten. Mehrere Zeilen als TSV mit Kopfzeile, eine
     * einzelne als Datensatz "Spalte: Wert" je Zeile - als TSV hielt Claude sie für eine ganze Tabelle (2026-11-26; vorher ging
     * bei nur einer markierten Zeile sogar das ganze Ergebnis mit).
     */
    fun write(grid: DataGrid): Pair<File, String>? {
        val model = grid.getDataModel(DataAccessType.DATA_WITH_MUTATIONS)
        val selected = grid.selectionModel.selectedRows
        val rows: ModelIndexSet<com.intellij.database.datagrid.GridRow> = if (selected.size() > 0) selected else grid.visibleRows
        val cols = grid.visibleColumns
        if (rows.size() == 0 || cols.size() == 0) return null
        fun clean(s: String?) = (s ?: "").replace(Regex("[\\t\\r\\n]+"), " ")
        val names = cols.asList().map { clean(model.getColumn(it)?.name) }
        val table = grid.displayName.removePrefix("editor:").trim().ifBlank { "Query result" }
        val out = StringBuilder()
        val single = rows.size() == 1
        if (single) {
            val r = rows.first()
            out.append("One record from ").append(table).append(":\n")
            cols.asList().forEachIndexed { n, c -> out.append(names[n]).append(": ").append(clean(GridUtil.getText(grid, r, c))).append('\n') }
        } else {
            out.append(names.joinToString("\t")).append('\n')
            for (r in rows.asList()) out.append(cols.asList().joinToString("\t") { c -> clean(GridUtil.getText(grid, r, c)) }).append('\n')
        }
        val dir = File(System.getProperty("user.home"), ".claude/handoff/data").apply { mkdirs() }
        val name = table.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').take(60).ifBlank { "result" }
        val file = File(dir, "$name-${SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())}." + if (single) "txt" else "tsv")
        file.writeText(out.toString())
        val n = rows.size()
        return file to "[$table: " + (if (single) "1 record" else "$n rows") + "]"
    }
}
