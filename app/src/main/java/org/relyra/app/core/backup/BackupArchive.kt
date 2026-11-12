package org.relyra.app.core.backup

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** ZIP container inside the authenticated backup stream. Entry paths and every extracted hash are verified. */
internal object BackupArchive {
    private const val MAX_ENTRIES = 20_000
    private const val MAX_TOTAL_BYTES = 4L * 1024 * 1024 * 1024
    private const val MAX_PREF_BYTES = 1024 * 1024
    private const val MANIFEST = "manifest.rly"

    private data class Digest(val size: Long, val sha256: ByteArray)

    fun pack(database: File, roots: Map<String, File>, preferences: Map<String, Map<String, String>>, output: File) {
        require(database.isFile && database.length() > 0)
        val files = mutableListOf<Pair<String, File>>()
        files += "database.db" to database
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
                    files += "files/$name/$relative" to canonical
                }
            }
        }
        val prefBytes = preferences.toSortedMap().map { (name, values) ->
            require(name.matches(Regex("[a-z][a-z0-9_-]{0,63}")))
            val props = Properties()
            values.forEach { (key, value) -> props.setProperty(key, value) }
            val bytes = ByteArrayOutputStream().also { props.store(it, "RELYRA backup preferences") }.toByteArray()
            "prefs/$name.properties" to bytes
        }
        val estimatedTotal = files.sumOf { it.second.length() } + prefBytes.sumOf { it.second.size.toLong() }
        if (estimatedTotal > MAX_TOTAL_BYTES) throw BackupException("backup is too large")
        val digests = linkedMapOf<String, Digest>()
        ZipOutputStream(BufferedOutputStream(FileOutputStream(output))).use { zip ->
            files.forEach { (name, file) -> digests[name] = addFile(zip, name, file) }
            prefBytes.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
                digests[name] = Digest(bytes.size.toLong(), sha256(bytes))
            }
            val manifest = ByteArrayOutputStream()
            DataOutputStream(manifest).use { data ->
                data.writeInt(digests.size)
                digests.forEach { (name, digest) ->
                    data.writeUTF(name)
                    data.writeLong(digest.size)
                    data.writeUTF(hex(digest.sha256))
                }
            }
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(manifest.toByteArray())
            zip.closeEntry()
        }
    }

    fun unpack(bundle: File, databaseOut: File, filesRoot: File, prefsOut: File): Map<String, Map<String, String>> {
        var entries = 0
        var total = 0L
        val seen = mutableSetOf<String>()
        val actual = linkedMapOf<String, Digest>()
        var expectedManifest: Map<String, Digest>? = null
        val prefs = linkedMapOf<String, Map<String, String>>()
        val canonicalFilesRoot = filesRoot.canonicalFile
        ZipInputStream(BufferedInputStream(FileInputStream(bundle))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                if (entries > MAX_ENTRIES) throw BackupException("backup contains too many files")
                val name = entry.name
                if (!seen.add(name)) throw BackupException("backup contains duplicate entries")
                if (entry.isDirectory) throw BackupException("backup contains an unexpected directory entry")
                when {
                    name == MANIFEST -> {
                        if (expectedManifest != null) throw BackupException("backup contains duplicate manifests")
                        val bytes = zip.readLimited(8 * 1024 * 1024)
                        total += bytes.size
                        expectedManifest = readManifest(bytes)
                    }
                    name == "database.db" -> {
                        val digest = copyEntry(zip, databaseOut)
                        actual[name] = digest
                        total += digest.size
                    }
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
                        val digest = copyEntry(zip, target)
                        actual[name] = digest
                        total += digest.size
                    }
                    name.startsWith("prefs/") && name.endsWith(".properties") -> {
                        val prefName = name.removePrefix("prefs/").removeSuffix(".properties")
                        if (!prefName.matches(Regex("[a-z][a-z0-9_-]{0,63}"))) throw BackupException("invalid preferences entry")
                        val bytes = zip.readLimited(MAX_PREF_BYTES)
                        total += bytes.size
                        actual[name] = Digest(bytes.size.toLong(), sha256(bytes))
                        val props = Properties()
                        props.load(ByteArrayInputStream(bytes))
                        prefs[prefName] = props.stringPropertyNames().associateWith { props.getProperty(it) }
                    }
                    else -> throw BackupException("backup contains an unknown entry")
                }
                if (total > MAX_TOTAL_BYTES) throw BackupException("backup is too large")
                zip.closeEntry()
            }
        }
        if (!databaseOut.isFile || databaseOut.length() <= 0) throw BackupException("backup database is missing")
        val manifest = expectedManifest ?: throw BackupException("backup manifest is missing")
        if (manifest.keys != actual.keys) throw BackupException("backup manifest does not match its contents")
        manifest.forEach { (name, expected) ->
            val got = actual[name] ?: throw BackupException("backup entry is missing")
            if (got.size != expected.size || !MessageDigest.isEqual(got.sha256, expected.sha256)) {
                throw BackupException("backup file failed its size or SHA-256 check")
            }
        }
        if (!prefsOut.mkdirs() && !prefsOut.isDirectory) throw IOException("could not create preference staging directory")
        return prefs
    }

    private fun addFile(zip: ZipOutputStream, name: String, file: File): Digest {
        val before = hashFile(file)
        zip.putNextEntry(ZipEntry(name))
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                zip.write(buffer, 0, n)
                digest.update(buffer, 0, n)
                size += n
            }
        }
        zip.closeEntry()
        val after = hashFile(file)
        val copied = Digest(size, digest.digest())
        if (before.size != copied.size || !MessageDigest.isEqual(before.sha256, copied.sha256) ||
            after.size != copied.size || !MessageDigest.isEqual(after.sha256, copied.sha256)) {
            throw IOException("a file changed while the backup was being created")
        }
        return copied
    }

    private fun copyEntry(input: ZipInputStream, output: File): Digest {
        output.parentFile?.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        FileOutputStream(output).use { out ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                copied += n
                if (copied > MAX_TOTAL_BYTES) throw BackupException("backup is too large")
                digest.update(buffer, 0, n)
                out.write(buffer, 0, n)
            }
            out.fd.sync()
        }
        return Digest(copied, digest.digest())
    }

    private fun readManifest(bytes: ByteArray): Map<String, Digest> {
        try {
            val result = linkedMapOf<String, Digest>()
            DataInputStream(ByteArrayInputStream(bytes)).use { data ->
                val count = data.readInt()
                if (count !in 1..MAX_ENTRIES) throw BackupException("invalid backup manifest")
                repeat(count) {
                    val name = data.readUTF()
                    validateEntryName(name)
                    val size = data.readLong()
                    val hash = parseHex(data.readUTF())
                    if (size < 0 || hash.size != 32 || result.put(name, Digest(size, hash)) != null) {
                        throw BackupException("invalid backup manifest")
                    }
                }
                if (data.available() != 0) throw BackupException("invalid backup manifest")
            }
            return result
        } catch (e: BackupException) {
            throw e
        } catch (_: Exception) {
            throw BackupException("invalid backup manifest")
        }
    }

    private fun validateEntryName(name: String) {
        if (name != "database.db" && !name.startsWith("files/") &&
            !name.startsWith("prefs/")) throw BackupException("invalid backup manifest entry")
        if (name.startsWith("files/")) {
            val rest = name.removePrefix("files/")
            val slash = rest.indexOf('/')
            if (slash <= 0) throw BackupException("invalid backup manifest entry")
            validatePath(rest.substring(slash + 1))
        }
    }

    private fun validatePath(path: String) {
        if (path.isBlank() || path.startsWith('/') || path.contains('\\') ||
            path.split('/').any { it.isBlank() || it == "." || it == ".." }) {
            throw BackupException("backup contains an unsafe path")
        }
    }

    private fun hashFile(file: File): Digest {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
                size += n
            }
        }
        return Digest(size, digest.digest())
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    private fun parseHex(value: String): ByteArray {
        if (!value.matches(Regex("(?i)[0-9a-f]{64}"))) throw BackupException("invalid backup manifest hash")
        return ByteArray(32) { i -> value.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    private fun ZipInputStream.readLimited(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = read(buffer)
            if (n < 0) break
            if (out.size() + n > limit) throw BackupException("backup preferences entry is too large")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
