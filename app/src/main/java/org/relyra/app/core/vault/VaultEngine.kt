package org.relyra.app.core.vault

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.relyra.app.core.database.VaultDao
import org.relyra.app.core.database.VaultItemEntity
import org.relyra.app.core.transfer.FileCrypto
import org.relyra.app.core.transfer.FileNames
import org.relyra.app.core.transfer.FileTooLargeException
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.UUID

sealed interface VaultImport {
    class Ok(val item: VaultItemEntity) : VaultImport
    data object Empty : VaultImport
    data object TooLarge : VaultImport
    data object Unreadable : VaultImport
}

/**
 * Private files, encrypted on this device only. Each file gets its own random key (Tink AES-GCM-HKDF streaming, the same
 * construction as file attachments); the key is stored in the SQLCipher database, the ciphertext in app-private storage.
 * Opening the vault screen requires the device's biometrics or screen lock.
 */
class VaultEngine(
    private val dao: VaultDao,
    private val dir: File,
    private val tempDir: File,
) {
    companion object {
        const val MAX_BYTES = 200L * 1024 * 1024
    }

    fun observe() = dao.observeAll()

    private fun blobOf(id: String) = File(dir, "$id.enc")

    suspend fun import(resolver: ContentResolver, uri: Uri): VaultImport = withContext(Dispatchers.IO) {
        var name = "file"
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) {
                    val shown = c.getString(i)
                    if (shown != null) name = shown
                }
                Unit
            }
        } catch (e: RuntimeException) {
            // The name is cosmetic; fall back to "file".
        }
        val mime = resolver.getType(uri) ?: "application/octet-stream"
        val stream = (try { resolver.openInputStream(uri) } catch (e: Exception) { null })
            ?: return@withContext VaultImport.Unreadable
        dir.mkdirs()
        val id = UUID.randomUUID().toString()
        val blob = blobOf(id)
        val key = FileCrypto.newKey()
        try {
            val enc = stream.use { FileCrypto.encrypt(it, blob, key, MAX_BYTES) }
            if (enc.plainSize == 0L) {
                blob.delete()
                VaultImport.Empty
            } else {
                val item = VaultItemEntity(
                    id = id,
                    name = FileNames.sanitize(name),
                    mime = FileNames.sanitizeMime(mime),
                    size = enc.plainSize,
                    plainSha256 = enc.plainSha256,
                    fileKey = key,
                    addedAt = System.currentTimeMillis(),
                )
                dao.put(item)
                VaultImport.Ok(item)
            }
        } catch (e: FileTooLargeException) {
            blob.delete()
            VaultImport.TooLarge
        } catch (e: IOException) {
            blob.delete()
            VaultImport.Unreadable
        } catch (e: GeneralSecurityException) {
            blob.delete()
            VaultImport.Unreadable
        }
    }

    /** Decrypts into a temporary file, checking every segment and the SHA-256. Caller must delete the file. */
    private fun decryptToTemp(item: VaultItemEntity): File? {
        val blob = blobOf(item.id)
        if (!blob.isFile) return null
        tempDir.mkdirs()
        val tmp = File(tempDir, "vault-" + UUID.randomUUID().toString())
        val ok = FileCrypto.decrypt(blob, tmp, item.fileKey, item.plainSha256, item.size)
        if (!ok) {
            tmp.delete()
            return null
        }
        return tmp
    }

    /** Writes a decrypted copy to [dest]. False if the stored file is missing or fails its integrity check. */
    suspend fun export(resolver: ContentResolver, id: String, dest: Uri): Boolean = withContext(Dispatchers.IO) {
        val item = dao.get(id) ?: return@withContext false
        val tmp = decryptToTemp(item) ?: return@withContext false
        try {
            val out = resolver.openOutputStream(dest, "w") ?: return@withContext false
            out.use { o -> tmp.inputStream().use { it.copyTo(o) } }
            true
        } catch (e: IOException) {
            false
        } finally {
            tmp.delete()
        }
    }

    /** True only if the stored ciphertext authenticates completely and matches the recorded SHA-256 and size. */
    suspend fun verify(id: String): Boolean = withContext(Dispatchers.IO) {
        val item = dao.get(id) ?: return@withContext false
        val tmp = decryptToTemp(item) ?: return@withContext false
        tmp.delete()
        true
    }

    suspend fun rename(id: String, name: String) = withContext(Dispatchers.IO) { dao.rename(id, FileNames.sanitize(name)) }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        blobOf(id).delete()
        dao.delete(id)
    }

    /** Used when the identity is reset: the rows go with the database, this removes the encrypted files. */
    fun wipeFiles() {
        dir.deleteRecursively()
    }
}
