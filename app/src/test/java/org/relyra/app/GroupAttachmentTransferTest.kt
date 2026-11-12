package org.relyra.app

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.relyra.app.core.messaging.GroupAttachmentData
import org.relyra.app.core.messaging.GroupFilePayload
import org.relyra.app.core.messaging.GroupPayloadCodec
import org.relyra.app.core.network.Hex
import org.relyra.app.core.transfer.BlossomClient
import org.relyra.app.core.transfer.BlossomDownload
import org.relyra.app.core.transfer.FileCrypto
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random

class GroupAttachmentTransferTest {
    private val server = MockWebServer()
    private val client = BlossomClient(OkHttpClient())

    @Before fun start() { server.start() }
    @After fun stop() { server.shutdown() }

    private fun base() = server.url("/").toString().trimEnd('/')
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun temp(suffix: String) = File.createTempFile("relyra-group-", suffix).also { it.deleteOnExit() }

    @Test(timeout = 15_000)
    fun encryptedGroupBlobDownloadsVerifiesAndDecryptsUsingTheGroupDescriptor() = runBlocking {
        val plaintext = Random(91).nextBytes(180_000)
        val key = FileCrypto.newKey()
        val encrypted = temp(".enc")
        val result = FileCrypto.encrypt(ByteArrayInputStream(plaintext), encrypted, key, 2_000_000)
        val blobHash = FileCrypto.sha256(encrypted)
        val descriptor = GroupAttachmentData(
            name = "group-report.pdf",
            mime = "application/pdf",
            size = result.plainSize,
            blobSize = result.blobSize,
            plainSha256 = result.plainSha256,
            blobSha256 = blobHash,
            fileKey = key,
            serverUrl = base(),
        )
        val wire = GroupPayloadCodec.encodeFile(
            java.util.UUID.randomUUID().toString(),
            1_800_000_000_000L,
            descriptor,
        )
        val decoded = GroupPayloadCodec.decode(wire) as GroupFilePayload
        assertArrayEquals(key, decoded.file.fileKey)

        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(encrypted.readBytes())))
        val part = temp(".part").also { it.delete() }
        val downloaded = client.download(base(), Hex.encode(blobHash), part, result.blobSize) { }
        assertTrue(downloaded is BlossomDownload.Done)

        val opened = temp(".pdf").also { it.delete() }
        assertTrue(FileCrypto.decrypt(part, opened, decoded.file.fileKey, decoded.file.plainSha256, decoded.file.size))
        assertArrayEquals(plaintext, opened.readBytes())
    }

    @Test(timeout = 15_000)
    fun corruptedBlobIsRejectedBeforeGroupFileDecryption() = runBlocking {
        val plaintext = Random(92).nextBytes(64_000)
        val key = FileCrypto.newKey()
        val encrypted = temp(".enc")
        val result = FileCrypto.encrypt(ByteArrayInputStream(plaintext), encrypted, key, 2_000_000)
        val expectedHash = FileCrypto.sha256(encrypted)
        val corrupted = encrypted.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }

        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(corrupted)))
        val part = temp(".part").also { it.delete() }
        val downloaded = client.download(base(), Hex.encode(expectedHash), part, result.blobSize) { }
        assertTrue(downloaded is BlossomDownload.Failed)
    }

    @Test(timeout = 15_000)
    fun validBlobStillFailsWhenTheDescriptorKeyIsWrong() = runBlocking {
        val plaintext = Random(93).nextBytes(96_000)
        val key = FileCrypto.newKey()
        val encrypted = temp(".enc")
        val result = FileCrypto.encrypt(ByteArrayInputStream(plaintext), encrypted, key, 2_000_000)
        val blobHash = FileCrypto.sha256(encrypted)

        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(encrypted.readBytes())))
        val part = temp(".part").also { it.delete() }
        val downloaded = client.download(base(), Hex.encode(blobHash), part, result.blobSize) { }
        assertTrue(downloaded is BlossomDownload.Done)

        val output = temp(".pdf").also { it.delete() }
        assertFalse(
            FileCrypto.decrypt(
                part,
                output,
                FileCrypto.newKey(),
                result.plainSha256,
                result.plainSize,
            )
        )
    }
}
