package org.relyra.app.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import org.relyra.app.core.messaging.ControlKind
import org.relyra.app.core.messaging.ControlStore
import org.relyra.app.core.messaging.ReactionStore
import org.relyra.app.core.messaging.ScheduledState
import org.relyra.app.core.messaging.ScheduledStore
import org.relyra.app.core.messaging.StoredControl
import org.relyra.app.core.messaging.StoredReaction
import org.relyra.app.core.messaging.StoredScheduled

@Entity(tableName = "reactions", primaryKeys = ["messageId", "reactorId"], indices = [Index("messageId")])
data class ReactionEntity(
    val messageId: String,
    val reactorId: String,
    val emoji: String,
    val updatedAt: Long,
) {
    fun toModel() = StoredReaction(messageId, reactorId, emoji, updatedAt)
}

fun StoredReaction.toEntity() = ReactionEntity(messageId, reactorId, emoji, updatedAt)

@Entity(
    tableName = "pending_controls",
    indices = [Index(value = ["done", "nextAttemptAt"]), Index("targetId")],
)
data class ControlEntity(
    @PrimaryKey val controlId: String,
    val chatId: String,
    val targetId: String,
    val kind: String,
    val plaintext: ByteArray,
    val createdAt: Long,
    val attempts: Int,
    val nextAttemptAt: Long,
    val done: Boolean,
) {
    fun toModel() = StoredControl(controlId, chatId, targetId, ControlKind.valueOf(kind), plaintext, createdAt, attempts, nextAttemptAt, done)
}

fun StoredControl.toEntity() = ControlEntity(controlId, chatId, targetId, kind.name, plaintext, createdAt, attempts, nextAttemptAt, done)

@Entity(
    tableName = "scheduled_messages",
    indices = [Index(value = ["state", "sendAt"]), Index("chatId")],
)
data class ScheduledEntity(
    @PrimaryKey val id: String,
    val chatId: String,
    val body: String,
    val sendAt: Long,
    val zoneId: String,
    val state: String,
    val createdAt: Long,
    val messageId: String,
    val failReason: String?,
) {
    fun toModel() = StoredScheduled(id, chatId, body, sendAt, zoneId, ScheduledState.valueOf(state), createdAt, messageId, failReason)
}

fun StoredScheduled.toEntity() = ScheduledEntity(id, chatId, body, sendAt, zoneId, state.name, createdAt, messageId, failReason)

@Dao
interface ReactionDao {
    @Query("SELECT * FROM reactions WHERE messageId = :messageId AND reactorId = :reactorId")
    suspend fun get(messageId: String, reactorId: String): ReactionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(e: ReactionEntity)

    @Query("SELECT r.* FROM reactions r INNER JOIN messages m ON m.messageId = r.messageId WHERE m.chatId = :chatId AND r.emoji != ''")
    fun observeChat(chatId: String): Flow<List<ReactionEntity>>

    @Query("DELETE FROM reactions WHERE messageId NOT IN (SELECT messageId FROM messages)")
    suspend fun purgeOrphans()
}

@Dao
interface ControlDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(e: ControlEntity)

    @Query("SELECT * FROM pending_controls WHERE controlId = :id")
    suspend fun get(id: String): ControlEntity?

    @Query("SELECT * FROM pending_controls WHERE done = 0 AND nextAttemptAt <= :now ORDER BY createdAt ASC LIMIT :limit")
    suspend fun due(now: Long, limit: Int): List<ControlEntity>

    @Query("UPDATE pending_controls SET done = 1, nextAttemptAt = 9223372036854775807 WHERE targetId = :targetId AND kind = :kind AND done = 0 AND controlId != :exceptId")
    suspend fun supersede(targetId: String, kind: String, exceptId: String)

    @Query("DELETE FROM pending_controls WHERE done = 1 AND createdAt < :olderThan")
    suspend fun purgeDone(olderThan: Long)
}

@Dao
interface ScheduledDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(e: ScheduledEntity)

    @Update
    suspend fun update(e: ScheduledEntity)

    @Query("SELECT * FROM scheduled_messages WHERE id = :id")
    suspend fun get(id: String): ScheduledEntity?

    @Query("SELECT * FROM scheduled_messages WHERE state = 'PENDING' AND sendAt <= :now ORDER BY sendAt ASC LIMIT :limit")
    suspend fun due(now: Long, limit: Int): List<ScheduledEntity>

    @Query("SELECT * FROM scheduled_messages WHERE state = 'PENDING' ORDER BY sendAt ASC")
    suspend fun pending(): List<ScheduledEntity>

    @Query("SELECT * FROM scheduled_messages WHERE chatId = :chatId AND state = 'PENDING' ORDER BY sendAt ASC")
    fun observePending(chatId: String): Flow<List<ScheduledEntity>>

    @Query("SELECT COUNT(*) FROM scheduled_messages WHERE state = 'PENDING'")
    fun observePendingCount(): Flow<Int>
}

class RoomReactionStore(private val db: AppDatabase) : ReactionStore {
    override suspend fun current(messageId: String, reactorId: String) = db.reactionDao().get(messageId, reactorId)?.toModel()

    override suspend fun applyIfNewer(r: StoredReaction): Boolean = db.withTransaction {
        val existing = db.reactionDao().get(r.messageId, r.reactorId)
        if (existing != null && existing.updatedAt >= r.updatedAt) {
            false
        } else {
            db.reactionDao().put(r.toEntity())
            true
        }
    }

    fun observeChat(chatId: String) = db.reactionDao().observeChat(chatId)
}

class RoomControlStore(private val db: AppDatabase) : ControlStore {
    private val dao get() = db.controlDao()
    override suspend fun insert(c: StoredControl) = dao.put(c.toEntity())
    override suspend fun get(id: String) = dao.get(id)?.toModel()
    override suspend fun update(c: StoredControl) = dao.put(c.toEntity())
    override suspend fun due(now: Long, limit: Int) = dao.due(now, limit).map { it.toModel() }
    override suspend fun supersede(targetId: String, kind: ControlKind, exceptId: String) = dao.supersede(targetId, kind.name, exceptId)
    override suspend fun purgeDone(olderThan: Long) = dao.purgeDone(olderThan)
}

class RoomScheduledStore(private val db: AppDatabase) : ScheduledStore {
    private val dao get() = db.scheduledDao()
    override suspend fun insert(s: StoredScheduled) = dao.insert(s.toEntity())
    override suspend fun get(id: String) = dao.get(id)?.toModel()
    override suspend fun update(s: StoredScheduled) = dao.update(s.toEntity())
    override suspend fun due(now: Long, limit: Int) = dao.due(now, limit).map { it.toModel() }
    override suspend fun pending() = dao.pending().map { it.toModel() }
    fun observePending(chatId: String) = dao.observePending(chatId)
    fun observePendingCount() = dao.observePendingCount()
}
