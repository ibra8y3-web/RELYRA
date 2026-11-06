package org.relyra.app.core.transfer

import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.DigestOutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom

class FileTooLargeException : IOException("file is larger than the allowed maximum")

/**
 * File encryption with Tink's AES-GCM-HKDF streaming construction (1 MiB segments, each authenticated; reordering,
 * truncation and tampering are detected). One fresh random key per file. Nothing home-made.
 */
object FileCrypto {
    const val SEGMENT_BYTES = 1 shl 20
    private val AAD = "relyra-file-v1".toByteArray(Charsets.UTF_8)

    class Encrypted(val plainSha256: ByteArray, val plainSize: Long, val blobSha256: ByteArray, val blobSize: Long)

    fun newKey(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    private fun aead(key: ByteArray) = AesGcmHkdfStreaming(key, "HmacSha256", 32, SEGMENT_BYTES, 0)

    /** Encrypts [input] into [output]. Throws [FileTooLargeException] past [maxPlain] bytes. */
    fun encrypt(input: InputStream, output: File, key: ByteArray, maxPlain: Long): Encrypted {
        val plainDigest = MessageDigest.getInstance("SHA-256")
        val blobDigest = MessageDigest.getInstance("SHA-256")
        var plainSize = 0L
        output.outputStream().buffered().use { fileOut ->
            val digestOut = DigestOutputStream(fileOut, blobDigest)
            aead(key).newEncryptingStream(digestOut, AAD).use { enc ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    plainSize += n
                    if (plainSize > maxPlain) throw FileTooLargeException()
                    plainDigest.update(buf, 0, n)
                    enc.write(buf, 0, n)
                }
            }
        }
        return Encrypted(plainDigest.digest(), plainSize, blobDigest.digest(), output.length())
    }

    /** True only if every segment authenticates and the result has exactly the expected size and SHA-256. */
    fun decrypt(blob: File, output: File, key: ByteArray, expectedPlainSha256: ByteArray, expectedSize: Long): Boolean {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            var tooBig = false
            blob.inputStream().buffered().use { fin ->
                aead(key).newDecryptingStream(fin, AAD).use { dec ->
                    output.outputStream().buffered().use { fout ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = dec.read(buf)
                            if (n < 0) break
                            size += n
                            if (size > expectedSize) { tooBig = true; break }
                            digest.update(buf, 0, n)
                            fout.write(buf, 0, n)
                        }
                    }
                }
            }
            !tooBig && size == expectedSize && MessageDigest.isEqual(digest.digest(), expectedPlainSha256)
        } catch (e: IOException) {
            false
        } catch (e: GeneralSecurityException) {
            false
        }
    }

    fun sha256(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest()
    }
}
