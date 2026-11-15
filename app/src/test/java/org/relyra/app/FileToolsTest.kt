package org.relyra.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.relyra.app.core.tools.FileTools
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class FileToolsTest {
    private fun zipOf(vararg files: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((n, d) in files) { z.putNextEntry(ZipEntry(n)); z.write(d); z.closeEntry() }
        }
        return bos.toByteArray()
    }

    @Test fun knownDigestsOfAbc() {
        val d = FileTools.digests(ByteArrayInputStream("abc".toByteArray()))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", d.md5)
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", d.sha1)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", d.sha256)
        assertEquals(3L, d.size)
    }

    @Test fun digestOfEmptyInput() {
        val d = FileTools.digests(ByteArrayInputStream(ByteArray(0)))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", d.sha256)
        assertEquals(0L, d.size)
    }

    @Test fun hexDumpLayout() {
        val dump = FileTools.hexDump("Hello, RELYRA!\u0000\u0001x".toByteArray(Charsets.ISO_8859_1))
        val lines = dump.trimEnd().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("00000000  48 65 6c 6c 6f 2c 20 52  45 4c 59 52 41 21 00 01"))
        assertTrue(lines[0].endsWith("|Hello, RELYRA!..|"))
        assertTrue(lines[1].startsWith("00000010  78"))
        assertTrue(lines[1].endsWith("|x|"))
    }

    @Test fun hexDumpHonoursBaseOffset() {
        assertTrue(FileTools.hexDump(byteArrayOf(1, 2), 0x100).startsWith("00000100  01 02"))
    }

    @Test fun stringsFindsRunsOfPrintableText() {
        val data = byteArrayOf(0, 1, 2) + "hello".toByteArray() + byteArrayOf(0) + "ab".toByteArray() + byteArrayOf(0) + "world!".toByteArray()
        assertEquals(listOf("hello", "world!"), FileTools.strings(data))
        assertEquals(listOf("hello", "ab", "world!"), FileTools.strings(data, minLength = 2))
    }

    @Test fun stringsSplitsVeryLongRuns() {
        val data = ByteArray(700) { 'a'.code.toByte() }
        val out = FileTools.strings(data, minLength = 4, maxLength = 300)
        assertEquals(listOf(300, 300, 100), out.map { it.length })
    }

    @Test fun sniffsCommonTypesByContentNotName() {
        assertEquals("PNG image", FileTools.sniffType(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0)))
        assertEquals("JPEG image", FileTools.sniffType(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertEquals("PDF document", FileTools.sniffType("%PDF-1.7".toByteArray()))
        assertEquals("ZIP container (also APK, JAR, DOCX, XLSX)", FileTools.sniffType(zipOf("a" to byteArrayOf(1))))
        assertEquals("SQLite database", FileTools.sniffType("SQLite format 3\u0000".toByteArray()))
        val webp = "RIFF".toByteArray() + byteArrayOf(0, 0, 0, 0) + "WEBPVP8 ".toByteArray()
        assertEquals("WebP image", FileTools.sniffType(webp))
        val mp4 = byteArrayOf(0, 0, 0, 0x18) + "ftypisom".toByteArray()
        assertEquals("MP4 / QuickTime media", FileTools.sniffType(mp4))
        assertEquals("Text", FileTools.sniffType("just some words\nand a new line".toByteArray()))
        assertNull(FileTools.sniffType(byteArrayOf(0, 1, 2, 3, 0, 0, 9, 9)))
        assertNull(FileTools.sniffType(ByteArray(0)))
    }

    @Test fun textDetection() {
        assertTrue(FileTools.isProbablyText("مرحبا بالعالم".toByteArray(Charsets.UTF_8)))
        assertFalse(FileTools.isProbablyText(byteArrayOf(65, 66, 0, 67)))
    }

    @Test fun prettyJsonOnlyForValidJson() {
        assertEquals("{\n  \"a\": 1\n}", FileTools.prettyJson("{\"a\":1}"))
        assertNotNull(FileTools.prettyJson("[1,2,3]"))
        assertNull(FileTools.prettyJson("{broken"))
        assertNull(FileTools.prettyJson("plain text"))
    }

    @Test fun zipListingShowsNamesAndSizes() {
        val z = zipOf("a.txt" to "hello".toByteArray(), "dir/b.bin" to ByteArray(1000))
        val l = FileTools.listZip(ByteArrayInputStream(z))
        assertEquals(listOf("a.txt", "dir/b.bin"), l.items.map { it.name })
        assertEquals(listOf(5L, 1000L), l.items.map { it.size })
        assertFalse(l.truncated)
    }

    @Test fun zipListingStopsAtTheEntryLimit() {
        val z = zipOf("1" to byteArrayOf(1), "2" to byteArrayOf(1), "3" to byteArrayOf(1))
        val l = FileTools.listZip(ByteArrayInputStream(z), maxEntries = 2)
        assertEquals(2, l.items.size)
        assertTrue(l.truncated)
    }

    @Test fun zipEntryNamesAreCleanedForDisplay() {
        assertEquals("../etc/passwd", FileTools.cleanName("../etc/passwd"))
        assertEquals("ab", FileTools.cleanName("a\u0000\nb"))
    }

    @Test fun copiesOneZipEntry() {
        val z = zipOf("a.txt" to "hello".toByteArray(), "b.txt" to "world".toByteArray())
        val out = ByteArrayOutputStream()
        assertEquals(5L, FileTools.copyZipEntry(ByteArrayInputStream(z), "b.txt", out, 1000))
        assertEquals("world", out.toString("UTF-8"))
        assertEquals(-1L, FileTools.copyZipEntry(ByteArrayInputStream(z), "missing", ByteArrayOutputStream(), 1000))
    }

    @Test fun copyingAnOversizedEntryIsRefused() {
        val z = zipOf("big.bin" to ByteArray(10_000))
        try {
            FileTools.copyZipEntry(ByteArrayInputStream(z), "big.bin", ByteArrayOutputStream(), 100)
            fail("expected an IOException")
        } catch (e: IOException) {
            // expected
        }
    }
}

class FileToolsSkipTest {
    @Test fun skipsExactlyAndStopsAtTheEnd() {
        val data = ByteArray(100) { it.toByte() }
        val input = ByteArrayInputStream(data)
        assertEquals(40L, FileTools.skipFully(input, 40))
        assertEquals(40, input.read())
        assertEquals(59L, FileTools.skipFully(input, 500))
        assertEquals(-1, input.read())
    }
}
