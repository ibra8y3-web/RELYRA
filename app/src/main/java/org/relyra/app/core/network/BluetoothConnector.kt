package org.relyra.app.core.network

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

enum class BluetoothTransportStatus { Disabled, PermissionRequired, Unsupported, BluetoothOff, Listening, Active, Failed }

/**
 * Bluetooth Classic RFCOMM transport for already-bonded Android devices.
 *
 * Pairing is deliberately left to Android Settings. RELYRA then performs a mutual Ed25519 challenge-response
 * and only accepts contacts already present in its local address book. A Bluetooth bond by itself is not
 * treated as a RELYRA identity.
 */
class BluetoothConnector(
    context: Context,
    private val knownPeer: suspend (String) -> Boolean,
    private val onWire: suspend (ByteArray) -> Boolean,
    private val scope: CoroutineScope,
) : Connector {
    override val name: String = "Bluetooth Classic"

    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter
    private val _status = MutableStateFlow(BluetoothTransportStatus.Disabled)
    val status: StateFlow<BluetoothTransportStatus> = _status
    private val _peers = MutableStateFlow<List<String>>(emptyList())
    val peers: StateFlow<List<String>> = _peers
    private val peerDevices = ConcurrentHashMap<String, android.bluetooth.BluetoothDevice>()
    private val activeConnections = Semaphore(MAX_CONCURRENT_CONNECTIONS)

    @Volatile private var ownPublicId: String? = null
    @Volatile private var signer: ((ByteArray) -> ByteArray?)? = null
    @Volatile private var enabled = false
    private var server: BluetoothServerSocket? = null
    private var acceptJob: Job? = null
    private var discoveryJob: Job? = null

    @Synchronized
    @Suppress("MissingPermission")
    fun configure(publicId: String?, sign: ((ByteArray) -> ByteArray?)?, shouldEnable: Boolean) {
        if (!shouldEnable || publicId.isNullOrBlank() || sign == null) {
            stopLocked()
            return
        }
        if (!hasConnectPermission()) {
            stopLocked()
            _status.value = BluetoothTransportStatus.PermissionRequired
            return
        }
        val bt = adapter
        if (bt == null) {
            stopLocked()
            _status.value = BluetoothTransportStatus.Unsupported
            return
        }
        if (!bt.isEnabled) {
            stopLocked()
            _status.value = BluetoothTransportStatus.BluetoothOff
            return
        }
        if (enabled && ownPublicId == publicId && server != null) return

        stopLocked()
        enabled = true
        ownPublicId = publicId
        signer = sign
        try {
            server = bt.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
            val listener = server ?: error("Bluetooth server socket unavailable")
            acceptJob = scope.launch(Dispatchers.IO) { acceptLoop(listener) }
            _status.value = BluetoothTransportStatus.Listening
        } catch (_: SecurityException) {
            stopLocked()
            _status.value = BluetoothTransportStatus.PermissionRequired
        } catch (_: IOException) {
            stopLocked()
            _status.value = BluetoothTransportStatus.Failed
        } catch (_: Exception) {
            stopLocked()
            _status.value = BluetoothTransportStatus.Failed
        }
    }

    @Synchronized
    private fun stopLocked() {
        enabled = false
        ownPublicId = null
        signer = null
        acceptJob?.cancel()
        discoveryJob?.cancel()
        acceptJob = null
        discoveryJob = null
        runCatching { server?.close() }
        server = null
        peerDevices.clear()
        _peers.value = emptyList()
        _status.value = BluetoothTransportStatus.Disabled
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    @Suppress("MissingPermission")
    private suspend fun acceptLoop(listener: BluetoothServerSocket) {
        while (enabled && kotlinx.coroutines.currentCoroutineContext().isActive) {
            val socket = try {
                listener.accept()
            } catch (_: SecurityException) {
                _status.value = BluetoothTransportStatus.PermissionRequired
                break
            } catch (_: IOException) {
                if (!enabled) break
                continue
            }
            if (!activeConnections.tryAcquire()) {
                runCatching { socket.close() }
                continue
            }
            scope.launch(Dispatchers.IO) {
                try {
                    handleInbound(socket)
                } finally {
                    activeConnections.release()
                }
            }
        }
    }

    @Suppress("MissingPermission")
    private suspend fun handleInbound(socket: BluetoothSocket) {
        socket.use { connection ->
            val self = ownPublicId ?: return
            val sign = signer ?: return
            val remote = withTimeoutClose(connection) {
                NearbyHandshakeProtocol.receive(
                    connection.inputStream,
                    connection.outputStream,
                    self,
                    sign,
                    knownPeer,
                    onWire,
                )
            }
            if (remote != null) remember(remote, connection.remoteDevice)
        }
    }

    /** Explicit user-triggered check of already-paired devices; it does not start a Bluetooth radio scan. */
    @Suppress("MissingPermission")
    fun discoverBondedPeers() {
        if (!enabled || !hasConnectPermission()) {
            _status.value = BluetoothTransportStatus.PermissionRequired
            return
        }
        val bt = adapter ?: run {
            _status.value = BluetoothTransportStatus.Unsupported
            return
        }
        discoveryJob?.cancel()
        discoveryJob = scope.launch(Dispatchers.IO) {
            val devices = try {
                bt.bondedDevices.toList()
            } catch (_: SecurityException) {
                _status.value = BluetoothTransportStatus.PermissionRequired
                emptyList()
            }
            for (device in devices) {
                if (!enabled || !kotlinx.coroutines.currentCoroutineContext().isActive) break
                probe(device)
            }
            if (enabled && peerDevices.isEmpty()) _status.value = BluetoothTransportStatus.Listening
        }
    }

    @Suppress("MissingPermission")
    private suspend fun probe(device: android.bluetooth.BluetoothDevice) {
        val self = ownPublicId ?: return
        val sign = signer ?: return
        withContext(Dispatchers.IO) {
            val socket = try {
                device.createRfcommSocketToServiceRecord(SERVICE_UUID)
            } catch (_: SecurityException) {
                _status.value = BluetoothTransportStatus.PermissionRequired
                return@withContext
            } catch (_: IOException) {
                return@withContext
            }
            try {
                socket.connect()
                val remote = withTimeoutClose(socket) {
                    NearbyHandshakeProtocol.send(
                        socket.inputStream,
                        socket.outputStream,
                        self,
                        expectedPeerId = null,
                        sign = sign,
                        knownPeer = knownPeer,
                        payload = ByteArray(0),
                    )
                }
                if (remote != null) remember(remote, device)
            } catch (_: Exception) {
                Unit
            } finally {
                runCatching { socket.close() }
            }
        }
    }

    private suspend fun <T> withTimeoutClose(socket: BluetoothSocket, block: suspend () -> T): T {
        val closeJob = scope.launch {
            delay(SOCKET_TIMEOUT_MS.toLong())
            runCatching { socket.close() }
        }
        return try {
            block()
        } finally {
            closeJob.cancel()
        }
    }

    private fun remember(publicId: String, device: android.bluetooth.BluetoothDevice) {
        peerDevices[publicId] = device
        _peers.value = peerDevices.keys.sorted()
        _status.value = BluetoothTransportStatus.Active
    }

    override fun isReady(): Boolean =
        enabled && server != null && peerDevices.isNotEmpty()

    @Suppress("MissingPermission")
    override suspend fun send(toPeer: String, payload: ByteArray): org.relyra.app.core.messaging.SendOutcome {
        if (!enabled || payload.size > NearbyHandshakeProtocol.MAX_FRAME_BYTES) {
            return org.relyra.app.core.messaging.SendOutcome.Unavailable
        }
        val self = ownPublicId ?: return org.relyra.app.core.messaging.SendOutcome.Unavailable
        val sign = signer ?: return org.relyra.app.core.messaging.SendOutcome.Unavailable
        val cached = peerDevices[toPeer]
        val candidates = buildList {
            if (cached != null) add(cached)
            val bonded = try { adapter?.bondedDevices?.toList().orEmpty() } catch (_: SecurityException) { emptyList() }
            bonded.filterNot { it.address == cached?.address }.forEach(::add)
        }
        for (device in candidates) {
            val result = withContext(Dispatchers.IO) {
                val socket = try {
                    device.createRfcommSocketToServiceRecord(SERVICE_UUID)
                } catch (_: Exception) {
                    return@withContext null
                }
                try {
                    socket.connect()
                    withTimeoutClose(socket) {
                        NearbyHandshakeProtocol.send(
                            socket.inputStream,
                            socket.outputStream,
                            self,
                            expectedPeerId = toPeer,
                            sign = sign,
                            knownPeer = knownPeer,
                            payload = payload,
                        )
                    }
                } catch (_: Exception) {
                    null
                } finally {
                    runCatching { socket.close() }
                }
            }
            if (result == toPeer) {
                remember(result, device)
                return org.relyra.app.core.messaging.SendOutcome.Accepted
            }
        }
        return org.relyra.app.core.messaging.SendOutcome.Unavailable
    }

    private companion object {
        const val SERVICE_NAME = "RELYRA Secure Nearby"
        const val SOCKET_TIMEOUT_MS = 8_000
        const val MAX_CONCURRENT_CONNECTIONS = 4
        val SERVICE_UUID: UUID = UUID.fromString("6b3f7a12-9e43-4a66-a2c8-1e8f5c0d7b31")
    }
}
