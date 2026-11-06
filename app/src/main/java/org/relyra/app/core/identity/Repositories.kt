package org.relyra.app.core.identity

import kotlinx.coroutines.flow.Flow
import org.relyra.app.core.crypto.CardDecode
import org.relyra.app.core.crypto.ContactCardCodec
import org.relyra.app.core.crypto.IdentityCrypto
import org.relyra.app.core.crypto.KeyMaterial
import org.relyra.app.core.crypto.PayloadCrypto
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

    fun cardUri(identity: IdentityEntity): String? {
        val seed = secure.loadSeed() ?: return null
        return ContactCardCodec.encode(
            identity.displayName, seed, identity.publicKey,
            PayloadCrypto.publicKey(PayloadCrypto.deriveSecret(seed)),
            NostrCrypto.publicKeyX(NostrCrypto.deriveSecret(seed)),
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

    suspend fun reset() {
        db.clearAllTables()
        secure.clearSeed()
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
        if (text.startsWith("relyra://")) {
            when (val d = ContactCardCodec.decode(text)) {
                is CardDecode.Ok -> {
                    key = d.card.publicKey; name = d.card.name; signed = true
                    enc = d.card.encKey; nostr = d.card.nostrKey
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
            // A newer signed card can add the encryption keys an older contact is missing.
            if (signed && enc != null && nostr != null && (existing.encKey == null || existing.nostrKey == null)) {
                db.contactDao().setKeys(id, enc, nostr)
                log.log(EventType.CONTACT_ADDED, name)
                return AddResult.Updated(existing.displayName)
            }
            return AddResult.AlreadyExists
        }
        db.contactDao().insert(
            ContactEntity(id, name, key, verified = false, blocked = false, cardSigned = signed,
                addedAt = System.currentTimeMillis(), encKey = enc, nostrKey = nostr)
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
        db.messageDao().deleteChat(c.publicId)
        cleanFiles(fileIds, filePaths)
        log.log(EventType.CONTACT_DELETED, c.displayName)
    }
}
