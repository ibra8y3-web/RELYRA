package org.relyra.app.core.identity

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.relyra.app.core.crypto.DoubleRatchet
import org.relyra.app.core.crypto.PayloadCrypto
import org.relyra.app.core.crypto.RatchetState
import org.relyra.app.core.crypto.RatchetWire
import org.relyra.app.core.database.AppDatabase
import org.relyra.app.core.database.RatchetEntity
import org.relyra.app.core.messaging.Opened
import org.relyra.app.core.messaging.PayloadProtector
import org.relyra.app.core.storage.SecureStore

/**
 * Wire v2 = Double Ratchet. Wire v1 (sealed box, no ratchet) is still sent only by the side that cannot start the
 * ratchet yet, and is still accepted from older builds.
 */
class E2eeProtector(private val db: AppDatabase, private val secure: SecureStore) : PayloadProtector {
    private val lock = Mutex()

    private suspend fun load(peerId: String): RatchetState? =
        db.ratchetDao().get(peerId)?.let { DoubleRatchet.deserialize(it.state) }

    private suspend fun save(peerId: String, state: RatchetState) =
        db.ratchetDao().put(RatchetEntity(peerId, DoubleRatchet.serialize(state)))

    override suspend fun protect(peerId: String, plaintext: ByteArray): ByteArray? = lock.withLock {
        val me = db.identityDao().get() ?: return@withLock null
        val seed = secure.loadSeed() ?: return@withLock null
        val peerKey = db.contactDao().get(peerId)?.encKey ?: return@withLock null
        val secret = PayloadCrypto.deriveSecret(seed)
        val iAmAlice = me.publicId < peerId

        var state = load(peerId)
        if (state == null && iAmAlice) {
            val shared = DoubleRatchet.sharedSecret(secret, peerKey, me.publicId, peerId)
            state = DoubleRatchet.initAlice(shared, peerKey)
        }
        if (state != null) {
            val enc = DoubleRatchet.encrypt(state, plaintext, DoubleRatchet.aad(me.publicId, peerId))
            if (enc != null) {
                save(peerId, enc.newState)
                return@withLock RatchetWire.pack(me.publicId, enc.header, enc.body)
            }
        }
        // Bob, before Alice's first ratcheted message has arrived: the older sealed-box format.
        PayloadCrypto.seal(me.publicId, secret, peerId, peerKey, plaintext)
    }

    override suspend fun open(wire: ByteArray): Opened? {
        if (wire.isEmpty()) return null
        return when (wire[0].toInt()) {
            RatchetWire.VERSION -> openRatchet(wire)
            1 -> openSealed(wire)
            else -> null
        }
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
                // Only Bob can start from nothing: Alice always has her own session before Bob can reply.
                if (iAmAlice) return@withLock null
                state = DoubleRatchet.initBob(DoubleRatchet.sharedSecret(secret, senderKey, sender.publicId, me.publicId), secret)
            }
            val result = DoubleRatchet.decrypt(state, parsed.header, parsed.body,
                DoubleRatchet.aad(sender.publicId, me.publicId)) ?: return@withLock null
            save(sender.publicId, result.newState)
            Opened(sender.publicId, result.plaintext)
        }
    }
}
