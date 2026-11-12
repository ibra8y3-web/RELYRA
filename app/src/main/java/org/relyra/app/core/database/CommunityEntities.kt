package org.relyra.app.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Local SQLCipher copy of a signed community manifest; private communities are not in public discovery. */
@Entity(tableName = "communities", indices = [Index(value = ["updatedAt"])])
data class CommunityEntity(
    @PrimaryKey val communityId: String,
    val manifestJson: String,
    val localRole: String,
    val updatedAt: Long,
    val savedAt: Long,
)
