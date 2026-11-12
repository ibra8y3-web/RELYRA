package org.relyra.app.core.identity

import kotlinx.coroutines.flow.Flow
import org.relyra.app.core.crypto.CardDecode
import org.relyra.app.core.crypto.ContactCardCodec
import org.relyra.app.core.crypto.IdentityCrypto
import org.relyra.app.core.crypto.KeyMaterial
import org.relyra.app.core.crypto.PayloadCrypto
import org.relyra.app.core.crypto.PreKeyRepository
import org.relyra.app.core.network.NostrCrypto
import org.relyra.app.core.database.AppDatabase
import org.relyra.app.core.database.ContactEntity
import org.relyra.app.core.database.EventType
import org.relyra.app.core.database.IdentityEntity
import org.relyra.app.core.database.SecurityEventEntity
import org.relyra.app.core.storage.SecureStore

class SecurityLog(private val db: AppDatabase) {
    suspend fun log(type: String, detail: String = "") =
        db.eventDao().insert(SecurityEventEntity(timestamp = System.currentTimeMillis(), type = type, detail = detail))

    fun observeRecent(limit: Int) = db.eventDao().observeRecent(limit)
    fun observeCount() = db.eventDao().observeCount()
}

class IdentityRepository(
    private val db: AppDatabase,
    private val secure: SecureStore,
    private val log: SecurityLog,
    private val wipeFiles: () -> Unit = {},
) {
    private val prekeys = PreKeyRepository(db, secure)
    fun observe(): Flow<IdentityEntity?> = db.identityDao().observe()

    suspend fun create(name: String) {
        check(db.identityDao().get() == null) { "identity already exists" }
        persist(name, IdentityCrypto.generate(), EventType.IDENTITY_CREATED)
    }

    /** Returns false when the recovery key is malformed or its checksum fails. */
    suspend fun restore(name: String, recoveryKey: String): Boolean {
        check(db.identityDao().get() == null) { "identity already exists" }
        val seed = IdentityCrypto.parseRecoveryKey(recoveryKey) ?: return false
        persist(name, IdentityCrypto.fromSeed(seed), EventType.IDENTITY_RESTORED)
        return true
    }

    private suspend fun persist(name: String, keys: KeyMaterial, event: String) {
        secure.saveSeed(keys.seed)
        db.identityDao().put(
            IdentityEntity(
                displayName = ContactCardCodec.sanitizeName(name),
                publicId = IdentityCrypto.publicId(keys.publicKey),
                publicKey = keys.publicKey,
                createdAt = System.currentTimeMillis(),
            )
        )
        log.log(event)
    }

    suspend fun rename(name: String) = db.identityDao().rename(ContactCardCodec.sanitizeName(name))

    suspend fun cardUri(identity: IdentityEntity, rotateOneTime: Boolean = false): String? {
        val seed = secure.loadSeed() ?: return null
        val encKey = PayloadCrypto.publicKey(PayloadCrypto.deriveSecret(seed))
        val bundle = prekeys.currentBundle(
            identity.publicId, seed, identity.publicKey, encKey, System.currentTimeMillis() / 1000, rotateOneTime,
        )
        return ContactCardCodec.encodeV3(
            identity.displayName, seed, identity.publicKey, encKey,
            NostrCrypto.publicKeyX(NostrCrypto.deriveSecret(seed)), bundle,
        )
    }

    fun nostrSecret(): ByteArray? = secure.loadSeed()?.let { NostrCrypto.deriveSecret(it) }

    /** Public Nostr key derived from the identity seed (hex). */
    fun nostrPublicKeyHex(): String? = secure.loadSeed()?.let {
        org.relyra.app.core.network.Hex.encode(
            org.relyra.app.core.network.NostrCrypto.publicKeyX(org.relyra.app.core.network.NostrCrypto.deriveSecret(it))
        )
    }

    fun recoveryKey(): String? = secure.loadSeed()?.let { IdentityCrypto.recoveryKey(it) }

    /** Signs transport challenge transcripts with the existing RELYRA Ed25519 identity seed. */
    fun sign(message: ByteArray): ByteArray? =
        secure.loadSeed()?.let { IdentityCrypto.sign(it, message) }

    suspend fun reset() {
        db.clearAllTables()
        secure.clearSeed()
        secure.clearPreKeys()
        secure.clearDeviceState()
        wipeFiles()
    }
}

sealed interface AddResult {
    class Added(val name: String) : AddResult
    class Updated(val name: String) : AddResult
    data object Malformed : AddResult
    data object BadSignature : AddResult
    data object IsSelf : AddResult
    data object AlreadyExists : AddResult
    data object NameRequired : AddResult
    data object FreshCardRequired : AddResult
}

class ContactRepository(
    private val db: AppDatabase,
    private val log: SecurityLog,
    /** Called with (message IDs, local file paths) of a deleted chat so its files are removed from disk too. */
    private val cleanFiles: (List<String>, List<String>) -> Unit = { _, _ -> },
) {
    fun observeAll(): Flow<List<ContactEntity>> = db.contactDao().observeAll()

    val peers = object : org.relyra.app.core.messaging.PeerDirectory {
        override suspend fun status(publicId: String): org.relyra.app.core.messaging.PeerStatus {
            val c = db.contactDao().get(publicId) ?: return org.relyra.app.core.messaging.PeerStatus.Unknown
            return when {
                c.blocked -> org.relyra.app.core.messaging.PeerStatus.Blocked
                c.encKey == null || c.nostrKey == null -> org.relyra.app.core.messaging.PeerStatus.NoKeys
                else -> org.relyra.app.core.messaging.PeerStatus.Known
            }
        }
    }

    suspend fun nostrKeyOf(publicId: String): ByteArray? = db.contactDao().get(publicId)?.nostrKey

    /** [input] is either a signed relyra://contact link or a bare public ID (then [manualName] is required). */
    suspend fun add(input: String, manualName: String): AddResult {
        val text = input.trim()
        val key: ByteArray
        val name: String
        val signed: Boolean
        var enc: ByteArray? = null
        var nostr: ByteArray? = null
        var preKeyBundle: org.relyra.app.core.crypto.X3dh.PreKeyBundle? = null
        if (text.startsWith("relyra://")) {
            when (val d = ContactCardCodec.decode(text)) {
                is CardDecode.Ok -> {
                    key = d.card.publicKey; name = d.card.name; signed = true
                    enc = d.card.encKey; nostr = d.card.nostrKey; preKeyBundle = d.card.preKeyBundle
                }
                CardDecode.Malformed -> return AddResult.Malformed
                CardDecode.BadSignature -> return AddResult.BadSignature
            }
        } else {
            key = IdentityCrypto.parsePublicId(text) ?: return AddResult.Malformed
            name = ContactCardCodec.sanitizeName(manualName)
            if (name.isBlank()) return AddResult.NameRequired
            signed = false
        }
        if (db.identityDao().get()?.publicKey?.contentEquals(key) == true) return AddResult.IsSelf
        val id = IdentityCrypto.publicId(key)
        val existing = db.contactDao().get(id)
        if (existing != null) {
            // A restored contact must be refreshed with a v3 signed card carrying a fresh X3DH prekey bundle.
            if (signed && existing.encKey == null && preKeyBundle == null) return AddResult.FreshCardRequired
            // A newer signed card can add the encryption keys an older contact is missing.
            if (signed && enc != null && nostr != null &&
                (existing.encKey == null || existing.nostrKey == null || preKeyBundle != null)) {
                val sessionKeysChanged = existing.encKey == null ||
                    !existing.encKey.contentEquals(enc) ||
                    (preKeyBundle != null && existing.signedPreKeyId != preKeyBundle.signedPreKeyId)
                db.contactDao().setKeys(id, enc, nostr)
                if (sessionKeysChanged) {
                    db.ratchetDao().delete(id)
                    db.encryptedPacketDao().deleteForChat(id)
                }
                preKeyBundle?.takeIf {
                    it.signedPreKeyExpiresAt > (existing.signedPreKeyExpiresAt ?: 0L) ||
                        (it.signedPreKeyExpiresAt == existing.signedPreKeyExpiresAt &&
                            it.oneTimePreKeyId != existing.oneTimePreKeyId)
                }?.let {
                    db.contactDao().setPreKeys(id, it.signedPreKeyId, it.signedPreKey, it.signedPreKeyExpiresAt,
                        it.signedPreKeySignature, it.oneTimePreKeyId, it.oneTimePreKey)
                }
                log.log(EventType.CONTACT_ADDED, name)
                return AddResult.Updated(existing.displayName)
            }
            return AddResult.AlreadyExists
        }
        db.contactDao().insert(
            ContactEntity(id, name, key, verified = false, blocked = false, cardSigned = signed,
                addedAt = System.currentTimeMillis(), encKey = enc, nostrKey = nostr,
                signedPreKeyId = preKeyBundle?.signedPreKeyId,
                signedPreKey = preKeyBundle?.signedPreKey,
                signedPreKeyExpiresAt = preKeyBundle?.signedPreKeyExpiresAt,
                signedPreKeySignature = preKeyBundle?.signedPreKeySignature,
                oneTimePreKeyId = preKeyBundle?.oneTimePreKeyId,
                oneTimePreKey = preKeyBundle?.oneTimePreKey)
        )
        log.log(EventType.CONTACT_ADDED, name)
        return AddResult.Added(name)
    }

    suspend fun setVerified(c: ContactEntity, v: Boolean) {
        db.contactDao().setVerified(c.publicId, v)
        log.log(if (v) EventType.CONTACT_VERIFIED else EventType.CONTACT_UNVERIFIED, c.displayName)
    }

    suspend fun setBlocked(c: ContactEntity, b: Boolean) {
        db.contactDao().setBlocked(c.publicId, b)
        log.log(if (b) EventType.CONTACT_BLOCKED else EventType.CONTACT_UNBLOCKED, c.displayName)
    }

    suspend fun delete(c: ContactEntity) {
        val fileIds = db.attachmentDao().idsForChat(c.publicId)
        val filePaths = db.attachmentDao().pathsForChat(c.publicId)
        db.contactDao().delete(c.publicId)
        db.ratchetDao().delete(c.publicId)
        db.attachmentDao().deleteForChat(c.publicId)
        db.encryptedPacketDao().deleteForChat(c.publicId)
        db.messageDao().deleteChat(c.publicId)
        cleanFiles(fileIds, filePaths)
        log.log(EventType.CONTACT_DELETED, c.displayName)
    }
}
