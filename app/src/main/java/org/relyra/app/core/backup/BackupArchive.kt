package org.relyra.app.core.backup

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** ZIP container inside the authenticated backup stream. Entry names are relative and strictly validated. */
internal object BackupArchive {
    private const val MAX_ENTRIES = 20_000
    private const val MAX_TOTAL_BYTES = 4L * 1024 * 1024 * 1024

    fun pack(database: File, roots: Map<String, File>, preferences: Map<String, Map<String, String>>, output: File) {
        require(database.isFile && database.length() > 0)
        ZipOutputStream(BufferedOutputStream(FileOutputStream(output))).use { zip ->
            addFile(zip, "database.db", database)
            roots.toSortedMap().forEach { (name, root) ->
                require(name.matches(Regex("[a-z][a-z0-9_-]{0,31}")))
                if (root.exists()) {
                    val canonicalRoot = root.canonicalFile
                    root.walkTopDown().filter { it.isFile }.forEach { file ->
                        val canonical = file.canonicalFile
                        if (!canonical.toPath().startsWith(canonicalRoot.toPath())) {
                            throw IOException("backup source contains a path outside its root")
                        }
                        val relative = canonicalRoot.toPath().relativize(canonical.toPath()).toString()
                            .replace(File.separatorChar, '/')
                        validatePath(relative)
                        addFile(zip, "files/$name/$relative", canonical)
                    }
                }
            }
            preferences.toSortedMap().forEach { (name, values) ->
                require(name.matches(Regex("[a-z][a-z0-9_-]{0,63}")))
                val props = Properties()
                values.forEach { (key, value) -> props.setProperty(key, value) }
                zip.putNextEntry(ZipEntry("prefs/$name.properties"))
                props.store(zip, "RELYRA backup preferences")
                zip.closeEntry()
            }
        }
    }

    fun unpack(bundle: File, databaseOut: File, filesRoot: File, prefsOut: File): Map<String, Map<String, String>> {
        var entries = 0
        var total = 0L
        val seen = mutableSetOf<String>()
        val prefs = linkedMapOf<String, Map<String, String>>()
        val canonicalFilesRoot = filesRoot.canonicalFile
        ZipInputStream(BufferedInputStream(FileInputStream(bundle))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                if (entries > MAX_ENTRIES) throw BackupException("backup contains too many files")
                val name = entry.name
                if (!seen.add(name)) throw BackupException("backup contains duplicate entries")
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                when {
                    name == "database.db" -> total += copyEntry(zip, databaseOut)
                    name.startsWith("files/") -> {
                        val rest = name.removePrefix("files/")
                        val slash = rest.indexOf('/')
                        if (slash <= 0) throw BackupException("invalid backup file path")
                        val rootName = rest.substring(0, slash)
                        val relative = rest.substring(slash + 1)
                        if (!rootName.matches(Regex("[a-z][a-z0-9_-]{0,31}"))) throw BackupException("invalid backup root")
                        validatePath(relative)
                        val target = File(File(canonicalFilesRoot, rootName), relative).canonicalFile
                        if (!target.toPath().startsWith(canonicalFilesRoot.toPath())) throw BackupException("invalid backup file path")
                        total += copyEntry(zip, target)
                    }
                    name.startsWith("prefs/") && name.endsWith(".properties") -> {
                        val prefName = name.removePrefix("prefs/").removeSuffix(".properties")
                        if (!prefName.matches(Regex("[a-z][a-z0-9_-]{0,63}"))) throw BackupException("invalid preferences entry")
                        val props = Properties()
                        props.load(zip)
                        prefs[prefName] = props.stringPropertyNames().associateWith { props.getProperty(it) }
                    }
                    else -> throw BackupException("backup contains an unknown entry")
                }
                if (total > MAX_TOTAL_BYTES) throw BackupException("backup is too large")
                zip.closeEntry()
            }
        }
        if (!databaseOut.isFile || databaseOut.length() <= 0) throw BackupException("backup database is missing")
        if (!prefsOut.mkdirs() && !prefsOut.isDirectory) throw IOException("could not create preference staging directory")
        return prefs
    }

    private fun addFile(zip: ZipOutputStream, name: String, file: File) {
        val beforeLength = file.length()
        val beforeModified = file.lastModified()
        zip.putNextEntry(ZipEntry(name))
        val copied = FileInputStream(file).use { it.copyTo(zip, 64 * 1024) }
        zip.closeEntry()
        if (copied != beforeLength || file.length() != beforeLength || file.lastModified() != beforeModified) {
            throw IOException("a file changed while the backup was being created")
        }
    }

    private fun copyEntry(input: ZipInputStream, output: File): Long {
        output.parentFile?.mkdirs()
        var copied = 0L
        FileOutputStream(output).use { out ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                copied += n
                if (copied > MAX_TOTAL_BYTES) throw BackupException("backup is too large")
                out.write(buffer, 0, n)
            }
            out.fd.sync()
        }
        return copied
    }

    private fun validatePath(path: String) {
        if (path.isBlank() || path.startsWith('/') || path.contains('\\') ||
            path.split('/').any { it.isBlank() || it == "." || it == ".." }) {
            throw BackupException("backup contains an unsafe path")
        }
    }
}
