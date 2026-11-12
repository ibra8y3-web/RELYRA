package org.relyra.app.core.tools

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Pure file inspection helpers. No Android classes except org.json, so they run in plain JVM tests. */
object FileTools {
    class Digests(val md5: String, val sha1: String, val sha256: String, val size: Long)

    fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4]).append(HEX[v and 15])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()

    /** One pass over the stream computes MD5, SHA-1 and SHA-256. (MD5 and SHA-1 are for comparing with old checksums only.) */
    fun digests(input: InputStream): Digests {
        val md5 = MessageDigest.getInstance("MD5")
        val sha1 = MessageDigest.getInstance("SHA-1")
        val sha256 = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md5.update(buf, 0, n); sha1.update(buf, 0, n); sha256.update(buf, 0, n)
            total += n
        }
        return Digests(hex(md5.digest()), hex(sha1.digest()), hex(sha256.digest()), total)
    }

    /** Classic hex dump: offset, 16 bytes in hex, printable characters. */
    fun hexDump(data: ByteArray, baseOffset: Long = 0L): String {
        val sb = StringBuilder()
        var i = 0
        while (i < data.size) {
            val n = minOf(16, data.size - i)
            sb.append(String.format("%08x  ", baseOffset + i))
            for (j in 0 until 16) {
                if (j < n) sb.append(String.format("%02x ", data[i + j].toInt() and 0xff)) else sb.append("   ")
                if (j == 7) sb.append(' ')
            }
            sb.append(" |")
            for (j in 0 until n) {
                val c = data[i + j].toInt() and 0xff
                sb.append(if (c in 32..126) c.toChar() else '.')
            }
            sb.append("|\n")
            i += 16
        }
        return sb.toString()
    }

    /** Runs of printable ASCII, like the `strings` tool. */
    fun strings(data: ByteArray, minLength: Int = 4, maxCount: Int = 5000, maxLength: Int = 300): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        fun flush() {
            if (cur.length >= minLength && out.size < maxCount) out.add(cur.toString())
            cur.setLength(0)
        }
        for (b in data) {
            val c = b.toInt() and 0xff
            if (c in 32..126 || c == 9) {
                cur.append(c.toChar())
                if (cur.length >= maxLength) flush()
            } else {
                flush()
            }
        }
        flush()
        return out
    }

    private fun startsWith(d: ByteArray, vararg sig: Int, at: Int = 0): Boolean {
        if (d.size < at + sig.size) return false
        for (i in sig.indices) if ((d[at + i].toInt() and 0xff) != sig[i]) return false
        return true
    }

    /** What the bytes say the file is, regardless of its name. Null when nothing matches. */
    fun sniffType(head: ByteArray): String? = when {
        startsWith(head, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "PNG image"
        startsWith(head, 0xFF, 0xD8, 0xFF) -> "JPEG image"
        startsWith(head, 0x47, 0x49, 0x46, 0x38) -> "GIF image"
        startsWith(head, 0x25, 0x50, 0x44, 0x46) -> "PDF document"
        startsWith(head, 0x50, 0x4B, 0x03, 0x04) -> "ZIP container (also APK, JAR, DOCX, XLSX)"
        startsWith(head, 0x1F, 0x8B) -> "GZIP data"
        startsWith(head, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C) -> "7-Zip archive"
        startsWith(head, 0x52, 0x61, 0x72, 0x21) -> "RAR archive"
        startsWith(head, 0x7F, 0x45, 0x4C, 0x46) -> "ELF executable"
        startsWith(head, 0x64, 0x65, 0x78, 0x0A) -> "Android DEX code"
        startsWith(head, 0x53, 0x51, 0x4C, 0x69, 0x74, 0x65, 0x20, 0x66) -> "SQLite database"
        startsWith(head, 0x52, 0x49, 0x46, 0x46) && startsWith(head, 0x57, 0x45, 0x42, 0x50, at = 8) -> "WebP image"
        startsWith(head, 0x52, 0x49, 0x46, 0x46) && startsWith(head, 0x57, 0x41, 0x56, 0x45, at = 8) -> "WAV audio"
        startsWith(head, 0x66, 0x74, 0x79, 0x70, at = 4) -> "MP4 / QuickTime media"
        startsWith(head, 0x49, 0x44, 0x33) -> "MP3 audio (ID3 tag)"
        startsWith(head, 0x4F, 0x67, 0x67, 0x53) -> "Ogg media"
        startsWith(head, 0x42, 0x4D) -> "BMP image"
        startsWith(head, 0x4D, 0x5A) -> "Windows executable"
        startsWith(head, 0x75, 0x73, 0x74, 0x61, 0x72, at = 257) -> "TAR archive"
        isProbablyText(head) -> "Text"
        else -> null
    }

    /** No NUL bytes and almost everything printable or ordinary whitespace (UTF-8 multi-byte text counts as printable). */
    fun isProbablyText(data: ByteArray): Boolean {
        if (data.isEmpty()) return false
        var odd = 0
        for (b in data) {
            val c = b.toInt() and 0xff
            if (c == 0) return false
            if (c < 9 || (c in 14..31)) odd++
        }
        return odd * 100 / data.size < 2
    }

    /** Pretty-prints JSON consistently across Android and the host JVM implementation of org.json. */
    fun prettyJson(text: String): String? {
        val t = text.trim()
        return try {
            when {
                t.startsWith("{") -> prettyValue(JSONObject(t), 0)
                t.startsWith("[") -> prettyValue(JSONArray(t), 0)
                else -> null
            }
        } catch (_: JSONException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun prettyValue(value: Any, depth: Int): String = when (value) {
        is JSONObject -> {
            val keys = value.keys().asSequence().toList()
            if (keys.isEmpty()) "{}" else keys.joinToString(
                prefix = "{\n",
                postfix = "\n" + " ".repeat(depth * 2) + "}",
                separator = ",\n",
            ) { key ->
                " ".repeat((depth + 1) * 2) + JSONObject.quote(key) + ": " +
                    prettyValue(value.get(key), depth + 1)
            }
        }
        is JSONArray -> {
            if (value.length() == 0) "[]" else (0 until value.length()).joinToString(
                prefix = "[\n",
                postfix = "\n" + " ".repeat(depth * 2) + "]",
                separator = ",\n",
            ) { index ->
                " ".repeat((depth + 1) * 2) + prettyValue(value.get(index), depth + 1)
            }
        }
        is String -> JSONObject.quote(value)
        value === JSONObject.NULL -> "null"
        is Number, is Boolean -> value.toString()
        else -> JSONObject.quote(value.toString())
    }

    /** Skips exactly [n] bytes (or until the stream ends). Returns how many were skipped. */
    fun skipFully(input: InputStream, n: Long): Long {
        var left = n
        while (left > 0) {
            val s = input.skip(left)
            if (s > 0) {
                left -= s
            } else {
                if (input.read() < 0) break
                left -= 1
            }
        }
        return n - left
    }

    /** Reads at most [max] bytes. */
    fun readUpTo(input: InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var left = max
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size, left))
            if (n < 0) break
            out.write(buf, 0, n)
            left -= n
        }
        return out.toByteArray()
    }

    // ---- ZIP ------------------------------------------------------------------------------------------------

    class ZipItem(val name: String, val size: Long, val directory: Boolean)
    class ZipListing(val items: List<ZipItem>, val truncated: Boolean)

    /** Names are shown, never used as file paths, so a "../" name is harmless. Control characters are removed. */
    fun cleanName(raw: String): String = raw.filter { !it.isISOControl() }.take(300)

    private fun countBytes(z: InputStream, cap: Long): Long {
        val buf = ByteArray(32 * 1024)
        var total = 0L
        while (true) {
            val n = z.read(buf)
            if (n < 0) return total
            total += n
            if (total > cap) return -1L
        }
    }

    /** Lists a ZIP stream (also APK/JAR/DOCX). Throws IOException for a damaged archive. */
    fun listZip(input: InputStream, maxEntries: Int = 5000, perEntryCap: Long = 64L * 1024 * 1024): ZipListing {
        val items = ArrayList<ZipItem>()
        var truncated = false
        ZipInputStream(input).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (items.size >= maxEntries) { truncated = true; break }
                var size = e.size
                if (!e.isDirectory && size < 0) size = countBytes(z, perEntryCap)
                items.add(ZipItem(cleanName(e.name), size, e.isDirectory))
            }
        }
        return ZipListing(items, truncated)
    }

    /** Copies one entry (the first with that exact name) to [out]. Returns the bytes copied, or -1 if there is no such entry. */
    fun copyZipEntry(input: InputStream, name: String, out: OutputStream, maxBytes: Long): Long {
        ZipInputStream(input).use { z ->
            while (true) {
                val e = z.nextEntry ?: return -1L
                if (e.isDirectory || cleanName(e.name) != name) continue
                val buf = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    val n = z.read(buf)
                    if (n < 0) return total
                    total += n
                    if (total > maxBytes) throw IOException("entry is larger than the allowed size")
                    out.write(buf, 0, n)
                }
            }
        }
        return -1L
    }
}
