package org.relyra.app.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface IdentityDao {
    @Query("SELECT * FROM identity WHERE slot = 1")
    fun observe(): Flow<IdentityEntity?>

    @Query("SELECT * FROM identity WHERE slot = 1")
    suspend fun get(): IdentityEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: IdentityEntity)

    @Query("UPDATE identity SET displayName = :name WHERE slot = 1")
    suspend fun rename(name: String)
}

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY displayName COLLATE NOCASE")
    fun observeAll(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE publicId = :id")
    suspend fun get(id: String): ContactEntity?

    @Query("SELECT * FROM contacts ORDER BY displayName COLLATE NOCASE")
    suspend fun allForDeviceSync(): List<ContactEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(contact: ContactEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(contact: ContactEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: ContactEntity)

    @Query("UPDATE contacts SET verified = :verified WHERE publicId = :id")
    suspend fun setVerified(id: String, verified: Boolean)

    @Query("UPDATE contacts SET blocked = :blocked WHERE publicId = :id")
    suspend fun setBlocked(id: String, blocked: Boolean)

    @Query("UPDATE contacts SET encKey = :enc, nostrKey = :nostr WHERE publicId = :id")
    suspend fun setKeys(id: String, enc: ByteArray, nostr: ByteArray)

    @Query("UPDATE contacts SET signedPreKeyId = :signedId, signedPreKey = :signedKey, signedPreKeyExpiresAt = :expiresAt, signedPreKeySignature = :signature, oneTimePreKeyId = :oneTimeId, oneTimePreKey = :oneTimeKey WHERE publicId = :id")
    suspend fun setPreKeys(id: String, signedId: Long, signedKey: ByteArray, expiresAt: Long, signature: ByteArray, oneTimeId: Long?, oneTimeKey: ByteArray?)

    @Query("DELETE FROM contacts WHERE publicId = :id")
    suspend fun delete(id: String)
}

@Dao
interface SecurityEventDao {
    @Insert
    suspend fun insert(event: SecurityEventEntity)

    @Query("SELECT * FROM security_events ORDER BY id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<SecurityEventEntity>>

    @Query("SELECT COUNT(*) FROM security_events")
    fun observeCount(): Flow<Int>
}

 
@Dao
interface PreKeyDao {
    @Query("SELECT * FROM prekeys WHERE kind = :kind ORDER BY keyId")
    suspend fun byKind(kind: String): List<PreKeyEntity>

    @Query("SELECT * FROM prekeys WHERE keyId = :keyId")
    suspend fun get(keyId: String): PreKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: PreKeyEntity)

    @Query("UPDATE prekeys SET consumed = 1 WHERE keyId = :keyId AND kind = 'ONE_TIME' AND consumed = 0")
    suspend fun consumeOneTime(keyId: String): Int

    @Query("DELETE FROM prekeys WHERE kind = :kind")
    suspend fun deleteKind(kind: String)

    @Query("DELETE FROM prekeys WHERE keyId = :keyId")
    suspend fun delete(keyId: String)
}


/** Device inventory. State transitions are conditional so a revoked device cannot be re-approved
 * by a stale update and a pending device cannot silently become trusted.
 */
@Dao
interface TrustedDeviceDao {
    @Query("SELECT * FROM trusted_devices WHERE accountId = :accountId ORDER BY createdAt DESC")
    fun observeForAccount(accountId: String): Flow<List<TrustedDeviceEntity>>

    @Query("SELECT * FROM trusted_devices WHERE deviceId = :deviceId LIMIT 1")
    suspend fun get(deviceId: String): TrustedDeviceEntity?

    @Query("SELECT COUNT(*) FROM trusted_devices WHERE accountId = :accountId")
    suspend fun countForAccount(accountId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPending(device: TrustedDeviceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSnapshot(device: TrustedDeviceEntity)

    @Query("UPDATE trusted_devices SET status = 'APPROVED', approvedAt = :approvedAt, lastSeenAt = :approvedAt WHERE deviceId = :deviceId AND accountId = :accountId AND status = 'PENDING'")
    suspend fun approvePending(deviceId: String, accountId: String, approvedAt: Long): Int

    @Query("UPDATE trusted_devices SET status = 'REVOKED', revokedAt = :revokedAt WHERE deviceId = :deviceId AND accountId = :accountId AND status = 'APPROVED'")
    suspend fun revokeApproved(deviceId: String, accountId: String, revokedAt: Long): Int

    @Query("UPDATE trusted_devices SET status = 'REVOKED', revokedAt = :revokedAt WHERE deviceId = :deviceId AND accountId = :accountId AND status IN ('APPROVED','PENDING')")
    suspend fun revokeAny(deviceId: String, accountId: String, revokedAt: Long): Int

    @Query("UPDATE trusted_devices SET lastSeenAt = :seenAt WHERE deviceId = :deviceId AND accountId = :accountId AND status = 'APPROVED' AND :seenAt >= lastSeenAt")
    suspend fun updateLastSeen(deviceId: String, accountId: String, seenAt: Long): Int

    @Query("UPDATE trusted_devices SET syncCursor = :cursor WHERE deviceId = :deviceId AND accountId = :accountId AND status = 'APPROVED' AND :cursor > syncCursor")
    suspend fun advanceSyncCursor(deviceId: String, accountId: String, cursor: Long): Int

    @Query("UPDATE trusted_devices SET signingPublicKey = :signingKey, encryptionPublicKey = :encryptionKey, keyVersion = :newVersion WHERE deviceId = :deviceId AND accountId = :accountId AND status = 'APPROVED' AND keyVersion = :expectedVersion")
    suspend fun rotateDeviceKeys(deviceId: String, accountId: String, expectedVersion: Long, newVersion: Long, signingKey: ByteArray, encryptionKey: ByteArray): Int
}
