package org.relyra.app.core.transfer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import org.json.JSONException
import org.json.JSONObject
import org.relyra.app.core.network.Hex
import org.relyra.app.core.network.Http
import org.relyra.app.core.network.NostrEvents
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface BlossomUpload {
    class Ok(val server: String) : BlossomUpload
    data object Unavailable : BlossomUpload
    class Rejected(val reason: String) : BlossomUpload
}

sealed interface BlossomDownload {
    data object Done : BlossomDownload
    class Failed(val reason: String, val retryable: Boolean) : BlossomDownload
}

/**
 * Blossom (BUD-01 / BUD-02) over HTTPS: `PUT /upload` with a signed kind-24242 authorization event, and
 * `GET /<sha256>` with HTTP Range resume. Everything stored there is already ciphertext.
 */
class BlossomClient(base: OkHttpClient = Http.client) {
    private val http: OkHttpClient = base.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .pingInterval(0, TimeUnit.SECONDS)
        .build()

    companion object {
        const val AUTH_KIND = 24242
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
    }

    suspend fun upload(server: String, file: File, sha256Hex: String, secret: ByteArray, nowSeconds: Long): BlossomUpload =
        withContext(Dispatchers.IO) {
            val auth = NostrEvents.create(
                secret, AUTH_KIND,
                listOf(listOf("t", "upload"), listOf("x", sha256Hex), listOf("expiration", (nowSeconds + 600).toString())),
                "Upload", nowSeconds,
            )
            val header = "Nostr " + Base64.getEncoder().encodeToString(NostrEvents.toJson(auth).toByteArray(Charsets.UTF_8))
            try {
                val request = Request.Builder()
                    .url("$server/upload")
                    .put(file.asRequestBody("application/octet-stream".toMediaType()))
                    .header("Authorization", header)
                    .header("X-SHA-256", sha256Hex)
                    .build()
                http.newCall(request).await().use { resp -> classifyUpload(resp, sha256Hex, server) }
            } catch (e: IOException) {
                BlossomUpload.Unavailable
            } catch (e: IllegalArgumentException) {
                BlossomUpload.Rejected("invalid server address")
            }
        }

    private fun classifyUpload(resp: Response, sha256Hex: String, server: String): BlossomUpload {
        val code = resp.code
        if (code in 200..299) {
            val text = resp.body?.string().orEmpty()
            val reported = try { JSONObject(text).optString("sha256", "") } catch (e: JSONException) { "" }
            if (!reported.matches(Regex("(?i)[0-9a-f]{64}"))) {
                return BlossomUpload.Rejected("the server response did not contain a valid SHA-256")
            }
            if (!reported.equals(sha256Hex, ignoreCase = true)) {
                return BlossomUpload.Rejected("the server reported a different hash")
            }
            return BlossomUpload.Ok(server)
        }
        if (code == 408 || code == 429 || code >= 500) return BlossomUpload.Unavailable
        val reason = resp.header("X-Reason")?.take(120)
        return BlossomUpload.Rejected("HTTP $code" + (if (reason != null) ": $reason" else ""))
    }

    /**
     * Downloads the blob into [part], resuming from whatever is already there. Verifies the exact size and SHA-256
     * of the ciphertext before returning [BlossomDownload.Done]. A partial file is kept after retryable failures.
     */
    suspend fun download(
        server: String,
        sha256Hex: String,
        part: File,
        expectedSize: Long,
        onProgress: (Long) -> Unit,
    ): BlossomDownload = withContext(Dispatchers.IO) {
        if (part.exists() && part.length() > expectedSize) part.delete()
        if (!part.exists() || part.length() < expectedSize) {
            val failure = fetch(server, sha256Hex, part, expectedSize, onProgress)
            if (failure != null) return@withContext failure
        }
        if (part.length() != expectedSize) return@withContext BlossomDownload.Failed("download incomplete", true)
        val actual = FileCrypto.sha256(part)
        if (!Hex.encode(actual).equals(sha256Hex, ignoreCase = true)) {
            part.delete()
            return@withContext BlossomDownload.Failed("the file does not match its hash", false)
        }
        BlossomDownload.Done
    }

    /** Returns null when the body was written to [part] without a problem. */
    private suspend fun fetch(
        server: String, sha256Hex: String, part: File, expectedSize: Long, onProgress: (Long) -> Unit,
    ): BlossomDownload.Failed? {
        val have = if (part.exists()) part.length() else 0L
        val builder = Request.Builder().url("$server/$sha256Hex")
        if (have > 0) builder.header("Range", "bytes=$have-")
        return try {
            http.newCall(builder.build()).await().use { resp ->
                when {
                    resp.code == 416 -> {
                        part.delete()
                        BlossomDownload.Failed("the server rejected the resume request", true)
                    }
                    resp.code == 404 -> BlossomDownload.Failed("the file is not on this server (any more)", false)
                    resp.code == 408 || resp.code == 429 || resp.code >= 500 ->
                        BlossomDownload.Failed("server busy (HTTP ${resp.code})", true)
                    resp.code != 200 && resp.code != 206 -> BlossomDownload.Failed("HTTP ${resp.code}", false)
                    else -> writeBody(resp, part, have, expectedSize, onProgress)
                }
            }
        } catch (e: IOException) {
            BlossomDownload.Failed("connection lost", true)
        } catch (e: IllegalArgumentException) {
            BlossomDownload.Failed("invalid server address", false)
        }
    }

    private fun writeBody(
        resp: Response, part: File, have: Long, expectedSize: Long, onProgress: (Long) -> Unit,
    ): BlossomDownload.Failed? {
        val body = resp.body ?: return BlossomDownload.Failed("empty response", true)
        val resumed = resp.code == 206 && have > 0
        if (resumed) {
            val start = Regex("bytes (\\d+)-").find(resp.header("Content-Range").orEmpty())
                ?.groupValues?.get(1)?.toLongOrNull()
            if (start != have) {
                part.delete()
                return BlossomDownload.Failed("the server resumed at the wrong position", true)
            }
        }
        val startAt = if (resumed) have else 0L
        val declared = body.contentLength()
        if (declared > expectedSize - startAt) {
            part.delete()
            return BlossomDownload.Failed("the server sent more data than expected", false)
        }
        var written = startAt
        var tooMuch = false
        FileOutputStream(part, resumed).use { out ->
            val buf = ByteArray(64 * 1024)
            body.byteStream().use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    written += n
                    if (written > expectedSize) { tooMuch = true; break }
                    out.write(buf, 0, n)
                    onProgress(written)
                }
            }
        }
        if (tooMuch) {
            part.delete()
            return BlossomDownload.Failed("the server sent more data than expected", false)
        }
        return null
    }
}
