package app.heckit.ide.aihandoff

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.util.IconLoader
import com.intellij.ui.JBColor
import java.io.File
import java.net.URL
import javax.swing.Icon

/**
 * Icons des Plugins per Datei-Adresse statt per Pfad. Ein pfadbasiertes Icon hängt an unserem Classloader, und die IDE
 * behält es nach einem Plugin-Update bis zu 10 min im Speicher für ausgegraute Icons (iconToDisabledIcon wird beim
 * Entladen nicht geleert, IJPL-252580, behoben ab 2027.1) - dann bleibt die alte Plugin-Version geladen. Ohne Classloader
 * findet die IDE aber keine Varianten mehr; deshalb wählen Aufrufer Größe und hell/dunkel selbst.
 */
object HandoffIcons {
    /**
     * Icon als Datei im Temp-Ordner der IDE: Eine jar-URL lieferte nach einem Hot-Reload kein Bild mehr (die JAR wird unter
     * gleichem Namen ersetzt, der Zip-Cache der IDE kennt noch die alte). Die Datei-URL hängt weder an JAR noch Classloader.
     */
    fun url(path: String): URL? = runCatching {
        val bytes = HandoffIcons::class.java.getResourceAsStream(path)?.use { it.readBytes() } ?: return null
        val dir = File(PathManager.getTempPath(), "handoff-icons").apply { mkdirs() }
        // Name aus Inhalt, damit eine geänderte Grafik nicht aus einem Cache der alten Datei kommt.
        val file = File(dir, path.substringAfterLast('/').removeSuffix(".svg") + "-" + bytes.contentHashCode().toUInt().toString(16) + ".svg")
        if (!file.isFile) file.writeBytes(bytes)
        file.toURI().toURL()
    }.getOrNull()

    fun isDark(): Boolean = com.intellij.ide.ui.LafManager.getInstance().currentUIThemeLookAndFeel?.isDark ?: !JBColor.isBright()

    private val cache = HashMap<String, Icon?>()

    /** Mikrofon für den Diktier-Knopf im Session-Reiter. */
    fun mic(on: Boolean = false): Icon {
        val path = if (on) "/icons/mic_on.svg" else "/icons/mic${if (isDark()) "_dark" else ""}.svg"
        return synchronized(cache) { cache.getOrPut(path) { url(path)?.let { IconLoader.findIcon(it, false) } } }
            ?: com.intellij.icons.AllIcons.Actions.Execute
    }

    /** Weltkugel für Remote Control; [on]: verbunden (blau gefüllt, wirkt wie ein gedrückter Knopf). */
    fun globe(on: Boolean = false): Icon {
        val path = if (on) "/icons/globe_on.svg" else "/icons/globe${if (isDark()) "_dark" else ""}.svg"
        return synchronized(cache) { cache.getOrPut(path) { url(path)?.let { IconLoader.findIcon(it, false) } } }
            ?: com.intellij.icons.AllIcons.General.Web
    }

    /** Sprechblase einer Notiz im Zeilenrand: Form wie im Handoff-Symbol (Spitze links unten), ohne Claude-Zeichen. */
    fun note(): Icon {
        val path = "/icons/note${if (isDark()) "_dark" else ""}.svg"
        return synchronized(cache) { cache.getOrPut(path) { url(path)?.let { IconLoader.findIcon(it, false) } } }
            ?: com.intellij.icons.AllIcons.General.Balloon
    }

    /** 16-px-Symbol für Editor-Reiter (Session, Anleitung, Nachrichten), passend zum Theme. */
    fun editor(): Icon {
        val path = "/icons/expui/toolwindow/handoff${if (isDark()) "_dark" else ""}.svg"
        return synchronized(cache) { cache.getOrPut(path) { url(path)?.let { IconLoader.findIcon(it, false) } } }
            ?: com.intellij.icons.AllIcons.FileTypes.Text
    }
}
