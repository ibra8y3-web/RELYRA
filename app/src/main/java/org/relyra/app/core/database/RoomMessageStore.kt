package org.relyra.app.core.database

import androidx.room.withTransaction
import org.relyra.app.core.messaging.MessageStore
import org.relyra.app.core.messaging.StoredAttachment
import org.relyra.app.core.messaging.StoredMessage

class RoomMessageStore(private val db: AppDatabase) : MessageStore {
    private val dao get() = db.messageDao()

    override suspend fun insertOutgoing(m: StoredMessage) = dao.insert(m.toEntity())
    override suspend fun dueOutgoing(now: Long, limit: Int) = dao.due(now, limit).map { it.toModel() }
    override suspend fun get(id: String) = dao.get(id)?.toModel()
    override suspend fun update(m: StoredMessage) = dao.update(m.toEntity())

    override suspend fun recordIncoming(m: StoredMessage): Boolean = db.withTransaction {
        if (dao.insertProcessed(ProcessedIdEntity(m.messageId, m.createdAt)) == -1L) {
            false
        } else {
            dao.insert(m.toEntity())
            true
        }
    }

    override suspend fun unreadIncoming(chatId: String) = dao.unread(chatId).map { it.toModel() }
    override suspend fun markReadLocal(ids: List<String>) = dao.markRead(ids)

    override suspend fun insertOutgoingAttachment(m: StoredMessage, a: StoredAttachment) {
        db.withTransaction {
            dao.insert(m.toEntity())
            db.attachmentDao().insert(a.toEntity())
        }
    }

    override suspend fun attachment(messageId: String): StoredAttachment? = db.attachmentDao().get(messageId)?.toModel()

    override suspend fun updateAttachment(a: StoredAttachment) = db.attachmentDao().update(a.toEntity())

    override suspend fun recordIncomingAttachment(m: StoredMessage, a: StoredAttachment): Boolean = db.withTransaction {
        if (dao.insertProcessed(ProcessedIdEntity(m.messageId, m.createdAt)) == -1L) {
            false
        } else {
            dao.insert(m.toEntity())
            db.attachmentDao().insert(a.toEntity())
            true
        }
    }

    suspend fun resetStaleDownloads() = db.attachmentDao().resetStaleDownloads()
    fun observeAttachments(chatId: String) = db.attachmentDao().observeChat(chatId)

    suspend fun deleteMessage(id: String): String? {
        val path = db.attachmentDao().get(id)?.localPath
        db.withTransaction {
            db.attachmentDao().deleteOne(id)
            dao.deleteOne(id)
        }
        return path
    }

    fun observeChat(chatId: String) = dao.observeChat(chatId)
    fun observeSummaries() = dao.observeSummaries()
    fun observePending() = dao.observePending()
}
