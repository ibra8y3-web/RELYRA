package org.relyra.app.core.messaging

sealed interface SendOutcome {
    data object Accepted : SendOutcome      // handed to the network / peer
    data object Relayed : SendOutcome       // accepted by a relay on the way
    data object Unavailable : SendOutcome   // no route right now; retry later
    class Rejected(val reason: String) : SendOutcome // permanent
}

/** A network connector (Nostr, libp2p, mesh...). Added in later phases. */
interface Transport {
    val name: String
    /** False when no route can exist right now, so nothing needs to be encrypted (and no ratchet key spent) for it. */
    fun isReady(): Boolean = true
    suspend fun send(toPeer: String, payload: ByteArray): SendOutcome
}

enum class PeerStatus { Known, NoKeys, Blocked, Unknown }

interface PeerDirectory {
    suspend fun status(publicId: String): PeerStatus
}

enum class AttachmentPhase {
    AWAITING_UPLOAD,   // outgoing: encrypted blob is on disk, not yet on a file server
    UPLOADED,          // outgoing: the file server has it; the descriptor can be sent
    REMOTE,            // incoming: on the sender's file server, not downloaded (downloads are always manual)
    DOWNLOADING,
    READY,             // incoming: downloaded, hash-checked and decrypted on this device
    FAILED,
}

class StoredAttachment(
    val messageId: String,
    val name: String,
    val mime: String,
    val size: Long,
    val blobSize: Long,
    val plainSha256: ByteArray,
    val blobSha256: ByteArray,
    val fileKey: ByteArray,
    val serverUrl: String,       // "" until an outgoing file has been uploaded
    val localPath: String?,      // outgoing: encrypted blob awaiting upload; incoming: decrypted file once READY
    val phase: AttachmentPhase,
) {
    fun copy(
        serverUrl: String = this.serverUrl,
        localPath: String? = this.localPath,
        phase: AttachmentPhase = this.phase,
    ) = StoredAttachment(messageId, name, mime, size, blobSize, plainSha256, blobSha256, fileKey, serverUrl, localPath, phase)
}

sealed interface UploadOutcome {
    class Done(val serverUrl: String) : UploadOutcome
    data object Unavailable : UploadOutcome                 // try again later
    class Rejected(val reason: String) : UploadOutcome      // permanent
}

/** Puts an already-encrypted blob on a file server (Blossom). */
interface BlobUploader {
    suspend fun upload(blobPath: String, sha256: ByteArray): UploadOutcome
    /** Deletes the local encrypted blob once it is no longer needed. */
    fun discard(blobPath: String)
}

interface MessageStore {
    suspend fun insertOutgoing(m: StoredMessage)
    suspend fun dueOutgoing(now: Long, limit: Int): List<StoredMessage>
    suspend fun get(id: String): StoredMessage?
    suspend fun update(m: StoredMessage)
    /** Atomically remembers the ID and stores the message. Returns false if the ID was already processed. */
    suspend fun recordIncoming(m: StoredMessage): Boolean
    suspend fun unreadIncoming(chatId: String): List<StoredMessage>
    suspend fun markReadLocal(ids: List<String>)

    suspend fun insertOutgoingAttachment(m: StoredMessage, a: StoredAttachment)
    suspend fun attachment(messageId: String): StoredAttachment?
    suspend fun updateAttachment(a: StoredAttachment)
    /** Like [recordIncoming], for a file message: ID, message and attachment are stored in one transaction. */
    suspend fun recordIncomingAttachment(m: StoredMessage, a: StoredAttachment): Boolean
}

class Opened(val senderId: String, val plaintext: ByteArray)

/** End-to-end encryption boundary. Everything a Transport sees has already passed through [protect]. */
interface PayloadProtector {
    /** Null when we cannot encrypt for this peer (no key yet). */
    suspend fun protect(peerId: String, plaintext: ByteArray): ByteArray?
    /** Null when the packet is not for us, not from a known contact, or fails authentication. */
    suspend fun open(wire: ByteArray): Opened?
}

/**
 * Receivers that advance cryptographic state implement this callback form so the ratchet state is committed
 * only after the authenticated plaintext has been durably accepted by the message layer.
 */
interface CommitAwarePayloadProtector : PayloadProtector {
    suspend fun accept(wire: ByteArray, consume: suspend (Opened) -> Boolean): Boolean
}

/** Told about every NEW incoming message (a replayed copy is not new). Used for notifications; must not block. */
interface IncomingListener {
    /** [conversation] is the sender's public ID for a one-to-one chat, or the group ID. [body] is null for files. */
    fun onMessage(conversation: String, group: Boolean, sender: String, body: String?)
    fun onInvited(groupId: String, name: String, invitedBy: String)
}
