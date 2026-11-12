package org.relyra.app.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import org.relyra.app.core.messaging.AttachmentPhase
import org.relyra.app.core.messaging.MessageState
import org.relyra.app.core.messaging.StoredAttachment
import org.relyra.app.core.messaging.StoredMessage

@Entity(tableName = "messages", indices = [Index(value = ["chatId", "createdAt"])])
data class MessageEntity(
    @PrimaryKey val messageId: String,
    val chatId: String,
    val outgoing: Boolean,
    val body: String,
    val createdAt: Long,
    val state: String,
    val attempts: Int,
    val nextAttemptAt: Long,
    val readLocally: Boolean,
    val wirePayload: ByteArray? = null,
) {
    fun toModel() = StoredMessage(messageId, chatId, outgoing, body, createdAt,
        MessageState.valueOf(state), attempts, nextAttemptAt, readLocally, wirePayload)
}

fun StoredMessage.toEntity() = MessageEntity(messageId, chatId, outgoing, body, createdAt,
    state.name, attempts, nextAttemptAt, readLocally, wirePayload)

/** IDs of every message ever received. Kept even if the message is deleted, so a replay is never re-saved. */
@Entity(tableName = "processed_ids")
data class ProcessedIdEntity(
    @PrimaryKey val messageId: String,
    val receivedAt: Long,
)

data class ChatSummary(val chatId: String, val lastAt: Long, val unread: Int, val lastBody: String)

/** Local-only choices about a conversation (a contact's public ID or a group ID). Never sent to anyone. */
@Entity(tableName = "chat_prefs")
data class ChatPrefEntity(
    @PrimaryKey val chatId: String,
    val pinned: Boolean,
    val archived: Boolean,
    val muted: Boolean,
    val updatedAt: Long = 0,
)

/** File descriptor of a message. The file key lives only in this encrypted database. */
@Entity(tableName = "attachments")
data class AttachmentEntity(
    @PrimaryKey val messageId: String,
    val name: String,
    val mime: String,
    val size: Long,
    val blobSize: Long,
    val plainSha256: ByteArray,
    val blobSha256: ByteArray,
    val fileKey: ByteArray,
    val serverUrl: String,
    val localPath: String?,
    val phase: String,
) {
    fun toModel() = StoredAttachment(messageId, name, mime, size, blobSize, plainSha256, blobSha256, fileKey,
        serverUrl, localPath, AttachmentPhase.valueOf(phase))
}

fun StoredAttachment.toEntity() = AttachmentEntity(messageId, name, mime, size, blobSize, plainSha256, blobSha256,
    fileKey, serverUrl, localPath, phase.name)

/** Double Ratchet session with one contact. Contains secrets; lives only in the encrypted database. */
@Entity(tableName = "ratchet_sessions")
data class RatchetEntity(
    @PrimaryKey val peerId: String,
    val state: ByteArray,
)

/** Ciphertext cache keyed by outgoing message ID, committed atomically with its ratchet advancement. */
@Entity(tableName = "encrypted_packets")
data class EncryptedPacketEntity(
    @PrimaryKey val messageId: String,
    val peerId: String,
    val packet: ByteArray,
)
