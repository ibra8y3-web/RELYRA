package org.relyra.app.core.backup

import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Proxy
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Minimal WebDAV client for backups on a NAS (Synology, QNAP, TrueNAS, ...) or any WebDAV server (Nextcloud, Apache, nginx).
 * Backups are already encrypted (.rlybk) before they get here, so the server only ever sees ciphertext.
 *
 * Transfers: uploads go to "<name>.part" and are renamed with MOVE only after the size matches, so a half-written backup
 * never looks complete. Downloads resume with HTTP Range when the server answers 206. WebDAV has no standard upload-resume,
 * so an interrupted upload restarts from the beginning (we never claim otherwise). After upload the file is read back and its
 * SHA-256 compared with the local one. Redirects are never followed (credentials must not travel to another host), plain
 * http:// is refused unless explicitly allowed, and an optional [proxy] lets the user send backups through Tor.
 */
class WebDavClient(
    baseUrl: String,
    private val username: String,
    password: CharArray,
    allowInsecureHttp: Boolean = false,
    proxy: Proxy? = null,
) {
    class Entry(val name: String, val size: Long, val isDirectory: Boolean)
    class WebDavException(message: String, val status: Int = -1) : IOException(message)

    private val base: HttpUrl = normalize(baseUrl, allowInsecureHttp)
    private val authHeader: String? = if (username.isEmpty()) null else Credentials.basic(username, String(password), Charsets.UTF_8)
    private val http: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS)
        .apply { if (proxy != null) proxy(proxy) }
        .build()

    companion object {
        const val MAX_LISTING_BYTES = 2L * 1024 * 1024
        private val XML = "application/xml".toMediaType()
        private val BIN = "application/octet-stream".toMediaType()
        private const val PROPFIND_BODY =
            """<?xml version="1.0" encoding="utf-8"?><propfind xmlns="DAV:"><prop><getcontentlength/><resourcetype/></prop></propfind>"""

        fun normalize(raw: String, allowInsecureHttp: Boolean): HttpUrl {
            val u = raw.trim().toHttpUrlOrNull() ?: throw WebDavException("invalid URL")
            if (u.scheme != "https" && !(u.scheme == "http" && allowInsecureHttp)) {
                throw WebDavException("plain HTTP is disabled; use https or enable the LAN option")
            }
            if (u.username.isNotEmpty() || u.password.isNotEmpty()) throw WebDavException("do not put credentials in the URL")
            val path = u.encodedPath
            return if (path.endsWith("/")) u else u.newBuilder().encodedPath("$path/").build()
        }

        /** Backup names we create and accept back: letters, digits, dot, dash, underscore only. */
        fun safeName(name: String) = Regex("^[A-Za-z0-9._-]{1,120}$").matches(name) && name != "." && name != ".."

        fun sha256(input: InputStream): ByteArray {
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            return md.digest()
        }

        internal fun parseListing(xml: String, basePath: String): List<Entry> {
            val out = ArrayList<Entry>()
            val opts = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
            for (r in Regex("<(?:\\w+:)?response[ >].*?</(?:\\w+:)?response>", opts).findAll(xml)) {
                val block = r.value
                val href = Regex("<(?:\\w+:)?href>(.*?)</(?:\\w+:)?href>", opts).find(block)?.groupValues?.get(1)?.trim() ?: continue
                val path = try { java.net.URI(href).path ?: continue } catch (e: Exception) { continue }
                val isDir = Regex("<(?:\\w+:)?collection\\s*/?>", RegexOption.IGNORE_CASE).containsMatchIn(block) || path.endsWith("/")
                val size = Regex("<(?:\\w+:)?getcontentlength>\\s*(\\d+)\\s*<", RegexOption.IGNORE_CASE).find(block)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val rel = path.removePrefix(basePath).trim('/')
                if (rel.isEmpty() || rel.contains('/')) continue
                out.add(Entry(rel, size, isDir))
            }
            return out
        }
    }

    private fun urlFor(path: String): HttpUrl {
        val clean = path.trim('/')
        val b = base.newBuilder()
        if (clean.isNotEmpty()) {
            val parts = clean.split('/')
            if (!parts.all { safeName(it) }) throw WebDavException("illegal path")
            parts.forEach { b.addPathSegment(it) }
            if (path.endsWith("/")) b.addPathSegment("")
        }
        return b.build()
    }

    private fun request(method: String, path: String, headers: Map<String, String> = emptyMap(), body: RequestBody? = null): Response {
        val rb = Request.Builder().url(urlFor(path))
        authHeader?.let { rb.header("Authorization", it) }
        headers.forEach { (k, v) -> rb.header(k, v) }
        rb.method(method, body)
        return http.newCall(rb.build()).execute()
    }

    private fun Response.require(ok: Set<Int>, what: String): Response {
        if (code in 300..399) { close(); throw WebDavException("$what: the server redirected (not followed for safety)", code) }
        if (code == 401 || code == 403) { close(); throw WebDavException("$what: access denied (check user name and password)", code) }
        if (code !in ok) { close(); throw WebDavException("$what failed (HTTP $code)", code) }
        return this
    }

    fun testConnection(): Boolean =
        request("PROPFIND", "", mapOf("Depth" to "0"), PROPFIND_BODY.toRequestBody(XML)).require(setOf(200, 207), "connection test").use { true }

    fun ensureFolder(folder: String) {
        var acc = ""
        for (part in folder.trim('/').split('/').filter { it.isNotEmpty() }) {
            acc = if (acc.isEmpty()) part else "$acc/$part"
            request("MKCOL", "$acc/").require(setOf(201, 405), "creating folder").close() // 405 = already exists
        }
    }

    fun list(folder: String): List<Entry> {
        val path = folder.trim('/').let { if (it.isEmpty()) "" else "$it/" }
        request("PROPFIND", path, mapOf("Depth" to "1"), PROPFIND_BODY.toRequestBody(XML)).require(setOf(207), "listing").use { r ->
            val src = r.body!!.source()
            val raw = run {
                val buf = okio.Buffer()
                var total = 0L
                while (true) {
                    val n = src.read(buf, 8192)
                    if (n < 0) break
                    total += n
                    if (total > MAX_LISTING_BYTES) throw WebDavException("listing is too large")
                }
                buf.readByteArray()
            }
            return parseListing(String(raw, Charsets.UTF_8), base.encodedPath + path).filter { !it.isDirectory }
        }
    }

    private fun remoteSize(path: String): Long? {
        request("HEAD", path).use { r ->
            return when (r.code) {
                200 -> r.header("Content-Length")?.toLongOrNull()
                404 -> null
                else -> { r.require(setOf(200), "checking file"); null }
            }
        }
    }

    /**
     * Uploads [file] as [folder]/[name] and verifies it. Never overwrites an existing backup: if [name] exists the caller must
     * pick another name (version conflict). Returns the SHA-256 of the stored file.
     */
    fun uploadVerified(folder: String, name: String, file: File, onProgress: (Long, Long) -> Unit = { _, _ -> }): ByteArray {
        if (!safeName(name)) throw WebDavException("illegal file name")
        ensureFolder(folder)
        val dir = folder.trim('/')
        val finalPath = if (dir.isEmpty()) name else "$dir/$name"
        val partPath = "$finalPath.part"
        if (remoteSize(finalPath) != null) throw WebDavException("a backup with this name already exists", 412)
        val total = file.length()
        val body = object : RequestBody() {
            override fun contentType() = BIN
            override fun contentLength() = total
            override fun writeTo(sink: BufferedSink) {
                file.inputStream().use { input ->
                    val buf = ByteArray(64 * 1024); var sent = 0L
                    while (true) { val n = input.read(buf); if (n < 0) break; sink.write(buf, 0, n); sent += n; onProgress(sent, total) }
                }
            }
        }
        request("PUT", partPath, body = body).require(setOf(200, 201, 204), "upload").close()
        if (remoteSize(partPath) != total) {
            runCatching { delete(partPath) }
            throw WebDavException("the server stored a different size than was sent")
        }
        request("MOVE", partPath, mapOf("Destination" to urlFor(finalPath).toString(), "Overwrite" to "F"))
            .require(setOf(201, 204), "finalising upload").close()
        val remoteHash = request("GET", finalPath).require(setOf(200), "verification download").use { sha256(it.body!!.byteStream()) }
        val localHash = file.inputStream().use { sha256(it) }
        if (!MessageDigest.isEqual(remoteHash, localHash)) {
            runCatching { delete(finalPath) }
            throw WebDavException("verification failed: the stored copy does not match; it was removed")
        }
        return localHash
    }

    /** Downloads into [target]; if a partial [target] exists and the server supports Range it continues from there. */
    fun download(folder: String, name: String, target: File, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        if (!safeName(name)) throw WebDavException("illegal file name")
        val dir = folder.trim('/')
        val path = if (dir.isEmpty()) name else "$dir/$name"
        val total = remoteSize(path) ?: throw WebDavException("the backup was not found on the server", 404)
        var have = if (target.isFile) target.length() else 0L
        if (have > total) { target.delete(); have = 0 }
        if (have < total) {
            val headers = if (have > 0) mapOf("Range" to "bytes=$have-") else emptyMap()
            request("GET", path, headers).require(setOf(200, 206), "download").use { r ->
                val resumed = have > 0 && r.code == 206
                if (have > 0 && !resumed) target.delete()
                FileOutputStream(target, resumed).use { out ->
                    r.body!!.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        var done = if (resumed) have else 0L
                        while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); done += n; onProgress(done, total) }
                    }
                }
            }
        }
        if (target.length() != total) throw WebDavException("the download is incomplete; try again to resume")
    }

    fun delete(path: String) {
        request("DELETE", path).require(setOf(200, 204, 404), "delete").close()
    }

    /** Keeps the newest [keep] backups named relyra-*.rlybk and deletes older ones. Returns how many were removed. */
    fun prune(folder: String, keep: Int): Int {
        require(keep >= 1)
        val dir = folder.trim('/')
        var removed = 0
        for (e in list(folder).filter { it.name.startsWith("relyra-") && it.name.endsWith(".rlybk") }.sortedByDescending { it.name }.drop(keep)) {
            delete(if (dir.isEmpty()) e.name else "$dir/${e.name}"); removed++
        }
        return removed
    }
}
