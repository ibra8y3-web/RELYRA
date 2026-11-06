package org.relyra.app.core.database

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
) {
    fun toModel() = GroupRecord(groupId, name, GroupKind.valueOf(kind), ownerId, epoch, GroupState.valueOf(state), createdAt, invitedBy)
}

fun GroupRecord.toEntity() = GroupEntity(groupId, name, kind.name, owner, epoch, state.name, createdAt, invitedBy)

@Entity(tableName = "group_members", primaryKeys = ["groupId", "memberId"])
data class GroupMemberEntity(
    val groupId: String,
    val memberId: String,
    val role: String,
) {
    fun toModel() = GroupMemberRef(memberId, GroupRole.valueOf(role))
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
) {
    fun toModel() = OutboxRow(id, groupId, memberId, baseMessageId, payload, createdAt, attempts, nextAttemptAt, acked, failed)
}

fun OutboxRow.toEntity() = GroupOutboxEntity(id, groupId, memberId, baseMessageId, payload, createdAt, attempts, nextAttemptAt, acked, failed)

/** How many of a group message's copies have been acknowledged. */
data class GroupDeliveryCount(val messageId: String, val total: Int, val delivered: Int)

data class GroupSummary(val groupId: String, val lastAt: Long, val unread: Int)
