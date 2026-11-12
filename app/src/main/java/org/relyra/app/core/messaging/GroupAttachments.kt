package org.relyra.app.core.messaging

enum class GroupAttachmentPhase {
    UPLOADED,
    REMOTE,
    DOWNLOADING,
    READY,
    FAILED,
}

/** Attachment metadata retained in the SQLCipher database; file bytes stay encrypted in Blossom. */
data class GroupAttachmentRecord(
    val messageId: String,
    val groupId: String,
    val senderId: String,
    val name: String,
    val mime: String,
    val size: Long,
    val blobSize: Long,
    val plainSha256: ByteArray,
    val blobSha256: ByteArray,
    val fileKey: ByteArray,
    val serverUrl: String,
    val localPath: String?,
    val phase: GroupAttachmentPhase,
    val createdAt: Long,
    val outgoing: Boolean,
)

data class GroupAttachmentData(
    val name: String,
    val mime: String,
    val size: Long,
    val blobSize: Long,
    val plainSha256: ByteArray,
    val blobSha256: ByteArray,
    val fileKey: ByteArray,
    val serverUrl: String,
)
