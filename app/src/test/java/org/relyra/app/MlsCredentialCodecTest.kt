package org.relyra.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.relyra.app.core.crypto.IdentityCrypto
import org.relyra.app.core.messaging.MlsCredentialCodec
import java.util.UUID

class MlsCredentialCodecTest {
    @Test
    fun credentialIsSignedByTheExistingRelyraIdentityAndBoundToOneDevice() {
        val identity = IdentityCrypto.generate()
        val publicId = IdentityCrypto.publicId(identity.publicKey)
        val deviceId = UUID.randomUUID().toString()
        val payload = MlsCredentialCodec.signingPayload(publicId, deviceId)
        val encoded = MlsCredentialCodec.encode(
            publicId,
            deviceId,
            IdentityCrypto.sign(identity.seed, payload),
        )

        assertTrue(MlsCredentialCodec.verify(encoded, publicId))
        assertFalse(MlsCredentialCodec.verify(encoded, IdentityCrypto.publicId(IdentityCrypto.generate().publicKey)))
        assertNotNull(MlsCredentialCodec.decode(encoded))
    }

    @Test
    fun credentialRejectsSignatureTamperingAndTrailingBytes() {
        val identity = IdentityCrypto.generate()
        val publicId = IdentityCrypto.publicId(identity.publicKey)
        val deviceId = UUID.randomUUID().toString()
        val signature = IdentityCrypto.sign(
            identity.seed,
            MlsCredentialCodec.signingPayload(publicId, deviceId),
        )
        val encoded = MlsCredentialCodec.encode(publicId, deviceId, signature)

        val tampered = encoded.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertFalse(MlsCredentialCodec.verify(tampered, publicId))
        assertFalse(MlsCredentialCodec.verify(encoded + byteArrayOf(0), publicId))
        assertFalse(MlsCredentialCodec.verify(encoded.copyOf(encoded.size - 1), publicId))
    }

    @Test
    fun credentialCannotBeReusedForAnotherInstallation() {
        val identity = IdentityCrypto.generate()
        val publicId = IdentityCrypto.publicId(identity.publicKey)
        val firstDevice = UUID.randomUUID().toString()
        val secondDevice = UUID.randomUUID().toString()
        val firstSignature = IdentityCrypto.sign(
            identity.seed,
            MlsCredentialCodec.signingPayload(publicId, firstDevice),
        )
        val encoded = MlsCredentialCodec.encode(publicId, firstDevice, firstSignature)

        assertTrue(MlsCredentialCodec.verify(encoded, publicId))
        val substitutedPayload = MlsCredentialCodec.signingPayload(publicId, secondDevice)
        assertFalse(IdentityCrypto.verify(identity.publicKey, firstSignature, substitutedPayload))
    }
}
