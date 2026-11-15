package org.relyra.app.core.network

import org.relyra.app.core.crypto.IdentityCrypto
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException

/**
 * Authenticated framing shared by LAN, Bluetooth and Wi-Fi Direct.
 *
 * Both peers prove possession of the RELYRA Ed25519 identity seed before a payload is accepted.
 * Payload bytes remain opaque end-to-end ciphertext; this layer never decrypts application messages.
 */
object NearbyHandshakeProtocol {
    const val MAX_FRAME_BYTES = 256 * 1024
    private const val SIGNATURE_BYTES = 64
    private const val NONCE_BYTES = 32
    private const val ACK = 1
    private const val REJECT = 0
    private const val DOMAIN = "RELYRA-NEARBY-HANDSHAKE-V1"

    fun isValidFrameLength(length: Int): Boolean = length in 0..MAX_FRAME_BYTES

    suspend fun receive(
        inputStream: InputStream,
        outputStream: OutputStream,
        ownPublicId: String,
        sign: (ByteArray) -> ByteArray?,
        knownPeer: suspend (String) -> Boolean,
        onWire: suspend (ByteArray) -> Boolean,
    ): String? {
        val input = DataInputStream(inputStream)
        val output = DataOutputStream(outputStream)
        return try {
            val remoteId = input.readUTF().lowercase()
            val remoteKey = IdentityCrypto.parsePublicId(remoteId)
            val clientNonce = ByteArray(NONCE_BYTES).also { input.readFully(it) }
            if (remoteKey == null || remoteId == ownPublicId || !knownPeer(remoteId)) {
                output.writeUTF("")
                output.flush()
                return null
            }

            val serverNonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
            val transcript = transcript(remoteId, ownPublicId, clientNonce, serverNonce)
            val serverSignature = sign(transcript) ?: return null
            if (serverSignature.size != SIGNATURE_BYTES) return null

            output.writeUTF(ownPublicId)
            output.write(serverNonce)
            output.write(serverSignature)
            output.flush()

            val clientSignature = ByteArray(SIGNATURE_BYTES).also { input.readFully(it) }
            if (!IdentityCrypto.verify(remoteKey, clientSignature, transcript)) return null

            val length = input.readInt()
            if (!isValidFrameLength(length)) {
                output.writeByte(REJECT)
                output.flush()
                return null
            }

            val accepted = if (length == 0) {
                true
            } else {
                val frame = ByteArray(length)
                input.readFully(frame)
                try {
                    onWire(frame)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
            }
            output.writeByte(if (accepted) ACK else REJECT)
            output.flush()
            if (accepted) remoteId else null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    suspend fun send(
        inputStream: InputStream,
        outputStream: OutputStream,
        ownPublicId: String,
        expectedPeerId: String?,
        sign: (ByteArray) -> ByteArray?,
        knownPeer: suspend (String) -> Boolean,
        payload: ByteArray,
    ): String? {
        if (!isValidFrameLength(payload.size)) return null
        val input = DataInputStream(inputStream)
        val output = DataOutputStream(outputStream)
        return try {
            val clientNonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
            output.writeUTF(ownPublicId)
            output.write(clientNonce)
            output.flush()

            val remoteId = input.readUTF().lowercase()
            val remoteKey = IdentityCrypto.parsePublicId(remoteId)
            if (remoteKey == null || remoteId == ownPublicId ||
                (expectedPeerId != null && remoteId != expectedPeerId.lowercase()) ||
                !knownPeer(remoteId)
            ) return null

            val serverNonce = ByteArray(NONCE_BYTES).also { input.readFully(it) }
            val serverSignature = ByteArray(SIGNATURE_BYTES).also { input.readFully(it) }
            val transcript = transcript(ownPublicId, remoteId, clientNonce, serverNonce)
            if (!IdentityCrypto.verify(remoteKey, serverSignature, transcript)) return null

            val clientSignature = sign(transcript) ?: return null
            if (clientSignature.size != SIGNATURE_BYTES) return null
            output.write(clientSignature)
            output.writeInt(payload.size)
            if (payload.isNotEmpty()) output.write(payload)
            output.flush()

            if (input.readUnsignedByte() == ACK) remoteId else null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private fun transcript(
        clientId: String,
        serverId: String,
        clientNonce: ByteArray,
        serverNonce: ByteArray,
    ): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeUTF(DOMAIN)
            output.writeUTF(clientId)
            output.writeUTF(serverId)
            output.write(clientNonce)
            output.write(serverNonce)
        }
        bytes.toByteArray()
    }
}
