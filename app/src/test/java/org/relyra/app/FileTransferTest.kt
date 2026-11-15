package org.relyra.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.relyra.app.core.transfer.BlossomUrl
import org.relyra.app.core.transfer.FileCrypto
import org.relyra.app.core.transfer.FileNames
import org.relyra.app.core.transfer.FileTooLargeException
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlin.random.Random

class FileCryptoTest {
    private fun tmp(): File = File.createTempFile("relyra", ".bin").also { it.deleteOnExit() }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)
    private val max = 100L * 1024 * 1024

    @Test fun roundTripAcrossSeveralSegments() {
        val data = Random(1).nextBytes(2_600_000)          // 3 segments of 1 MiB
        val key = FileCrypto.newKey()
        val blob = tmp()
        val enc = FileCrypto.encrypt(ByteArrayInputStream(data), blob, key, max)
        assertEquals(data.size.toLong(), enc.plainSize)
        assertArrayEquals(sha(data), enc.plainSha256)
        assertArrayEquals(sha(blob.readBytes()), enc.blobSha256)
        assertEquals(blob.length(), enc.blobSize)
        assertTrue(enc.blobSize > enc.plainSize)
        assertTrue(enc.blobSize <= enc.plainSize + enc.plainSize / 1000 + 1024)

        val out = tmp()
        assertTrue(FileCrypto.decrypt(blob, out, key, enc.plainSha256, enc.plainSize))
        assertArrayEquals(data, out.readBytes())
    }

    @Test fun ciphertextDoesNotContainThePlaintext() {
        val marker = "SECRET-MARKER-SECRET-MARKER".toByteArray()
        val data = marker + ByteArray(5000)
        val blob = tmp()
        FileCrypto.encrypt(ByteArrayInputStream(data), blob, FileCrypto.newKey(), max)
        val text = String(blob.readBytes(), Charsets.ISO_8859_1)
        assertFalse(text.contains("SECRET-MARKER"))
    }

    @Test fun tamperingWrongKeyTruncationAndWrongSizeAreAllRejected() {
        val data = Random(2).nextBytes(2_200_000)
        val key = FileCrypto.newKey()
        val blob = tmp()
        val enc = FileCrypto.encrypt(ByteArrayInputStream(data), blob, key, max)

        val flipped = tmp().also { blob.copyTo(it, overwrite = true) }
        RandomAccessFile(flipped, "rw").use { f ->
            f.seek(1_500_000)
            val b = f.read()
            f.seek(1_500_000)
            f.write(b xor 1)
        }
        assertFalse(FileCrypto.decrypt(flipped, tmp(), key, enc.plainSha256, enc.plainSize))

        assertFalse(FileCrypto.decrypt(blob, tmp(), FileCrypto.newKey(), enc.plainSha256, enc.plainSize))

        val truncated = tmp().also { it.writeBytes(blob.readBytes().copyOf((blob.length() - 100).toInt())) }
        assertFalse(FileCrypto.decrypt(truncated, tmp(), key, enc.plainSha256, enc.plainSize))

        assertFalse(FileCrypto.decrypt(blob, tmp(), key, enc.plainSha256, enc.plainSize - 1))
        assertFalse(FileCrypto.decrypt(blob, tmp(), key, ByteArray(32), enc.plainSize))
    }

    @Test fun oversizedInputIsRefused() {
        try {
            FileCrypto.encrypt(ByteArrayInputStream(ByteArray(1000)), tmp(), FileCrypto.newKey(), 999)
            fail("expected FileTooLargeException")
        } catch (e: FileTooLargeException) {
            // expected
        }
    }

    @Test fun emptyInputReportsZeroSize() {
        val enc = FileCrypto.encrypt(ByteArrayInputStream(ByteArray(0)), tmp(), FileCrypto.newKey(), max)
        assertEquals(0L, enc.plainSize)
    }
}

class FileNamesTest {
    @Test fun pathTricksAreNeutralised() {
        assertEquals("_etc_passwd", FileNames.sanitize("../etc/passwd"))
        assertFalse(FileNames.sanitize("../../etc/passwd").contains('/'))
        assertFalse(FileNames.sanitize("a\\b:c*d?.txt").any { it == '\\' || it == ':' || it == '*' || it == '?' })
        assertEquals("file", FileNames.sanitize(".."))
        assertEquals("file", FileNames.sanitize("   "))
        assertTrue(FileNames.sanitize("a\u0000b\u0001c").none { it.isISOControl() })
    }

    @Test fun arabicNamesSurviveAndLongNamesAreCut() {
        assertEquals("تقرير.pdf", FileNames.sanitize("تقرير.pdf"))
        assertTrue(FileNames.sanitize("x".repeat(500)).length <= 120)
    }

    @Test fun mimeTypesAreValidated() {
        assertEquals("application/pdf", FileNames.sanitizeMime("application/pdf"))
        assertEquals("application/octet-stream", FileNames.sanitizeMime("not a mime"))
        assertEquals("application/octet-stream", FileNames.sanitizeMime("a/b/c"))
        assertEquals("application/octet-stream", FileNames.sanitizeMime(""))
    }
}

class BlossomUrlTest {
    @Test fun httpsOnlyAndCanonical() {
        assertEquals("https://files.example.org", BlossomUrl.normalize("Files.Example.org/", allowPrivate = false))
        assertEquals("https://files.example.org:8443/blossom", BlossomUrl.normalize("https://files.example.org:8443/blossom/", allowPrivate = false))
        assertNull(BlossomUrl.normalize("http://files.example.org", allowPrivate = false))
        assertNull(BlossomUrl.normalize("ftp://files.example.org", allowPrivate = false))
        assertNull(BlossomUrl.normalize("https://user:pw@files.example.org", allowPrivate = false))
        assertNull(BlossomUrl.normalize("https://files.example.org/?x=1", allowPrivate = false))
        assertNull(BlossomUrl.normalize("https://localhost", allowPrivate = true))
        assertNull(BlossomUrl.normalize("not a url", allowPrivate = true))
    }

    @Test fun addressesFromOtherPeopleCannotPointAtPrivateNetworks() {
        for (u in listOf("https://127.0.0.1", "https://10.1.2.3", "https://192.168.0.1", "https://172.16.5.5",
            "https://172.31.255.255", "https://169.254.1.1", "https://100.64.0.1", "https://printer.local", "https://nas.lan")) {
            assertNull(u, BlossomUrl.normalize(u, allowPrivate = false))
        }
        assertEquals("https://172.32.0.1", BlossomUrl.normalize("https://172.32.0.1", allowPrivate = false))
    }

    @Test fun usersMayAddTheirOwnLanServer() {
        assertEquals("https://192.168.1.20:3000", BlossomUrl.normalize("https://192.168.1.20:3000", allowPrivate = true))
        assertEquals("https://nas.local", BlossomUrl.normalize("https://nas.local", allowPrivate = true))
    }
}
