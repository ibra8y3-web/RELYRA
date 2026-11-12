package org.relyra.app.core.backup

import org.relyra.app.core.crypto.KeystoreVault
import org.relyra.app.core.database.AppDatabase
import org.relyra.app.core.storage.SecureStore
import org.relyra.app.core.transfer.FileCrypto
import java.security.MessageDigest
import java.util.UUID
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import android.content.Context
import android.content.SharedPreferences
import java.util.Properties

/**
 * Encrypted database backup. The database snapshot is checkpointed and copied while a Room transaction
 * prevents concurrent writers. Restoring is staged and applied before Room is opened on the next launch.
 *
 * The encrypted archive contains the SQLCipher database, private attachment/Vault/MLS ciphertext, wrapped
 * identity/database secrets, and portable app/server preferences. Ratchet and one-time prekey state is deliberately
 * reset during restore so an old snapshot can never roll a secure session backwards.
 */
class BackupManager(
    private val db: AppDatabase,
    private val secure: SecureStore,
    private val dbFile: File,
    private val stageDir: File,
    private val filesDir: File,
    private val preferenceStores: Map<String, SharedPreferences>,
) {
    private val backupRoots = mapOf(
        "vault" to File(filesDir, "vault"),
        "inbox" to File(filesDir, "inbox"),
        "outbox" to File(filesDir, "outbox"),
        "mls" to File(filesDir, "mls"),
    )

    private fun encodePreferences(): Map<String, Map<String, String>> = preferenceStores.mapValues { (_, prefs) ->
        prefs.all.mapNotNull { (key, value) ->
            val encoded = when (value) {
                is String -> "s:$value"
                is Boolean -> "b:$value"
                is Int -> "i:$value"
                is Long -> "l:$value"
                is Float -> "f:$value"
                else -> null
            }
            encoded?.let { key to it }
        }.toMap()
    }

    private fun writePendingPreferences(values: Map<String, Map<String, String>>) {
        val dir = File(stageDir, "pending-prefs")
        dir.deleteRecursively()
        check(dir.mkdirs() || dir.isDirectory)
        values.forEach { (name, entries) ->
            val props = Properties()
            entries.forEach { (key, value) -> props.setProperty(key, value) }
            FileOutputStream(File(dir, "$name.properties")).use { out -> props.store(out, "RELYRA restore preferences"); out.fd.sync() }
        }
    }
    fun export(out: OutputStream, passphrase: CharArray): Long {
        require(BackupCrypto.passphraseAcceptable(passphrase)) { "passphrase too short" }
        check(dbFile.isFile && dbFile.length() > 0L) { "database file is missing or empty" }
        check(stageDir.mkdirs() || stageDir.isDirectory) { "could not create backup staging directory" }
        val snapshot = File(stageDir, "export.tmp")
        val bundle = File(stageDir, "export.bundle")
        check(snapshot.delete() || !snapshot.exists()) { "could not clear old backup snapshot" }
        check(bundle.delete() || !bundle.exists()) { "could not clear old backup bundle" }
        val sdb = db.openHelper.writableDatabase
        var version = 0
        try {
            // A successful checkpoint is required; a busy WAL must not produce a partial snapshot.
            sdb.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
                if (!cursor.moveToFirst() || cursor.getInt(0) != 0) {
                    throw java.io.IOException("database checkpoint is busy; retry the backup")
                }
            }
            sdb.beginTransaction()
            try {
                validateReferencedFiles(sdb)
                FileInputStream(dbFile).use { input ->
                    FileOutputStream(snapshot).use { fileOut ->
                        input.copyTo(fileOut, 64 * 1024)
                        fileOut.fd.sync()
                    }
                }
                version = sdb.version
                BackupArchive.pack(snapshot, backupRoots, encodePreferences(), bundle)
                // Recheck references after copying: a file deleted or moved during export invalidates the snapshot.
                validateReferencedFiles(sdb)
            } finally {
                sdb.endTransaction()
            }
            val seed = secure.loadSeed()
            val key = secure.databaseKey()
            BackupCrypto.seal(out, passphrase).use { sealed -> BackupPayload.write(sealed, seed, key, version, bundle) }
            return bundle.length()
        } finally {
            snapshot.delete()
            bundle.delete()
            File(stageDir, "verify-vault").deleteRecursively()
        }
    }


    /** Database rows and their app-private files must describe the same restorable snapshot. */
    private fun validateReferencedFiles(sdb: androidx.sqlite.db.SupportSQLiteDatabase) {
        val inboxRoot = File(filesDir, "inbox").canonicalFile.toPath()
        val outboxRoot = File(filesDir, "outbox").canonicalFile.toPath()
        val vaultRoot = File(filesDir, "vault").canonicalFile.toPath()

        fun verifyAttachment(pathValue: String, plainSize: Long, plainHash: ByteArray, blobSize: Long, blobHash: ByteArray) {
            val file = File(pathValue).canonicalFile
            val path = file.toPath()
            if (!file.isFile) throw java.io.IOException("an attachment referenced by the database is missing")
            when {
                path.startsWith(inboxRoot) -> {
                    if (file.length() != plainSize || !MessageDigest.isEqual(FileCrypto.sha256(file), plainHash)) {
                        throw java.io.IOException("a downloaded attachment failed its size or SHA-256 check")
                    }
                }
                path.startsWith(outboxRoot) -> {
                    if (file.length() != blobSize || !MessageDigest.isEqual(FileCrypto.sha256(file), blobHash)) {
                        throw java.io.IOException("an outgoing encrypted attachment failed its size or SHA-256 check")
                    }
                }
                else -> throw java.io.IOException("an attachment path is outside RELYRA private storage")
            }
        }

        sdb.query("SELECT localPath, size, plainSha256, blobSize, blobSha256 FROM attachments WHERE localPath IS NOT NULL").use { cursor ->
            while (cursor.moveToNext()) {
                verifyAttachment(cursor.getString(0), cursor.getLong(1), cursor.getBlob(2), cursor.getLong(3), cursor.getBlob(4))
            }
        }
        sdb.query("SELECT localPath, size, plainSha256, blobSize, blobSha256 FROM group_attachments WHERE localPath IS NOT NULL").use { cursor ->
            while (cursor.moveToNext()) {
                verifyAttachment(cursor.getString(0), cursor.getLong(1), cursor.getBlob(2), cursor.getLong(3), cursor.getBlob(4))
            }
        }
        sdb.query("SELECT COUNT(*) FROM chat_groups WHERE mlsEnabled = 1").use { cursor ->
            if (cursor.moveToFirst() && cursor.getLong(0) > 0L &&
                !File(filesDir, "mls/groups.db").isFile) {
                throw java.io.IOException("MLS group state is missing; refusing an incomplete backup")
            }
        }
        sdb.query("SELECT id, size, plainSha256, fileKey FROM vault_items").use { cursor ->
            while (cursor.moveToNext()) {
                val blob = File(File(filesDir, "vault"), cursor.getString(0) + ".enc").canonicalFile
                if (!blob.isFile || !blob.toPath().startsWith(vaultRoot)) {
                    throw java.io.IOException("a Vault item referenced by the database is missing")
                }
                val verifyDir = File(stageDir, "verify-vault")
                check(verifyDir.mkdirs() || verifyDir.isDirectory) { "could not create Vault verification directory" }
                val temp = File(verifyDir, "${UUID.randomUUID()}.tmp")
                try {
                    if (!FileCrypto.decrypt(blob, temp, cursor.getBlob(3), cursor.getBlob(2), cursor.getLong(1))) {
                        throw java.io.IOException("a Vault item failed its authentication or SHA-256 check")
                    }
                } finally {
                    temp.delete()
                }
            }
        }
    }

    /** Fully authenticates and stages the backup. The live database is never modified here. */
    fun stageRestore(input: InputStream, passphrase: CharArray) {
        check(stageDir.mkdirs() || stageDir.isDirectory) { "could not create restore staging directory" }
        RestoreApplier.clear(stageDir)
        val part = File(stageDir, RestoreApplier.DB_PART)
        val bundle = File(stageDir, "restore.bundle")
        val restoredFiles = File(stageDir, "restored-files")
        try {
            val restored = input.use { source -> BackupCrypto.open(source, passphrase).use { BackupPayload.read(it, bundle) } }
            restoredFiles.deleteRecursively()
            val signature = ByteArray(2)
            val isArchive = bundle.inputStream().use { it.read(signature) == 2 && signature[0] == 0x50.toByte() && signature[1] == 0x4B.toByte() }
            if (isArchive) {
                val restoredPrefs = BackupArchive.unpack(bundle, part, restoredFiles, File(stageDir, "pending-prefs"))
                writePendingPreferences(restoredPrefs)
            } else {
                // Backward compatibility: older encrypted backups stored the database bytes directly.
                if (!bundle.renameTo(part)) throw BackupException("could not stage legacy database backup")
                writePendingPreferences(emptyMap())
            }
            bundle.delete()
            if (restored.dbVersion < 1 || restored.dbVersion > db.openHelper.readableDatabase.version) {
                throw BackupException("this backup was made by a newer version of RELYRA")
            }
            if (!part.isFile || part.length() <= 0L) throw BackupException("backup database is empty")
            val prekeyDir = File(restoredFiles, "secure-prekeys")
            val prekeys = if (prekeyDir.isDirectory) prekeyDir.listFiles().orEmpty().associate { file ->
                val keyId = file.name.replaceFirst("_", ":")
                if (!keyId.matches(Regex("[a-z-]+:[0-9]+")) || !file.isFile || file.length() != 32L) {
                    throw BackupException("backup contains an invalid private prekey")
                }
                keyId to file.readBytes()
            } else emptyMap()
            if (prekeys.size > 10_000) throw BackupException("backup contains too many private prekeys")
            val raw = java.io.ByteArrayOutputStream()
            DataOutputStream(raw).use { data ->
                val seed = restored.seed ?: ByteArray(0)
                data.writeShort(seed.size)
                data.write(seed)
                data.writeShort(restored.dbKey.size)
                data.write(restored.dbKey)
                data.writeShort(prekeys.size)
                prekeys.toSortedMap().forEach { (id, value) -> data.writeUTF(id); data.writeShort(value.size); data.write(value) }
            }
            File(stageDir, RestoreApplier.KEYS).writeBytes(
                KeystoreVault.wrap(raw.toByteArray(), RestoreApplier.AAD)
            )
            if (!part.renameTo(File(stageDir, RestoreApplier.DB_READY))) {
                throw BackupException("could not stage the backup")
            }
            // Marker is written last: its presence means every staged component is complete.
            FileOutputStream(File(stageDir, RestoreApplier.MARKER)).use {
                it.write(byteArrayOf(1))
                it.fd.sync()
            }
        } catch (t: Throwable) {
            RestoreApplier.clear(stageDir)
            throw t
        }
    }

    fun hasPending(): Boolean = File(stageDir, RestoreApplier.MARKER).isFile

    fun cancelPending() = RestoreApplier.clear(stageDir)
}

/** Applies a fully verified backup before Room is opened. The marker is removed only after success. */
object RestoreApplier {
    const val DB_PART = "pending.db.part"
    const val DB_READY = "pending.db"
    const val KEYS = "pending.keys"
    const val MARKER = "pending.ready"
    private const val ROLLBACK_KEYS = "rollback.keys"
    private const val ROLLBACK_PREFS = "rollback.prefs"
    val AAD = "relyra-restore-v1".toByteArray(Charsets.UTF_8)
    private val ROLLBACK_KEYS_AAD = "relyra-restore-rollback-v1".toByteArray(Charsets.UTF_8)
    private val ROLLBACK_PREFS_AAD = "relyra-restore-prefs-v1".toByteArray(Charsets.UTF_8)

    /** Named durable boundaries used by the crash-recovery instrumentation suite. */
    internal enum class RestoreCheckpoint {
        ROLLBACK_STATE_SAVED,
        DATABASE_PRESERVED,
        JOURNALS_PRESERVED,
        CANDIDATE_SANITIZED,
        DATABASE_INSTALLED,
        KEYS_INSTALLED,
        FILES_INSTALLED,
        PREFERENCES_INSTALLED,
        COMMIT_MARKER_REMOVED,
    }

    /** Test-only process-death signal: intentionally bypasses in-process rollback. */
    internal class RestoreProcessInterrupted(val checkpoint: RestoreCheckpoint) : Error(
        "simulated process interruption at $checkpoint"
    )

    fun clear(stageDir: File) {
        listOf(DB_PART, DB_READY, KEYS, MARKER, ROLLBACK_KEYS, ROLLBACK_PREFS, ROLLBACK_KEYS + ".tmp", ROLLBACK_PREFS + ".tmp", "export.tmp", "export.bundle", "restore.bundle").forEach { File(stageDir, it).delete() }
        File(stageDir, "restored-files").deleteRecursively()
        File(stageDir, "pending-prefs").deleteRecursively()
        File(stageDir, "verify-vault").deleteRecursively()
    }

    /** Remove rollback-sensitive messaging state before the restored DB can become live. */
    private fun sanitizeRestoredDatabase(context: Context, candidate: File, key: ByteArray) {
        val restoredDb = AppDatabase.buildNamed(context, key, candidate.name)
        try {
            val sdb = restoredDb.openHelper.writableDatabase
            sdb.beginTransaction()
            try {
                sdb.execSQL("DELETE FROM ratchet_sessions")
                sdb.execSQL("DELETE FROM encrypted_packets")
                sdb.execSQL("DELETE FROM prekeys")
                sdb.execSQL(
                    "UPDATE contacts SET verified = 0, encKey = NULL, nostrKey = NULL, " +
                        "signedPreKeyId = NULL, signedPreKey = NULL, signedPreKeyExpiresAt = NULL, " +
                        "signedPreKeySignature = NULL, oneTimePreKeyId = NULL, oneTimePreKey = NULL"
                )
                sdb.execSQL(
                    "UPDATE messages SET state = 'FAILED', wirePayload = NULL, nextAttemptAt = 9223372036854775807 " +
                        "WHERE outgoing = 1 AND state IN ('QUEUED', 'SENT', 'RELAYED')"
                )
                sdb.setTransactionSuccessful()
            } finally {
                sdb.endTransaction()
            }
            sdb.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
                if (!cursor.moveToFirst() || cursor.getInt(0) != 0 ||
                    cursor.getInt(1) != cursor.getInt(2)) {
                    throw java.io.IOException("could not checkpoint restored database")
                }
            }
            // Do not switch journal_mode while Room's connection pool is open: SQLCipher
            // may still hold pooled connections, making journal_mode=DELETE fail with SQLITE_LOCKED.
            // A successful TRUNCATE checkpoint followed by closing Room is sufficient; the WAL
            // sidecar is verified and removed below after every connection has been closed.
        } finally {
            restoredDb.close()
        }
        val wal = File(candidate.parentFile, candidate.name + "-wal")
        if (wal.exists() && wal.length() > 0L) throw java.io.IOException("restored database still has a WAL file")
        wal.delete()
        File(candidate.parentFile, candidate.name + "-shm").delete()
    }

    private fun persistRollbackState(
        stageDir: File,
        secure: SecureStore,
        stores: Map<String, SharedPreferences>,
    ) {
        val keysFile = File(stageDir, ROLLBACK_KEYS)
        if (!keysFile.isFile) {
            val seed = secure.loadSeed() ?: ByteArray(0)
            val key = secure.databaseKey()
            val prekeys = secure.exportPreKeys()
            val raw = java.io.ByteArrayOutputStream()
            DataOutputStream(raw).use { data ->
                data.writeShort(seed.size); data.write(seed)
                data.writeShort(key.size); data.write(key)
                data.writeShort(prekeys.size)
                prekeys.toSortedMap().forEach { (id, value) ->
                    data.writeUTF(id); data.writeShort(value.size); data.write(value)
                }
            }
            writeAtomically(keysFile, KeystoreVault.wrap(raw.toByteArray(), ROLLBACK_KEYS_AAD))
        }
        val prefsFile = File(stageDir, ROLLBACK_PREFS)
        if (!prefsFile.isFile) {
            val props = Properties()
            stores.forEach { (storeName, prefs) ->
                prefs.all.forEach { (key, value) ->
                    val encoded = when (value) {
                        is String -> "s:$value"
                        is Boolean -> "b:$value"
                        is Int -> "i:$value"
                        is Long -> "l:$value"
                        is Float -> "f:$value"
                        is Set<*> -> "ss:" + value.filterIsInstance<String>()
                            .map { java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8)) }
                            .sorted().joinToString(",")
                        else -> throw BackupException("unsupported preference value in rollback snapshot")
                    }
                    val encodedKey = java.util.Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(key.toByteArray(Charsets.UTF_8))
                    props.setProperty("$storeName.$encodedKey", encoded)
                }
            }
            val bytes = java.io.ByteArrayOutputStream().also { props.store(it, "RELYRA restore rollback") }.toByteArray()
            writeAtomically(prefsFile, KeystoreVault.wrap(bytes, ROLLBACK_PREFS_AAD))
        }
    }

    private fun writeAtomically(file: File, bytes: ByteArray) {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.delete()
        FileOutputStream(temp).use { output -> output.write(bytes); output.fd.sync() }
        if (!temp.renameTo(file)) {
            temp.delete()
            throw java.io.IOException("could not persist restore rollback state")
        }
    }

    private fun readRollbackKeys(file: File): Triple<ByteArray?, ByteArray, Map<String, ByteArray>> {
        val decoded = KeystoreVault.unwrap(file.readBytes(), ROLLBACK_KEYS_AAD)
        val input = DataInputStream(decoded.inputStream())
        val seedSize = input.readUnsignedShort()
        if (seedSize > 128 || seedSize > input.available()) throw BackupException("invalid rollback identity key")
        val seed = ByteArray(seedSize).also { input.readFully(it) }
        val keySize = input.readUnsignedShort()
        if (keySize != 32 || input.available() < keySize) throw BackupException("invalid rollback database key")
        val key = ByteArray(keySize).also { input.readFully(it) }
        val prekeys = linkedMapOf<String, ByteArray>()
        val count = input.readUnsignedShort()
        if (count > 10_000) throw BackupException("too many rollback private prekeys")
        repeat(count) {
            val id = input.readUTF()
            val size = input.readUnsignedShort()
            if (!id.matches(Regex("[a-z-]+:[0-9]+")) || size != 32 || id in prekeys || input.available() < size) {
                throw BackupException("invalid rollback private prekey")
            }
            prekeys[id] = ByteArray(size).also { input.readFully(it) }
        }
        if (input.available() != 0) throw BackupException("invalid rollback key payload")
        return Triple(seed.takeIf { it.isNotEmpty() }, key, prekeys)
    }

    private fun readRollbackPreferences(file: File, storeNames: Set<String>): Map<String, Map<String, Any>> {
        val props = Properties()
        KeystoreVault.unwrap(file.readBytes(), ROLLBACK_PREFS_AAD).inputStream().use(props::load)
        return storeNames.associateWith { storeName ->
            val prefix = "$storeName."
            props.stringPropertyNames().filter { it.startsWith(prefix) }.associate { property ->
                val encodedKey = property.removePrefix(prefix)
                val key = try {
                    String(java.util.Base64.getUrlDecoder().decode(encodedKey), Charsets.UTF_8)
                } catch (_: IllegalArgumentException) {
                    throw BackupException("invalid rollback preference key")
                }
                val value = props.getProperty(property)
                val restored: Any = when {
                    value.startsWith("s:") -> value.substring(2)
                    value.startsWith("b:") -> value.substring(2).toBooleanStrict()
                    value.startsWith("i:") -> value.substring(2).toInt()
                    value.startsWith("l:") -> value.substring(2).toLong()
                    value.startsWith("f:") -> value.substring(2).toFloat()
                    value.startsWith("ss:") -> value.substring(3).split(',').filter { it.isNotEmpty() }
                        .map { token ->
                            try { String(java.util.Base64.getUrlDecoder().decode(token), Charsets.UTF_8) }
                            catch (_: IllegalArgumentException) { throw BackupException("invalid rollback preference set") }
                        }.toSet()
                    else -> throw BackupException("invalid rollback preference value")
                }
                key to restored
            }
        }
    }

    /**
     * Replaces the database using same-filesystem renames and keeps rollback copies until key restoration
     * succeeds. Sidecar WAL/journal files are moved aside as part of the same guarded operation.
     */
    fun applyIfPending(context: Context, stageDir: File, dbFile: File, secure: SecureStore, filesDir: File, preferenceStores: Map<String, SharedPreferences>): Boolean =
        applyIfPendingInternal(context, stageDir, dbFile, secure, filesDir, preferenceStores) { }

    internal fun applyIfPendingInternal(
        context: Context,
        stageDir: File,
        dbFile: File,
        secure: SecureStore,
        filesDir: File,
        preferenceStores: Map<String, SharedPreferences>,
        afterCheckpoint: (RestoreCheckpoint) -> Unit,
    ): Boolean {
        if (!File(stageDir, MARKER).isFile) {
            // No restore is pending: remove abandoned plaintext staging and old rollback artifacts.
            clear(stageDir)
            cleanupOldBackups(stageDir, dbFile, filesDir)
            return false
        }
        val stagedDb = File(stageDir, DB_READY)
        val keysFile = File(stageDir, KEYS)
        if (!stagedDb.isFile || stagedDb.length() <= 0L || !keysFile.isFile) {
            clear(stageDir)
            return false
        }

        val decoded = KeystoreVault.unwrap(keysFile.readBytes(), AAD)
        val d = DataInputStream(decoded.inputStream())
        val seedSize = d.readUnsignedShort()
        if (seedSize > 128 || seedSize > d.available()) throw BackupException("invalid staged identity key")
        val seed = ByteArray(seedSize).also { d.readFully(it) }
        val keySize = d.readUnsignedShort()
        if (keySize != 32 || d.available() < keySize) throw BackupException("invalid staged database key")
        val key = ByteArray(keySize).also { d.readFully(it) }
        val prekeys = linkedMapOf<String, ByteArray>()
        if (d.available() > 0) {
            val count = d.readUnsignedShort()
            if (count > 10_000) throw BackupException("too many staged private prekeys")
            repeat(count) {
                val id = d.readUTF()
                val size = d.readUnsignedShort()
                if (!id.matches(Regex("[a-z-]+:[0-9]+")) || size != 32 || id in prekeys || d.available() < size) {
                    throw BackupException("invalid staged private prekey")
                }
                prekeys[id] = ByteArray(size).also { d.readFully(it) }
            }
        }
        if (d.available() != 0) throw BackupException("invalid staged key payload")

        val parent = dbFile.parentFile ?: throw java.io.IOException("database directory is unavailable")
        if (!parent.mkdirs() && !parent.isDirectory) throw java.io.IOException("database directory is unavailable")
        val candidate = File(parent, dbFile.name + ".restore-new")
        val previous = File(parent, dbFile.name + ".restore-old")
        val suffixes = listOf("-wal", "-shm", "-journal")
        val sidecarBackups = suffixes.associateWith { File(parent, dbFile.name + it + ".restore-old") }
        candidate.delete()

        var installed = false
        var keysUpdated = false
        var restoredDirs: Map<String, File?>? = null
        // Persist rollback material before touching the live DB, keys, files, or preferences.
        // A process restart must not mistake already-restored keys for the original rollback state.
        persistRollbackState(stageDir, secure, preferenceStores)
        afterCheckpoint(RestoreCheckpoint.ROLLBACK_STATE_SAVED)
        val (previousSeed, previousKey, previousPrekeys) = readRollbackKeys(File(stageDir, ROLLBACK_KEYS))
        val previousPreferences = readRollbackPreferences(File(stageDir, ROLLBACK_PREFS), preferenceStores.keys)
        try {
            FileInputStream(stagedDb).use { input ->
                FileOutputStream(candidate).use { output ->
                    input.copyTo(output, 64 * 1024)
                    output.fd.sync()
                }
            }
            if (dbFile.exists()) {
                if (!previous.exists()) {
                    if (!dbFile.renameTo(previous)) throw java.io.IOException("could not preserve the current database")
                } else {
                    // A prior process may have installed the staged DB and crashed before clearing the marker.
                    if (!dbFile.delete()) throw java.io.IOException("could not replace partially restored database")
                }
            }
            afterCheckpoint(RestoreCheckpoint.DATABASE_PRESERVED)
            for ((suffix, backup) in sidecarBackups) {
                val sidecar = File(parent, dbFile.name + suffix)
                if (sidecar.exists()) {
                    if (!backup.exists()) {
                        if (!sidecar.renameTo(backup)) throw java.io.IOException("could not preserve database journal files")
                    } else if (!sidecar.delete()) {
                        throw java.io.IOException("could not replace partially restored database journal files")
                    }
                }
            }
            afterCheckpoint(RestoreCheckpoint.JOURNALS_PRESERVED)
            sanitizeRestoredDatabase(context, candidate, key)
            afterCheckpoint(RestoreCheckpoint.CANDIDATE_SANITIZED)
            if (!candidate.renameTo(dbFile)) throw java.io.IOException("could not install restored database")
            installed = true
            afterCheckpoint(RestoreCheckpoint.DATABASE_INSTALLED)

            // One-time prekeys from an older snapshot may already have been consumed after that snapshot.
            // Never roll them back: a fresh signed/one-time prekey bundle is generated after restore.
            prekeys.values.forEach { it.fill(0) }
            keysUpdated = true
            secure.restoreKeys(if (seed.isEmpty()) null else seed, key, emptyMap())
            afterCheckpoint(RestoreCheckpoint.KEYS_INSTALLED)
            restoredDirs = applyRestoredFiles(stageDir, filesDir)
            afterCheckpoint(RestoreCheckpoint.FILES_INSTALLED)
            applyRestoredPreferences(stageDir, preferenceStores)
            afterCheckpoint(RestoreCheckpoint.PREFERENCES_INSTALLED)
            // Removing the pending marker is the commit point. If the process dies afterwards,
            // startup cleanup removes rollback copies without trying to replay the restore.
            clear(stageDir)
            afterCheckpoint(RestoreCheckpoint.COMMIT_MARKER_REMOVED)
            restoredDirs.values.filterNotNull().forEach { it.deleteRecursively() }
            previous.delete()
            sidecarBackups.values.forEach { it.delete() }
            return true
        } catch (t: Throwable) {
            if (t is RestoreProcessInterrupted) throw t
            if (restoredDirs != null) rollbackRestoredFiles(stageDir, filesDir, restoredDirs!!)
            if (keysUpdated) runCatching { secure.restoreKeys(previousSeed, previousKey, previousPrekeys) }
            runCatching { restorePreferenceSnapshot(preferenceStores, previousPreferences) }
            if (installed) dbFile.delete()
            if (previous.exists() && !previous.renameTo(dbFile)) {
                throw java.io.IOException("restore failed and the previous database could not be recovered", t)
            }
            for ((suffix, backup) in sidecarBackups) {
                val sidecar = File(parent, dbFile.name + suffix)
                if (backup.exists() && !backup.renameTo(sidecar)) {
                    throw java.io.IOException("restore failed and database journal recovery failed", t)
                }
            }
            candidate.delete()
            throw t
        }
    }
    /** Idempotent directory swap: staged sources remain available if the process dies mid-restore. */
    private fun applyRestoredFiles(stageDir: File, filesDir: File): Map<String, File?> {
        val stagedRoot = File(stageDir, "restored-files")
        val names = listOf("vault", "inbox", "outbox", "mls")
        val old = linkedMapOf<String, File?>()
        try {
            names.forEach { name ->
                val target = File(filesDir, name)
                val backup = File(filesDir, "$name.restore-old")
                if (!backup.exists()) {
                    if (target.exists()) {
                        if (!target.renameTo(backup)) throw java.io.IOException("could not preserve existing $name data")
                    } else if (!backup.mkdirs() && !backup.isDirectory) {
                        throw java.io.IOException("could not stage previous $name directory state")
                    }
                }
                old[name] = backup
                val source = File(stagedRoot, name)
                if (source.exists()) {
                    target.deleteRecursively()
                    if (!source.renameTo(target)) throw java.io.IOException("could not install restored $name data")
                } else if (!target.exists() && !target.mkdirs()) {
                    throw java.io.IOException("could not create restored $name directory")
                }
            }
            return old
        } catch (t: Throwable) {
            rollbackRestoredFiles(stageDir, filesDir, old)
            throw t
        }
    }

    private fun applyRestoredPreferences(stageDir: File, stores: Map<String, SharedPreferences>) {
        val dir = File(stageDir, "pending-prefs")
        stores.forEach { (name, prefs) ->
            val file = File(dir, "$name.properties")
            if (!file.isFile) return@forEach
            val props = Properties().also { file.inputStream().use(it::load) }
            val editor = prefs.edit().clear()
            props.stringPropertyNames().forEach { key ->
                val encoded = props.getProperty(key)
                when {
                    encoded.startsWith("s:") -> editor.putString(key, encoded.substring(2))
                    encoded.startsWith("b:") -> editor.putBoolean(key, encoded.substring(2).toBooleanStrict())
                    encoded.startsWith("i:") -> editor.putInt(key, encoded.substring(2).toInt())
                    encoded.startsWith("l:") -> editor.putLong(key, encoded.substring(2).toLong())
                    encoded.startsWith("f:") -> editor.putFloat(key, encoded.substring(2).toFloat())
                    else -> throw BackupException("invalid backed up preference")
                }
            }
            check(editor.commit()) { "could not restore $name preferences" }
        }
    }

    private fun rollbackRestoredFiles(stageDir: File, filesDir: File, previous: Map<String, File?>) {
        previous.forEach { (name, backup) ->
            val target = File(filesDir, name)
            val staged = File(File(stageDir, "restored-files"), name)
            if (target.exists()) {
                staged.parentFile?.mkdirs()
                if (!staged.exists()) {
                    if (!target.renameTo(staged)) target.deleteRecursively()
                } else {
                    target.deleteRecursively()
                }
            }
            if (backup != null && backup.exists() && !backup.renameTo(target)) {
                throw java.io.IOException("could not roll back restored $name files")
            }
        }
    }

    private fun cleanupOldBackups(stageDir: File, dbFile: File, filesDir: File) {
        File(dbFile.parentFile, dbFile.name + ".restore-new").delete()
        File(dbFile.parentFile, dbFile.name + ".restore-old").delete()
        listOf("-wal", "-shm", "-journal").forEach {
            File(dbFile.parentFile, dbFile.name + it + ".restore-old").delete()
        }
        listOf("vault", "inbox", "outbox", "mls").forEach {
            File(filesDir, "$it.restore-old").deleteRecursively()
        }
    }

    private fun restorePreferenceSnapshot(
        stores: Map<String, SharedPreferences>,
        snapshot: Map<String, Map<String, *>>,
    ) {
        stores.forEach { (name, prefs) ->
            val editor = prefs.edit().clear()
            snapshot[name].orEmpty().forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            check(editor.commit()) { "could not roll back $name preferences" }
        }
    }

}
