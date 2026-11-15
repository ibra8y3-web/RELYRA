package org.relyra.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relyra.app.core.messaging.*
import java.util.UUID

internal class FakeUploader : BlobUploader {
    var outcome: UploadOutcome = UploadOutcome.Done("https://files.example.org")
    var calls = 0
    val discarded = mutableListOf<String>()
    override suspend fun upload(blobPath: String, sha256: ByteArray): UploadOutcome { calls++; return outcome }
    override fun discard(blobPath: String) { discarded += blobPath }
}

class AttachmentEngineTest {
    private var now = 1_000_000L
    private val clock = { now }
    private val uploader = FakeUploader()

    private fun pair(): Pair<Node, Node> {
        val a = Node("alice", "bob", clock, uploader)
        val b = Node("bob", "alice", clock)
        a.link.deliver = { b.engine.onPacket(it) }
        b.link.deliver = { a.engine.onPacket(it) }
        return a to b
    }

    private fun descriptor(id: String, path: String? = "/tmp/blob.enc") = StoredAttachment(
        id, "report.pdf", "application/pdf", 5_000, 5_100,
        ByteArray(32) { 1 }, ByteArray(32) { 2 }, ByteArray(32) { 3 },
        "", path, AttachmentPhase.AWAITING_UPLOAD,
    )

    private suspend fun queue(a: Node): String =
        (a.engine.enqueueAttachment("bob") { descriptor(it) } as EnqueueResult.Queued).messageId

    @Test fun fileIsUploadedOnceThenDeliveredAndStoredOnce() = runBlocking {
        val (a, b) = pair()
        val id = queue(a)
        a.engine.processOutbox()
        assertEquals(1, uploader.calls)
        assertEquals(listOf("/tmp/blob.enc"), uploader.discarded)
        val mine = a.store.attachments[id]!!
        assertEquals(AttachmentPhase.UPLOADED, mine.phase)
        assertEquals("https://files.example.org", mine.serverUrl)
        assertNull(mine.localPath)
        assertEquals(MessageState.DELIVERED, a.store.rows[id]!!.state)

        val theirs = b.store.attachments[id]!!
        assertEquals(AttachmentPhase.REMOTE, theirs.phase)
        assertEquals("report.pdf", theirs.name)
        assertEquals(5_000L, theirs.size)
        assertArrayEquals(ByteArray(32) { 3 }, theirs.fileKey)
        assertEquals(1, b.store.rows.values.count { !it.outgoing })
    }

    @Test fun lostAckResendsDescriptorWithoutUploadingAgain() = runBlocking {
        val (a, b) = pair()
        b.link.dropNext = 1
        val id = queue(a)
        a.engine.processOutbox()
        assertEquals(MessageState.SENT, a.store.rows[id]!!.state)
        now += 2 * 60_000L
        a.engine.processOutbox()
        assertEquals(1, uploader.calls)
        assertEquals(2, a.link.sendCount)
        assertEquals(1, b.store.rows.values.count { !it.outgoing })
        assertEquals(MessageState.DELIVERED, a.store.rows[id]!!.state)
    }

    @Test fun sameFileSentTwiceIsTwoMessages() = runBlocking {
        val (a, b) = pair()
        val first = queue(a)
        val second = queue(a)
        a.engine.processOutbox()
        assertTrue(first != second)
        assertEquals(2, b.store.attachments.size)
    }

    @Test fun unavailableUploadKeepsMessageQueuedAndSendsNothing() = runBlocking {
        val (a, b) = pair()
        uploader.outcome = UploadOutcome.Unavailable
        val id = queue(a)
        a.engine.processOutbox()
        assertEquals(MessageState.QUEUED, a.store.rows[id]!!.state)
        assertEquals(AttachmentPhase.AWAITING_UPLOAD, a.store.attachments[id]!!.phase)
        assertEquals(0, a.link.sendCount)
        assertTrue(b.store.rows.isEmpty())

        uploader.outcome = UploadOutcome.Done("https://files.example.org")
        now += DeliveryEngine.backoff(1) + 1
        a.engine.processOutbox()
        assertEquals(MessageState.DELIVERED, a.store.rows[id]!!.state)
        assertEquals(1, b.store.rows.values.count { !it.outgoing })
    }

    @Test fun rejectedUploadMarksTheMessageFailed() = runBlocking {
        val (a, b) = pair()
        uploader.outcome = UploadOutcome.Rejected("too large")
        val id = queue(a)
        a.engine.processOutbox()
        assertEquals(MessageState.FAILED, a.store.rows[id]!!.state)
        assertEquals(AttachmentPhase.FAILED, a.store.attachments[id]!!.phase)
        assertEquals(0, a.link.sendCount)
        assertTrue(b.store.rows.isEmpty())
    }

    @Test fun cancellingAQueuedFileDropsTheLocalEncryptedCopy() = runBlocking {
        val (a, _) = pair()
        uploader.outcome = UploadOutcome.Unavailable
        val id = queue(a)
        a.engine.processOutbox()
        a.engine.cancel(id)
        assertEquals(MessageState.CANCELLED, a.store.rows[id]!!.state)
        assertTrue(uploader.discarded.contains("/tmp/blob.enc"))
        assertNull(a.store.attachments[id]!!.localPath)
    }

    @Test fun expiredQueuedFileIsNeverUploaded() = runBlocking {
        val (a, _) = pair()
        val id = queue(a)
        now += DeliveryEngine.TTL_MS + 1
        a.engine.processOutbox()
        assertEquals(0, uploader.calls)
        assertEquals(MessageState.EXPIRED, a.store.rows[id]!!.state)
    }

    @Test fun incomingDescriptorWithPrivateServerIsDropped() = runBlocking {
        val (_, b) = pair()
        for (url in listOf("https://192.168.1.5", "https://10.0.0.1:8443", "http://files.example.org", "https://router.local")) {
            val env = Envelope.Attachment(UUID.randomUUID().toString(), "alice", "bob", 1, "x.bin", "application/octet-stream",
                5_000, 5_100, ByteArray(32), ByteArray(32), ByteArray(32), url)
            b.engine.onPacket(EnvelopeCodec.encode(env))
        }
        assertTrue(b.store.rows.isEmpty())
        assertEquals(0, b.link.sendCount)
    }

    @Test fun incomingFileNameIsSanitized() = runBlocking {
        val (_, b) = pair()
        val id = UUID.randomUUID().toString()
        b.engine.onPacket(EnvelopeCodec.encode(Envelope.Attachment(id, "alice", "bob", 1, "../../etc/passwd", "text/plain",
            100, 200, ByteArray(32), ByteArray(32), ByteArray(32), "https://files.example.org")))
        val a = b.store.attachments[id]
        assertNotNull(a)
        assertTrue(!a!!.name.contains('/'))
        assertTrue(!a.name.startsWith("."))
    }

    @Test fun duplicateDescriptorIsStoredOnceAndReAcknowledged() = runBlocking {
        val (a, b) = pair()
        queue(a)
        a.engine.processOutbox()
        val packet = a.link.sent.first()
        repeat(4) { b.engine.onPacket(packet) }
        assertEquals(1, b.store.attachments.size)
        assertEquals(1 + 4, b.link.sendCount)
    }

    @Test fun descriptorRoundTripAndBounds() {
        val id = UUID.randomUUID().toString()
        val env = Envelope.Attachment(id, "a", "b", 7, "مستند.pdf", "application/pdf", 1234, 1300,
            ByteArray(32) { 9 }, ByteArray(32) { 8 }, ByteArray(32) { 7 }, "https://files.example.org")
        val back = EnvelopeCodec.decode(EnvelopeCodec.encode(env)) as Envelope.Attachment
        assertEquals("مستند.pdf", back.name)
        assertEquals(1234L, back.size)
        assertArrayEquals(ByteArray(32) { 7 }, back.key)

        fun withSizes(size: Long, blob: Long) = EnvelopeCodec.encode(
            Envelope.Attachment(id, "a", "b", 7, "f", "x/y", size, blob, ByteArray(32), ByteArray(32), ByteArray(32), "https://a.b"))
        assertNull(EnvelopeCodec.decode(withSizes(0, 10)))
        assertNull(EnvelopeCodec.decode(withSizes(AttachmentLimits.MAX_BYTES + 1, AttachmentLimits.MAX_BYTES + 2)))
        assertNull(EnvelopeCodec.decode(withSizes(1000, 1000)))          // ciphertext is always larger
        assertNull(EnvelopeCodec.decode(withSizes(1000, 500_000)))       // absurd overhead
        assertNotNull(EnvelopeCodec.decode(withSizes(1000, 1100)))
    }
}
