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
    private val client = BlossomClient(OkHttpClient())

    @Before fun start() = server.start()
    @After fun stop() = server.shutdown()

    private fun base() = server.url("/").toString().trimEnd('/')
    private fun tmp(): File = File.createTempFile("relyra", ".bin").also { it.deleteOnExit() }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private fun body(b: ByteArray) = Buffer().write(b)

    @Test fun uploadSendsSignedAuthorizationAndTheExactBytes() = runBlocking {
        val data = Random(3).nextBytes(5000)
        val file = tmp().also { it.writeBytes(data) }
        val hex = Hex.encode(sha(data))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"sha256\":\"$hex\",\"size\":5000}"))

        val result = client.upload(base(), file, hex, NostrCrypto.randomSecret(), 1_700_000_000)
        assertTrue(result is BlossomUpload.Ok)
        assertEquals(base(), (result as BlossomUpload.Ok).server)

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

    @Test fun uploadErrorsAreClassified() = runBlocking {
        val file = tmp().also { it.writeBytes(ByteArray(10) { 1 }) }
        val hex = Hex.encode(sha(ByteArray(10) { 1 }))
        val secret = NostrCrypto.randomSecret()

        server.enqueue(MockResponse().setResponseCode(401).addHeader("X-Reason", "not allowed"))
        val denied = client.upload(base(), file, hex, secret, 1) as BlossomUpload.Rejected
        assertTrue(denied.reason.contains("401") && denied.reason.contains("not allowed"))

        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(client.upload(base(), file, hex, secret, 1) is BlossomUpload.Unavailable)

        server.enqueue(MockResponse().setResponseCode(429))
        assertTrue(client.upload(base(), file, hex, secret, 1) is BlossomUpload.Unavailable)

        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"sha256\":\"${"0".repeat(64)}\"}"))
        assertTrue(client.upload(base(), file, hex, secret, 1) is BlossomUpload.Rejected)
    }

    @Test fun uploadRejectsMissingOrMalformedServerHash() = runBlocking {
        val data = ByteArray(32) { it.toByte() }
        val file = tmp().also { it.writeBytes(data) }
        val hex = Hex.encode(sha(data))
        val secret = NostrCrypto.randomSecret()

        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        assertTrue(client.upload(base(), file, hex, secret, 1) is BlossomUpload.Rejected)

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"sha256":"not-a-hash"}"""))
        assertTrue(client.upload(base(), file, hex, secret, 1) is BlossomUpload.Rejected)
    }

    @Test fun unreachableServerIsUnavailable() = runBlocking {
        val file = tmp().also { it.writeBytes(ByteArray(10)) }
        val dead = base()
        server.shutdown()
        assertTrue(client.upload(dead, file, "0".repeat(64), NostrCrypto.randomSecret(), 1) is BlossomUpload.Unavailable)
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
}
