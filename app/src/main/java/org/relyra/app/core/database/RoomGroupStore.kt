package org.relyra.app.core.database

import androidx.room.withTransaction
import org.relyra.app.core.messaging.GroupMemberRef
import org.relyra.app.core.messaging.GroupMessage
import org.relyra.app.core.messaging.GroupAttachmentRecord
import org.relyra.app.core.messaging.GroupRecord
import org.relyra.app.core.messaging.GroupState
import org.relyra.app.core.messaging.GroupStore
import org.relyra.app.core.messaging.OutboxRow

class RoomGroupStore(private val db: AppDatabase) : GroupStore {
    private val dao get() = db.groupDao()

    override suspend fun group(id: String): GroupRecord? = dao.get(id)?.toModel()
    override suspend fun members(groupId: String): List<GroupMemberRef> = dao.members(groupId).map { it.toModel() }

    override suspend fun putGroup(g: GroupRecord, members: List<GroupMemberRef>) {
        db.withTransaction {
            dao.put(g.toEntity())
            dao.clearMembers(g.groupId)
            dao.putMembers(members.map { GroupMemberEntity(g.groupId, it.id, it.role.name, it.mlsJoined) })
        }
    }

    override suspend fun setState(groupId: String, state: GroupState) = dao.setState(groupId, state.name)

    override suspend fun insertOutgoingMessage(m: GroupMessage, copies: List<OutboxRow>) {
        db.withTransaction {
            dao.insertMessage(m.toEntity())
            dao.insertOutbox(copies.map { it.toEntity() })
        }
    }

    override suspend fun insertOutgoingGroupAttachment(
        m: GroupMessage,
        attachment: GroupAttachmentRecord,
        copies: List<OutboxRow>,
    ) {
        db.withTransaction {
            dao.insertMessage(m.toEntity())
            dao.insertGroupAttachment(attachment.toEntity())
            dao.insertOutbox(copies.map { it.toEntity() })
        }
    }

    override suspend fun recordIncomingGroupAttachment(
        m: GroupMessage,
        attachment: GroupAttachmentRecord,
    ): Boolean = db.withTransaction {
        if (db.messageDao().insertProcessed(ProcessedIdEntity(m.messageId, m.createdAt)) == -1L) {
            false
        } else {
            dao.insertMessage(m.toEntity())
            dao.insertGroupAttachment(attachment.toEntity())
            true
        }
    }

    override suspend fun groupAttachment(messageId: String): GroupAttachmentRecord? =
        dao.groupAttachment(messageId)?.toModel()

    override suspend fun updateGroupAttachment(attachment: GroupAttachmentRecord) =
        dao.updateGroupAttachment(attachment.toEntity())

    override suspend fun recordIncomingMessage(m: GroupMessage): Boolean = db.withTransaction {
        if (db.messageDao().insertProcessed(ProcessedIdEntity(m.messageId, m.createdAt)) == -1L) {
            false
        } else {
            dao.insertMessage(m.toEntity())
            true
        }
    }

    override suspend fun enqueue(rows: List<OutboxRow>) = dao.insertOutbox(rows.map { it.toEntity() })
    override suspend fun dueOutbox(now: Long, limit: Int): List<OutboxRow> = dao.due(now, limit).map { it.toModel() }
    override suspend fun updateOutbox(row: OutboxRow) = dao.updateOutbox(row.toEntity())
    override suspend fun outbox(id: String): OutboxRow? = dao.outbox(id)?.toModel()
    override suspend fun markAcked(id: String, memberId: String): Boolean = dao.ack(id, memberId) > 0

    override suspend fun markMessagesRead(groupId: String): List<GroupMessage> = db.withTransaction {
        val unread = dao.unreadMessages(groupId).map { it.toModel() }
        if (unread.isNotEmpty()) dao.markRead(groupId)
        unread
    }

    override suspend fun markReadReceipt(copyId: String, groupId: String, memberId: String, readAt: Long): Boolean =
        dao.markReadReceipt(copyId, groupId, memberId, readAt) > 0

    override suspend fun recordControlMessage(id: String, receivedAt: Long): Boolean =
        db.messageDao().insertProcessed(ProcessedIdEntity(id, receivedAt)) != -1L

    override suspend fun hasControlMessage(id: String): Boolean = dao.hasProcessedId(id)

    override suspend fun deleteGroup(groupId: String) {
        db.withTransaction {
            dao.deleteMessages(groupId)
            dao.deleteOutbox(groupId)
            dao.deleteGroupAttachments(groupId)
            dao.clearMembers(groupId)
            dao.deleteGroup(groupId)
        }
    }

    fun observeGroups() = dao.observeAll()
    fun observeGroup(id: String) = dao.observe(id)
    fun observeMembers(id: String) = dao.observeMembers(id)
    fun observeMessages(id: String) = dao.observeMessages(id)
    fun observeSummaries() = dao.observeSummaries()
    fun observeDelivery(id: String) = dao.observeDelivery(id)
    fun observeGroupAttachments(id: String) = dao.observeGroupAttachments(id)
    suspend fun markRead(id: String) = dao.markRead(id)
}
