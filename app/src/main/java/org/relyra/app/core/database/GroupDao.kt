package org.relyra.app.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {
    @Query("SELECT * FROM chat_groups WHERE groupId = :id")
    suspend fun get(id: String): GroupEntity?

    @Query("SELECT * FROM chat_groups WHERE groupId = :id")
    fun observe(id: String): Flow<GroupEntity?>

    @Query("SELECT * FROM chat_groups ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<GroupEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(e: GroupEntity)

    @Query("UPDATE chat_groups SET state = :state WHERE groupId = :id")
    suspend fun setState(id: String, state: String)

    @Query("SELECT * FROM group_members WHERE groupId = :id")
    suspend fun members(id: String): List<GroupMemberEntity>

    @Query("SELECT * FROM group_members WHERE groupId = :id")
    fun observeMembers(id: String): Flow<List<GroupMemberEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMembers(list: List<GroupMemberEntity>)

    @Query("DELETE FROM group_members WHERE groupId = :id")
    suspend fun clearMembers(id: String)

    @Query("SELECT * FROM group_messages WHERE groupId = :id ORDER BY createdAt ASC")
    fun observeMessages(id: String): Flow<List<GroupMessageEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMessage(e: GroupMessageEntity)

    @Query("SELECT groupId, MAX(createdAt) AS lastAt, SUM(CASE WHEN outgoing = 0 AND readLocally = 0 THEN 1 ELSE 0 END) AS unread FROM group_messages GROUP BY groupId")
    fun observeSummaries(): Flow<List<GroupSummary>>

    @Query("UPDATE group_messages SET readLocally = 1 WHERE groupId = :id AND outgoing = 0")
    suspend fun markRead(id: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOutbox(rows: List<GroupOutboxEntity>)

    @Query("SELECT * FROM group_outbox WHERE acked = 0 AND failed = 0 AND nextAttemptAt <= :now ORDER BY createdAt ASC LIMIT :limit")
    suspend fun due(now: Long, limit: Int): List<GroupOutboxEntity>

    @Update
    suspend fun updateOutbox(e: GroupOutboxEntity)

    @Query("UPDATE group_outbox SET acked = 1 WHERE id = :id AND memberId = :memberId")
    suspend fun ack(id: String, memberId: String): Int

    @Query("SELECT baseMessageId AS messageId, COUNT(*) AS total, SUM(acked) AS delivered FROM group_outbox WHERE groupId = :id AND baseMessageId IS NOT NULL GROUP BY baseMessageId")
    fun observeDelivery(id: String): Flow<List<GroupDeliveryCount>>

    @Query("DELETE FROM group_messages WHERE groupId = :id")
    suspend fun deleteMessages(id: String)

    @Query("DELETE FROM group_outbox WHERE groupId = :id")
    suspend fun deleteOutbox(id: String)

    @Query("DELETE FROM chat_groups WHERE groupId = :id")
    suspend fun deleteGroup(id: String)

    @Query("SELECT COUNT(*) FROM group_outbox WHERE acked = 0 AND failed = 0")
    fun observePending(): Flow<Int>
}
