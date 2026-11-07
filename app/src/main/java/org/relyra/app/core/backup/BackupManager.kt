package org.relyra.app.core.backup

import org.relyra.app.core.crypto.KeystoreVault
import org.relyra.app.core.database.AppDatabase
import org.relyra.app.core.storage.SecureStore
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Encrypted backup of the whole local database plus the two secrets that open it (database key, identity seed).
 * The file is useless without the passphrase, and the passphrase is never stored. Downloaded attachment files are NOT included.
 *
 * Restoring is done in two steps because the database is open while the app runs: the backup is verified and staged,
 * then [RestoreApplier] swaps it in the next time the app starts.
 */
class BackupManager(
    private val db: AppDatabase,
    private val secure: SecureStore,
    private val dbFile: File,
    private val stageDir: File,
) {
    /** Writes an encrypted backup to [out] (closed afterwards). Returns the size of the database that was saved. */
    fun export(out: OutputStream, passphrase: CharArray): Long {
        require(BackupCrypto.passphraseAcceptable(passphrase)) { "passphrase too short" }
        stageDir.mkdirs()
        val snapshot = File(stageDir, "export.tmp")
        snapshot.delete()
        var version = 0
        val sdb = db.openHelper.writableDatabase
        try {
            // Move everything from the write-ahead log into the main file, then hold the write lock while copying it,
            // so the snapshot is a consistent database.
            sdb.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
            sdb.beginTransaction()
            try {
                dbFile.copyTo(snapshot, overwrite = true)
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

    /**
     * Decrypts and verifies [input] completely, then stages it so the next start applies it.
     * Nothing about the running database changes. Throws [BackupException] for a wrong passphrase or a bad file.
     */
    fun stageRestore(input: InputStream, passphrase: CharArray) {
        stageDir.mkdirs()
        RestoreApplier.clear(stageDir)
        val part = File(stageDir, RestoreApplier.DB_PART)
        try {
            val restored = BackupCrypto.open(input, passphrase).use { BackupPayload.read(it, part) }
            if (restored.dbVersion < 1 || restored.dbVersion > db.openHelper.readableDatabase.version) {
                throw BackupException("this backup was made by a newer version of RELYRA")
            }
            val raw = java.io.ByteArrayOutputStream()
            DataOutputStream(raw).apply {
                writeShort(restored.seed?.size ?: 0); write(restored.seed ?: ByteArray(0))
                writeShort(restored.dbKey.size); write(restored.dbKey)
                flush()
            }
            File(stageDir, RestoreApplier.KEYS).writeBytes(KeystoreVault.wrap(raw.toByteArray(), RestoreApplier.AAD))
            if (!part.renameTo(File(stageDir, RestoreApplier.DB_READY))) throw BackupException("could not stage the backup")
            File(stageDir, RestoreApplier.MARKER).writeText("1")
        } catch (t: Throwable) {
            RestoreApplier.clear(stageDir)
            throw t
        }
    }

    fun hasPending(): Boolean = File(stageDir, RestoreApplier.MARKER).isFile

    fun cancelPending() = RestoreApplier.clear(stageDir)
}

/** Runs at app start, before the database is opened. Safe to run again if it was interrupted: the marker goes last. */
object RestoreApplier {
    const val DB_PART = "pending.db.part"
    const val DB_READY = "pending.db"
    const val KEYS = "pending.keys"
    const val MARKER = "pending.ready"
    val AAD = "relyra-restore-v1".toByteArray(Charsets.UTF_8)

    fun clear(stageDir: File) {
        listOf(DB_PART, DB_READY, KEYS, MARKER, "export.tmp").forEach { File(stageDir, it).delete() }
    }

    /** Returns true when a staged backup was applied. */
    fun applyIfPending(stageDir: File, dbFile: File, secure: SecureStore): Boolean {
        if (!File(stageDir, MARKER).isFile) return false
        val db = File(stageDir, DB_READY)
        val keysFile = File(stageDir, KEYS)
        if (!db.isFile || !keysFile.isFile) {
            clear(stageDir)
            return false
        }
        val d = DataInputStream(KeystoreVault.unwrap(keysFile.readBytes(), AAD).inputStream())
        val seed = ByteArray(d.readUnsignedShort()).also { d.readFully(it) }
        val key = ByteArray(d.readUnsignedShort()).also { d.readFully(it) }
        require(key.size == 32)
        for (suffix in listOf("", "-wal", "-shm", "-journal")) File(dbFile.path + suffix).delete()
        dbFile.parentFile?.mkdirs()
        db.copyTo(dbFile, overwrite = true)
        secure.restoreKeys(if (seed.isEmpty()) null else seed, key)
        clear(stageDir)
        return true
    }
}
