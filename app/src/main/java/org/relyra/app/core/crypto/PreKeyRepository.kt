package org.relyra.app.core.crypto

import org.relyra.app.core.database.AppDatabase
import org.relyra.app.core.database.PreKeyEntity
import org.relyra.app.core.storage.SecureStore
import java.security.SecureRandom

/**
 * Public prekey inventory is in SQLCipher; private key bytes are wrapped by Android Keystore.
 * One-time keys are atomically consumed with session creation by E2eeProtector.
 */
class PreKeyRepository(private val db: AppDatabase, private val secure: SecureStore) {
    suspend fun currentBundle(
        identityId: String,
        signingSeed: ByteArray,
        signingPublic: ByteArray,
        identityDhPublic: ByteArray,
        nowSeconds: Long,
    ): X3dh.PreKeyBundle {
        val dao = db.preKeyDao()
        var signed = dao.byKind("SIGNED").firstOrNull {
            it.expiresAt > nowSeconds && secure.loadPreKeyPrivate(it.keyId) != null
        }
        if (signed == null) {
            dao.deleteKind("SIGNED")
            val id = nextId()
            val key = X3dh.createSignedPreKey(identityId, signingSeed, id, nowSeconds)
            signed = PreKeyEntity("signed:$id", "SIGNED", key.publicKey, key.expiresAt)
            secure.savePreKeyPrivate(signed.keyId, key.privateKey)
            dao.put(signed)
        }
        var oneTime = dao.byKind("ONE_TIME").firstOrNull {
            !it.consumed && secure.loadPreKeyPrivate(it.keyId) != null
        }
        if (oneTime == null) {
            repeat(20) {
                val key = X3dh.createOneTimePreKey(nextId())
                val row = PreKeyEntity("one-time:${key.id}", "ONE_TIME", key.publicKey, 0L)
                secure.savePreKeyPrivate(row.keyId, key.privateKey)
                dao.put(row)
            }
            oneTime = dao.byKind("ONE_TIME").firstOrNull { !it.consumed }
        }
        val signedId = signed.keyId.substringAfter(':').toLong()
        val signedPrivate = secure.loadPreKeyPrivate(signed.keyId) ?: error("signed prekey private key missing")
        val signature = X3dh.signPreKey(identityId, signingSeed, signedId, signed.publicKey, signed.expiresAt)
        val signedMaterial = X3dh.SignedPreKey(signedId, signedPrivate, signed.publicKey, signed.expiresAt, signature)
        val oneTimeMaterial = oneTime?.let {
            X3dh.OneTimePreKey(
                it.keyId.substringAfter(':').toLong(),
                secure.loadPreKeyPrivate(it.keyId) ?: error("one-time prekey private key missing"),
                it.publicKey,
            )
        }
        return X3dh.bundle(identityId, signingPublic, identityDhPublic, signedMaterial, oneTimeMaterial)
    }

    suspend fun signedPrivate(id: Long): ByteArray? = secure.loadPreKeyPrivate("signed:$id")
    suspend fun oneTimePrivate(id: Long): ByteArray? = secure.loadPreKeyPrivate("one-time:$id")
    fun deleteOneTimePrivate(id: Long) = secure.deletePreKeyPrivate("one-time:$id")
    private fun nextId(): Long = SecureRandom().nextLong().ushr(1)
}
