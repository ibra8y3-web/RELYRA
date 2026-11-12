package org.relyra.app.core.backup

import org.relyra.app.core.crypto.KeystoreVault
import org.relyra.app.core.database.AppDatabase
import org.relyra.app.core.storage.SecureStore
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Encrypted database backup. The database snapshot is checkpointed and copied while a Room transaction
 * prevents concurrent writers. Restoring is staged and applied before Room is opened on the next launch.
 *
 * Note: version 1 payloads contain the SQLCipher database and identity/database keys only. External
 * attachment and vault ciphertext is not silently represented as backed up by this format.
 */
class BackupManager(
    private val db: AppDatabase,
    private val secure: SecureStore,
    private val dbFile: File,
    private val stageDir: File,
) {
    fun export(out: OutputStream, passphrase: CharArray): Long {
        require(BackupCrypto.passphraseAcceptable(passphrase)) { "passphrase too short" }
        check(dbFile.isFile && dbFile.length() > 0L) { "database file is missing or empty" }
        check(stageDir.mkdirs() || stageDir.isDirectory) { "could not create backup staging directory" }
        val snapshot = File(stageDir, "export.tmp")
        check(snapshot.delete() || !snapshot.exists()) { "could not clear old backup snapshot" }
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
                FileInputStream(dbFile).use { input ->
                    FileOutputStream(snapshot).use { fileOut ->
                        input.copyTo(fileOut, 64 * 1024)
                        fileOut.fd.sync()
                    }
                }
                version = sdb.version
            } finally {
                sdb.endTransaction()
            }
            val seed = secure.loadSeed()
            val key = secure.databaseKey()
            BackupCrypto.seal(out, passphrase).use { sealed ->
                BackupPayload.write(sealed, seed, key, version, snapshot)
            }
            return snapshot.length()
        } finally {
            snapshot.delete()
        }
    }

    /** Fully authenticates and stages the backup. The live database is never modified here. */
    fun stageRestore(input: InputStream, passphrase: CharArray) {
        check(stageDir.mkdirs() || stageDir.isDirectory) { "could not create restore staging directory" }
        RestoreApplier.clear(stageDir)
        val part = File(stageDir, RestoreApplier.DB_PART)
        try {
            val restored = input.use { source ->
                BackupCrypto.open(source, passphrase).use { BackupPayload.read(it, part) }
            }
            if (restored.dbVersion < 1 || restored.dbVersion > db.openHelper.readableDatabase.version) {
                throw BackupException("this backup was made by a newer version of RELYRA")
            }
            if (!part.isFile || part.length() <= 0L) throw BackupException("backup database is empty")
            val raw = java.io.ByteArrayOutputStream()
            DataOutputStream(raw).use { data ->
                val seed = restored.seed ?: ByteArray(0)
                data.writeShort(seed.size)
                data.write(seed)
                data.writeShort(restored.dbKey.size)
                data.write(restored.dbKey)
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
    val AAD = "relyra-restore-v1".toByteArray(Charsets.UTF_8)

    fun clear(stageDir: File) {
        listOf(DB_PART, DB_READY, KEYS, MARKER, "export.tmp").forEach { File(stageDir, it).delete() }
    }

    /**
     * Replaces the database using same-filesystem renames and keeps rollback copies until key restoration
     * succeeds. Sidecar WAL/journal files are moved aside as part of the same guarded operation.
     */
    fun applyIfPending(stageDir: File, dbFile: File, secure: SecureStore): Boolean {
        if (!File(stageDir, MARKER).isFile) return false
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
        if (keySize != 32 || d.available() != keySize) throw BackupException("invalid staged database key")
        val key = ByteArray(keySize).also { d.readFully(it) }

        val parent = dbFile.parentFile ?: throw java.io.IOException("database directory is unavailable")
        if (!parent.mkdirs() && !parent.isDirectory) throw java.io.IOException("database directory is unavailable")
        val candidate = File(parent, dbFile.name + ".restore-new")
        val previous = File(parent, dbFile.name + ".restore-old")
        val suffixes = listOf("-wal", "-shm", "-journal")
        val sidecarBackups = suffixes.associateWith { File(parent, dbFile.name + it + ".restore-old") }
        candidate.delete()
        previous.delete()
        sidecarBackups.values.forEach { it.delete() }

        try {
            FileInputStream(stagedDb).use { input ->
                FileOutputStream(candidate).use { output ->
                    input.copyTo(output, 64 * 1024)
                    output.fd.sync()
                }
            }
            if (dbFile.exists() && !dbFile.renameTo(previous)) {
                throw java.io.IOException("could not preserve the current database")
            }
            for ((suffix, backup) in sidecarBackups) {
                val sidecar = File(parent, dbFile.name + suffix)
                if (sidecar.exists() && !sidecar.renameTo(backup)) {
                    throw java.io.IOException("could not preserve database journal files")
                }
            }
            if (!candidate.renameTo(dbFile)) throw java.io.IOException("could not install restored database")

            secure.restoreKeys(if (seed.isEmpty()) null else seed, key)
            previous.delete()
            sidecarBackups.values.forEach { it.delete() }
            clear(stageDir)
            return true
        } catch (t: Throwable) {
            dbFile.delete()
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
}
