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
import android.content.SharedPreferences
import java.util.Properties

/**
 * Encrypted database backup. The database snapshot is checkpointed and copied while a Room transaction
 * prevents concurrent writers. Restoring is staged and applied before Room is opened on the next launch.
 *
 * The encrypted archive contains the SQLCipher database, private attachment/Vault ciphertext, wrapped
 * identity/database/prekey secrets, and portable app/server preferences. Legacy database-only backups remain readable.
 */
class BackupManager(
    private val db: AppDatabase,
    private val secure: SecureStore,
    private val dbFile: File,
    private val stageDir: File,
    private val filesDir: File,
    private val preferenceStores: Map<String, SharedPreferences>,
) {
    private val backupRoots = mapOf("vault" to File(filesDir, "vault"), "inbox" to File(filesDir, "inbox"), "outbox" to File(filesDir, "outbox"))

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
            val prekeyDir = File(stageDir, "export-prekeys")
            prekeyDir.deleteRecursively()
            check(prekeyDir.mkdirs() || prekeyDir.isDirectory)
            sdb.beginTransaction()
            try {
                validateReferencedFiles(sdb)
                val privatePrekeys = secure.exportPreKeys()
                sdb.query("SELECT keyId FROM prekeys WHERE kind = 'SIGNED' OR (kind = 'ONE_TIME' AND consumed = 0)").use { cursor ->
                    while (cursor.moveToNext()) {
                        val keyId = cursor.getString(0)
                        if (keyId !in privatePrekeys) throw java.io.IOException("private prekey $keyId is missing; refusing an incomplete backup")
                    }
                }
                FileInputStream(dbFile).use { input ->
                    FileOutputStream(snapshot).use { fileOut ->
                        input.copyTo(fileOut, 64 * 1024)
                        fileOut.fd.sync()
                    }
                }
                version = sdb.version
                privatePrekeys.forEach { (id, value) ->
                    val fileName = id.replace(':', '_')
                    check(fileName.matches(Regex("[a-z-]+_[0-9]+")))
                    File(prekeyDir, fileName).writeBytes(value)
                }
                BackupArchive.pack(snapshot, backupRoots + ("secure-prekeys" to prekeyDir), encodePreferences(), bundle)
                // Recheck references after copying: a file deleted or moved during export invalidates the snapshot.
                validateReferencedFiles(sdb)
            } finally {
                sdb.endTransaction()
            }
            prekeyDir.deleteRecursively()
            val seed = secure.loadSeed()
            val key = secure.databaseKey()
            BackupCrypto.seal(out, passphrase).use { sealed -> BackupPayload.write(sealed, seed, key, version, bundle) }
            return bundle.length()
        } finally {
            snapshot.delete()
            bundle.delete()
            File(stageDir, "export-prekeys").deleteRecursively()
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
    val AAD = "relyra-restore-v1".toByteArray(Charsets.UTF_8)

    fun clear(stageDir: File) {
        listOf(DB_PART, DB_READY, KEYS, MARKER, "export.tmp", "export.bundle", "restore.bundle").forEach { File(stageDir, it).delete() }
        File(stageDir, "restored-files").deleteRecursively()
        File(stageDir, "pending-prefs").deleteRecursively()
        File(stageDir, "export-prekeys").deleteRecursively()
        File(stageDir, "verify-vault").deleteRecursively()
    }

    /**
     * Replaces the database using same-filesystem renames and keeps rollback copies until key restoration
     * succeeds. Sidecar WAL/journal files are moved aside as part of the same guarded operation.
     */
    fun applyIfPending(stageDir: File, dbFile: File, secure: SecureStore, filesDir: File, preferenceStores: Map<String, SharedPreferences>): Boolean {
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
        val previousSeed = secure.loadSeed()
        val previousKey = secure.databaseKey()
        val previousPreferences = preferenceStores.mapValues { it.value.all }
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
            if (!candidate.renameTo(dbFile)) throw java.io.IOException("could not install restored database")
            installed = true

            secure.restoreKeys(if (seed.isEmpty()) null else seed, key, prekeys)
            keysUpdated = true
            restoredDirs = applyRestoredFiles(stageDir, filesDir)
            applyRestoredPreferences(stageDir, preferenceStores)
            // Removing the pending marker is the commit point. If the process dies afterwards,
            // startup cleanup removes rollback copies without trying to replay the restore.
            clear(stageDir)
            restoredDirs.values.filterNotNull().forEach { it.deleteRecursively() }
            previous.delete()
            sidecarBackups.values.forEach { it.delete() }
            return true
        } catch (t: Throwable) {
            if (restoredDirs != null) rollbackRestoredFiles(stageDir, filesDir, restoredDirs!!)
            if (keysUpdated) runCatching { secure.restoreKeys(previousSeed, previousKey) }
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
        val names = listOf("vault", "inbox", "outbox")
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
        listOf("vault", "inbox", "outbox").forEach {
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
