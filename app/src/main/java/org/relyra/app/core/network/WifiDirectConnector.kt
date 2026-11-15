package org.relyra.app.core.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.relyra.app.core.messaging.SendOutcome
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap

data class WifiDirectDiscoveredPeer(
    val name: String,
    val deviceAddress: String,
    val status: Int,
)

data class WifiDirectVerifiedPeer(
    val publicId: String,
    val address: InetAddress,
)

enum class WifiDirectTransportStatus {
    Disabled, PermissionRequired, Unsupported, WifiDirectUnavailable, Discovering, Connecting, Connected, Failed
}

/**
 * Android Wi-Fi Direct transport.
 *
 * Discovery only provides a device address; it never establishes RELYRA identity. Before a peer is
 * displayed as verified or allowed to exchange messages, both sides sign a fresh Ed25519 transcript.
 * Application payloads stay end-to-end encrypted above this transport.
 */
class WifiDirectConnector(
    context: Context,
    private val knownPeer: suspend (String) -> Boolean,
    private val sign: (ByteArray) -> ByteArray?,
    private val onWire: suspend (ByteArray) -> Boolean,
    private val scope: CoroutineScope,
) : Connector {
    override val name: String = "Wi-Fi Direct"

    private val appContext = context.applicationContext
    private val _status = MutableStateFlow(WifiDirectTransportStatus.Disabled)
    private val manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val channel: WifiP2pManager.Channel? = try {
        manager?.initialize(appContext, Looper.getMainLooper()) {
            _status.value = WifiDirectTransportStatus.Failed
        }
    } catch (_: Exception) {
        null
    }

    val status: StateFlow<WifiDirectTransportStatus> = _status
    private val _discovered = MutableStateFlow<List<WifiDirectDiscoveredPeer>>(emptyList())
    val discovered: StateFlow<List<WifiDirectDiscoveredPeer>> = _discovered
    private val _verified = MutableStateFlow<List<WifiDirectVerifiedPeer>>(emptyList())
    val verified: StateFlow<List<WifiDirectVerifiedPeer>> = _verified

    private val endpoints = ConcurrentHashMap<String, InetAddress>()
    @Volatile private var ownPublicId: String? = null
    @Volatile private var enabled = false
    @Volatile private var receiverRegistered = false

    private var receiver: BroadcastReceiver? = null
    private var server: ServerSocket? = null
    private var acceptJob: Job? = null
    private var ownerProbeJob: Job? = null

    @Synchronized
    fun configure(publicId: String?, shouldEnable: Boolean) {
        if (!shouldEnable || publicId.isNullOrBlank()) {
            stopLocked(removeGroup = enabled)
            return
        }
        if (!appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            stopLocked()
            _status.value = WifiDirectTransportStatus.Unsupported
            return
        }
        if (!hasRequiredPermission()) {
            stopLocked()
            _status.value = WifiDirectTransportStatus.PermissionRequired
            return
        }
        if (manager == null || channel == null) {
            stopLocked()
            _status.value = WifiDirectTransportStatus.Unsupported
            return
        }
        if (enabled && ownPublicId == publicId) return

        stopLocked(removeGroup = enabled)
        enabled = true
        ownPublicId = publicId
        registerReceiver()
        discoverPeers()
    }

    fun refreshPeers() {
        if (!enabled) return
        discoverPeers()
    }

    @Suppress("MissingPermission")
    fun connect(deviceAddress: String) {
        if (!enabled || !hasRequiredPermission()) {
            _status.value = WifiDirectTransportStatus.PermissionRequired
            return
        }
        val target = _discovered.value.firstOrNull { it.deviceAddress == deviceAddress } ?: return
        val p2p = manager ?: return
        val p2pChannel = channel ?: return
        val config = WifiP2pConfig().apply {
            this.deviceAddress = target.deviceAddress
            groupOwnerIntent = 0
        }
        _status.value = WifiDirectTransportStatus.Connecting
        try {
            p2p.connect(p2pChannel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    _status.value = WifiDirectTransportStatus.Connecting
                }

                override fun onFailure(reason: Int) {
                    _status.value = WifiDirectTransportStatus.Failed
                }
            })
        } catch (_: SecurityException) {
            _status.value = WifiDirectTransportStatus.PermissionRequired
        } catch (_: Exception) {
            _status.value = WifiDirectTransportStatus.Failed
        }
    }

    private fun hasRequiredPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            "android.permission.NEARBY_WIFI_DEVICES"
        } else {
            android.Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        val broadcast = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        if (state == WifiP2pManager.WIFI_P2P_STATE_DISABLED) {
                            closeTransport()
                            _status.value = WifiDirectTransportStatus.WifiDirectUnavailable
                        } else if (enabled) {
                            discoverPeers()
                        }
                    }

                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> requestConnectionInfo()
                    WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> Unit
                }
            }
        }
        receiver = broadcast
        try {
            ContextCompat.registerReceiver(appContext, broadcast, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        } catch (_: Exception) {
            _status.value = WifiDirectTransportStatus.Failed
        }
    }

    @Suppress("MissingPermission")
    private fun discoverPeers() {
        if (!enabled || !hasRequiredPermission()) {
            _status.value = WifiDirectTransportStatus.PermissionRequired
            return
        }
        val p2p = manager ?: return
        val p2pChannel = channel ?: return
        _status.value = WifiDirectTransportStatus.Discovering
        try {
            p2p.discoverPeers(p2pChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = requestPeers()
                override fun onFailure(reason: Int) {
                    _status.value = WifiDirectTransportStatus.Failed
                }
            })
        } catch (_: SecurityException) {
            _status.value = WifiDirectTransportStatus.PermissionRequired
        } catch (_: Exception) {
            _status.value = WifiDirectTransportStatus.Failed
        }
    }

    @Suppress("MissingPermission")
    private fun requestPeers() {
        if (!enabled || !hasRequiredPermission()) return
        val p2p = manager ?: return
        val p2pChannel = channel ?: return
        try {
            p2p.requestPeers(p2pChannel) { list ->
                _discovered.value = list.deviceList
                    .map { WifiDirectDiscoveredPeer(it.deviceName.orEmpty(), it.deviceAddress, it.status) }
                    .sortedBy { it.name.lowercase() }
            }
        } catch (_: SecurityException) {
            _status.value = WifiDirectTransportStatus.PermissionRequired
        } catch (_: Exception) {
            _status.value = WifiDirectTransportStatus.Failed
        }
    }

    @Suppress("MissingPermission")
    private fun requestConnectionInfo() {
        if (!enabled || !hasRequiredPermission()) return
        val p2p = manager ?: return
        val p2pChannel = channel ?: return
        try {
            p2p.requestConnectionInfo(p2pChannel) { info -> onConnectionInfo(info) }
        } catch (_: SecurityException) {
            _status.value = WifiDirectTransportStatus.PermissionRequired
        } catch (_: Exception) {
            _status.value = WifiDirectTransportStatus.Failed
        }
    }

    private fun onConnectionInfo(info: WifiP2pInfo) {
        val ownerAddress = info.groupOwnerAddress
        if (!enabled || !info.groupFormed || (!info.isGroupOwner && ownerAddress == null)) {
            closeTransport()
            if (enabled) _status.value = WifiDirectTransportStatus.Discovering
            return
        }
        startServer()
        _status.value = WifiDirectTransportStatus.Connected
        if (!info.isGroupOwner) {
            val address = ownerAddress ?: return
            ownerProbeJob?.cancel()
            ownerProbeJob = scope.launch(Dispatchers.IO) {
                repeat(6) {
                    if (!isActive || !enabled) return@launch
                    if (probeGroupOwner(address)) return@launch
                    delay(700L * (it + 1))
                }
            }
        }
    }

    private fun startServer() {
        if (server?.isBound == true && server?.isClosed == false) return
        try {
            val socket = ServerSocket()
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(TRANSPORT_PORT))
            server = socket
            acceptJob?.cancel()
            acceptJob = scope.launch(Dispatchers.IO) { acceptLoop(socket) }
        } catch (_: IOException) {
            _status.value = WifiDirectTransportStatus.Failed
        } catch (_: SecurityException) {
            _status.value = WifiDirectTransportStatus.Failed
        }
    }

    private suspend fun acceptLoop(listener: ServerSocket) {
        while (enabled && scope.coroutineContext.isActive && !listener.isClosed) {
            val connection = try {
                listener.accept()
            } catch (_: SocketException) {
                break
            } catch (_: IOException) {
                if (listener.isClosed) break else continue
            }
            scope.launch(Dispatchers.IO) { receive(connection) }
        }
    }

    private suspend fun receive(connection: Socket) {
        connection.use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val self = ownPublicId ?: return
            val remote = NearbyHandshakeProtocol.receive(
                socket.getInputStream(),
                socket.getOutputStream(),
                self,
                sign,
                knownPeer,
                onWire,
            )
            if (remote != null) remember(remote, socket.inetAddress)
        }
    }

    private suspend fun probeGroupOwner(address: InetAddress): Boolean = withContext(Dispatchers.IO) {
        val self = ownPublicId ?: return@withContext false
        val remote = try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(address, TRANSPORT_PORT), CONNECT_TIMEOUT_MS)
                socket.soTimeout = SOCKET_TIMEOUT_MS
                NearbyHandshakeProtocol.send(
                    socket.getInputStream(),
                    socket.getOutputStream(),
                    self,
                    expectedPeerId = null,
                    sign = sign,
                    knownPeer = knownPeer,
                    payload = ByteArray(0),
                )
            }
        } catch (_: Exception) {
            null
        }
        if (remote != null) {
            remember(remote, address)
            true
        } else {
            false
        }
    }

    private fun remember(publicId: String, address: InetAddress) {
        endpoints[publicId] = address
        _verified.value = endpoints.entries
            .map { WifiDirectVerifiedPeer(it.key, it.value) }
            .sortedBy { it.publicId }
        _status.value = WifiDirectTransportStatus.Connected
    }

    private fun closeTransport() {
        ownerProbeJob?.cancel()
        ownerProbeJob = null
        acceptJob?.cancel()
        acceptJob = null
        runCatching { server?.close() }
        server = null
        endpoints.clear()
        _verified.value = emptyList()
    }

    @Synchronized
    private fun stopLocked(removeGroup: Boolean = false) {
        enabled = false
        ownPublicId = null
        closeTransport()
        _discovered.value = emptyList()
        receiver?.let {
            if (receiverRegistered) runCatching { appContext.unregisterReceiver(it) }
        }
        receiver = null
        receiverRegistered = false
        if (removeGroup) {
            val p2p = manager
            val p2pChannel = channel
            if (p2p != null && p2pChannel != null) {
                runCatching {
                    p2p.removeGroup(p2pChannel, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() = Unit
                        override fun onFailure(reason: Int) = Unit
                    })
                }
            }
        }
        _status.value = WifiDirectTransportStatus.Disabled
    }

    override fun isReady(): Boolean {
        val listener = server ?: return false
        return enabled && listener.isBound && !listener.isClosed && endpoints.isNotEmpty()
    }

    override suspend fun send(toPeer: String, payload: ByteArray): SendOutcome {
        if (!enabled || payload.size > NearbyHandshakeProtocol.MAX_FRAME_BYTES) return SendOutcome.Unavailable
        val self = ownPublicId ?: return SendOutcome.Unavailable
        val address = endpoints[toPeer] ?: return SendOutcome.Unavailable
        return withContext(Dispatchers.IO) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(address, TRANSPORT_PORT), CONNECT_TIMEOUT_MS)
                    socket.soTimeout = SOCKET_TIMEOUT_MS
                    val remote = NearbyHandshakeProtocol.send(
                        socket.getInputStream(),
                        socket.getOutputStream(),
                        self,
                        expectedPeerId = toPeer,
                        sign = sign,
                        knownPeer = knownPeer,
                        payload = payload,
                    )
                    if (remote == toPeer) SendOutcome.Accepted else SendOutcome.Unavailable
                }
            } catch (_: IOException) {
                SendOutcome.Unavailable
            } catch (_: SecurityException) {
                SendOutcome.Unavailable
            }
        }
    }

    companion object {
        private const val TRANSPORT_PORT = 39_481
        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val SOCKET_TIMEOUT_MS = 8_000
    }
}
