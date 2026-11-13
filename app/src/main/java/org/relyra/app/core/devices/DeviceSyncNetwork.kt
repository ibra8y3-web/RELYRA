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
    }

    private data class IncomingTransfer(
        val senderId: String,
        val count: Int,
        val createdAt: Long,
        val directory: File,
    )

    private val receiveLock = Mutex()
    // Persist opaque, already device-encrypted chunks so an Android process death does not
    // discard a partially received transfer. The manifest contains routing metadata only.
    private val incomingRoot: File by lazy {
        File(File(database.openHelper.writableDatabase.path).parentFile, "device-sync-incoming")
            .apply { mkdirs() }
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
        var sentDevices=0
        for (target in trusted) {
            val wire = sync.exportEncrypted(target.deviceId)
            require(wire.size <= CHUNK_BYTES * MAX_CHUNKS) { "Device sync payload exceeds the relay transfer limit" }
            val transferId = UUID.randomUUID().toString()
            val createdAt = System.currentTimeMillis() / 1000
            val chunks = wire.toList().chunked(CHUNK_BYTES)
            chunks.forEachIndexed { index, bytes ->
                val tags = listOf(
                    listOf("p", publicKey),
                    listOf("d", target.deviceId),
                    listOf("s", local.deviceId),
                    listOf("e", transferId),
                    listOf("n", index.toString()),
                    listOf("c", chunks.size.toString()),
                    listOf("t", SYNC_TAG),
                )
                val content = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray())
                val event = NostrEvents.create(secret, KIND, tags, content, createdAt)
                check(pool.publish(event)) { "No configured relay accepted device-sync chunk $index" }
            }
            sentDevices++
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

    private fun purgeStaleTransfers() {
        val now = System.currentTimeMillis() / 1000
        incomingRoot.listFiles()?.forEach { directory ->
            if (!directory.isDirectory) {
                directory.delete()
            } else {
                val created = runCatching {
                    JSONObject(File(directory, "manifest.json").readText()).getLong("createdAt")
                }.getOrNull()
                if (created == null || created > now + 300 || now - created > 24 * 60 * 60) {
                    directory.deleteRecursively()
                }
            }
        }
    }

    private suspend fun receive(event: NostrEvent) = receiveLock.withLock {
        val ownPublicKey = identities.nostrPublicKeyHex() ?: return@withLock
        if (event.kind != KIND || event.pubkey != ownPublicKey) return@withLock
        fun tag(name: String): String? = event.tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)
        if (tag("t") != SYNC_TAG || tag("p") != ownPublicKey ||
            tag("d") != secure.deviceId()) return@withLock

        val senderId = tag("s") ?: return@withLock
        val transferId = tag("e") ?: return@withLock
        val index = tag("n")?.toIntOrNull() ?: return@withLock
        val count = tag("c")?.toIntOrNull() ?: return@withLock
        if (count !in 1..MAX_CHUNKS || index !in 0 until count ||
            runCatching { UUID.fromString(transferId).toString() == transferId }.getOrDefault(false).not() ||
            runCatching { UUID.fromString(senderId).toString() == senderId }.getOrDefault(false).not()) return@withLock
        val sender = database.trustedDeviceDao().get(senderId) ?: return@withLock
        if (sender.status != TrustedDeviceStatus.APPROVED) return@withLock
        val nowSeconds = System.currentTimeMillis() / 1000
        if (event.createdAt > nowSeconds + 300 || nowSeconds - event.createdAt > 24 * 60 * 60) return@withLock
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
                network.inboxSince = maxOf(network.inboxSince, transfer.createdAt)
                directory.deleteRecursively()
            }
    }
}
