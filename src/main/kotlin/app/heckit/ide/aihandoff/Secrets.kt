package app.heckit.ide.aihandoff

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/**
 * Geheimnisse (Passwörter, Tokens), die der Nutzer in einer Karte eintippt: Das Plugin schreibt sie selbst in die Datei,
 * Claude sieht den Wert nie - weder im Chat noch im Ergebnis der Karte.
 */
object Secrets {
    /**
     * Mit [key]: Zeile `KEY=wert` (auch `export KEY=…`) ersetzen oder anhängen - .env, .properties, gradle.properties.
     * Ohne: Der Wert wird der ganze Inhalt der Datei. Neue Dateien nur für den Nutzer lesbar (600). Liefert den Text für Claude.
     */
    fun write(file: File, key: String, value: String): String {
        require(!file.isDirectory) { "is a folder" }
        val created = !file.exists()
        file.parentFile?.mkdirs()
        if (key.isEmpty()) file.writeText(value)
        else {
            val line = "$key=" + quoted(value)
            val lines = if (created) mutableListOf() else file.readLines().toMutableList()
            val pattern = Regex("^\\s*(export\\s+)?${Regex.escape(key)}\\s*[=:].*$")
            val i = lines.indexOfFirst { pattern.matches(it) }
            if (i >= 0) lines[i] = (pattern.find(lines[i])?.groupValues?.get(1).orEmpty()) + line else lines += line
            file.writeText(lines.joinToString("\n", postfix = "\n"))
        }
        if (created) runCatching { Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------")) }
        com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
        val what = if (key.isEmpty()) "The user entered the secret; it is now the content of ${file.path}" else "The user entered the secret; $key is set in ${file.path}"
        return "set. $what (you do not see the value - do not try to read or print it)." + gitWarning(file)
    }

    /** Werte mit Leer- oder Sonderzeichen in Anführungszeichen, sonst wie eingegeben. */
    private fun quoted(v: String): String =
        if (Regex("^[A-Za-z0-9_./:@+\\-=,~%^]*$").matches(v)) v
        else "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** Liegt die Datei in einem Git-Repo und wird nicht ignoriert, käme das Geheimnis mit dem nächsten Commit hinein. */
    private fun gitWarning(file: File): String = runCatching {
        val p = ProcessBuilder("git", "check-ignore", "-q", file.name).directory(file.parentFile).redirectErrorStream(true).start()
        p.inputStream.readAllBytes()
        if (p.waitFor() == 1) " Warning: the file is not ignored by git - add it to .gitignore before committing." else ""
    }.getOrDefault("")
}
