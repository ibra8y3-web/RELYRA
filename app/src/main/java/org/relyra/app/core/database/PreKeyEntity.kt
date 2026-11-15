package org.relyra.app.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Public prekey metadata. Private material is stored separately in Keystore-wrapped SecureStore. */
@Entity(tableName = "prekeys")
data class PreKeyEntity(
    @PrimaryKey val keyId: String,
    val kind: String,
    val publicKey: ByteArray,
    val expiresAt: Long,
    val consumed: Boolean = false,
)
