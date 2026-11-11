package org.relyra.app.core.crypto

import com.google.crypto.tink.subtle.AesGcmJce
import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import org.relyra.app.core.network.Hex
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Mutable ratchet state of one conversation. Persisted in the encrypted database. Functions in [DoubleRatchet]
 * never modify a state they are given: they work on a copy and return the new state, so a failed or forged packet
 * can never damage the session.
 */
class RatchetState(
    var dhsPriv: ByteArray,
    var dhsPub: ByteArray,
    var dhr: ByteArray?,
    var rk: ByteArray,
    var cks: ByteArray?,
    var ckr: ByteArray?,
    var ns: Int,
    var nr: Int,
    var pn: Int,
    val skipped: LinkedHashMap<String, ByteArray>,
) {
    fun deepCopy() = RatchetState(dhsPriv.copyOf(), dhsPub.copyOf(), dhr?.copyOf(), rk.copyOf(), cks?.copyOf(),
        ckr?.copyOf(), ns, nr, pn, LinkedHashMap(skipped))

    val canSend: Boolean get() = cks != null
}

/**
 * Double Ratchet (Signal specification) built only from Tink primitives: X25519, HKDF-SHA256, HMAC-SHA256, AES-256-GCM.
 * Gives forward secrecy and post-compromise recovery for one-to-one chats.
 *
 * Start-up without prekeys: both sides derive the same initial secret from their long-term X25519 keys. The side whose
 * public ID sorts first ("Alice") starts the ratchet using the other side's long-term key as its first ratchet key.
 * The other side ("Bob") can only send ratcheted messages after receiving Alice's first one.
 */
object DoubleRatchet {
    const val HEADER_LEN = 40            // ratchet public key (32) | previous chain length (4) | message number (4)
    const val MAX_SKIP = 5_000
    private const val MAX_STORED = 5_000
    private const val STATE_VERSION = 1

    private val INFO_INIT = "relyra-dr-init".toByteArray(Charsets.UTF_8)
    private val INFO_RK = "relyra-dr-rk".toByteArray(Charsets.UTF_8)
    private val INFO_MSG = "relyra-dr-msg".toByteArray(Charsets.UTF_8)

    class Encrypted(val header: ByteArray, val body: ByteArray, val newState: RatchetState)
    class Decrypted(val plaintext: ByteArray, val newState: RatchetState)

    fun sharedSecret(ownSecret: ByteArray, peerPublic: ByteArray, aliceId: String, bobId: String): ByteArray =
        Hkdf.computeHkdf(
            "HmacSha256", X25519.computeSharedSecret(ownSecret, peerPublic), ByteArray(0),
            INFO_INIT + "\n$aliceId\n$bobId".toByteArray(Charsets.UTF_8), 32,
        )

    fun aad(senderId: String, recipientId: String): ByteArray =
        "relyra-dr-v1\n$senderId\n$recipientId".toByteArray(Charsets.UTF_8)

    fun initAlice(shared: ByteArray, bobPublic: ByteArray): RatchetState {
        val priv = X25519.generatePrivateKey()
        val (rk, cks) = kdfRk(shared, X25519.computeSharedSecret(priv, bobPublic))
        return RatchetState(priv, X25519.publicFromPrivate(priv), bobPublic, rk, cks, null, 0, 0, 0, LinkedHashMap())
    }

    fun initBob(shared: ByteArray, ownSecret: ByteArray): RatchetState =
        RatchetState(ownSecret, X25519.publicFromPrivate(ownSecret), null, shared, null, null, 0, 0, 0, LinkedHashMap())

    private fun kdfRk(rk: ByteArray, dhOut: ByteArray): Pair<ByteArray, ByteArray> {
        val out = Hkdf.computeHkdf("HmacSha256", dhOut, rk, INFO_RK, 64)
        return out.copyOfRange(0, 32) to out.copyOfRange(32, 64)
    }

    private fun hmac(key: ByteArray, byte: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(byteArrayOf(byte.toByte()))
    }

    /** Returns (next chain key, message key). */
    private fun kdfCk(ck: ByteArray): Pair<ByteArray, ByteArray> = hmac(ck, 2) to hmac(ck, 1)

    private fun messageKey(mk: ByteArray): ByteArray = Hkdf.computeHkdf("HmacSha256", mk, ByteArray(0), INFO_MSG, 32)

    private fun int(v: Int): ByteArray = ByteBuffer.allocate(4).putInt(v).array()
    private fun readInt(b: ByteArray, offset: Int): Int = ByteBuffer.wrap(b, offset, 4).int
    private fun skippedKey(dhr: ByteArray, n: Int) = Hex.encode(dhr) + ":" + n

    /** Null when this side cannot send ratcheted messages yet (Bob before Alice's first message). */
    fun encrypt(state: RatchetState, plaintext: ByteArray, aad: ByteArray): Encrypted? {
        val s = state.deepCopy()
        val chain = s.cks ?: return null
        val (nextChain, mk) = kdfCk(chain)
        val header = s.dhsPub + int(s.pn) + int(s.ns)
        val body = AesGcmJce(messageKey(mk)).encrypt(plaintext, header + aad)
        s.cks = nextChain
        s.ns += 1
        return Encrypted(header, body, s)
    }

    /** Null for anything that does not authenticate (forgery, replay, wrong session, too many skipped messages). */
    fun decrypt(state: RatchetState, header: ByteArray, body: ByteArray, aad: ByteArray): Decrypted? {
        if (header.size != HEADER_LEN) return null
        val s = state.deepCopy()
        val theirKey = header.copyOfRange(0, 32)
        val pn = readInt(header, 32)
        val n = readInt(header, 36)
        if (pn < 0 || n < 0) return null
        try {
            val stored = s.skipped.remove(skippedKey(theirKey, n))
            if (stored != null) return open(stored, header, body, aad, s)

            val current = s.dhr
            if (current == null || !current.contentEquals(theirKey)) {
                if (!skipUntil(s, pn)) return null
                s.pn = s.ns
                s.ns = 0
                s.nr = 0
                s.dhr = theirKey
                val (rk1, ckr) = kdfRk(s.rk, X25519.computeSharedSecret(s.dhsPriv, theirKey))
                s.ckr = ckr
                val newPriv = X25519.generatePrivateKey()
                s.dhsPriv = newPriv
                s.dhsPub = X25519.publicFromPrivate(newPriv)
                val (rk2, cks) = kdfRk(rk1, X25519.computeSharedSecret(newPriv, theirKey))
                s.rk = rk2
                s.cks = cks
            }
            if (n < s.nr) return null
            if (!skipUntil(s, n)) return null
            val chain = s.ckr ?: return null
            val (nextChain, mk) = kdfCk(chain)
            s.ckr = nextChain
            s.nr += 1
            return open(mk, header, body, aad, s)
        } catch (e: GeneralSecurityException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }
    }

    private fun open(mk: ByteArray, header: ByteArray, body: ByteArray, aad: ByteArray, s: RatchetState): Decrypted {
        val plain = AesGcmJce(messageKey(mk)).decrypt(body, header + aad)   // throws GeneralSecurityException
        return Decrypted(plain, s)
    }

    private fun skipUntil(s: RatchetState, until: Int): Boolean {
        val chainStart = s.ckr ?: return true
        if (until - s.nr > MAX_SKIP) return false
        var chain = chainStart
        val theirKey = s.dhr ?: return true
        while (s.nr < until) {
            val (next, mk) = kdfCk(chain)
            chain = next
            s.skipped[skippedKey(theirKey, s.nr)] = mk
            s.nr += 1
            if (s.skipped.size > MAX_STORED) s.skipped.remove(s.skipped.keys.first())
        }
        s.ckr = chain
        return true
    }

    // ---- persistence ---------------------------------------------------------------------------------------

    fun serialize(s: RatchetState): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { o ->
            o.writeByte(STATE_VERSION)
            o.write(s.dhsPriv); o.write(s.dhsPub)
            fun optional(b: ByteArray?) { if (b == null) o.writeBoolean(false) else { o.writeBoolean(true); o.write(b) } }
            optional(s.dhr)
            o.write(s.rk)
            optional(s.cks); optional(s.ckr)
            o.writeInt(s.ns); o.writeInt(s.nr); o.writeInt(s.pn)
            o.writeInt(s.skipped.size)
            for ((k, v) in s.skipped) { o.writeUTF(k); o.write(v) }
        }
        return bos.toByteArray()
    }

    fun deserialize(bytes: ByteArray): RatchetState? = try {
        DataInputStream(ByteArrayInputStream(bytes)).use { i ->
            if (i.readUnsignedByte() != STATE_VERSION) return null
            fun key() = ByteArray(32).also { i.readFully(it) }
            fun optional(): ByteArray? = if (i.readBoolean()) key() else null
            val priv = key()
            val pub = key()
            val dhr = optional()
            val rk = key()
            val cks = optional()
            val ckr = optional()
            val ns = i.readInt()
            val nr = i.readInt()
            val pn = i.readInt()
            val count = i.readInt()
            if (ns < 0 || nr < 0 || pn < 0 || count < 0 || count > MAX_STORED) return null
            if (!X25519.publicFromPrivate(priv).contentEquals(pub)) return null
            val map = LinkedHashMap<String, ByteArray>()
            repeat(count) {
                val id = i.readUTF()
                val value = key()
                if (!id.matches(Regex("[0-9a-f]{64}:[0-9]+")) || map.put(id, value) != null) return null
            }
            if (i.available() != 0) return null
            RatchetState(priv, pub, dhr, rk, cks, ckr, ns, nr, pn, map)
        }
    } catch (e: IOException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }
}

/** Wire format version 2: 0x02 | idLen | senderId | header(40) | AES-GCM(iv | ciphertext | tag). */
object RatchetWire {
    const val VERSION = 2
    private const val MIN_BODY = 28

    class Parsed(val senderId: String, val header: ByteArray, val body: ByteArray)

    fun pack(senderId: String, header: ByteArray, body: ByteArray): ByteArray {
        val id = senderId.toByteArray(Charsets.UTF_8)
        require(id.size in 1..255)
        return byteArrayOf(VERSION.toByte(), id.size.toByte()) + id + header + body
    }

    fun parse(wire: ByteArray): Parsed? {
        if (wire.size < 3 || wire[0].toInt() != VERSION) return null
        val idLen = wire[1].toInt() and 0xFF
        val headerStart = 2 + idLen
        val bodyStart = headerStart + DoubleRatchet.HEADER_LEN
        if (idLen == 0 || wire.size < bodyStart + MIN_BODY) return null
        return Parsed(
            String(wire, 2, idLen, Charsets.UTF_8),
            wire.copyOfRange(headerStart, bodyStart),
            wire.copyOfRange(bodyStart, wire.size),
        )
    }
}
