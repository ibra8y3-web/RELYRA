package org.relyra.app

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.relyra.app.core.backup.BackupManager
import org.relyra.app.core.backup.RestoreApplier
import org.relyra.app.core.database.*
import org.relyra.app.core.storage.SecureStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BackupRestoreSqlCipherIntegrationTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun everyHistoricalMigrationExecutesAgainstAnEncryptedSqlCipherDatabase() {
        val name = "migration-${UUID.randomUUID()}.db"
        val key = ByteArray(32) { (it + 11).toByte() }
        val helper = SupportOpenHelperFactory(key).create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE identity (slot INTEGER NOT NULL PRIMARY KEY, displayName TEXT NOT NULL, publicId TEXT NOT NULL, publicKey BLOB NOT NULL, createdAt INTEGER NOT NULL)")
                        db.execSQL("CREATE TABLE contacts (publicId TEXT NOT NULL PRIMARY KEY, displayName TEXT NOT NULL, publicKey BLOB NOT NULL, verified INTEGER NOT NULL, blocked INTEGER NOT NULL, cardSigned INTEGER NOT NULL, addedAt INTEGER NOT NULL)")
                        db.execSQL("CREATE TABLE security_events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, timestamp INTEGER NOT NULL, type TEXT NOT NULL, detail TEXT NOT NULL)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                        error("The migration test must start from a version-one database")
                    }
                })
                .build()
        )
        try {
            val db = helper.writableDatabase
            val migrations = listOf(
                AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8, AppDatabase.MIGRATION_8_9,
                AppDatabase.MIGRATION_9_10, AppDatabase.MIGRATION_10_11,
                AppDatabase.MIGRATION_11_12, AppDatabase.MIGRATION_12_13,
                AppDatabase.MIGRATION_13_14, AppDatabase.MIGRATION_14_15,
                AppDatabase.MIGRATION_15_16,
            )
            migrations.forEachIndexed { index, migration ->
                db.beginTransaction()
                try {
                    migration.migrate(db)
                    db.execSQL("PRAGMA user_version = ${index + 2}")
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
            assertEquals(16, db.version)
            val tables = mutableSetOf<String>()
            db.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
                while (cursor.moveToNext()) tables += cursor.getString(0)
            }
            assertTrue(tables.containsAll(setOf(
                "identity", "contacts", "security_events", "messages", "processed_ids",
                "attachments", "ratchet_sessions", "chat_groups", "group_members",
                "group_messages", "group_outbox", "chat_prefs", "vault_items", "prekeys",
                "encrypted_packets", "group_attachments", "communities",
            )))
            val contactColumns = mutableSetOf<String>()
            db.query("PRAGMA table_info(contacts)").use { cursor ->
                while (cursor.moveToNext()) contactColumns += cursor.getString(1)
            }
            assertTrue(contactColumns.containsAll(setOf(
                "encKey", "nostrKey", "signedPreKeyId", "signedPreKey",
                "signedPreKeyExpiresAt", "signedPreKeySignature", "oneTimePreKeyId", "oneTimePreKey",
            )))
            val groupColumns = mutableSetOf<String>()
            db.query("PRAGMA table_info(chat_groups)").use { cursor ->
                while (cursor.moveToNext()) groupColumns += cursor.getString(1)
            }
            assertTrue(groupColumns.containsAll(setOf("mlsEnabled", "mlsEpoch")))
            val communityIndexes = mutableSetOf<String>()
            db.query("PRAGMA index_list(communities)").use { cursor ->
                while (cursor.moveToNext()) communityIndexes += cursor.getString(1)
            }
            assertTrue("index_communities_updatedAt" in communityIndexes)
        } finally {
            helper.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun encryptedBackupRestoreRestoresFilesAndPreferencesAndSanitizesRatchetState() = runBlocking {
        val id = UUID.randomUUID().toString()
        val databaseName = "restore-$id.db"
        val dbFile = context.getDatabasePath(databaseName)
        val filesRoot = File(context.filesDir, "restore-$id")
        val stageDir = File(context.cacheDir, "restore-stage-$id")
        val prefs = context.getSharedPreferences("restore-test-$id", Context.MODE_PRIVATE)
        val secure = SecureStore(context)
        val databaseKey = secure.databaseKey()
        secure.saveSeed(ByteArray(32) { (it + 1).toByte() })
        val db = AppDatabase.buildNamed(context, databaseKey, databaseName)
        val vaultFile = File(filesRoot, "vault/kept.enc").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 3, 3, 7))
        }
        File(filesRoot, "inbox/download.bin").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(8, 6, 7, 5)) }
        File(filesRoot, "outbox/pending.enc").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(2, 4, 6, 8)) }
        File(filesRoot, "mls/groups.db").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(9, 9, 1)) }

        try {
            db.openHelper.writableDatabase
            db.identityDao().put(IdentityEntity(1, "Original", "identity-original", ByteArray(32) { 4 }, 100L))
            db.contactDao().insert(ContactEntity(
                "peer-original", "Original peer", ByteArray(32) { 5 }, true, false, true, 101L,
                encKey = ByteArray(32) { 6 }, nostrKey = ByteArray(32) { 7 },
            ))
            db.messageDao().insert(MessageEntity(
                "message-original", "peer-original", true, "queued before backup", 102L,
                "QUEUED", 2, 103L, false, byteArrayOf(11, 12, 13),
            ))
            db.ratchetDao().put(RatchetEntity("peer-original", byteArrayOf(21, 22)))
            db.encryptedPacketDao().put(EncryptedPacketEntity("message-original", "peer-original", byteArrayOf(31, 32)))
            db.preKeyDao().put(PreKeyEntity("one-time:7", "ONE_TIME", ByteArray(32) { 8 }, 999L, false))
            prefs.edit().putString("relay_url", "wss://relay.example").putBoolean("enabled", true).commit()

            val manager = BackupManager(db, secure, dbFile, stageDir, filesRoot, mapOf("relyra_settings" to prefs))
            val passphrase = "integration-test-passphrase".toCharArray()
            val archive = ByteArrayOutputStream()
            manager.export(archive, passphrase)

            db.contactDao().insert(ContactEntity(
                "peer-after-backup", "Must disappear", ByteArray(32) { 9 }, true, false, true, 200L,
            ))
            vaultFile.writeBytes(byteArrayOf(0, 0, 0))
            prefs.edit().putString("relay_url", "wss://changed.example").putBoolean("enabled", false).commit()

            manager.stageRestore(ByteArrayInputStream(archive.toByteArray()), passphrase)
            assertTrue(manager.hasPending())
            db.close()

            assertTrue(RestoreApplier.applyIfPending(
                context, stageDir, dbFile, secure, filesRoot, mapOf("relyra_settings" to prefs),
            ))
            assertFalse(File(stageDir, RestoreApplier.MARKER).exists())

            val restored = AppDatabase.buildNamed(context, secure.databaseKey(), databaseName)
            try {
                assertEquals("Original", restored.identityDao().get()?.displayName)
                val contact = restored.contactDao().get("peer-original")
                assertEquals(false, contact?.verified)
                assertNull(contact?.encKey)
                assertNull(contact?.nostrKey)
                assertNull(restored.contactDao().get("peer-after-backup"))
                assertNull(restored.ratchetDao().get("peer-original"))
                assertNull(restored.encryptedPacketDao().get("message-original"))
                assertTrue(restored.preKeyDao().byKind("ONE_TIME").isEmpty())
                val outgoing = restored.messageDao().get("message-original")
                assertEquals("FAILED", outgoing?.state)
                assertNull(outgoing?.wirePayload)
                assertEquals("wss://relay.example", prefs.getString("relay_url", null))
                assertTrue(prefs.getBoolean("enabled", false))
                assertArrayEquals(byteArrayOf(1, 3, 3, 7), File(filesRoot, "vault/kept.enc").readBytes())
                assertArrayEquals(byteArrayOf(8, 6, 7, 5), File(filesRoot, "inbox/download.bin").readBytes())
                assertArrayEquals(byteArrayOf(2, 4, 6, 8), File(filesRoot, "outbox/pending.enc").readBytes())
                assertArrayEquals(byteArrayOf(9, 9, 1), File(filesRoot, "mls/groups.db").readBytes())
            } finally {
                restored.close()
            }
        } finally {
            runCatching { db.close() }
            runCatching { AppDatabase.buildNamed(context, secure.databaseKey(), databaseName).close() }
            stageDir.deleteRecursively()
            filesRoot.deleteRecursively()
            prefs.edit().clear().commit()
            context.deleteDatabase(databaseName)
            secure.clearSeed()
        }
    }
    @Test
    fun interruptedRestoreAtEveryDurableBoundaryResumesWithoutLosingTheSnapshot() = runBlocking {
        val secure = SecureStore(context)
        val passphrase = "crash-recovery-integration-passphrase".toCharArray()
        for (checkpoint in RestoreApplier.RestoreCheckpoint.values()) {
            val id = UUID.randomUUID().toString()
            val databaseName = "restore-crash-$id.db"
            val dbFile = context.getDatabasePath(databaseName)
            val filesRoot = File(context.filesDir, "restore-crash-$id")
            val stageDir = File(context.cacheDir, "restore-crash-stage-$id")
            val prefs = context.getSharedPreferences("restore-crash-prefs-$id", Context.MODE_PRIVATE)
            val dbKey = secure.databaseKey()
            secure.saveSeed(ByteArray(32) { (it + checkpoint.ordinal).toByte() })
            val db = AppDatabase.buildNamed(context, dbKey, databaseName)
            try {
                db.openHelper.writableDatabase
                db.identityDao().put(IdentityEntity(
                    1, "Snapshot ${checkpoint.name}", "identity-${checkpoint.name.lowercase()}",
                    ByteArray(32) { 3 }, 300L,
                ))
                listOf("vault", "inbox", "outbox", "mls").forEach { File(filesRoot, it).mkdirs() }
                File(filesRoot, "vault/marker.enc").writeBytes(byteArrayOf(4, 5, 6, checkpoint.ordinal.toByte()))
                File(filesRoot, "inbox/marker.bin").writeBytes(byteArrayOf(7, 8, 9))
                prefs.edit().putString("checkpoint", checkpoint.name).commit()

                val manager = BackupManager(
                    db, secure, dbFile, stageDir, filesRoot, mapOf("relyra_settings" to prefs),
                )
                val archive = ByteArrayOutputStream()
                manager.export(archive, passphrase)
                manager.stageRestore(ByteArrayInputStream(archive.toByteArray()), passphrase)
                db.close()

                try {
                    RestoreApplier.applyIfPendingInternal(
                        context, stageDir, dbFile, secure, filesRoot, mapOf("relyra_settings" to prefs),
                    ) { reached ->
                        if (reached == checkpoint) throw RestoreApplier.RestoreProcessInterrupted(reached)
                    }
                    fail("expected simulated process interruption at $checkpoint")
                } catch (interrupted: RestoreApplier.RestoreProcessInterrupted) {
                    assertEquals(checkpoint, interrupted.checkpoint)
                }

                val wasCommitted = checkpoint == RestoreApplier.RestoreCheckpoint.COMMIT_MARKER_REMOVED
                assertEquals(!wasCommitted, RestoreApplier.applyIfPending(
                    context, stageDir, dbFile, secure, filesRoot, mapOf("relyra_settings" to prefs),
                ))
                val restored = AppDatabase.buildNamed(context, secure.databaseKey(), databaseName)
                try {
                    assertEquals("Snapshot ${checkpoint.name}", restored.identityDao().get()?.displayName)
                    assertEquals(checkpoint.name, prefs.getString("checkpoint", null))
                    assertArrayEquals(
                        byteArrayOf(4, 5, 6, checkpoint.ordinal.toByte()),
                        File(filesRoot, "vault/marker.enc").readBytes(),
                    )
                    assertArrayEquals(byteArrayOf(7, 8, 9), File(filesRoot, "inbox/marker.bin").readBytes())
                } finally {
                    restored.close()
                }
            } finally {
                runCatching { db.close() }
                stageDir.deleteRecursively()
                filesRoot.deleteRecursively()
                prefs.edit().clear().commit()
                context.deleteDatabase(databaseName)
                secure.clearSeed()
            }
        }
    }

}
