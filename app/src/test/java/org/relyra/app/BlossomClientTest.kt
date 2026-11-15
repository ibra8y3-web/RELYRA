package org.relyra.app

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.relyra.app.core.network.Hex
import org.relyra.app.core.network.NostrCrypto
import org.relyra.app.core.network.NostrEvents
import org.relyra.app.core.transfer.BlossomClient
import org.relyra.app.core.transfer.BlossomDownload
import org.relyra.app.core.transfer.BlossomUpload
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlin.random.Random

class BlossomClientTest {
    private val server = MockWebServer()
    private val testHttp = OkHttpClient()
    private val client = BlossomClient(testHttp)

    @Before fun start() = server.start()
    @After fun stop() {
        server.shutdown()
        testHttp.dispatcher.executorService.shutdown()
        testHttp.connectionPool.evictAll()
    }

    private fun base() = server.url("/").toString().trimEnd('/')
    private fun tmp(): File = File.createTempFile("relyra", ".bin").also { it.deleteOnExit() }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private fun body(b: ByteArray) = Buffer().write(b)

    private fun descriptor(hash: String, size: Int) =
        """{"url":"https://cdn.example.test/$hash","sha256":"$hash","size":$size,"type":"application/octet-stream","uploaded":1700000000}"""

    private fun enqueuePreflightUnsupported() {
        server.enqueue(MockResponse().setResponseCode(404))
    }

    @Test fun uploadSendsSignedAuthorizationAndTheExactBytes() = runBlocking {
        val data = Random(3).nextBytes(5000)
        val file = tmp().also { it.writeBytes(data) }
        val hex = Hex.encode(sha(data))
        server.enqueue(MockResponse().setResponseCode(200)) // BUD-06 preflight accepted
        server.enqueue(MockResponse().setResponseCode(201).setBody(descriptor(hex, data.size)))

        val result = client.upload(base(), file, hex, NostrCrypto.randomSecret(), 1_700_000_000)
        assertTrue(result is BlossomUpload.Ok)
        assertEquals(base(), (result as BlossomUpload.Ok).server)

        val preflight = server.takeRequest()
        assertEquals("HEAD", preflight.method)
        assertEquals("/upload", preflight.path)
        assertEquals(data.size.toString(), preflight.getHeader("X-Content-Length"))
        assertEquals("application/octet-stream", preflight.getHeader("X-Content-Type"))
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/upload", req.path)
        assertEquals(hex, req.getHeader("X-SHA-256"))
        assertArrayEquals(data, req.body.readByteArray())

        val auth = req.getHeader("Authorization")!!
        assertTrue(auth.startsWith("Nostr "))
        val json = String(Base64.getDecoder().decode(auth.removePrefix("Nostr ")), Charsets.UTF_8)
        val event = NostrEvents.fromJson(JSONObject(json))!!
        assertTrue(NostrEvents.verify(event))
        assertEquals(24242, event.kind)
        assertTrue(event.tags.any { it == listOf("t", "upload") })
        assertTrue(event.tags.any { it == listOf("x", hex) })
        assertTrue(event.tags.any { it[0] == "expiration" && it[1].toLong() > 1_700_000_000 })
    }


    @Test fun interruptedUploadCanBeSafelyRetriedFromThePersistedCiphertext() = runBlocking {
        val data = Random(17).nextBytes(24_000)
        val file = tmp().also { it.writeBytes(data) }
        val hash = Hex.encode(sha(data))
        val secret = NostrCrypto.randomSecret()

        enqueuePreflightUnsupported()
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue(client.upload(base(), file, hash, secret, 1) is BlossomUpload.Unavailable)
        assertTrue("encrypted outbox blob must remain available for retry", file.isFile)

        enqueuePreflightUnsupported()
        server.enqueue(MockResponse().setResponseCode(201).setBody(descriptor(hash, data.size)))
        assertTrue(client.upload(base(), file, hash, secret, 2) is BlossomUpload.Ok)
        val retryPreflight = server.takeRequest()
        assertEquals("HEAD", retryPreflight.method)
        assertEquals("/upload", retryPreflight.path)
        val retried = server.takeRequest()
        assertEquals("PUT", retried.method)
        assertEquals(hash, retried.getHeader("X-SHA-256"))
        assertArrayEquals(data, retried.body.readByteArray())
        assertTrue(file.isFile)
    }

    @Test fun uploadErrorsAreClassified() = runBlocking {
        val file = tmp().also { it.writeBytes(ByteArray(10) { 1 }) }
        val hex = Hex.encode(sha(ByteArray(10) { 1 }))
        val secret = NostrCrypto.randomSecret()

        enqueuePreflightUnsupported()
        server.enqueue(MockResponse().setResponseCode(401).addHeader("X-Reason", "not allowed"))
        val denied = client.upload(base(), file, hex, secret, 1) as BlossomUpload.Rejected
        assertTrue(denied.reason.contains("401") && denied.reason.contains("not allowed"))

        enqueuePreflightUnsupported()
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(client.upload(base(), file, hex, secret, 1) is BlossomUpload.Unavailable)

        enqueuePreflightUnsupported()
        server.enqueue(MockResponse().setResponseCode(429))
        assertTrue(client.upload(base(), file, hex, secret, 1) is BlossomUpload.Unavailable)

        enqueuePreflightUnsupported()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"sha256":"${"0".repeat(64)}","size":10,"url":"https://cdn.example.test/x","type":"application/octet-stream","uploaded":1}"""))
        assertTrue(client.upload(base(), file, hex, secret, 1) is BlossomUpload.Rejected)
    }

    @Test fun uploadRejectsMissingOrMalformedServerHash() = runBlocking {
        val data = ByteArray(32) { it.toByte() }
        val file = tmp().also { it.writeBytes(data) }
        val hex = Hex.encode(sha(data))
        val secret = NostrCrypto.randomSecret()

        enqueuePreflightUnsupported()
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        assertTrue(client.upload(base(), file, hex, secret, 1) is BlossomUpload.Rejected)

        enqueuePreflightUnsupported()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"sha256":"not-a-hash"}"""))
        assertTrue(client.upload(base(), file, hex, secret, 1) is BlossomUpload.Rejected)
    }

    @Test(timeout = 15_000) fun unreachableServerIsUnavailable() = runBlocking {
        val data = ByteArray(10)
        val file = tmp().also { it.writeBytes(data) }
        val hash = Hex.encode(sha(data))
        // Close the listener before starting the request. This creates a stable connection-refused
        // condition instead of relying on MockWebServer's asynchronous socket-disconnect policy.
        val serverUrl = base()
        server.shutdown()
        val result = client.upload(serverUrl, file, hash, NostrCrypto.randomSecret(), 1)
        assertTrue("Expected a closed server to be classified as unavailable, got $result", result is BlossomUpload.Unavailable)
    }

    @Test fun downloadWritesVerifiesAndReportsProgress() = runBlocking {
        val data = Random(4).nextBytes(300_000)
        val hex = Hex.encode(sha(data))
        server.enqueue(MockResponse().setResponseCode(200).setBody(body(data)))
        val part = tmp().also { it.delete() }
        var last = 0L
        val result = client.download(base(), hex, part, data.size.toLong()) { last = it }
        assertTrue(result is BlossomDownload.Done)
        assertArrayEquals(data, part.readBytes())
        assertEquals(data.size.toLong(), last)
        assertEquals("/$hex", server.takeRequest().path)
    }

    @Test fun downloadResumesFromThePartialFile() = runBlocking {
        val data = Random(5).nextBytes(200_000)
        val hex = Hex.encode(sha(data))
        val part = tmp().also { it.writeBytes(data.copyOf(70_000)) }
        server.enqueue(
            MockResponse().setResponseCode(206)
                .addHeader("Content-Range", "bytes 70000-199999/200000")
                .setBody(body(data.copyOfRange(70_000, data.size)))
        )
        val result = client.download(base(), hex, part, data.size.toLong()) { }
        assertTrue(result is BlossomDownload.Done)
        assertEquals("bytes=70000-", server.takeRequest().getHeader("Range"))
        assertArrayEquals(data, part.readBytes())
    }

    @Test fun serverThatIgnoresRangeStillWorks() = runBlocking {
        val data = Random(6).nextBytes(50_000)
        val hex = Hex.encode(sha(data))
        val part = tmp().also { it.writeBytes(data.copyOf(10_000)) }
        server.enqueue(MockResponse().setResponseCode(200).setBody(body(data)))
        assertTrue(client.download(base(), hex, part, data.size.toLong()) { } is BlossomDownload.Done)
        assertArrayEquals(data, part.readBytes())
    }

    @Test fun wrongContentIsRejectedAndDeleted() = runBlocking {
        val data = Random(7).nextBytes(20_000)
        val hex = Hex.encode(sha(data))
        val evil = Random(8).nextBytes(20_000)
        server.enqueue(MockResponse().setResponseCode(200).setBody(body(evil)))
        val part = tmp().also { it.delete() }
        val r = client.download(base(), hex, part, data.size.toLong()) { } as BlossomDownload.Failed
        assertFalse(r.retryable)
        assertFalse(part.exists())
    }

    @Test fun serverSendingMoreThanDeclaredIsRejected() = runBlocking {
        val data = Random(9).nextBytes(10_000)
        val hex = Hex.encode(sha(data))
        server.enqueue(MockResponse().setResponseCode(200).setBody(body(data + ByteArray(5_000))))
        val part = tmp().also { it.delete() }
        val r = client.download(base(), hex, part, data.size.toLong()) { } as BlossomDownload.Failed
        assertFalse(r.retryable)
        assertFalse(part.exists())
    }

    @Test fun missingFileAndBusyServerAreClassified() = runBlocking {
        val part = tmp().also { it.delete() }
        server.enqueue(MockResponse().setResponseCode(404))
        assertFalse((client.download(base(), "a".repeat(64), part, 100) { } as BlossomDownload.Failed).retryable)
        server.enqueue(MockResponse().setResponseCode(503))
        assertTrue((client.download(base(), "a".repeat(64), part, 100) { } as BlossomDownload.Failed).retryable)
    }

    @Test fun interruptedDownloadKeepsThePartFileForResume() = runBlocking {
        val data = Random(10).nextBytes(100_000)
        val hex = Hex.encode(sha(data))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(body(data))
                .setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )
        val part = tmp().also { it.delete() }
        val r = client.download(base(), hex, part, data.size.toLong()) { }
        assertTrue(r is BlossomDownload.Failed && r.retryable)
        assertTrue(part.length() < data.size)
    }
    @Test fun uploadRejectsFileThatChangedAfterItsHashWasPrepared() = runBlocking {
        val original = ByteArray(64) { it.toByte() }
        val file = tmp().also { it.writeBytes(original) }
        val expected = Hex.encode(sha(original))
        file.writeBytes(ByteArray(64) { (it + 1).toByte() })
        val result = client.upload(base(), file, expected, NostrCrypto.randomSecret(), 1)
        assertTrue(result is BlossomUpload.Rejected)
        assertEquals(0, server.requestCount)
    }

    @Test fun downloadRejectsPartialResponseWhenNoResumeWasRequested() = runBlocking {
        val data = ByteArray(128) { it.toByte() }
        val hex = Hex.encode(sha(data))
        server.enqueue(MockResponse().setResponseCode(206)
            .addHeader("Content-Range", "bytes 10-127/128").setBody(body(data.copyOfRange(10, data.size))))
        val part = tmp().also { it.delete() }
        val result = client.download(base(), hex, part, data.size.toLong()) { } as BlossomDownload.Failed
        assertTrue(result.retryable)
        assertFalse(part.exists())
        assertEquals(null, server.takeRequest().getHeader("Range"))
    }

    @Test fun bud06RejectsUploadBeforeTheBodyIsSent() = runBlocking {
        val data = ByteArray(256) { it.toByte() }
        val file = tmp().also { it.writeBytes(data) }
        val hash = Hex.encode(sha(data))
        server.enqueue(MockResponse().setResponseCode(413).addHeader("X-Reason", "quota exceeded"))
        val result = client.upload(base(), file, hash, NostrCrypto.randomSecret(), 1)
        assertTrue(result is BlossomUpload.Rejected)
        val request = server.takeRequest()
        assertEquals("HEAD", request.method)
        assertEquals("/upload", request.path)
        assertEquals(data.size.toString(), request.getHeader("X-Content-Length"))
        assertEquals("application/octet-stream", request.getHeader("X-Content-Type"))
        assertEquals(hash, request.getHeader("X-SHA-256"))
        assertTrue(request.getHeader("Authorization")!!.startsWith("Nostr "))
        assertEquals(1, server.requestCount)
    }

}
