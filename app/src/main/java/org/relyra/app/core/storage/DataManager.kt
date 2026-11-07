package org.relyra.app.core.storage

import android.os.StatFs
import androidx.room.withTransaction
import org.relyra.app.core.database.AppDatabase
import org.relyra.app.core.database.ChatPrefEntity
import java.io.File

class StorageUsage(
    val databaseBytes: Long,
    val filesBytes: Long,
    val cacheBytes: Long,
    val freeBytes: Long,
    val totalBytes: Long,
)

class DataCounts(val messages: Int, val groupMessages: Int, val attachments: Int)

/** Local data housekeeping: per-chat choices, deleting messages, clearing temporary files, measuring storage. */
class DataManager(
    private val db: AppDatabase,
    private val cleanFiles: (messageIds: List<String>, paths: List<String>) -> Unit,
    private val filesDir: File,
    private val cacheDir: File,
    private val databaseFile: File,
) {
    // ---- per-chat choices (pin, archive, mute) -------------------------------------------------------------

    fun observePrefs() = db.chatPrefDao().observeAll()

    suspend fun setPinned(chatId: String, value: Boolean) = update(chatId) { it.copy(pinned = value) }
    suspend fun setArchived(chatId: String, value: Boolean) = update(chatId) { it.copy(archived = value) }
    suspend fun setMuted(chatId: String, value: Boolean) = update(chatId) { it.copy(muted = value) }

    private suspend fun update(chatId: String, change: (ChatPrefEntity) -> ChatPrefEntity) {
        val next = change(db.chatPrefDao().get(chatId) ?: ChatPrefEntity(chatId, pinned = false, archived = false, muted = false))
        if (!next.pinned && !next.archived && !next.muted) db.chatPrefDao().delete(chatId) else db.chatPrefDao().put(next)
    }

    suspend fun isMuted(chatId: String): Boolean = db.chatPrefDao().get(chatId)?.muted == true

    // ---- deleting messages ----------------------------------------------------------------------------------

    /** Removes one message from this device only. Its ID stays remembered, so a replayed copy is never shown again. */
    suspend fun deleteMessage(messageId: String) {
        val path = db.attachmentDao().get(messageId)?.localPath
        db.withTransaction {
            db.attachmentDao().deleteOne(messageId)
            db.messageDao().deleteOne(messageId)
        }
        cleanFiles(listOf(messageId), listOfNotNull(path))
    }

    suspend fun deleteGroupMessage(messageId: String) = db.groupDao().deleteMessage(messageId)

    /** Empties a one-to-one conversation on this device. The contact stays. */
    suspend fun clearChat(chatId: String) {
        val ids = db.attachmentDao().idsForChat(chatId)
        val paths = db.attachmentDao().pathsForChat(chatId)
        db.withTransaction {
            db.attachmentDao().deleteForChat(chatId)
            db.messageDao().deleteChat(chatId)
        }
        cleanFiles(ids, paths)
    }

    suspend fun clearGroupChat(groupId: String) = db.groupDao().deleteMessages(groupId)

    /** Deletes finished messages older than [days] days (and their downloaded files). Returns how many were removed. */
    suspend fun deleteOlderThan(days: Int, now: Long = System.currentTimeMillis()): Int {
        require(days >= 1)
        val cutoff = now - days * 86_400_000L
        val ids = db.messageDao().idsOlderThan(cutoff)
        var removed = 0
        for (chunk in ids.chunked(500)) {
            val paths = db.attachmentDao().pathsFor(chunk)
            db.withTransaction {
                db.attachmentDao().deleteMany(chunk)
                db.messageDao().deleteMany(chunk)
            }
            cleanFiles(chunk, paths)
            removed += chunk.size
        }
        removed += db.groupDao().deleteMessagesOlderThan(cutoff)
        return removed
    }

    suspend fun counts() = DataCounts(db.messageDao().countAll(), db.groupDao().countMessages(), db.attachmentDao().countAll())

    // ---- storage ----------------------------------------------------------------------------------------------

    /** Deletes only temporary files: the app cache. Messages, keys and downloaded files are untouched. */
    fun clearCache(): Long {
        var freed = 0L
        cacheDir.listFiles()?.forEach {
            freed += sizeOf(it)
            it.deleteRecursively()
        }
        return freed
    }

    fun usage(): StorageUsage {
        val dbBytes = listOf("", "-wal", "-shm", "-journal").sumOf { File(databaseFile.path + it).let { f -> if (f.isFile) f.length() else 0L } }
        val stat = StatFs(filesDir.path)
        return StorageUsage(
            databaseBytes = dbBytes,
            filesBytes = sizeOf(File(filesDir, "inbox")) + sizeOf(File(filesDir, "outbox")),
            cacheBytes = sizeOf(cacheDir),
            freeBytes = stat.availableBytes,
            totalBytes = stat.totalBytes,
        )
    }

    private fun sizeOf(f: File): Long =
        if (f.isFile) f.length() else f.listFiles()?.sumOf { sizeOf(it) } ?: 0L
}
