package org.relyra.app.core.messaging

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.relyra.app.core.transfer.BlossomUrl
import org.relyra.app.core.transfer.FileNames
import java.util.UUID

sealed interface EnqueueResult {
    class Queued(val messageId: String) : EnqueueResult
    data object Empty : EnqueueResult
    data object TooLong : EnqueueResult
    data object Blocked : EnqueueResult
    data object UnknownPeer : EnqueueResult
    data object NoEncryptionKey : EnqueueResult
    data object NoIdentity : EnqueueResult
}

/**
 * Owns a message's life from "user pressed send" to "peer acknowledged".
 *
 * Rule: de-duplicate the DELIVERY, never the CONTENT.
 *  - every user send gets a fresh MessageID (even identical text);
 *  - automatic retries reuse the same MessageID;
 *  - the receiver stores the ID and message atomically, then acks; a repeat only re-acks.
 */
class DeliveryEngine(
    private val store: MessageStore,
    private val peers: PeerDirectory,
    private val protector: PayloadProtector,
    private val transports: () -> List<Transport>,
    private val ownId: suspend () -> String?,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val uploader: BlobUploader? = null,
    private val groups: GroupInbound? = null,
    private val incoming: IncomingListener? = null,
) {
    private val lock = Mutex()
    private val uploadLock = Mutex()

    companion object {
        const val TTL_MS = 7L * 24 * 3600 * 1000
        fun backoff(attempts: Int): Long = minOf(5_000L shl minOf(attempts - 1, 10).coerceAtLeast(0), 900_000L)
        fun ackWait(attempts: Int): Long = minOf(60_000L shl minOf(attempts - 1, 8).coerceAtLeast(0), 3_600_000L)
    }

    suspend fun enqueue(chatId: String, body: String): EnqueueResult {
        if (body.isBlank()) return EnqueueResult.Empty
        if (body.length > EnvelopeCodec.MAX_BODY) return EnqueueResult.TooLong
        rejectionFor(chatId)?.let { return it }
        val now = clock()
        val id = newId()
        store.insertOutgoing(
            StoredMessage(id, chatId, true, body, now, MessageState.QUEUED, 0, now, true)
        )
        return EnqueueResult.Queued(id)
    }

    private suspend fun rejectionFor(chatId: String): EnqueueResult? {
        if (ownId() == null) return EnqueueResult.NoIdentity
        return when (peers.status(chatId)) {
            PeerStatus.Unknown -> EnqueueResult.UnknownPeer
            PeerStatus.Blocked -> EnqueueResult.Blocked
            PeerStatus.NoKeys -> EnqueueResult.NoEncryptionKey
            PeerStatus.Known -> null
        }
    }

    /**
     * Queues a file message. The caller has already encrypted the file; [build] receives the new MessageID and
     * returns its descriptor. The upload and the descriptor message are handled by [processOutbox].
     */
    suspend fun enqueueAttachment(chatId: String, build: (messageId: String) -> StoredAttachment): EnqueueResult {
        rejectionFor(chatId)?.let { return it }
        val now = clock()
        val id = newId()
        val attachment = build(id)
        store.insertOutgoingAttachment(
            StoredMessage(id, chatId, true, attachment.name, now, MessageState.QUEUED, 0, now, true),
            attachment,
        )
        return EnqueueResult.Queued(id)
    }

    /** Encrypts [plaintext] for [peer] and hands it to the first transport that accepts it. Also used by groups. */
    suspend fun transmit(peer: String, plaintext: ByteArray): SendOutcome {
        // Do not encrypt (which advances the ratchet) when there is no route to send on anyway.
        if (transports().none { it.isReady() }) return SendOutcome.Unavailable
        val payload = protector.protect(peer, plaintext) ?: return SendOutcome.Rejected("no encryption key for peer")
        var rejected: SendOutcome.Rejected? = null
        var unavailable = false
        for (t in transports()) {
            when (val r = t.send(peer, payload)) {
                SendOutcome.Accepted, SendOutcome.Relayed -> return r
                SendOutcome.Unavailable -> unavailable = true
                is SendOutcome.Rejected -> rejected = r
            }
        }
        return if (unavailable) SendOutcome.Unavailable else rejected ?: SendOutcome.Unavailable
    }

    /** Sends everything that is due. Safe to call from the UI and from the background worker. */
    suspend fun processOutbox(): Int = processDirect() + (groups?.processOutbox() ?: 0)

    private suspend fun processDirect(): Int {
        uploadPending()
        lock.withLock {
            val me = ownId() ?: return 0
            val now = clock()
            var sent = 0
            for (m in store.dueOutgoing(now, 50)) {
                if (now - m.createdAt > TTL_MS) {
                    store.update(
                        m.copy(
                            state = if (m.state == MessageState.QUEUED) MessageState.EXPIRED else m.state,
                            nextAttemptAt = Long.MAX_VALUE,
                        )
                    )
                    if (m.state == MessageState.QUEUED) abandonAttachment(m.messageId)
                    continue
                }
                val attachment = store.attachment(m.messageId)
                val payload: ByteArray
                if (attachment == null) {
                    payload = EnvelopeCodec.encode(Envelope.Chat(m.messageId, me, m.chatId, m.createdAt, m.body))
                } else when (attachment.phase) {
                    AttachmentPhase.UPLOADED -> {
                        payload = EnvelopeCodec.encode(
                            Envelope.Attachment(
                                m.messageId, me, m.chatId, m.createdAt, attachment.name, attachment.mime,
                                attachment.size, attachment.blobSize, attachment.plainSha256, attachment.blobSha256,
                                attachment.fileKey, attachment.serverUrl,
                            )
                        )
                    }
                    AttachmentPhase.AWAITING_UPLOAD -> {
                        // An upload is running right now: leave the message alone, it becomes due when that finishes.
                        if (uploadLock.isLocked) continue
                        // The upload has not succeeded (no server, no network). Try again later with backoff.
                        val attempts = m.attempts + 1
                        store.update(m.copy(attempts = attempts, nextAttemptAt = now + backoff(attempts)))
                        continue
                    }
                    else -> {
                        store.update(m.copy(state = MessageState.FAILED, nextAttemptAt = Long.MAX_VALUE))
                        continue
                    }
                }
                when (val r = transmit(m.chatId, payload)) {
                    SendOutcome.Accepted, SendOutcome.Relayed -> {
                        val target = if (r == SendOutcome.Relayed) MessageState.RELAYED else MessageState.SENT
                        val attempts = m.attempts + 1
                        store.update(
                            m.copy(
                                state = if (MessageState.canAdvance(m.state, target)) target else m.state,
                                attempts = attempts,
                                nextAttemptAt = now + ackWait(attempts),
                            )
                        )
                        sent++
                    }
                    SendOutcome.Unavailable -> {
                        val attempts = m.attempts + 1
                        store.update(m.copy(attempts = attempts, nextAttemptAt = now + backoff(attempts)))
                    }
                    is SendOutcome.Rejected ->
                        store.update(m.copy(state = MessageState.FAILED, nextAttemptAt = Long.MAX_VALUE))
                }
            }
            return sent
        }
    }

    /**
     * Uploads encrypted files for queued file messages. Runs under its own lock so a slow upload never blocks
     * acknowledgements or text messages. Failures are applied to the message by [processOutbox].
     */
    private suspend fun uploadPending() {
        val up = uploader ?: return
        // Another call is already uploading: do not wait behind it (a slow upload must never delay a text message).
        if (!uploadLock.tryLock()) return
        try {
            val now = clock()
            for (m in store.dueOutgoing(now, 50)) {
                if (!m.outgoing || m.state != MessageState.QUEUED || now - m.createdAt > TTL_MS) continue
                val a = store.attachment(m.messageId) ?: continue
                if (a.phase != AttachmentPhase.AWAITING_UPLOAD) continue
                val path = a.localPath.orEmpty()
                when (val r = up.upload(path, a.blobSha256)) {
                    is UploadOutcome.Done -> {
                        store.updateAttachment(a.copy(serverUrl = r.serverUrl, localPath = null, phase = AttachmentPhase.UPLOADED))
                        up.discard(path)
                    }
                    UploadOutcome.Unavailable -> Unit
                    is UploadOutcome.Rejected -> {
                        store.updateAttachment(a.copy(localPath = null, phase = AttachmentPhase.FAILED))
                        up.discard(path)
                    }
                }
            }
        } finally {
            uploadLock.unlock()
        }
    }

    /** A file message that will never be sent: drop its local encrypted copy. */
    private suspend fun abandonAttachment(messageId: String) {
        val a = store.attachment(messageId) ?: return
        if (a.phase != AttachmentPhase.AWAITING_UPLOAD) return
        a.localPath?.let { uploader?.discard(it) }
        store.updateAttachment(a.copy(localPath = null, phase = AttachmentPhase.FAILED))
    }

    /** Entry point for encrypted packets from a transport: authenticate and decrypt, then process. */
    suspend fun onWire(wire: ByteArray) {
        acceptWire(wire)
    }

    /**
     * Authenticates a transport frame and reports whether it was a valid packet for this identity.
     * Local transports use the result to avoid treating malformed/spoofed frames as accepted sends.
     * Duplicate but authentic packets return true because they are safely re-acknowledged by [onPacket].
     */
    suspend fun acceptWire(wire: ByteArray): Boolean {
        val opened = protector.open(wire) ?: return false
        val env = EnvelopeCodec.decode(opened.plaintext) ?: return false
        if (env.from != opened.senderId) return false
        val me = ownId() ?: return false
        if (env.to != me || env.from == me) return false
        if (peers.status(env.from) != PeerStatus.Known) return false
        onPacket(opened.plaintext)
        return true
    }

    /** Entry point for every decrypted packet a transport receives. Malformed or unwanted packets are dropped silently. */
    suspend fun onPacket(bytes: ByteArray) {
        val env = EnvelopeCodec.decode(bytes) ?: return
        val me = ownId() ?: return
        if (env.to != me || env.from == me) return
        if (peers.status(env.from) != PeerStatus.Known) return
        when (env) {
            is Envelope.Chat -> {
                // Save first, acknowledge second. A repeat of a known ID is only re-acknowledged.
                val fresh = store.recordIncoming(
                    StoredMessage(env.messageId, env.from, false, env.body, clock(),
                        MessageState.DELIVERED, 0, Long.MAX_VALUE, false)
                )
                if (fresh) runCatching { incoming?.onMessage(env.from, false, env.from, env.body) }
                transmit(env.from, EnvelopeCodec.encode(Envelope.Ack(env.messageId, me, env.from, AckKind.DELIVERED)))
            }
            is Envelope.Attachment -> {
                if (!AttachmentLimits.validSizes(env.size, env.blobSize)) return
                val url = BlossomUrl.normalize(env.serverUrl, allowPrivate = false) ?: return
                val name = FileNames.sanitize(env.name)
                val fresh = store.recordIncomingAttachment(
                    StoredMessage(env.messageId, env.from, false, name, clock(),
                        MessageState.DELIVERED, 0, Long.MAX_VALUE, false),
                    StoredAttachment(env.messageId, name, FileNames.sanitizeMime(env.mime), env.size, env.blobSize,
                        env.plainSha256, env.blobSha256, env.key, url, null, AttachmentPhase.REMOTE),
                )
                if (fresh) runCatching { incoming?.onMessage(env.from, false, env.from, null) }
                transmit(env.from, EnvelopeCodec.encode(Envelope.Ack(env.messageId, me, env.from, AckKind.DELIVERED)))
            }
            is Envelope.GroupChat, is Envelope.GroupSync, is Envelope.GroupLeave -> groups?.onEnvelope(env)
            is Envelope.Ack -> lock.withLock {
                if (groups?.onAck(env) == true) return
                val m = store.get(env.messageId) ?: return
                if (!m.outgoing || m.chatId != env.from) return
                val target = if (env.kind == AckKind.READ) MessageState.READ else MessageState.DELIVERED
                if (MessageState.canAdvance(m.state, target)) store.update(m.copy(state = target, nextAttemptAt = Long.MAX_VALUE))
            }
        }
    }

    suspend fun markChatRead(chatId: String, sendReceipts: Boolean) {
        val unread = store.unreadIncoming(chatId)
        if (unread.isEmpty()) return
        store.markReadLocal(unread.map { it.messageId })
        if (!sendReceipts) return
        val me = ownId() ?: return
        for (m in unread) {
            transmit(chatId, EnvelopeCodec.encode(Envelope.Ack(m.messageId, me, chatId, AckKind.READ)))
        }
    }

    suspend fun cancel(messageId: String) {
        lock.withLock {
            val m = store.get(messageId) ?: return
            if (m.outgoing && m.state == MessageState.QUEUED) {
                store.update(m.copy(state = MessageState.CANCELLED, nextAttemptAt = Long.MAX_VALUE))
                abandonAttachment(messageId)
            }
        }
    }
}
