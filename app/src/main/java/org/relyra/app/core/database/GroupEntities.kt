package org.relyra.app.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import org.relyra.app.core.messaging.GroupKind
import org.relyra.app.core.messaging.GroupMemberRef
import org.relyra.app.core.messaging.GroupMessage
import org.relyra.app.core.messaging.GroupRecord
import org.relyra.app.core.messaging.GroupRole
import org.relyra.app.core.messaging.GroupState
import org.relyra.app.core.messaging.OutboxRow

// The table is called chat_groups because GROUPS is a keyword in SQLite.
@Entity(tableName = "chat_groups")
data class GroupEntity(
    @PrimaryKey val groupId: String,
    val name: String,
    val kind: String,
    val ownerId: String,
    val epoch: Long,
    val state: String,
    val createdAt: Long,
    val invitedBy: String,
    @ColumnInfo(defaultValue = "0") val mlsEnabled: Boolean = false,
    @ColumnInfo(defaultValue = "0") val mlsEpoch: Long = 0,
) {
    fun toModel() = GroupRecord(groupId, name, GroupKind.valueOf(kind), ownerId, epoch, GroupState.valueOf(state), createdAt, invitedBy, mlsEnabled, mlsEpoch)
}

fun GroupRecord.toEntity() = GroupEntity(groupId, name, kind.name, owner, epoch, state.name, createdAt, invitedBy, mlsEnabled, mlsEpoch)

@Entity(tableName = "group_members", primaryKeys = ["groupId", "memberId"])
data class GroupMemberEntity(
    val groupId: String,
    val memberId: String,
    val role: String,
    @ColumnInfo(defaultValue = "0") val mlsJoined: Boolean = false,
) {
    fun toModel() = GroupMemberRef(memberId, GroupRole.valueOf(role), mlsJoined)
}

@Entity(tableName = "group_messages")
data class GroupMessageEntity(
    @PrimaryKey val messageId: String,
    val groupId: String,
    val senderId: String,
    val outgoing: Boolean,
    val body: String,
    val createdAt: Long,
    val readLocally: Boolean,
) {
    fun toModel() = GroupMessage(messageId, groupId, senderId, outgoing, body, createdAt, readLocally)
}

fun GroupMessage.toEntity() = GroupMessageEntity(messageId, groupId, senderId, outgoing, body, createdAt, readLocally)

@Entity(tableName = "group_outbox")
data class GroupOutboxEntity(
    @PrimaryKey val id: String,
    val groupId: String,
    val memberId: String,
    val baseMessageId: String?,
    val payload: ByteArray,
    val createdAt: Long,
    val attempts: Int,
    val nextAttemptAt: Long,
    val acked: Boolean,
    val failed: Boolean,
    val readAt: Long? = null,
) {
    fun toModel() = OutboxRow(id, groupId, memberId, baseMessageId, payload, createdAt, attempts, nextAttemptAt, acked, failed, readAt)
}

fun OutboxRow.toEntity() = GroupOutboxEntity(id, groupId, memberId, baseMessageId, payload, createdAt, attempts, nextAttemptAt, acked, failed, readAt)

@Entity(tableName = "group_attachments")
data class GroupAttachmentEntity(
    @androidx.room.PrimaryKey val messageId: String,
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
    val phase: String,
    val createdAt: Long,
    val outgoing: Boolean,
) {
    fun toModel() = org.relyra.app.core.messaging.GroupAttachmentRecord(
        messageId, groupId, senderId, name, mime, size, blobSize, plainSha256, blobSha256,
        fileKey, serverUrl, localPath,
        org.relyra.app.core.messaging.GroupAttachmentPhase.valueOf(phase), createdAt, outgoing,
    )
}

fun org.relyra.app.core.messaging.GroupAttachmentRecord.toEntity() = GroupAttachmentEntity(
    messageId, groupId, senderId, name, mime, size, blobSize, plainSha256, blobSha256,
    fileKey, serverUrl, localPath, phase.name, createdAt, outgoing,
)

/** How many of a group message's copies have been acknowledged. */
data class GroupDeliveryCount(val messageId: String, val total: Int, val delivered: Int, val readCount: Int)

data class GroupSummary(val groupId: String, val lastAt: Long, val unread: Int)
