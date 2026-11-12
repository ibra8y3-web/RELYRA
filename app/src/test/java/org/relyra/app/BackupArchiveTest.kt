package org.relyra.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.relyra.app.core.backup.BackupArchive
import org.relyra.app.core.backup.BackupException
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.zip.ZipInputStream

class BackupArchiveTest {
    @Test fun archiveRoundTripIncludesAttachmentVaultAndPreferences() {
        val root = java.nio.file.Files.createTempDirectory("relyra-archive").toFile()
        try {
            val db = File(root, "source.db").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
            val sourceFiles = File(root, "source-files")
            File(sourceFiles, "inbox/message/file.bin").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(9, 8, 7)) }
            File(sourceFiles, "vault/item.enc").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(6, 5, 4, 3)) }
            File(sourceFiles, "outbox/pending.enc").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(2, 1)) }
            File(sourceFiles, "mls/groups.db").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(4, 2, 4, 2)) }
            val bundle = File(root, "bundle.zip")
            BackupArchive.pack(
                db,
                mapOf("inbox" to File(sourceFiles, "inbox"), "vault" to File(sourceFiles, "vault"), "outbox" to File(sourceFiles, "outbox"), "mls" to File(sourceFiles, "mls")),
                mapOf("relyra_settings" to mapOf("theme_mode" to "i:2", "notifications_on" to "b:true")),
                bundle,
            )
            val restoredDb = File(root, "restored.db")
            val restoredFiles = File(root, "restored-files")
            val prefs = BackupArchive.unpack(bundle, restoredDb, restoredFiles, File(root, "prefs"))
            assertArrayEquals(db.readBytes(), restoredDb.readBytes())
            assertArrayEquals(File(sourceFiles, "inbox/message/file.bin").readBytes(), File(restoredFiles, "inbox/message/file.bin").readBytes())
            assertArrayEquals(File(sourceFiles, "vault/item.enc").readBytes(), File(restoredFiles, "vault/item.enc").readBytes())
            assertArrayEquals(File(sourceFiles, "outbox/pending.enc").readBytes(), File(restoredFiles, "outbox/pending.enc").readBytes())
            assertArrayEquals(File(sourceFiles, "mls/groups.db").readBytes(), File(restoredFiles, "mls/groups.db").readBytes())
            assertEquals("i:2", prefs["relyra_settings"]?.get("theme_mode"))
            assertEquals("b:true", prefs["relyra_settings"]?.get("notifications_on"))
        } finally {
            root.deleteRecursively()
        }
    }


    @Test fun archiveRejectsChangedFileAgainstManifest() {
        val root = java.nio.file.Files.createTempDirectory("relyra-archive-hash").toFile()
        try {
            val db = File(root, "source.db").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val vault = File(root, "vault/item.enc").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(4, 5, 6)) }
            val original = File(root, "original.zip")
            BackupArchive.pack(db, mapOf("vault" to File(root, "vault")), emptyMap(), original)
            val tampered = File(root, "tampered.zip")
            ZipInputStream(original.inputStream()).use { input ->
                ZipOutputStream(tampered.outputStream()).use { output ->
                    while (true) {
                        val entry = input.nextEntry ?: break
                        output.putNextEntry(ZipEntry(entry.name))
                        val bytes = input.readBytes()
                        output.write(if (entry.name == "files/vault/item.enc") bytes.map { (it.toInt() xor 1).toByte() }.toByteArray() else bytes)
                        output.closeEntry()
                        input.closeEntry()
                    }
                }
            }
            try {
                BackupArchive.unpack(tampered, File(root, "restored.db"), File(root, "restored-files"), File(root, "prefs"))
                fail("expected BackupException")
            } catch (_: BackupException) {
                assertTrue(vault.exists())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun archiveRejectsPathTraversal() {
        val root = java.nio.file.Files.createTempDirectory("relyra-archive-slip").toFile()
        try {
            val bundle = File(root, "bad.zip")
            ZipOutputStream(FileOutputStream(bundle)).use { zip ->
                zip.putNextEntry(ZipEntry("database.db"))
                zip.write(byteArrayOf(1))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("files/vault/../../escape"))
                zip.write(byteArrayOf(7))
                zip.closeEntry()
            }
            try {
                BackupArchive.unpack(bundle, File(root, "db"), File(root, "files"), File(root, "prefs"))
                fail("expected BackupException")
            } catch (_: BackupException) {
                assertTrue(!File(root, "escape").exists())
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
