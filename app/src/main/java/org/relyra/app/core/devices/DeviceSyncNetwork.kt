package org.relyra.app.core.devices

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.relyra.app.core.database.AppDatabase
import org.relyra.app.core.database.TrustedDeviceStatus
import org.relyra.app.core.identity.IdentityRepository
import org.relyra.app.core.network.NetworkManager
import org.relyra.app.core.network.NostrEvent
import org.relyra.app.core.network.NostrEvents
import org.relyra.app.core.network.NostrPool
import org.relyra.app.core.storage.SecureStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject
import java.util.Base64
import java.util.UUID

/**
 * Automatic encrypted device-to-device replication over the account's configured Nostr relays.
 * Payloads are encrypted to one device at a time; relay operators only see bounded ciphertext chunks.
 */
class DeviceSyncNetwork(
    private val network: NetworkManager,
    private val identities: IdentityRepository,
    private val database: AppDatabase,
    private val secure: SecureStore,
    private val devices: DeviceManager,
    private val sync: DeviceSyncRepository,
    private val scope: CoroutineScope,
) {
    companion object {
        private const val KIND = 4748
        private const val CHUNK_BYTES = 20 * 1024
        private const val MAX_CHUNKS = 512
        private const val SYNC_TAG = "relyra-device-sync-v1"
        private const val ACK_TAG = "relyra-device-sync-ack-v1"
        private const val MAX_TRANSFER_AGE_SECONDS = 24 * 60 * 60L
    }

    private data class OutgoingTransfer(
        val targetId: String,
        val senderId: String,
        val count: Int,
        val createdAt: Long,
        val directory: File,
    )

    private data class IncomingTransfer(
        val senderId: String,
        val count: Int,
        val createdAt: Long,
        val directory: File,
    )

    private val receiveLock = Mutex()
    // Persist opaque, already device-encrypted chunks so an Android process death does not
    // discard a partially received transfer. The manifest contains routing metadata only.
    private val databaseDirectory by lazy { File(database.openHelper.writableDatabase.path).parentFile }
    private val incomingRoot: File by lazy {
        File(databaseDirectory, "device-sync-incoming").apply { mkdirs() }
    }
    private val outgoingRoot: File by lazy {
        File(databaseDirectory, "device-sync-outgoing").apply { mkdirs() }
    }
    private val pool by lazy {
        NostrPool(
            http = OkHttpClient.Builder().build(),
            scope = scope,
            sinceProvider = { network.inboxSince },
            onEvent = { receive(it) },
            eventKind = KIND,
        )
    }
    private var connectionJob: Job? = null
    private var syncJob: Job? = null

    @Synchronized
    fun start() {
        if (connectionJob?.isActive == true) return
        connectionJob = scope.launch {
            while (isActive) {
                val secret = identities.nostrSecret()
                val urls = network.relays.value.filter { it.enabled }.map { it.url }.toSet()
                pool.configure(secret, urls)
                delay(60_000)
            }
        }
        syncJob = scope.launch {
            while (isActive) {
                runCatching { syncNow() }
                delay(15 * 60_000L)
            }
        }
    }

    suspend fun syncNow(): Int {
        val identity = database.identityDao().get() ?: return 0
        val secret = identities.nostrSecret() ?: return 0
        val publicKey = identities.nostrPublicKeyHex() ?: return 0
        val urls = network.relays.value.filter { it.enabled }.map { it.url }.toSet()
        if (urls.isEmpty()) return 0
        pool.configure(secret, urls)
        pool.waitForBacklog(20_000)

        val local = devices.ensureLocalDevice()
        val trusted = database.trustedDeviceDao().observeForAccount(identity.publicId).first()
            .filter { it.status == TrustedDeviceStatus.APPROVED && it.deviceId != local.deviceId }
        purgeStaleTransfers()
        var sentDevices = 0
        for (target in trusted) {
            var transfer = findPendingOutgoing(target.deviceId, local.deviceId)
            if (transfer == null) {
                val wire = sync.exportEncrypted(target.deviceId)
                require(wire.size <= 8 * 1024 * 1024) { "Device sync payload exceeds the encrypted snapshot limit" }
                transfer = stageOutgoingTransfer(target.deviceId, local.deviceId, wire)
            }
            if (publishOutgoingTransfer(secret, publicKey, transfer)) sentDevices++
        }
        return sentDevices
    }

    private fun loadOrCreateTransfer(
        directory: File,
        senderId: String,
        count: Int,
        createdAt: Long,
    ): IncomingTransfer? {
        if (!incomingRoot.exists() && !incomingRoot.mkdirs()) return null
        if (!directory.exists()) {
            val active = incomingRoot.listFiles()?.count { it.isDirectory } ?: 0
            if (active >= 4 || !directory.mkdirs()) return null
            val manifest = JSONObject()
                .put("version", 1)
                .put("sender", senderId)
                .put("count", count)
                .put("createdAt", createdAt)
            val temporary = File(directory, "manifest.tmp")
            return try {
                FileOutputStream(temporary).use { output ->
                    output.write(manifest.toString().toByteArray(Charsets.UTF_8))
                    output.fd.sync()
                }
                if (!temporary.renameTo(File(directory, "manifest.json"))) {
                    directory.deleteRecursively()
                    null
                } else IncomingTransfer(senderId, count, createdAt, directory)
            } catch (_: Exception) {
                directory.deleteRecursively()
                null
            }
        }
        return try {
            val manifest = JSONObject(File(directory, "manifest.json").readText())
            if (manifest.optInt("version") != 1) return null
            IncomingTransfer(
                manifest.getString("sender"),
                manifest.getInt("count"),
                manifest.getLong("createdAt"),
                directory,
            )
        } catch (_: Exception) {
            directory.deleteRecursively()
            null
        }
    }

    private suspend fun receive(event: NostrEvent) = receiveLock.withLock {
        val ownPublicKey = identities.nostrPublicKeyHex() ?: return@withLock
        if (event.kind != KIND || event.pubkey != ownPublicKey) return@withLock
        fun tag(name: String): String? = event.tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)
        if (tag("p") != ownPublicKey) return@withLock

        val senderId = tag("s") ?: return@withLock
        val transferId = tag("e") ?: return@withLock
        val sender = database.trustedDeviceDao().get(senderId) ?: return@withLock
        if (sender.status != TrustedDeviceStatus.APPROVED) return@withLock
        val nowSeconds = System.currentTimeMillis() / 1000
        if (event.createdAt > nowSeconds + 300 || nowSeconds - event.createdAt > MAX_TRANSFER_AGE_SECONDS) return@withLock
        when (tag("t")) {
            ACK_TAG -> {
                if (tag("d") != secure.deviceId()) return@withLock
                val directory = File(outgoingRoot, transferId)
                val manifest = runCatching { JSONObject(File(directory, "manifest.json").readText()) }.getOrNull()
                    ?: return@withLock
                if (manifest.optString("target") == senderId &&
                    manifest.optString("sender") == secure.deviceId()) directory.deleteRecursively()
                return@withLock
            }
            SYNC_TAG -> if (tag("d") != secure.deviceId()) return@withLock
            else -> return@withLock
        }
        val index = tag("n")?.toIntOrNull() ?: return@withLock
        val count = tag("c")?.toIntOrNull() ?: return@withLock
        if (count !in 1..MAX_CHUNKS || index !in 0 until count ||
            runCatching { UUID.fromString(transferId).toString() == transferId }.getOrDefault(false).not() ||
            runCatching { UUID.fromString(senderId).toString() == senderId }.getOrDefault(false).not()) return@withLock
        val chunk = runCatching {
            Base64.getUrlDecoder().decode(event.content).also { require(it.size in 1..CHUNK_BYTES) }
        }.getOrNull() ?: return@withLock

        purgeStaleTransfers()
        val directory = File(incomingRoot, transferId)
        val transfer = loadOrCreateTransfer(directory, senderId, count, event.createdAt) ?: return@withLock
        if (transfer.senderId != senderId || transfer.count != count || transfer.createdAt != event.createdAt) {
            directory.deleteRecursively()
            return@withLock
        }
        val partFile = File(directory, "%04d.part".format(index))
        if (!partFile.isFile) {
            val storedBytes = directory.listFiles()?.filter { it.extension == "part" }?.sumOf { it.length() } ?: 0L
            if (storedBytes + chunk.size > 8L * 1024 * 1024) return@withLock
            val temporary = File(directory, partFile.name + ".tmp")
            try {
                FileOutputStream(temporary).use { output ->
                    output.write(chunk)
                    output.fd.sync()
                }
                if (!temporary.renameTo(partFile)) temporary.delete()
            } catch (_: Exception) {
                temporary.delete()
                return@withLock
            }
        }
        val received = (0 until transfer.count).all { File(directory, "%04d.part".format(it)).isFile }
        if (!received) return@withLock

        val output = ByteArrayOutputStream()
        for (part in 0 until transfer.count) {
            val file = File(directory, "%04d.part".format(part))
            if (!file.isFile || file.length() !in 1..CHUNK_BYTES.toLong()) return@withLock
            file.inputStream().use { it.copyTo(output) }
            if (output.size() > 8 * 1024 * 1024) {
                directory.deleteRecursively()
                return@withLock
            }
        }
        val wire = output.toByteArray()
        runCatching { sync.importEncrypted(wire) }
            .onSuccess {
                // Keep the durable chunks until the sender has a signed relay acknowledgement.
                // Re-importing the same encrypted snapshot is idempotent and lets us retry the ACK.
                if (publishTransferAck(transferId, senderId, secure.deviceId(), ownPublicKey)) {
                    network.inboxSince = maxOf(network.inboxSince, transfer.createdAt)
                    directory.deleteRecursively()
                }
            }
    }

    private fun findPendingOutgoing(targetId: String, senderId: String): OutgoingTransfer? {
        return outgoingRoot.listFiles()?.firstNotNullOfOrNull { directory ->
            if (!directory.isDirectory) return@firstNotNullOfOrNull null
            runCatching {
                val manifest = JSONObject(File(directory, "manifest.json").readText())
                if (manifest.optString("target") != targetId || manifest.optString("sender") != senderId) return@runCatching null
                OutgoingTransfer(
                    targetId, senderId, manifest.getInt("count"), manifest.getLong("createdAt"), directory,
                )
            }.getOrNull()
        }
    }

    private fun stageOutgoingTransfer(targetId: String, senderId: String, wire: ByteArray): OutgoingTransfer {
        require(wire.isNotEmpty() && wire.size <= 8 * 1024 * 1024)
        val chunks = wire.toList().chunked(CHUNK_BYTES)
        require(chunks.size in 1..MAX_CHUNKS)
        val transferId = UUID.randomUUID().toString()
        val directory = File(outgoingRoot, transferId)
        check(directory.mkdirs()) { "Could not stage device sync transfer" }
        val createdAt = System.currentTimeMillis() / 1000
        try {
            val manifest = JSONObject().put("version", 1).put("target", targetId)
                .put("sender", senderId).put("count", chunks.size).put("createdAt", createdAt)
            FileOutputStream(File(directory, "manifest.json")).use { output ->
                output.write(manifest.toString().toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            chunks.forEachIndexed { index, bytes ->
                FileOutputStream(File(directory, "%04d.part".format(index))).use { output ->
                    output.write(bytes.toByteArray())
                    output.fd.sync()
                }
            }
            return OutgoingTransfer(targetId, senderId, chunks.size, createdAt, directory)
        } catch (failure: Throwable) {
            directory.deleteRecursively()
            throw failure
        }
    }

    private suspend fun publishOutgoingTransfer(
        secret: ByteArray,
        publicKey: String,
        transfer: OutgoingTransfer,
    ): Boolean {
        for (index in 0 until transfer.count) {
            val part = File(transfer.directory, "%04d.part".format(index))
            if (!part.isFile || part.length() !in 1..CHUNK_BYTES.toLong()) return false
            val tags = listOf(
                listOf("p", publicKey),
                listOf("d", transfer.targetId),
                listOf("s", transfer.senderId),
                listOf("e", transfer.directory.name),
                listOf("n", index.toString()),
                listOf("c", transfer.count.toString()),
                listOf("t", SYNC_TAG),
            )
            val content = Base64.getUrlEncoder().withoutPadding().encodeToString(part.readBytes())
            val event = NostrEvents.create(secret, KIND, tags, content, transfer.createdAt)
            if (!pool.publish(event)) return false
        }
        return true
    }

    private suspend fun publishTransferAck(
        transferId: String,
        targetSenderId: String,
        localDeviceId: String,
        publicKey: String,
    ): Boolean {
        val secret = identities.nostrSecret() ?: return false
        val tags = listOf(
            listOf("p", publicKey),
            listOf("d", targetSenderId),
            listOf("s", localDeviceId),
            listOf("e", transferId),
            listOf("t", ACK_TAG),
        )
        val event = NostrEvents.create(secret, KIND, tags, "received", System.currentTimeMillis() / 1000)
        return runCatching { pool.publish(event) }.getOrDefault(false)
    }

    private fun purgeStaleTransfers() {
        val now = System.currentTimeMillis() / 1000
        listOf(incomingRoot, outgoingRoot).forEach { root ->
            root.listFiles()?.forEach { directory ->
                if (!directory.isDirectory) directory.delete()
                else {
                    val created = runCatching {
                        JSONObject(File(directory, "manifest.json").readText()).getLong("createdAt")
                    }.getOrNull()
                    if (created == null || created > now + 300 || now - created > MAX_TRANSFER_AGE_SECONDS) {
                        directory.deleteRecursively()
                    }
                }
            }
        }
    }
}
