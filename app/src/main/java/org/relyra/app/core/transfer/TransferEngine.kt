package org.relyra.app.core.transfer

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.relyra.app.core.messaging.AttachmentLimits
import org.relyra.app.core.messaging.AttachmentPhase
import org.relyra.app.core.messaging.BlobUploader
import org.relyra.app.core.messaging.MessageStore
import org.relyra.app.core.messaging.UploadOutcome
import org.relyra.app.core.network.Hex
import org.relyra.app.core.network.NostrCrypto
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PreparedFile(
    val name: String, val mime: String, val size: Long, val blobSize: Long,
    val plainSha256: ByteArray, val blobSha256: ByteArray, val key: ByteArray, val blobPath: String,
)

sealed interface PrepareResult {
    class Ok(val file: PreparedFile) : PrepareResult
    data object Unreadable : PrepareResult
    data object Empty : PrepareResult
    data object TooLarge : PrepareResult
}

sealed interface DownloadResult {
    data object Done : DownloadResult
    data object Busy : DownloadResult
    class Failed(val reason: String) : DownloadResult
}

/**
 * Encrypts files for sending, uploads the ciphertext to the user's Blossom servers, and downloads, verifies and
 * decrypts incoming files. Files live only in the app's private storage until the user saves a copy elsewhere.
 */
class TransferEngine(
    context: android.content.Context,
    private val servers: BlossomServers,
    private val store: MessageStore,
    private val identitySecret: () -> ByteArray?,
    private val client: BlossomClient = BlossomClient(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val ipfs: org.relyra.app.core.integrations.IpfsEncryptedStore? = null,
) : BlobUploader {
    private val root: File = context.applicationContext.filesDir
    private val outbox = File(root, "outbox")
    private val inbox = File(root, "inbox")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val active = ConcurrentHashMap.newKeySet<String>()

    /** Bytes of the encrypted blob downloaded so far, per message ID. */
    val progress = MutableStateFlow<Map<String, Long>>(emptyMap())

    private fun isInside(file: File, dir: File): Boolean = try {
        file.canonicalFile.toPath().startsWith(dir.canonicalFile.toPath())
    } catch (e: IOException) {
        false
    }

    // ---- sending -------------------------------------------------------------------------------------------

    suspend fun prepare(resolver: ContentResolver, uri: Uri): PrepareResult = withContext(Dispatchers.IO) {
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
            ?: return@withContext PrepareResult.Unreadable
        outbox.mkdirs()
        val blob = File(outbox, UUID.randomUUID().toString() + ".enc")
        val key = FileCrypto.newKey()
        try {
            val enc = stream.use { FileCrypto.encrypt(it, blob, key, AttachmentLimits.MAX_BYTES) }
            if (enc.plainSize == 0L) {
                blob.delete()
                PrepareResult.Empty
            } else {
                PrepareResult.Ok(
                    PreparedFile(
                        FileNames.sanitize(name), FileNames.sanitizeMime(mime), enc.plainSize, enc.blobSize,
                        enc.plainSha256, enc.blobSha256, key, blob.absolutePath,
                    )
                )
            }
        } catch (e: FileTooLargeException) {
            blob.delete()
            PrepareResult.TooLarge
        } catch (e: IOException) {
            blob.delete()
            PrepareResult.Unreadable
        } catch (e: GeneralSecurityException) {
            blob.delete()
            PrepareResult.Unreadable
        }
    }

    override suspend fun upload(blobPath: String, sha256: ByteArray): UploadOutcome {
        val file = File(blobPath)
        if (!isInside(file, outbox) || !file.isFile) return UploadOutcome.Rejected("the encrypted file is missing")
        val kubo = ipfs?.endpoint?.value.orEmpty()
        if (kubo.isNotBlank()) {
            return try {
                val ipfsStore = ipfs ?: return UploadOutcome.Unavailable
                val cid = ipfsStore.addCiphertext(kubo, file)
                UploadOutcome.Done("ipfs://$cid")
            } catch (e: Exception) {
                UploadOutcome.Rejected(e.message?.take(180) ?: "IPFS upload failed")
            }
        }
        val enabled = servers.servers.value.filter { it.enabled }
        if (enabled.isEmpty()) return UploadOutcome.Unavailable
        val hex = Hex.encode(sha256)
        var unavailable = false
        var rejected: String? = null
        for (s in enabled) {
            val secret = if (s.signWithIdentity) identitySecret() else NostrCrypto.randomSecret()
            if (secret == null) {
                unavailable = true
                continue
            }
            when (val r = client.upload(s.url, file, hex, secret, clock() / 1000)) {
                is BlossomUpload.Ok -> return UploadOutcome.Done(r.server)
                BlossomUpload.Unavailable -> unavailable = true
                is BlossomUpload.Rejected -> rejected = r.reason
            }
        }
        return if (unavailable) UploadOutcome.Unavailable else UploadOutcome.Rejected(rejected ?: "no server accepted the file")
    }

    override fun discard(blobPath: String) {
        val f = File(blobPath)
        if (isInside(f, outbox)) f.delete()
    }

    // ---- receiving -----------------------------------------------------------------------------------------

    /** Starts a manual download in the background. Never called automatically. */
    fun startDownload(messageId: String, onDone: (DownloadResult) -> Unit) {
        scope.launch { onDone(download(messageId)) }
    }

    suspend fun download(messageId: String): DownloadResult {
        if (!active.add(messageId)) return DownloadResult.Busy
        try {
            val a = store.attachment(messageId) ?: return DownloadResult.Failed("unknown file")
            if (a.phase == AttachmentPhase.READY) return DownloadResult.Done
            if (store.get(messageId)?.outgoing != false) return DownloadResult.Failed("not an incoming file")
            val isIpfs = a.serverUrl.startsWith("ipfs://")
            val ipfsCid = if (isIpfs) a.serverUrl.removePrefix("ipfs://") else null
            val url = if (isIpfs) null else BlossomUrl.normalize(a.serverUrl, allowPrivate = false)
                ?: return DownloadResult.Failed("the file server address is not allowed")
            if (isIpfs && !Regex("b[a-z2-7]{20,120}").matches(ipfsCid.orEmpty())) return DownloadResult.Failed("invalid IPFS CID")
            store.updateAttachment(a.copy(phase = AttachmentPhase.DOWNLOADING))
            inbox.mkdirs()
            val part = File(inbox, "$messageId.part")
            if (isIpfs) {
                try {
                    val endpoint = ipfs?.endpoint?.value.orEmpty()
                    require(endpoint.isNotBlank()) { "Configure the Kubo HTTPS endpoint to download this attachment" }
                    val ipfsStore = ipfs ?: error("IPFS storage is not configured")
                    ipfsStore.fetchCiphertext(endpoint, ipfsCid!!, part)
                    require(part.length() == a.blobSize && java.security.MessageDigest.isEqual(FileCrypto.sha256(part), a.blobSha256)) { "IPFS ciphertext hash mismatch" }
                } catch (e: Exception) {
                    part.delete()
                    store.updateAttachment(a.copy(phase = AttachmentPhase.REMOTE))
                    return DownloadResult.Failed(e.message ?: "IPFS download failed")
                }
            } else {
                val res = client.download(url!!, Hex.encode(a.blobSha256), part, a.blobSize) { bytes -> progress.update { it + (messageId to bytes) } }
                if (res is BlossomDownload.Failed) {
                    store.updateAttachment(a.copy(phase = if (res.retryable) AttachmentPhase.REMOTE else AttachmentPhase.FAILED))
                    return DownloadResult.Failed(res.reason)
                }
            }

            val dir = File(inbox, messageId)
            dir.mkdirs()
            val target = File(dir, a.name)
            val ok = withContext(Dispatchers.IO) {
                isInside(target, dir) && FileCrypto.decrypt(part, target, a.fileKey, a.plainSha256, a.size)
            }
            part.delete()
            if (!ok) {
                target.delete()
                store.updateAttachment(a.copy(phase = AttachmentPhase.FAILED))
                return DownloadResult.Failed("the file could not be decrypted or does not match")
            }
            store.updateAttachment(a.copy(phase = AttachmentPhase.READY, localPath = target.absolutePath))
            return DownloadResult.Done
        } finally {
            progress.update { it - messageId }
            active.remove(messageId)
        }
    }

    /**
     * Downloads a group attachment only after an explicit user action. The
     * Blossom object is verified and decrypted before it becomes exportable.
     */
    suspend fun downloadGroupAttachment(
        attachment: org.relyra.app.core.messaging.GroupAttachmentRecord,
        update: suspend (org.relyra.app.core.messaging.GroupAttachmentRecord) -> Unit,
    ): DownloadResult {
        val progressId = "group:${attachment.messageId}"
        if (!active.add(progressId)) return DownloadResult.Busy
        try {
            if (attachment.phase == org.relyra.app.core.messaging.GroupAttachmentPhase.READY &&
                attachment.localPath?.let { File(it).isFile } == true
            ) return DownloadResult.Done
            val isIpfs = attachment.serverUrl.startsWith("ipfs://")
            val ipfsCid = if (isIpfs) attachment.serverUrl.removePrefix("ipfs://") else null
            val url = if (isIpfs) null else BlossomUrl.normalize(attachment.serverUrl, allowPrivate = false)
                ?: return DownloadResult.Failed("the file server address is not allowed")
            if (isIpfs && !Regex("b[a-z2-7]{20,120}").matches(ipfsCid.orEmpty())) return DownloadResult.Failed("invalid IPFS CID")
            update(attachment.copy(phase = org.relyra.app.core.messaging.GroupAttachmentPhase.DOWNLOADING))
            val groupInbox = File(inbox, "groups")
            groupInbox.mkdirs()
            val part = File(groupInbox, "${attachment.messageId}.part")
            if (isIpfs) {
                try {
                    val endpoint = ipfs?.endpoint?.value.orEmpty()
                    require(endpoint.isNotBlank()) { "Configure the Kubo HTTPS endpoint to download this group attachment" }
                    val ipfsStore = ipfs ?: error("IPFS storage is not configured")
                    ipfsStore.fetchCiphertext(endpoint, ipfsCid!!, part)
                    require(part.length() == attachment.blobSize && java.security.MessageDigest.isEqual(FileCrypto.sha256(part), attachment.blobSha256)) { "IPFS group ciphertext hash mismatch" }
                } catch (e: Exception) {
                    part.delete()
                    update(attachment.copy(phase = org.relyra.app.core.messaging.GroupAttachmentPhase.REMOTE))
                    return DownloadResult.Failed(e.message ?: "IPFS group download failed")
                }
            } else {
                val result = client.download(url!!, Hex.encode(attachment.blobSha256), part, attachment.blobSize) { bytes -> progress.update { it + (progressId to bytes) } }
                if (result is BlossomDownload.Failed) {
                    update(attachment.copy(phase = if (result.retryable) org.relyra.app.core.messaging.GroupAttachmentPhase.REMOTE else org.relyra.app.core.messaging.GroupAttachmentPhase.FAILED))
                    return DownloadResult.Failed(result.reason)
                }
            }
            val targetDir = File(groupInbox, attachment.messageId)
            targetDir.mkdirs()
            val target = File(targetDir, FileNames.sanitize(attachment.name))
            val valid = withContext(Dispatchers.IO) {
                isInside(target, targetDir) &&
                    FileCrypto.decrypt(part, target, attachment.fileKey, attachment.plainSha256, attachment.size)
            }
            part.delete()
            if (!valid) {
                target.delete()
                update(attachment.copy(phase = org.relyra.app.core.messaging.GroupAttachmentPhase.FAILED))
                return DownloadResult.Failed("the group file failed decryption or SHA-256 verification")
            }
            update(attachment.copy(
                phase = org.relyra.app.core.messaging.GroupAttachmentPhase.READY,
                localPath = target.absolutePath,
            ))
            return DownloadResult.Done
        } finally {
            progress.update { it - progressId }
            active.remove(progressId)
        }
    }

    suspend fun exportGroupAttachment(
        resolver: ContentResolver,
        attachment: org.relyra.app.core.messaging.GroupAttachmentRecord,
        dest: Uri,
    ): Boolean = withContext(Dispatchers.IO) {
        if (attachment.phase != org.relyra.app.core.messaging.GroupAttachmentPhase.READY) return@withContext false
        val path = attachment.localPath ?: return@withContext false
        val groupInbox = File(inbox, "groups")
        val file = File(path)
        if (!isInside(file, groupInbox) || !file.isFile) return@withContext false
        try {
            val out = resolver.openOutputStream(dest, "w") ?: return@withContext false
            out.use { output -> file.inputStream().use { it.copyTo(output) } }
            true
        } catch (_: IOException) {
            false
        } catch (_: RuntimeException) {
            false
        }
    }

    /** Copies a downloaded file to a place the user picked (Storage Access Framework). */
    suspend fun export(resolver: ContentResolver, messageId: String, dest: Uri): Boolean = withContext(Dispatchers.IO) {
        val path = store.attachment(messageId)?.localPath ?: return@withContext false
        val f = File(path)
        if (!isInside(f, inbox) || !f.isFile) return@withContext false
        try {
            val out = resolver.openOutputStream(dest, "w") ?: return@withContext false
            out.use { o -> f.inputStream().use { it.copyTo(o) } }
            true
        } catch (e: IOException) {
            false
        } catch (e: RuntimeException) {
            false
        }
    }

    // ---- cleanup -------------------------------------------------------------------------------------------

    /** Removes files that belong to a deleted chat. */
    fun clean(messageIds: List<String>, paths: List<String>) {
        for (id in messageIds) {
            File(inbox, "$id.part").delete()
            File(inbox, id).deleteRecursively()
        }
        for (p in paths) discard(p)
    }

    fun wipeAll() {
        outbox.deleteRecursively()
        inbox.deleteRecursively()
    }
}
