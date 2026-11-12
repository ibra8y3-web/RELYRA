package org.relyra.app.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY createdAt ASC")
    fun observeChat(chatId: String): Flow<List<MessageEntity>>

    @Query(
        "SELECT chatId, MAX(createdAt) AS lastAt, " +
            "SUM(CASE WHEN outgoing = 0 AND readLocally = 0 THEN 1 ELSE 0 END) AS unread, " +
            "(SELECT m2.body FROM messages m2 WHERE m2.chatId = messages.chatId ORDER BY m2.createdAt DESC LIMIT 1) AS lastBody " +
            "FROM messages GROUP BY chatId"
    )
    fun observeSummaries(): Flow<List<ChatSummary>>

    @Query("SELECT COUNT(*) FROM messages WHERE outgoing = 1 AND state = 'QUEUED'")
    fun observePending(): Flow<Int>

    @Query(
        "SELECT * FROM messages WHERE outgoing = 1 AND state IN ('QUEUED','SENT','RELAYED') " +
            "AND nextAttemptAt <= :now ORDER BY createdAt ASC LIMIT :limit"
    )
    suspend fun due(now: Long, limit: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE messageId = :id")
    suspend fun get(id: String): MessageEntity?

    @Query("SELECT * FROM messages ORDER BY createdAt ASC LIMIT 100000")
    suspend fun allForDeviceSync(): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(e: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(e: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertProcessed(e: ProcessedIdEntity): Long

    @Update
    suspend fun update(e: MessageEntity)

    @Query("SELECT * FROM messages WHERE chatId = :chatId AND outgoing = 0 AND readLocally = 0")
    suspend fun unread(chatId: String): List<MessageEntity>

    @Query("UPDATE messages SET readLocally = 1 WHERE messageId IN (:ids)")
    suspend fun markRead(ids: List<String>)

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun deleteChat(chatId: String)

    @Query("DELETE FROM messages WHERE messageId = :id")
    suspend fun deleteOne(id: String)

    /** Old messages that are finished. A message still waiting to be sent is never selected. */
    @Query("SELECT messageId FROM messages WHERE createdAt < :cutoff AND NOT (outgoing = 1 AND state = 'QUEUED')")
    suspend fun idsOlderThan(cutoff: Long): List<String>

    @Query("DELETE FROM messages WHERE messageId IN (:ids)")
    suspend fun deleteMany(ids: List<String>)

    @Query("SELECT COUNT(*) FROM messages WHERE outgoing = 1 AND state = :state")
    suspend fun countOutgoing(state: String): Int

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun countAll(): Int
}

@Dao
interface ChatPrefDao {
    @Query("SELECT * FROM chat_prefs")
    fun observeAll(): Flow<List<ChatPrefEntity>>

    @Query("SELECT * FROM chat_prefs LIMIT 100000")
    suspend fun allForDeviceSync(): List<ChatPrefEntity>

    @Query("SELECT * FROM chat_prefs WHERE chatId = :id")
    suspend fun get(id: String): ChatPrefEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(e: ChatPrefEntity)

    @Query("DELETE FROM chat_prefs WHERE chatId = :id")
    suspend fun delete(id: String)
}

@Dao
interface AttachmentDao {
    @Query("SELECT a.* FROM attachments a INNER JOIN messages m ON m.messageId = a.messageId WHERE m.chatId = :chatId")
    fun observeChat(chatId: String): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE messageId = :id")
    suspend fun get(id: String): AttachmentEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(e: AttachmentEntity)

    @Update
    suspend fun update(e: AttachmentEntity)

    @Query("SELECT messageId FROM attachments WHERE messageId IN (SELECT messageId FROM messages WHERE chatId = :chatId)")
    suspend fun idsForChat(chatId: String): List<String>

    @Query(
        "SELECT localPath FROM attachments WHERE localPath IS NOT NULL " +
            "AND messageId IN (SELECT messageId FROM messages WHERE chatId = :chatId)"
    )
    suspend fun pathsForChat(chatId: String): List<String>

    @Query("DELETE FROM attachments WHERE messageId IN (SELECT messageId FROM messages WHERE chatId = :chatId)")
    suspend fun deleteForChat(chatId: String)

    @Query("DELETE FROM attachments WHERE messageId = :id")
    suspend fun deleteOne(id: String)

    @Query("SELECT localPath FROM attachments WHERE localPath IS NOT NULL AND messageId IN (:ids)")
    suspend fun pathsFor(ids: List<String>): List<String>

    @Query("DELETE FROM attachments WHERE messageId IN (:ids)")
    suspend fun deleteMany(ids: List<String>)

    @Query("SELECT COUNT(*) FROM attachments")
    suspend fun countAll(): Int

    /** A download that was interrupted by the app being killed can be started again. */
    @Query("UPDATE attachments SET phase = 'REMOTE' WHERE phase = 'DOWNLOADING'")
    suspend fun resetStaleDownloads()
}

@Dao
interface RatchetDao {
    @Query("SELECT * FROM ratchet_sessions WHERE peerId = :peerId")
    suspend fun get(peerId: String): RatchetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(e: RatchetEntity)

    @Query("DELETE FROM ratchet_sessions WHERE peerId = :peerId")
    suspend fun delete(peerId: String)
}

@Dao
interface EncryptedPacketDao {
    @Query("SELECT * FROM encrypted_packets WHERE messageId = :messageId")
    suspend fun get(messageId: String): EncryptedPacketEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: EncryptedPacketEntity)

    @Query("DELETE FROM encrypted_packets WHERE messageId = :messageId")
    suspend fun delete(messageId: String)

    @Query("DELETE FROM encrypted_packets WHERE messageId IN (SELECT messageId FROM messages WHERE chatId = :chatId)")
    suspend fun deleteForChat(chatId: String)
}
