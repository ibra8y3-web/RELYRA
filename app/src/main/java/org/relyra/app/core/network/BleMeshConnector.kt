package org.relyra.app.core.network

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.relyra.app.core.crypto.IdentityCrypto
import org.relyra.app.core.messaging.SendOutcome
import java.io.*
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class BleMeshStatus { Disabled, PermissionRequired, Unsupported, BluetoothOff, Starting, Scanning, Active, Failed }
data class BleMeshStats(val neighborCount: Int = 0, val queuedPackets: Int = 0, val queuedBytes: Long = 0, val expiredPackets: Long = 0)
data class MeshHop(val relayId: String, val signature: ByteArray)
data class MeshEnvelope(
    val messageId: String, val originId: String, val destinationId: String,
    val createdAt: Long, val expiresAt: Long, val maxHops: Int, val payload: ByteArray,
    val originSignature: ByteArray, val hops: List<MeshHop>,
)

/** Signed transit metadata around opaque RELYRA end-to-end ciphertext. */
object MeshEnvelopeCodec {
    const val MAX_PAYLOAD_BYTES = 256 * 1024
    const val MAX_FRAME_BYTES = 384 * 1024
    const val MAX_HOPS = 8
    const val MAX_TTL_MS = 24L * 60 * 60 * 1000
    private const val ORIGIN_DOMAIN = "RELYRA-OFFLINE-MESH-ORIGIN-V1"
    private const val HOP_DOMAIN = "RELYRA-OFFLINE-MESH-HOP-V1"

    fun create(id: String, origin: String, destination: String, payload: ByteArray,
               sign: (ByteArray) -> ByteArray?, now: Long = System.currentTimeMillis()): MeshEnvelope? {
        if (id.isBlank() || id.length > 80 || IdentityCrypto.parsePublicId(origin) == null ||
            IdentityCrypto.parsePublicId(destination) == null || payload.isEmpty() || payload.size > MAX_PAYLOAD_BYTES) return null
        val unsigned = MeshEnvelope(id, origin, destination, now, now + MAX_TTL_MS, MAX_HOPS,
            payload.copyOf(), ByteArray(0), emptyList())
        val signature = sign(originBytes(unsigned)) ?: return null
        if (signature.size != 64) return null
        return unsigned.copy(originSignature = signature)
    }

    fun appendHop(e: MeshEnvelope, relay: String, sign: (ByteArray) -> ByteArray?): MeshEnvelope? {
        if (e.hops.size >= e.maxHops || e.hops.size >= MAX_HOPS || relay == e.originId ||
            e.hops.any { it.relayId == relay } || IdentityCrypto.parsePublicId(relay) == null) return null
        val previous = e.hops.lastOrNull()
        val signature = sign(hopBytes(e, e.hops.size, relay, previous?.relayId ?: e.originId,
            previous?.signature ?: e.originSignature)) ?: return null
        if (signature.size != 64) return null
        return e.copy(hops = e.hops + MeshHop(relay, signature))
    }

    fun verify(e: MeshEnvelope, now: Long = System.currentTimeMillis()): Boolean {
        if (e.messageId.isBlank() || e.messageId.length > 80 ||
            IdentityCrypto.parsePublicId(e.originId) == null || IdentityCrypto.parsePublicId(e.destinationId) == null ||
            e.payload.isEmpty() || e.payload.size > MAX_PAYLOAD_BYTES || e.originSignature.size != 64 ||
            e.createdAt > now + 5 * 60 * 1000L || e.expiresAt <= now || e.expiresAt <= e.createdAt ||
            e.expiresAt - e.createdAt > MAX_TTL_MS || e.maxHops !in 1..MAX_HOPS || e.hops.size > e.maxHops) return false
        val key = IdentityCrypto.parsePublicId(e.originId) ?: return false
        if (!IdentityCrypto.verify(key, e.originSignature, originBytes(e))) return false
        val visited = hashSetOf(e.originId)
        var previousId = e.originId
        var previousSignature = e.originSignature
        e.hops.forEachIndexed { index, hop ->
            if (!visited.add(hop.relayId) || hop.signature.size != 64) return false
            val hopKey = IdentityCrypto.parsePublicId(hop.relayId) ?: return false
            if (!IdentityCrypto.verify(hopKey, hop.signature, hopBytes(e, index, hop.relayId, previousId, previousSignature))) return false
            previousId = hop.relayId
            previousSignature = hop.signature
        }
        return true
    }

    fun encode(e: MeshEnvelope): ByteArray {
        val hops = JSONArray()
        e.hops.forEach { hops.put(JSONObject().put("i", it.relayId).put("s", b64(it.signature))) }
        return JSONObject().put("v", 1).put("id", e.messageId).put("o", e.originId).put("d", e.destinationId)
            .put("c", e.createdAt).put("e", e.expiresAt).put("m", e.maxHops)
            .put("p", b64(e.payload)).put("s", b64(e.originSignature)).put("h", hops)
            .toString().toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): MeshEnvelope? {
        if (bytes.isEmpty() || bytes.size > MAX_FRAME_BYTES) return null
        return try {
            val j = JSONObject(String(bytes, Charsets.UTF_8))
            if (j.optInt("v", -1) != 1) return null
            val array = j.optJSONArray("h") ?: JSONArray()
            if (array.length() > MAX_HOPS) return null
            val hops = buildList {
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    add(MeshHop(item.getString("i"), unb64(item.getString("s"))))
                }
            }
            MeshEnvelope(j.getString("id"), j.getString("o"), j.getString("d"), j.getLong("c"),
                j.getLong("e"), j.getInt("m"), unb64(j.getString("p")), unb64(j.getString("s")), hops)
        } catch (_: Exception) { null }
    }

    private fun b64(bytes: ByteArray) = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    private fun unb64(value: String) = android.util.Base64.decode(value, android.util.Base64.DEFAULT)
    private fun originBytes(e: MeshEnvelope) = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeUTF(ORIGIN_DOMAIN); out.writeUTF(e.messageId); out.writeUTF(e.originId)
            out.writeUTF(e.destinationId); out.writeLong(e.createdAt); out.writeLong(e.expiresAt)
            out.writeInt(e.maxHops); out.write(MessageDigest.getInstance("SHA-256").digest(e.payload))
        }; bytes.toByteArray()
    }
    private fun hopBytes(e: MeshEnvelope, index: Int, relay: String, previous: String, previousSig: ByteArray) =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeUTF(HOP_DOMAIN); out.writeUTF(e.messageId)
                out.write(MessageDigest.getInstance("SHA-256").digest(e.originSignature))
                out.writeInt(index); out.writeUTF(relay); out.writeUTF(previous)
                out.write(MessageDigest.getInstance("SHA-256").digest(previousSig))
            }; bytes.toByteArray()
        }
}

/** Keystore-encrypted, bounded local queue. Files contain only signed envelopes and ciphertext. */
private class MeshTransitStore(context: Context) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "offline-mesh").apply { mkdirs() }
    private val prefs = app.getSharedPreferences("relyra_mesh_seen", Context.MODE_PRIVATE)
    private val alias = "relyra.offline.mesh.queue.v1"
    @Volatile var expiredCount = 0L
        private set

    @Synchronized fun put(e: MeshEnvelope, quotaBytes: Long): Boolean {
        purge()
        val target = fileFor(e.messageId)
        if (target.exists()) return true
        if (seen().contains(e.messageId)) return false
        val plain = MeshEnvelopeCodec.encode(e)
        val files = files()
        if (files.size >= MAX_ITEMS || files.sumOf { it.length() } + plain.size + 64 > quotaBytes) return false
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.doFinal(plain)
            val temp = File(dir, target.name + ".tmp")
            FileOutputStream(temp).use { out ->
                out.write(cipher.iv.size); out.write(cipher.iv); out.write(encrypted); out.fd.sync()
            }
            if (!temp.renameTo(target)) { temp.delete(); return false }
            remember(e.messageId)
            true
        } catch (_: Exception) { false }
    }

    @Synchronized fun pending(): List<MeshEnvelope> {
        purge()
        return files().mapNotNull { read(it) }
    }
    @Synchronized fun markDelivered(id: String) { remember(id); fileFor(id).delete() }
    @Synchronized fun markSeen(id: String) { remember(id) }
    @Synchronized fun stats(): Pair<Int, Long> {
        purge()
        val current = files()
        return current.size to current.sumOf { it.length() }
    }

    @Synchronized private fun purge() {
        dir.listFiles()?.filter { it.isFile && it.extension == "tmp" }.orEmpty().forEach { it.delete() }
        files().forEach { file ->
            val e = read(file)
            if (e == null || e.expiresAt <= System.currentTimeMillis()) {
                if (e != null) expiredCount++
                file.delete()
            }
        }
    }
    private fun files() = dir.listFiles()?.filter { it.isFile && it.extension == "mesh" }.orEmpty()
    private fun read(file: File): MeshEnvelope? {
        return try {
            val raw = file.readBytes()
            if (raw.size < 29) return null
            val ivLength = raw[0].toInt() and 0xff
            if (ivLength != 12 || raw.size <= 1 + ivLength + 16) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(1, 1 + ivLength)))
            MeshEnvelopeCodec.decode(cipher.doFinal(raw.copyOfRange(1 + ivLength, raw.size)))
        } catch (_: Exception) { null }
    }
    private fun fileFor(id: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(dir, "$hash.mesh")
    }
    private fun seen() = prefs.getStringSet("ids", emptySet()).orEmpty()
    private fun remember(id: String) {
        prefs.edit().putStringSet("ids", (seen() + id).toList().takeLast(MAX_SEEN).toSet()).apply()
    }
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        generator.init(android.security.keystore.KeyGenParameterSpec.Builder(alias,
            android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }
    companion object { const val MAX_ITEMS = 256; const val MAX_SEEN = 4096 }
}

/** Opt-in BLE GATT mesh. Relay consent is separate from using BLE for the user's own messages. */
class BleMeshConnector(context: Context, private val onWire: suspend (ByteArray) -> Boolean, private val scope: CoroutineScope) : Connector {
    override val name = "Bluetooth LE Mesh"
    private val app = context.applicationContext
    private val manager = app.getSystemService(BluetoothManager::class.java)
    private val adapter = manager?.adapter
    private val store = MeshTransitStore(app)
    private val _status = kotlinx.coroutines.flow.MutableStateFlow(BleMeshStatus.Disabled)
    val status: kotlinx.coroutines.flow.StateFlow<BleMeshStatus> = _status
    private val _stats = kotlinx.coroutines.flow.MutableStateFlow(BleMeshStats())
    val stats: kotlinx.coroutines.flow.StateFlow<BleMeshStats> = _stats
    private val peers = ConcurrentHashMap<String, GattPeer>()
    private val connecting = ConcurrentHashMap.newKeySet<String>()
    private val frames = ConcurrentHashMap<String, IncomingFrame>()
    private val writes = ConcurrentHashMap<String, CompletableDeferred<Int>>()
    private val sentTo = ConcurrentHashMap<String, MutableSet<String>>()
    private val flushLock = Mutex()
    @Volatile private var ownId: String? = null
    @Volatile private var signer: ((ByteArray) -> ByteArray?)? = null
    @Volatile private var enabled = false
    @Volatile private var relayEnabled = false
    @Volatile private var quotaBytes = 20L * 1024 * 1024
    private var gattServer: BluetoothGattServer? = null
    private var scanner: BluetoothLeScanner? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanCallback: ScanCallback? = null
    private var advertiseCallback: AdvertiseCallback? = null
    private var maintenance: Job? = null

    private data class GattPeer(val id: String, val gatt: BluetoothGatt, val write: BluetoothGattCharacteristic,
                                @Volatile var mtu: Int = 23, val lock: Mutex = Mutex())
    private class IncomingFrame { var expected = -1; val bytes = ByteArrayOutputStream() }

    @Synchronized fun configure(publicId: String?, sign: ((ByteArray) -> ByteArray?)?, shouldEnable: Boolean,
                                allowRelay: Boolean, quotaMb: Int) {
        relayEnabled = allowRelay
        quotaBytes = quotaMb.coerceIn(5, 100) * 1024L * 1024L
        if (!shouldEnable || publicId.isNullOrBlank() || sign == null) { stopLocked(); return }
        if (IdentityCrypto.parsePublicId(publicId) == null) { stopLocked(); _status.value = BleMeshStatus.Failed; return }
        if (!hasPermissions()) { stopLocked(); _status.value = BleMeshStatus.PermissionRequired; return }
        val bt = adapter
        if (bt == null) { stopLocked(); _status.value = BleMeshStatus.Unsupported; return }
        if (!bt.isEnabled) { stopLocked(); _status.value = BleMeshStatus.BluetoothOff; return }
        if (enabled && ownId == publicId && gattServer != null) { refreshStats(); return }
        stopLocked()
        enabled = true; ownId = publicId; signer = sign
        _status.value = BleMeshStatus.Starting
        try {
            openGattServer()
            maintenance = scope.launch(Dispatchers.IO) {
                while (enabled) { flushQueue(); refreshStats(); delay(MAINTENANCE_MS) }
            }
            _status.value = BleMeshStatus.Scanning
        } catch (_: SecurityException) { stopLocked(); _status.value = BleMeshStatus.PermissionRequired }
        catch (_: Exception) { stopLocked(); _status.value = BleMeshStatus.Failed }
    }

    private fun hasPermissions(): Boolean {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        return required.all { ContextCompat.checkSelfPermission(app, it) == PackageManager.PERMISSION_GRANTED }
    }

    @Suppress("MissingPermission")
    private fun openGattServer() {
        gattServer = manager?.openGattServer(app, object : BluetoothGattServerCallback() {
            override fun onServiceAdded(status: Int, service: BluetoothGattService) {
                if (status == BluetoothGatt.GATT_SUCCESS && enabled) {
                    runCatching { startAdvertising(); startScanning() }
                        .onFailure { _status.value = BleMeshStatus.Failed }
                } else if (enabled) {
                    _status.value = BleMeshStatus.Failed
                }
            }
            override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_DISCONNECTED) { frames.remove(device.address); refreshStats() }
            }
            override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int,
                                                     characteristic: BluetoothGattCharacteristic) {
                val value = if (characteristic.uuid == IDENTITY_UUID) ownId?.toByteArray(Charsets.UTF_8) else null
                val result = if (value == null || offset > value.size) BluetoothGatt.GATT_FAILURE else BluetoothGatt.GATT_SUCCESS
                val part = value?.copyOfRange(offset.coerceAtMost(value.size), value.size) ?: ByteArray(0)
                gattServer?.sendResponse(device, requestId, result, offset, part)
            }
            override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int,
                characteristic: BluetoothGattCharacteristic, preparedWrite: Boolean, responseNeeded: Boolean,
                offset: Int, value: ByteArray) {
                if (characteristic.uuid != RX_UUID || preparedWrite || offset != 0) {
                    if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
                    return
                }
                val complete = acceptChunk(device.address, value)
                if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                complete?.let { packet -> scope.launch(Dispatchers.IO) { receiveTransit(packet) } }
            }
        }) ?: error("Unable to open BLE GATT server")
        val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        service.addCharacteristic(BluetoothGattCharacteristic(IDENTITY_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
        service.addCharacteristic(BluetoothGattCharacteristic(RX_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE))
        check(gattServer?.addService(service) == true) { "Unable to register BLE mesh service" }
    }

    private fun acceptChunk(address: String, value: ByteArray): ByteArray? {
        if (value.isEmpty()) return null
        val frame = frames.getOrPut(address) { IncomingFrame() }
        synchronized(frame) {
            if (frame.expected < 0) {
                if (value.size < 4) { frames.remove(address); return null }
                frame.expected = DataInputStream(ByteArrayInputStream(value, 0, 4)).readInt()
                if (frame.expected !in 1..MeshEnvelopeCodec.MAX_FRAME_BYTES) { frames.remove(address); return null }
                if (value.size > 4) frame.bytes.write(value, 4, value.size - 4)
            } else frame.bytes.write(value)
            if (frame.bytes.size() > frame.expected) { frames.remove(address); return null }
            if (frame.bytes.size() == frame.expected) { frames.remove(address); return frame.bytes.toByteArray() }
        }
        return null
    }

    @Suppress("MissingPermission")
    private fun startAdvertising() {
        advertiser = adapter?.bluetoothLeAdvertiser ?: error("BLE advertiser unavailable")
        advertiseCallback = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) { _status.value = BleMeshStatus.Active }
            override fun onStartFailure(errorCode: Int) { _status.value = BleMeshStatus.Failed }
        }
        val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE_UUID)).setIncludeDeviceName(false).build()
        val settings = AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_LOW).setConnectable(true).setTimeout(0).build()
        advertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    @Suppress("MissingPermission")
    private fun startScanning() {
        scanner = adapter?.bluetoothLeScanner ?: error("BLE scanner unavailable")
        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val address = result.device.address
                if (!enabled || peers.values.any { it.gatt.device.address == address } ||
                    connecting.size >= MAX_NEIGHBORS || !connecting.add(address)) return
                result.device.connectGatt(app, false, clientCallback, BluetoothDevice.TRANSPORT_LE)
            }
            override fun onScanFailed(errorCode: Int) { _status.value = BleMeshStatus.Failed }
        }
        scanner?.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(), scanCallback)
    }

    private val clientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                peers.entries.removeAll { it.value.gatt.device.address == gatt.device.address }
                connecting.remove(gatt.device.address); runCatching { gatt.close() }; refreshStats()
            } else if (newState == BluetoothProfile.STATE_CONNECTED) gatt.discoverServices()
        }
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val identity = gatt.getService(SERVICE_UUID)?.getCharacteristic(IDENTITY_UUID)
            if (status != BluetoothGatt.GATT_SUCCESS || identity == null) {
                connecting.remove(gatt.device.address); gatt.disconnect(); return
            }
            gatt.requestMtu(517)
            gatt.readCharacteristic(identity)
        }
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            peers.values.firstOrNull { it.gatt === gatt }?.mtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
        }
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (characteristic.uuid != IDENTITY_UUID || status != BluetoothGatt.GATT_SUCCESS) return
            val id = String(characteristic.value ?: ByteArray(0), Charsets.UTF_8).trim().lowercase()
            val write = gatt.getService(SERVICE_UUID)?.getCharacteristic(RX_UUID)
            if (IdentityCrypto.parsePublicId(id) == null || id == ownId || write == null) {
                connecting.remove(gatt.device.address); gatt.disconnect(); return
            }
            peers[id] = GattPeer(id, gatt, write)
            connecting.remove(gatt.device.address)
            _status.value = BleMeshStatus.Active
            refreshStats()
            scope.launch(Dispatchers.IO) { flushQueue() }
        }
        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            writes.remove(gatt.device.address)?.complete(status)
        }
    }

    private suspend fun receiveTransit(packet: ByteArray) {
        val e = MeshEnvelopeCodec.decode(packet) ?: return
        if (!MeshEnvelopeCodec.verify(e)) return
        val self = ownId ?: return
        if (e.destinationId == self) {
            val accepted = runCatching { onWire(e.payload) }.getOrDefault(false)
            if (accepted) store.markDelivered(e.messageId)
            refreshStats()
            return
        }
        if (!relayEnabled || e.hops.any { it.relayId == self }) return
        val forwarded = MeshEnvelopeCodec.appendHop(e, self, signer ?: return) ?: return
        store.put(forwarded, quotaBytes)
        refreshStats(); flushQueue()
    }

    override fun isReady(): Boolean = enabled && peers.isNotEmpty()

    override suspend fun send(toPeer: String, payload: ByteArray): SendOutcome {
        if (!enabled) return SendOutcome.Unavailable
        val self = ownId ?: return SendOutcome.Unavailable
        val stableId = MessageDigest.getInstance("SHA-256").digest(payload)
            .joinToString("") { "%02x".format(it) }
        val envelope = MeshEnvelopeCodec.create(stableId, self, toPeer, payload, signer ?: return SendOutcome.Unavailable)
            ?: return SendOutcome.Rejected("invalid offline mesh envelope")
        if (!store.put(envelope, quotaBytes)) return SendOutcome.Rejected("offline mesh storage quota reached")
        refreshStats(); flushQueue()
        return SendOutcome.Relayed
    }

    private suspend fun flushQueue() = flushLock.withLock {
        if (!enabled || peers.isEmpty()) return@withLock
        for (envelope in store.pending()) {
            if (!MeshEnvelopeCodec.verify(envelope)) { store.markDelivered(envelope.messageId); continue }
            if (!relayEnabled && envelope.originId != ownId) continue
            val delivered = sentTo.getOrPut(envelope.messageId) { ConcurrentHashMap.newKeySet() }
            for (peer in peers.values) {
                if (peer.id == envelope.originId || envelope.hops.any { it.relayId == peer.id } || peer.id in delivered) continue
                if (writeFrame(peer, MeshEnvelopeCodec.encode(envelope))) delivered.add(peer.id)
            }
        }
        refreshStats()
    }

    private suspend fun writeFrame(peer: GattPeer, packet: ByteArray): Boolean = peer.lock.withLock {
        if (packet.isEmpty() || packet.size > MeshEnvelopeCodec.MAX_FRAME_BYTES) return@withLock false
        val frame = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { it.writeInt(packet.size); it.write(packet) }; bytes.toByteArray()
        }
        val chunkSize = (peer.mtu - 3).coerceIn(20, 514)
        var offset = 0
        while (offset < frame.size) {
            val chunk = frame.copyOfRange(offset, minOf(frame.size, offset + chunkSize))
            val deferred = CompletableDeferred<Int>()
            writes[peer.gatt.device.address] = deferred
            val started = try {
                if (Build.VERSION.SDK_INT >= 33) {
                    peer.gatt.writeCharacteristic(peer.write, chunk, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION") peer.write.value = chunk
                    @Suppress("DEPRECATION") peer.write.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    @Suppress("DEPRECATION") peer.gatt.writeCharacteristic(peer.write)
                }
            } catch (_: Exception) { false }
            if (!started) { writes.remove(peer.gatt.device.address); return@withLock false }
            val result = try { withTimeout(WRITE_TIMEOUT_MS) { deferred.await() } }
                catch (_: Exception) { BluetoothGatt.GATT_FAILURE }
            if (result != BluetoothGatt.GATT_SUCCESS) return@withLock false
            offset += chunk.size
        }
        true
    }

    private fun refreshStats() {
        val (count, bytes) = store.stats()
        _stats.value = BleMeshStats(peers.size, count, bytes, store.expiredCount)
    }

    @Synchronized @Suppress("MissingPermission")
    private fun stopLocked() {
        enabled = false; maintenance?.cancel(); maintenance = null
        scanCallback?.let { runCatching { scanner?.stopScan(it) } }
        advertiseCallback?.let { runCatching { advertiser?.stopAdvertising(it) } }
        scanCallback = null; advertiseCallback = null
        peers.values.forEach { runCatching { it.gatt.disconnect(); it.gatt.close() } }
        peers.clear(); connecting.clear(); frames.clear()
        writes.values.forEach { it.complete(BluetoothGatt.GATT_FAILURE) }; writes.clear()
        runCatching { gattServer?.clearServices(); gattServer?.close() }
        gattServer = null; scanner = null; advertiser = null; ownId = null; signer = null
        sentTo.clear(); _stats.value = _stats.value.copy(neighborCount = 0); _status.value = BleMeshStatus.Disabled
    }

    companion object {
        private const val MAINTENANCE_MS = 30_000L
        private const val MAX_NEIGHBORS = 8
        private const val WRITE_TIMEOUT_MS = 12_000L
        private val SERVICE_UUID = UUID.fromString("f9a7d4e1-3b21-4c18-9f62-6b7d8a0c1e55")
        private val IDENTITY_UUID = UUID.fromString("f9a7d4e2-3b21-4c18-9f62-6b7d8a0c1e55")
        private val RX_UUID = UUID.fromString("f9a7d4e3-3b21-4c18-9f62-6b7d8a0c1e55")
    }
}
