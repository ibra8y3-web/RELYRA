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
        val chunks: MutableMap<Int, ByteArray> = mutableMapOf(),
    )

    private val receiveLock = Mutex()
    private val transfers = mutableMapOf<String, IncomingTransfer>()
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

    suspend fun syncNow() {
        val identity = database.identityDao().get() ?: return
        val secret = identities.nostrSecret() ?: return
        val publicKey = identities.nostrPublicKeyHex() ?: return
        val urls = network.relays.value.filter { it.enabled }.map { it.url }.toSet()
        if (urls.isEmpty()) return
        pool.configure(secret, urls)

        val local = devices.ensureLocalDevice()
        val trusted = database.trustedDeviceDao().observeForAccount(identity.publicId).first()
            .filter { it.status == TrustedDeviceStatus.APPROVED && it.deviceId != local.deviceId }
        for (target in trusted) {
            val wire = sync.exportEncrypted(target.deviceId)
            require(wire.size <= CHUNK_BYTES * MAX_CHUNKS) { "Device sync payload exceeds the relay transfer limit" }
            val transferId = UUID.randomUUID().toString()
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
                val event = NostrEvents.create(secret, KIND, tags, content, System.currentTimeMillis() / 1000)
                check(pool.publish(event)) { "No configured relay accepted device-sync chunk $index" }
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
        if (count !in 1..MAX_CHUNKS || index !in 0 until count) return@withLock
        val sender = database.trustedDeviceDao().get(senderId) ?: return@withLock
        if (sender.status != TrustedDeviceStatus.APPROVED) return@withLock
        val chunk = runCatching {
            Base64.getUrlDecoder().decode(event.content).also { require(it.size in 1..CHUNK_BYTES) }
        }.getOrNull() ?: return@withLock

        if (transfers.size >= 4 && transferId !in transfers) return@withLock
        val transfer = transfers.getOrPut(transferId) {
            IncomingTransfer(senderId, count, event.createdAt)
        }
        if (transfer.senderId != senderId || transfer.count != count) {
            transfers.remove(transferId)
            return@withLock
        }
        transfer.chunks.putIfAbsent(index, chunk)
        if (transfer.chunks.size != transfer.count) return@withLock

        val output = ByteArrayOutputStream()
        for (part in 0 until transfer.count) {
            val bytes = transfer.chunks[part] ?: return@withLock
            output.write(bytes)
        }
        val wire = output.toByteArray()
        transfers.remove(transferId)
        runCatching { sync.importEncrypted(wire) }
            .onSuccess { network.inboxSince = maxOf(network.inboxSince, transfer.createdAt) }
    }
}
