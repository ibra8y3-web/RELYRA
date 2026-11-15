package org.relyra.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.relyra.app.core.backup.BackupCrypto
import org.relyra.app.core.backup.BackupException
import org.relyra.app.core.backup.BackupPayload
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.io.IOException
import java.util.Random

class BackupCryptoTest {
    private val pass = "correct horse battery".toCharArray()
    private val fast = 100_000
    private val headerLen = 6 + 4 + 16 + 8
    private val segLen = 4 + BackupCrypto.SEGMENT + 16

    private fun data(n: Int) = ByteArray(n).also { Random(42).nextBytes(it) }

    private fun seal(plain: ByteArray, p: CharArray = pass): ByteArray {
        val out = ByteArrayOutputStream()
        BackupCrypto.seal(out, p, fast).use { it.write(plain) }
        return out.toByteArray()
    }

    private fun open(blob: ByteArray, p: CharArray = pass): ByteArray =
        BackupCrypto.open(ByteArrayInputStream(blob), p).use { it.readBytes() }

    private fun assertRejected(blob: ByteArray, p: CharArray = pass) {
        try {
            open(blob, p)
            fail("expected BackupException")
        } catch (e: BackupException) {
            // expected
        }
    }

    @Test fun roundTripSmall() {
        val plain = data(1000)
        assertArrayEquals(plain, open(seal(plain)))
    }

    @Test fun roundTripEmpty() {
        assertArrayEquals(ByteArray(0), open(seal(ByteArray(0))))
    }

    @Test fun roundTripSeveralSegments() {
        val plain = data(BackupCrypto.SEGMENT * 2 + 12345)
        assertArrayEquals(plain, open(seal(plain)))
    }

    @Test fun roundTripExactSegmentBoundary() {
        val plain = data(BackupCrypto.SEGMENT * 2)
        assertArrayEquals(plain, open(seal(plain)))
    }

    @Test fun twoBackupsOfTheSameDataDiffer() {
        val plain = data(500)
        assertTrue(!seal(plain).contentEquals(seal(plain)))
    }

    @Test fun wrongPassphraseIsRejected() {
        assertRejected(seal(data(2000)), "another long passphrase".toCharArray())
    }

    @Test fun flippedBitIsRejected() {
        val blob = seal(data(5000))
        blob[headerLen + 4 + 100] = (blob[headerLen + 4 + 100].toInt() xor 1).toByte()
        assertRejected(blob)
    }

    @Test fun tamperedHeaderIsRejected() {
        val blob = seal(data(5000))
        blob[headerLen - 1] = (blob[headerLen - 1].toInt() xor 1).toByte()
        assertRejected(blob)
    }

    @Test fun cutAtSegmentBoundaryIsRejected() {
        val blob = seal(data(BackupCrypto.SEGMENT * 2 + 10))
        assertRejected(blob.copyOf(headerLen + segLen * 2))
    }

    @Test fun cutInTheMiddleIsRejected() {
        val blob = seal(data(BackupCrypto.SEGMENT + 10))
        assertRejected(blob.copyOf(blob.size - 5))
    }

    @Test fun onlyHeaderIsRejected() {
        assertRejected(seal(data(10)).copyOf(headerLen))
    }

    @Test fun appendedDataIsRejected() {
        val blob = seal(data(3000))
        assertRejected(blob + ByteArray(40) { 7 })
    }

    @Test fun swappedSegmentsAreRejected() {
        val blob = seal(data(BackupCrypto.SEGMENT * 2 + 10))
        val a = blob.copyOfRange(headerLen, headerLen + segLen)
        val b = blob.copyOfRange(headerLen + segLen, headerLen + segLen * 2)
        val swapped = blob.copyOf()
        System.arraycopy(b, 0, swapped, headerLen, segLen)
        System.arraycopy(a, 0, swapped, headerLen + segLen, segLen)
        assertRejected(swapped)
    }

    @Test fun notABackupIsRejected() {
        assertRejected(data(200))
        assertRejected(ByteArray(0))
        assertRejected("RLYBK1".toByteArray())
    }

    @Test fun absurdIterationCountIsRejected() {
        val blob = seal(data(10))
        blob[6] = 0x7F // iterations field becomes huge
        assertRejected(blob)
    }

    @Test fun shortPassphraseIsRefused() {
        try {
            BackupCrypto.seal(ByteArrayOutputStream(), "short".toCharArray(), fast)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }


    @Test fun outputStorageFailureIsPropagatedInsteadOfReturningAValidBackup() {
        val failing = object : OutputStream() {
            private var written = 0
            override fun write(value: Int) {
                if (written++ > 80) throw IOException("disk full")
            }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (written + length > 80) throw IOException("disk full")
                written += length
            }
        }
        try {
            BackupCrypto.seal(failing, pass, fast).use { it.write(ByteArray(1000) { 7 }) }
            fail("expected storage failure")
        } catch (e: IOException) {
            assertEquals("disk full", e.message)
        }
    }

    @Test fun payloadRoundTrip() {
        val dir = java.nio.file.Files.createTempDirectory("relyra-bk").toFile()
        try {
            val db = File(dir, "src.db").also { it.writeBytes(data(BackupCrypto.SEGMENT + 777)) }
            val seed = ByteArray(32) { it.toByte() }
            val key = ByteArray(32) { (it * 3).toByte() }
            val out = ByteArrayOutputStream()
            BackupCrypto.seal(out, pass, fast).use { BackupPayload.write(it, seed, key, 7, db) }

            val restoredDb = File(dir, "restored.db")
            val r = BackupCrypto.open(ByteArrayInputStream(out.toByteArray()), pass).use { BackupPayload.read(it, restoredDb) }
            assertArrayEquals(seed, r.seed)
            assertArrayEquals(key, r.dbKey)
            assertEquals(7, r.dbVersion)
            assertArrayEquals(db.readBytes(), restoredDb.readBytes())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun payloadWithoutIdentityKeepsSeedEmpty() {
        val dir = java.nio.file.Files.createTempDirectory("relyra-bk").toFile()
        try {
            val db = File(dir, "src.db").also { it.writeBytes(data(100)) }
            val out = ByteArrayOutputStream()
            BackupCrypto.seal(out, pass, fast).use { BackupPayload.write(it, null, ByteArray(32), 1, db) }
            val r = BackupCrypto.open(ByteArrayInputStream(out.toByteArray()), pass).use { BackupPayload.read(it, File(dir, "r.db")) }
            assertNull(r.seed)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun payloadWithTrailingBytesIsRejected() {
        val dir = java.nio.file.Files.createTempDirectory("relyra-bk").toFile()
        try {
            val db = File(dir, "src.db").also { it.writeBytes(data(100)) }
            val out = ByteArrayOutputStream()
            BackupCrypto.seal(out, pass, fast).use {
                BackupPayload.write(it, null, ByteArray(32), 1, db)
                it.write(byteArrayOf(1, 2, 3))
            }
            try {
                BackupCrypto.open(ByteArrayInputStream(out.toByteArray()), pass).use { BackupPayload.read(it, File(dir, "r.db")) }
                fail("expected BackupException")
            } catch (e: BackupException) {
                // expected
            }
        } finally {
            dir.deleteRecursively()
        }
    }
}
