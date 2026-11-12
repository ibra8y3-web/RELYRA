package org.relyra.app.core.database

import androidx.room.Index
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "identity")
data class IdentityEntity(
    @PrimaryKey val slot: Int = 1,
    val displayName: String,
    val publicId: String,
    val publicKey: ByteArray,
    val createdAt: Long,
)

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val publicId: String,
    val displayName: String,
    val publicKey: ByteArray,
    val verified: Boolean,
    val blocked: Boolean,
    val cardSigned: Boolean,
    val addedAt: Long,
    val encKey: ByteArray? = null,
    val nostrKey: ByteArray? = null,
    val signedPreKeyId: Long? = null,
    val signedPreKey: ByteArray? = null,
    val signedPreKeyExpiresAt: Long? = null,
    val signedPreKeySignature: ByteArray? = null,
    val oneTimePreKeyId: Long? = null,
    val oneTimePreKey: ByteArray? = null,
)

@Entity(tableName = "security_events")
data class SecurityEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val type: String,
    val detail: String,
)

object EventType {
    const val IDENTITY_CREATED = "IDENTITY_CREATED"
    const val IDENTITY_RESTORED = "IDENTITY_RESTORED"
    const val RECOVERY_VIEWED = "RECOVERY_VIEWED"
    const val CONTACT_ADDED = "CONTACT_ADDED"
    const val CONTACT_VERIFIED = "CONTACT_VERIFIED"
    const val CONTACT_UNVERIFIED = "CONTACT_UNVERIFIED"
    const val CONTACT_BLOCKED = "CONTACT_BLOCKED"
    const val CONTACT_UNBLOCKED = "CONTACT_UNBLOCKED"
    const val CONTACT_DELETED = "CONTACT_DELETED"
    const val APP_LOCK_ON = "APP_LOCK_ON"
    const val APP_LOCK_OFF = "APP_LOCK_OFF"
    const val DEVICE_ENROLLMENT_PENDING = "DEVICE_ENROLLMENT_PENDING"
    const val DEVICE_APPROVED = "DEVICE_APPROVED"
    const val DEVICE_REVOKED = "DEVICE_REVOKED"
    const val DEVICE_KEY_ROTATED = "DEVICE_KEY_ROTATED"
    const val DEVICE_SYNC_CONFLICT = "DEVICE_SYNC_CONFLICT"
}


/**
 * A device known to an account. Public keys are device-scoped; private keys must remain
 * in the Android Keystore and are never persisted in this row or synchronized as plaintext.
 */
@Entity(tableName = "trusted_devices", indices = [Index("accountId"), Index("status")])
data class TrustedDeviceEntity(
    @PrimaryKey val deviceId: String,
    val accountId: String,
    val displayName: String,
    val platform: String,
    val signingPublicKey: ByteArray,
    val encryptionPublicKey: ByteArray,
    val status: String,
    val createdAt: Long,
    val lastSeenAt: Long,
    val approvedAt: Long?,
    val revokedAt: Long?,
    val keyVersion: Long,
    val syncCursor: Long,
)

object TrustedDeviceStatus {
    const val PENDING = "PENDING"
    const val APPROVED = "APPROVED"
    const val REVOKED = "REVOKED"
}
