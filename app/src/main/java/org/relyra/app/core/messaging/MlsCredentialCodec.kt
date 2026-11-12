package org.relyra.app.core.messaging

import org.relyra.app.core.crypto.IdentityCrypto
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

data class MlsIdentityCredential(
    val relyraPublicId: String,
    val deviceId: String,
    val signature: ByteArray,
)

/**
 * RELYRA credential carried inside the OpenMLS BasicCredential.
 * The account's existing Ed25519 identity signs both the public account ID and
 * a per-installation device ID; the MLS KeyPackage signature alone is not enough
 * to establish a RELYRA identity.
 */
object MlsCredentialCodec {
    private const val MAGIC = 0x524D4C43 // "RMLC"
    private const val VERSION = 1
    private const val SIGNATURE_BYTES = 64
    private const val MAX_CREDENTIAL_BYTES = 512

    fun signingPayload(relyraPublicId: String, deviceId: String): ByteArray {
        require(IdentityCrypto.parsePublicId(relyraPublicId) != null) { "Invalid RELYRA public ID" }
        require(isDeviceId(deviceId)) { "Invalid RELYRA device ID" }
        return listOf(
            "RELYRA-MLS-CREDENTIAL-V1",
            relyraPublicId.lowercase(),
            deviceId.lowercase(),
        ).joinToString("\n").toByteArray(Charsets.UTF_8)
    }

    fun encode(relyraPublicId: String, deviceId: String, signature: ByteArray): ByteArray {
        val canonical = signingPayload(relyraPublicId, deviceId)
        require(signature.size == SIGNATURE_BYTES)
        require(IdentityCrypto.verify(
            IdentityCrypto.parsePublicId(relyraPublicId)!!,
            signature,
            canonical,
        )) { "RELYRA MLS credential signature is invalid" }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeInt(MAGIC)
            output.writeByte(VERSION)
            output.writeUTF(relyraPublicId.lowercase())
            output.writeUTF(deviceId.lowercase())
            output.write(signature)
        }
        return bytes.toByteArray().also { require(it.size <= MAX_CREDENTIAL_BYTES) }
    }

    fun decode(encoded: ByteArray): MlsIdentityCredential? {
        if (encoded.isEmpty() || encoded.size > MAX_CREDENTIAL_BYTES) return null
        return try {
            DataInputStream(ByteArrayInputStream(encoded)).use { input ->
                if (input.readInt() != MAGIC || input.readUnsignedByte() != VERSION) return null
                val publicId = input.readUTF()
                val deviceId = input.readUTF()
                val signature = ByteArray(SIGNATURE_BYTES).also { input.readFully(it) }
                if (input.available() != 0) return null
                signingPayload(publicId, deviceId)
                MlsIdentityCredential(publicId, deviceId, signature)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun verify(encoded: ByteArray, expectedPublicId: String): Boolean {
        val credential = decode(encoded) ?: return false
        if (!credential.relyraPublicId.equals(expectedPublicId, ignoreCase = true)) return false
        val publicKey = IdentityCrypto.parsePublicId(credential.relyraPublicId) ?: return false
        val payload = runCatching {
            signingPayload(credential.relyraPublicId, credential.deviceId)
        }.getOrNull() ?: return false
        return IdentityCrypto.verify(publicKey, credential.signature, payload)
    }

    private fun isDeviceId(value: String): Boolean =
        runCatching { UUID.fromString(value).toString().equals(value, ignoreCase = true) }.getOrDefault(false)
}
