package org.relyra.app.core.backup

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Anything wrong with a backup file: wrong passphrase, damaged, truncated, from a newer app version. */
class BackupException(message: String) : IOException(message)

/**
 * Passphrase-protected container. Nothing home-made: PBKDF2-HMAC-SHA256 (JDK) derives an AES-256 key, and the data is
 * sealed in 1 MiB AES-256-GCM segments (JDK). Every segment is bound to the header, to its position and to a "last
 * segment" flag, so reordering, cutting the file short, appending data and flipping bits are all detected.
 *
 * Layout: MAGIC(6) | iterations(4) | salt(16) | noncePrefix(8) | { length(4) | ciphertext }...
 */
object BackupCrypto {
    const val SEGMENT = 1 shl 20
    const val MIN_PASSPHRASE = 10
    const val DEFAULT_ITERATIONS = 310_000
    private const val MIN_ITERATIONS = 100_000
    private const val MAX_ITERATIONS = 5_000_000
    private const val SALT_LEN = 16
    private const val PREFIX_LEN = 8
    private const val TAG_LEN = 16
    private val MAGIC = "RLYBK1".toByteArray(Charsets.US_ASCII)
    private val HEADER_LEN = MAGIC.size + 4 + SALT_LEN + PREFIX_LEN

    fun passphraseAcceptable(p: CharArray): Boolean = p.size >= MIN_PASSPHRASE

    /** Wraps [out]. Closing the returned stream writes the final segment and closes [out]. */
    fun seal(out: OutputStream, passphrase: CharArray, iterations: Int = DEFAULT_ITERATIONS): OutputStream {
        require(passphraseAcceptable(passphrase)) { "passphrase too short" }
        require(iterations in MIN_ITERATIONS..MAX_ITERATIONS)
        val rnd = SecureRandom()
        val salt = ByteArray(SALT_LEN).also { rnd.nextBytes(it) }
        val prefix = ByteArray(PREFIX_LEN).also { rnd.nextBytes(it) }
        val header = ByteBuffer.allocate(HEADER_LEN)
            .put(MAGIC).putInt(iterations).put(salt).put(prefix).array()
        out.write(header)
        return SealingStream(out, deriveKey(passphrase, salt, iterations), header, prefix)
    }

    /** Reads the header of [input] and returns a stream of the decrypted bytes. Throws [BackupException] on any problem. */
    fun open(input: InputStream, passphrase: CharArray): InputStream {
        val header = ByteArray(HEADER_LEN)
        readFully(input, header, header.size) ?: throw BackupException("not a RELYRA backup")
        val buf = ByteBuffer.wrap(header)
        val magic = ByteArray(MAGIC.size).also { buf.get(it) }
        if (!magic.contentEquals(MAGIC)) throw BackupException("not a RELYRA backup")
        val iterations = buf.getInt()
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) throw BackupException("unsupported backup parameters")
        val salt = ByteArray(SALT_LEN).also { buf.get(it) }
        val prefix = ByteArray(PREFIX_LEN).also { buf.get(it) }
        return OpeningStream(input, deriveKey(passphrase, salt, iterations), header, prefix)
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        try {
            val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(raw, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun nonce(prefix: ByteArray, counter: Long): ByteArray =
        ByteBuffer.allocate(12).put(prefix).putInt(counter.toInt()).array()

    private fun aad(header: ByteArray, counter: Long, last: Boolean): ByteArray =
        ByteBuffer.allocate(header.size + 9).put(header).putLong(counter).put(if (last) 1 else 0).array()

    private fun cipher(mode: Int, key: SecretKeySpec, prefix: ByteArray, header: ByteArray, counter: Long, last: Boolean): Cipher {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(mode, key, GCMParameterSpec(TAG_LEN * 8, nonce(prefix, counter)))
        c.updateAAD(aad(header, counter, last))
        return c
    }

    /** Reads exactly [n] bytes. Returns null when the stream ends before the first byte, throws when it ends midway. */
    private fun readFully(input: InputStream, b: ByteArray, n: Int): Unit? {
        var got = 0
        while (got < n) {
            val r = input.read(b, got, n - got)
            if (r < 0) {
                if (got == 0) return null
                throw BackupException("backup file is cut short")
            }
            got += r
        }
        return Unit
    }

    private class SealingStream(
        private val out: OutputStream,
        private val key: SecretKeySpec,
        private val header: ByteArray,
        private val prefix: ByteArray,
    ) : OutputStream() {
        private val buf = ByteArray(SEGMENT)
        private var fill = 0
        private var counter = 0L
        private var closed = false

        override fun write(b: Int) { write(byteArrayOf(b.toByte()), 0, 1) }

        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!closed) { "stream closed" }
            var o = off
            var left = len
            while (left > 0) {
                if (fill == SEGMENT) flush(false)
                val n = minOf(left, SEGMENT - fill)
                System.arraycopy(b, o, buf, fill, n)
                fill += n; o += n; left -= n
            }
        }

        private fun flush(last: Boolean) {
            val ct = cipher(Cipher.ENCRYPT_MODE, key, prefix, header, counter, last).doFinal(buf, 0, fill)
            out.write(ByteBuffer.allocate(4).putInt(ct.size).array())
            out.write(ct)
            counter += 1
            fill = 0
        }

        override fun close() {
            if (closed) return
            closed = true
            flush(true)
            out.flush()
            out.close()
        }
    }

    private class OpeningStream(
        private val input: InputStream,
        private val key: SecretKeySpec,
        private val header: ByteArray,
        private val prefix: ByteArray,
    ) : InputStream() {
        private var cur = ByteArray(0)
        private var pos = 0
        private var counter = 0L
        private var finished = false
        private var nextLen = readLen()

        private fun readLen(): Int {
            val b = ByteArray(4)
            readFully(input, b, 4) ?: return -1
            val len = ByteBuffer.wrap(b).getInt()
            if (len < TAG_LEN || len > SEGMENT + TAG_LEN) throw BackupException("backup file is damaged")
            return len
        }

        private fun nextSegment(): Boolean {
            if (finished) return false
            if (nextLen < 0) throw BackupException("backup file is cut short")
            val ct = ByteArray(nextLen)
            readFully(input, ct, ct.size) ?: throw BackupException("backup file is cut short")
            val after = readLen()
            val last = after < 0
            cur = try {
                cipher(Cipher.DECRYPT_MODE, key, prefix, header, counter, last).doFinal(ct)
            } catch (e: GeneralSecurityException) {
                throw BackupException("wrong passphrase, or the backup is damaged")
            }
            pos = 0
            counter += 1
            nextLen = after
            if (last) finished = true
            return true
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= cur.size) {
                if (!nextSegment()) return -1
            }
            val n = minOf(len, cur.size - pos)
            System.arraycopy(cur, pos, b, off, n)
            pos += n
            return n
        }

        override fun close() { input.close() }
    }
}

/** What goes inside the sealed container: the two secrets that unlock the database, then the database file itself. */
object BackupPayload {
    private const val VERSION = 1
    private const val MAX_SECRET = 128
    private const val MAX_DB_BYTES = 4L * 1024 * 1024 * 1024

    class Restored(val seed: ByteArray?, val dbKey: ByteArray, val dbVersion: Int)

    fun write(out: OutputStream, seed: ByteArray?, dbKey: ByteArray, dbVersion: Int, dbFile: File) {
        val d = DataOutputStream(out)
        val s = seed ?: ByteArray(0)
        d.writeByte(VERSION)
        d.writeInt(dbVersion)
        d.writeShort(s.size); d.write(s)
        d.writeShort(dbKey.size); d.write(dbKey)
        val size = dbFile.length()
        d.writeLong(size)
        var copied = 0L
        dbFile.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                copied += n
                d.write(buf, 0, n)
            }
        }
        if (copied != size) throw IOException("database changed while it was being copied")
        d.flush()
    }

    /** Writes the database into [dbOut] and returns the secrets. Reads to the end, which verifies the final segment too. */
    fun read(input: InputStream, dbOut: File): Restored {
        val d = DataInputStream(input)
        try {
            if (d.readUnsignedByte() != VERSION) throw BackupException("unsupported backup version")
            val dbVersion = d.readInt()
            val seedLen = d.readUnsignedShort()
            if (seedLen > MAX_SECRET) throw BackupException("backup file is damaged")
            val seed = ByteArray(seedLen).also { d.readFully(it) }
            val keyLen = d.readUnsignedShort()
            if (keyLen != 32) throw BackupException("backup file is damaged")
            val dbKey = ByteArray(keyLen).also { d.readFully(it) }
            val size = d.readLong()
            if (size <= 0 || size > MAX_DB_BYTES) throw BackupException("backup file is damaged")
            var left = size
            dbOut.outputStream().buffered().use { fout ->
                val buf = ByteArray(64 * 1024)
                while (left > 0) {
                    val n = d.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) throw BackupException("backup file is cut short")
                    fout.write(buf, 0, n)
                    left -= n
                }
            }
            if (d.read() != -1) throw BackupException("backup file has unexpected extra data")
            return Restored(if (seed.isEmpty()) null else seed, dbKey, dbVersion)
        } catch (e: EOFException) {
            throw BackupException("backup file is cut short")
        }
    }
}
