package org.relyra.app.core.identity

import androidx.room.withTransaction
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.relyra.app.core.crypto.DoubleRatchet
import org.relyra.app.core.crypto.PayloadCrypto
import org.relyra.app.core.crypto.RatchetState
import org.relyra.app.core.crypto.RatchetWire
import org.relyra.app.core.crypto.X3dh
import org.relyra.app.core.crypto.X3dhRatchetBootstrap
import org.relyra.app.core.database.AppDatabase
import org.relyra.app.core.database.RatchetEntity
import org.relyra.app.core.messaging.Opened
import org.relyra.app.core.messaging.PayloadProtector
import org.relyra.app.core.storage.SecureStore

/** Wire v3 bootstraps X3DH + Double Ratchet; v2 is established ratchet traffic; v1 stays readable. */
class E2eeProtector(private val db: AppDatabase, private val secure: SecureStore) : PayloadProtector {
    private val lock = Mutex()

    private suspend fun load(peerId: String): RatchetState? =
        db.ratchetDao().get(peerId)?.let { DoubleRatchet.deserialize(it.state) }

    private suspend fun save(peerId: String, state: RatchetState) =
        db.ratchetDao().put(RatchetEntity(peerId, DoubleRatchet.serialize(state)))

    override suspend fun protect(peerId: String, plaintext: ByteArray): ByteArray? = lock.withLock {
        val me = db.identityDao().get() ?: return@withLock null
        val peer = db.contactDao().get(peerId) ?: return@withLock null
        if (peer.blocked) return@withLock null
        val seed = secure.loadSeed() ?: return@withLock null
        val ownDhPrivate = PayloadCrypto.deriveSecret(seed)
        val state = load(peerId)

        if (state == null && peer.signedPreKeyId != null && peer.signedPreKey != null &&
            peer.signedPreKeyExpiresAt != null && peer.signedPreKeySignature != null) {
            val bundle = X3dh.PreKeyBundle(
                peer.publicId, peer.publicKey, peer.encKey ?: return@withLock null,
                peer.signedPreKeyId, peer.signedPreKey, peer.signedPreKeyExpiresAt,
                peer.signedPreKeySignature, peer.oneTimePreKeyId, peer.oneTimePreKey,
            )
            val initial = try {
                X3dhRatchetBootstrap.initiate(
                    me.publicId, bundle, ownDhPrivate, plaintext, System.currentTimeMillis() / 1000,
                )
            } catch (_: Exception) {
                return@withLock null
            }
            save(peerId, initial.state)
            return@withLock initial.wire
        }

        // Backward compatibility for contacts imported from older v1/v2 cards.
        val peerKey = peer.encKey ?: return@withLock null
        val iAmAlice = me.publicId < peerId
        var current = state
        if (current == null && iAmAlice) {
            val shared = DoubleRatchet.sharedSecret(ownDhPrivate, peerKey, me.publicId, peerId)
            current = DoubleRatchet.initAlice(shared, peerKey)
        }
        if (current != null) {
            val encrypted = DoubleRatchet.encrypt(current, plaintext, DoubleRatchet.aad(me.publicId, peerId))
            if (encrypted != null) {
                save(peerId, encrypted.newState)
                return@withLock RatchetWire.pack(me.publicId, encrypted.header, encrypted.body)
            }
        }
        PayloadCrypto.seal(me.publicId, ownDhPrivate, peerId, peerKey, plaintext)
    }

    override suspend fun open(wire: ByteArray): Opened? {
        if (wire.isEmpty()) return null
        return when (wire[0].toInt() and 0xFF) {
            3 -> openX3dh(wire)
            RatchetWire.VERSION -> openRatchet(wire)
            1 -> openSealed(wire)
            else -> null
        }
    }

    private suspend fun openX3dh(wire: ByteArray): Opened? = lock.withLock {
        val senderId = X3dhRatchetBootstrap.senderId(wire) ?: return@withLock null
        val signedId = X3dhRatchetBootstrap.signedPreKeyId(wire) ?: return@withLock null
        val oneTimeId = X3dhRatchetBootstrap.oneTimePreKeyId(wire)
        val me = db.identityDao().get() ?: return@withLock null
        val sender = db.contactDao().get(senderId) ?: return@withLock null
        if (sender.blocked || sender.encKey == null || db.ratchetDao().get(senderId) != null) return@withLock null
        val seed = secure.loadSeed() ?: return@withLock null
        val ownIdentityDh = PayloadCrypto.deriveSecret(seed)
        val signedRow = db.preKeyDao().get("signed:$signedId") ?: return@withLock null
        if (signedRow.kind != "SIGNED" ||
            signedRow.expiresAt < System.currentTimeMillis() / 1000 - SIGNED_PREKEY_GRACE_SECONDS) return@withLock null
        val signedPrivate = secure.loadPreKeyPrivate(signedRow.keyId) ?: return@withLock null

        val accepted = db.withTransaction {
            val oneTimePrivate = if (oneTimeId != null) {
                val row = db.preKeyDao().get("one-time:$oneTimeId") ?: return@withTransaction null
                if (row.kind != "ONE_TIME" || row.consumed ||
                    row.expiresAt < System.currentTimeMillis() / 1000 - SIGNED_PREKEY_GRACE_SECONDS) return@withTransaction null
                secure.loadPreKeyPrivate(row.keyId) ?: return@withTransaction null
            } else null
            val result = X3dhRatchetBootstrap.accept(
                wire, senderId, me.publicId, sender.encKey, ownIdentityDh, signedId, signedPrivate,
                resolveOneTimePreKey = { requested -> if (requested == oneTimeId) oneTimePrivate else null },
            ) ?: return@withTransaction null
            if (result.consumedOneTimePreKeyId != null) {
                val changed = db.preKeyDao().consumeOneTime("one-time:${result.consumedOneTimePreKeyId}")
                if (changed != 1) return@withTransaction null
            }
            db.ratchetDao().put(RatchetEntity(senderId, DoubleRatchet.serialize(result.state)))
            result
        } ?: return@withLock null

        accepted.consumedOneTimePreKeyId?.let { secure.deletePreKeyPrivate("one-time:$it") }
        Opened(senderId, accepted.plaintext)
    }

    private suspend fun openSealed(wire: ByteArray): Opened? {
        val header = PayloadCrypto.parseHeader(wire) ?: return null
        val me = db.identityDao().get() ?: return null
        val sender = db.contactDao().get(header.senderId) ?: return null
        if (sender.blocked) return null
        val senderKey = sender.encKey ?: return null
        val seed = secure.loadSeed() ?: return null
        val plain = PayloadCrypto.open(wire, header, me.publicId, PayloadCrypto.deriveSecret(seed), senderKey) ?: return null
        return Opened(header.senderId, plain)
    }

    private suspend fun openRatchet(wire: ByteArray): Opened? {
        val parsed = RatchetWire.parse(wire) ?: return null
        return lock.withLock {
            val me = db.identityDao().get() ?: return@withLock null
            val sender = db.contactDao().get(parsed.senderId) ?: return@withLock null
            if (sender.blocked) return@withLock null
            val senderKey = sender.encKey ?: return@withLock null
            val seed = secure.loadSeed() ?: return@withLock null
            val secret = PayloadCrypto.deriveSecret(seed)
            val iAmAlice = me.publicId < sender.publicId
            var state = load(sender.publicId)
            if (state == null) {
                if (iAmAlice) return@withLock null
                state = DoubleRatchet.initBob(
                    DoubleRatchet.sharedSecret(secret, senderKey, sender.publicId, me.publicId), secret,
                )
            }
            val result = DoubleRatchet.decrypt(
                state, parsed.header, parsed.body, DoubleRatchet.aad(sender.publicId, me.publicId),
            ) ?: return@withLock null
            save(sender.publicId, result.newState)
            Opened(sender.publicId, result.plaintext)
        }
    }
    private companion object { const val SIGNED_PREKEY_GRACE_SECONDS = 30L * 24 * 60 * 60 }
}
