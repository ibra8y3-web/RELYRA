package org.relyra.app.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CommunityDao {
    @Query("SELECT * FROM communities ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<CommunityEntity>>

    @Query("SELECT * FROM communities WHERE communityId = :id")
    suspend fun get(id: String): CommunityEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: CommunityEntity)

    @Query("DELETE FROM communities WHERE communityId = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM communities")
    suspend fun count(): Int
}
