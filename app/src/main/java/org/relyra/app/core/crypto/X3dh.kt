package org.relyra.app.core.crypto

import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.X25519
import java.nio.ByteBuffer
import java.security.GeneralSecurityException

/**
 * X3DH primitives for RELYRA's asynchronous session bootstrap.
 *
 * The caller must transport [PreKeyBundle] in an identity-authenticated contact/prekey
 * exchange, atomically consume a one-time prekey on the responder, persist the returned
 * ratchet state, and include [Initiator.ephemeralPublic] plus key IDs in the first packet.
 * This class deliberately does not silently fall back to unauthenticated identity-only DH.
 */
object X3dh {
    private val INFO = "RELYRA-X3DH-v1".toByteArray(Charsets.UTF_8)
    private const val MAX_BUNDLE_LIFETIME_SECONDS = 30L * 24L * 60L * 60L

    data class PreKeyBundle(
        val identityId: String,
        val identitySigningKey: ByteArray,
        val identityDhKey: ByteArray,
        val signedPreKeyId: Long,
        val signedPreKey: ByteArray,
        val signedPreKeyExpiresAt: Long,
        val signedPreKeySignature: ByteArray,
        val oneTimePreKeyId: Long? = null,
        val oneTimePreKey: ByteArray? = null,
    )

    data class SignedPreKey(
        val id: Long,
        val privateKey: ByteArray,
        val publicKey: ByteArray,
        val expiresAt: Long,
        val signature: ByteArray,
    )

    data class OneTimePreKey(val id: Long, val privateKey: ByteArray, val publicKey: ByteArray)

    data class Initiator(
        val sharedSecret: ByteArray,
        val ephemeralPrivate: ByteArray,
        val ephemeralPublic: ByteArray,
        val signedPreKeyId: Long,
        val oneTimePreKeyId: Long?,
    )

    data class Responder(val sharedSecret: ByteArray, val consumedOneTimePreKeyId: Long?)

    /** Creates a signed prekey whose transcript is bound to this RELYRA identity and expiry. */
    fun createSignedPreKey(
        identityId: String,
        identitySigningSeed: ByteArray,
        id: Long,
        nowSeconds: Long,
        lifetimeSeconds: Long = 7L * 24L * 60L * 60L,
    ): SignedPreKey {
        require(id >= 0 && lifetimeSeconds in 60..MAX_BUNDLE_LIFETIME_SECONDS)
        val privateKey = X25519.generatePrivateKey()
        val publicKey = X25519.publicFromPrivate(privateKey)
        val expiry = Math.addExact(nowSeconds, lifetimeSeconds)
        val signature = IdentityCrypto.sign(
            identitySigningSeed,
            signedPreKeyTranscript(identityId, id, publicKey, expiry),
        )
        return SignedPreKey(id, privateKey, publicKey, expiry, signature)
    }

    fun createOneTimePreKey(id: Long): OneTimePreKey {
        require(id >= 0)
        val privateKey = X25519.generatePrivateKey()
        return OneTimePreKey(id, privateKey, X25519.publicFromPrivate(privateKey))
    }

    fun bundle(
        identityId: String,
        identitySigningKey: ByteArray,
        identityDhKey: ByteArray,
        signed: SignedPreKey,
        oneTime: OneTimePreKey? = null,
    ) = PreKeyBundle(
        identityId, identitySigningKey.copyOf(), identityDhKey.copyOf(),
        signed.id, signed.publicKey.copyOf(), signed.expiresAt, signed.signature.copyOf(),
        oneTime?.id, oneTime?.publicKey?.copyOf(),
    )

    fun verifyBundle(bundle: PreKeyBundle, nowSeconds: Long): Boolean {
        if (bundle.identityId.isBlank() || bundle.identitySigningKey.size != 32 ||
            bundle.identityDhKey.size != 32 || bundle.signedPreKey.size != 32 ||
            bundle.signedPreKeySignature.size != 64 || bundle.signedPreKeyId < 0) return false
        if (bundle.signedPreKeyExpiresAt < nowSeconds ||
            bundle.signedPreKeyExpiresAt - nowSeconds > MAX_BUNDLE_LIFETIME_SECONDS) return false
        if ((bundle.oneTimePreKeyId == null) != (bundle.oneTimePreKey == null)) return false
        if (bundle.oneTimePreKeyId != null &&
            (bundle.oneTimePreKeyId < 0 || bundle.oneTimePreKey?.size != 32)) return false
        if (IdentityCrypto.publicId(bundle.identitySigningKey) != bundle.identityId) return false
        return IdentityCrypto.verify(
            bundle.identitySigningKey,
            bundle.signedPreKeySignature,
            signedPreKeyTranscript(bundle.identityId, bundle.signedPreKeyId, bundle.signedPreKey, bundle.signedPreKeyExpiresAt),
        )
    }

    /** Alice-side X3DH. [identityDhPrivate] is the X25519 key derived from her identity seed. */
    fun initiate(
        identityDhPrivate: ByteArray,
        peer: PreKeyBundle,
        nowSeconds: Long,
        ephemeralPrivate: ByteArray = X25519.generatePrivateKey(),
    ): Initiator {
        require(verifyBundle(peer, nowSeconds)) { "invalid, expired, or unauthenticated prekey bundle" }
        require(identityDhPrivate.size == 32 && ephemeralPrivate.size == 32)
        val ephemeralPublic = X25519.publicFromPrivate(ephemeralPrivate)
        val dh1 = X25519.computeSharedSecret(identityDhPrivate, peer.signedPreKey)
        val dh2 = X25519.computeSharedSecret(ephemeralPrivate, peer.identityDhKey)
        val dh3 = X25519.computeSharedSecret(ephemeralPrivate, peer.signedPreKey)
        val dh4 = peer.oneTimePreKey?.let { X25519.computeSharedSecret(ephemeralPrivate, it) }
        return Initiator(
            derive(dh1, dh2, dh3, dh4), ephemeralPrivate.copyOf(), ephemeralPublic,
            peer.signedPreKeyId, peer.oneTimePreKeyId,
        )
    }

    /** Bob-side X3DH. The caller must remove the one-time key in the same DB transaction as session creation. */
    fun respond(
        identityDhPrivate: ByteArray,
        signedPreKeyPrivate: ByteArray,
        ephemeralPublic: ByteArray,
        aliceIdentityDhPublic: ByteArray,
        oneTimePreKeyPrivate: ByteArray? = null,
        consumedOneTimePreKeyId: Long? = null,
    ): Responder {
        require(identityDhPrivate.size == 32 && signedPreKeyPrivate.size == 32)
        require(ephemeralPublic.size == 32 && aliceIdentityDhPublic.size == 32)
        require((oneTimePreKeyPrivate == null) == (consumedOneTimePreKeyId == null))
        require(oneTimePreKeyPrivate == null || oneTimePreKeyPrivate.size == 32)
        val dh1 = X25519.computeSharedSecret(signedPreKeyPrivate, aliceIdentityDhPublic)
        val dh2 = X25519.computeSharedSecret(identityDhPrivate, ephemeralPublic)
        val dh3 = X25519.computeSharedSecret(signedPreKeyPrivate, ephemeralPublic)
        val dh4 = oneTimePreKeyPrivate?.let { X25519.computeSharedSecret(it, ephemeralPublic) }
        return Responder(derive(dh1, dh2, dh3, dh4), consumedOneTimePreKeyId)
    }

    private fun derive(dh1: ByteArray, dh2: ByteArray, dh3: ByteArray, dh4: ByteArray?): ByteArray {
        val material = ByteArray(32) { 0xFF.toByte() } + dh1 + dh2 + dh3 + (dh4 ?: ByteArray(0))
        return Hkdf.computeHkdf("HmacSha256", material, ByteArray(32), INFO, 32)
    }

    private fun signedPreKeyTranscript(identityId: String, id: Long, publicKey: ByteArray, expiresAt: Long): ByteArray {
        require(identityId.isNotBlank() && id >= 0 && publicKey.size == 32 && expiresAt > 0)
        val identity = identityId.toByteArray(Charsets.UTF_8)
        return "relyra-x3dh-signed-prekey-v1".toByteArray(Charsets.UTF_8) +
            byteArrayOf(0) + ByteBuffer.allocate(4).putInt(identity.size).array() + identity +
            ByteBuffer.allocate(8).putLong(id).array() + publicKey +
            ByteBuffer.allocate(8).putLong(expiresAt).array()
    }
}
