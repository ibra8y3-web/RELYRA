package org.relyra.app.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** One file kept in the vault. The file itself is encrypted on disk; its random key lives only in this encrypted database. */
@Entity(tableName = "vault_items")
data class VaultItemEntity(
    @PrimaryKey val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val plainSha256: ByteArray,
    val fileKey: ByteArray,
    val addedAt: Long,
)

@Dao
interface VaultDao {
    @Query("SELECT * FROM vault_items ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<VaultItemEntity>>

    @Query("SELECT * FROM vault_items WHERE id = :id")
    suspend fun get(id: String): VaultItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(item: VaultItemEntity)

    @Query("UPDATE vault_items SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Query("DELETE FROM vault_items WHERE id = :id")
    suspend fun delete(id: String)
}
