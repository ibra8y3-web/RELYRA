package org.relyra.app.core.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ext.SdkExtensions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.relyra.app.core.crypto.IdentityCrypto
import org.relyra.app.core.messaging.SendOutcome
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

data class LanPeer(
    val publicId: String,
    val host: InetAddress,
    val port: Int,
)

enum class NearbyLanStatus { Disabled, Starting, Searching, Active, Failed }

/** Maximum application frame policy; authenticated wire framing lives in NearbyHandshakeProtocol. */
object LanFramePolicy {
    const val MAX_FRAME_BYTES = 256 * 1024
    fun isValidLength(length: Int): Boolean = length in 1..MAX_FRAME_BYTES
}

/**
 * Opt-in same-LAN transport.
 *
 * Android NSD/mDNS advertises a public RELYRA ID. Discovered IDs are only endpoint hints and must belong
 * to known contacts. Every socket then performs the shared mutual Ed25519 challenge before the connector
 * accepts an already end-to-end-encrypted payload and passes it to DeliveryEngine.acceptWire().
 *
 * This requires both devices to share an IP local network (Wi-Fi or a local hotspot). It is not Bluetooth,
 * Wi-Fi Direct, Internet P2P, libp2p, or NAT traversal.
 */
class NearbyLanConnector(
    context: Context,
    private val knownPeer: suspend (String) -> Boolean,
    private val sign: (ByteArray) -> ByteArray?,
    private val onWire: suspend (ByteArray) -> Boolean,
    private val scope: CoroutineScope,
    private val connectTimeoutMs: Int = 2_500,
    private val socketTimeoutMs: Int = 5_000,
) : Connector {
    override val name: String = "Nearby LAN"

    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(NsdManager::class.java)
    private val wifi = appContext.getSystemService(WifiManager::class.java)

    private val endpoints = ConcurrentHashMap<String, LanPeer>()
    private val serviceToPeer = ConcurrentHashMap<String, String>()
    private val resolvingNames = ConcurrentHashMap.newKeySet<String>()
    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private val resolving = AtomicBoolean(false)
    private val activeSockets = Semaphore(MAX_CONCURRENT_SOCKETS)

    private val _peers = MutableStateFlow<List<LanPeer>>(emptyList())
    val peers: StateFlow<List<LanPeer>> = _peers
    private val _status = MutableStateFlow(NearbyLanStatus.Disabled)
    val status: StateFlow<NearbyLanStatus> = _status

    @Volatile private var enabled = false
    @Volatile private var ownPublicId: String? = null
    @Volatile private var generation = 0L

    private var serverSocket: ServerSocket? = null
    private var acceptJob: kotlinx.coroutines.Job? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    @Synchronized
    fun configure(publicId: String?, shouldEnable: Boolean) {
        if (!shouldEnable || publicId.isNullOrBlank() || IdentityCrypto.parsePublicId(publicId) == null) {
            stopLocked()
            return
        }
        if (enabled && ownPublicId == publicId && serverSocket?.isBound == true) return

        stopLocked()
        enabled = true
        ownPublicId = publicId
        _status.value = NearbyLanStatus.Starting
        val currentGeneration = generation

        try {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress(0))
            serverSocket = server
            acquireMulticastLock()
            acceptJob = scope.launch(Dispatchers.IO) { acceptLoop(server, currentGeneration) }

            val info = NsdServiceInfo().apply {
                serviceName = "RELYRA-" + publicId.takeLast(8)
                serviceType = SERVICE_TYPE
                port = server.localPort
                setAttribute(TXT_PUBLIC_ID, publicId)
            }
            val registration = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    if (generation == currentGeneration && enabled) _status.value = NearbyLanStatus.Searching
                }

                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    if (generation == currentGeneration) _status.value = NearbyLanStatus.Failed
                }

                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            }
            registrationListener = registration
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
            beginDiscovery(currentGeneration)
        } catch (_: Exception) {
            _status.value = NearbyLanStatus.Failed
            stopSocketOnly()
        }
    }

    @Synchronized
    private fun stopLocked() {
        generation++
        enabled = false
        ownPublicId = null
        _status.value = NearbyLanStatus.Disabled
        runCatching { discoveryListener?.let { nsd.stopServiceDiscovery(it) } }
        runCatching { registrationListener?.let { nsd.unregisterService(it) } }
        discoveryListener = null
        registrationListener = null
        resolvingNames.clear()
        resolveQueue.clear()
        endpoints.clear()
        serviceToPeer.clear()
        _peers.value = emptyList()
        resolving.set(false)
        stopSocketOnly()
        multicastLock?.let { lock -> runCatching { if (lock.isHeld) lock.release() } }
        multicastLock = null
    }

    private fun stopSocketOnly() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    /**
     * Android 12 and earlier, and Android 13 devices below T extension 7, need an explicit lock to receive
     * multicast. Newer foreground NSD manages this automatically; avoid holding a Wi-Fi lock there.
     */
    private fun acquireMulticastLock() {
        val needsLock = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) < 7
        if (!needsLock) return
        val manager = wifi ?: return
        runCatching {
            manager.createMulticastLock("relyra-local-discovery").also { lock ->
                lock.setReferenceCounted(false)
                if (!lock.isHeld) lock.acquire()
                multicastLock = lock
            }
        }
    }

    private fun beginDiscovery(currentGeneration: Long) {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                if (generation == currentGeneration && enabled) _status.value = NearbyLanStatus.Searching
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (generation != currentGeneration || !enabled) return
                if (serviceInfo.serviceType != SERVICE_TYPE) return
                if (resolvingNames.add(serviceInfo.serviceName)) {
                    resolveQueue.add(serviceInfo)
                    resolveNext(currentGeneration)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val id = serviceToPeer.remove(serviceInfo.serviceName)
                if (id != null) {
                    endpoints.remove(id)
                    publishPeers()
                }
            }

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                _status.value = NearbyLanStatus.Failed
                runCatching { nsd.stopServiceDiscovery(this) }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                runCatching { nsd.stopServiceDiscovery(this) }
            }
        }
        discoveryListener = listener
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) {
            _status.value = NearbyLanStatus.Failed
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveNext(currentGeneration: Long) {
        if (!resolving.compareAndSet(false, true)) return
        val candidate = resolveQueue.poll()
        if (candidate == null) {
            resolving.set(false)
            return
        }
        try {
            nsd.resolveService(candidate, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    resolvingNames.remove(candidate.serviceName)
                    resolving.set(false)
                    resolveNext(currentGeneration)
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    resolvingNames.remove(candidate.serviceName)
                    resolving.set(false)
                    scope.launch(Dispatchers.IO) {
                        if (generation != currentGeneration || !enabled) return@launch
                        val publicId = serviceInfo.attributes[TXT_PUBLIC_ID]
                            ?.let { String(it, Charsets.UTF_8) }
                            ?.trim()
                            ?.lowercase()
                            ?: return@launch
                        if (publicId == ownPublicId || IdentityCrypto.parsePublicId(publicId) == null) return@launch
                        val host = serviceInfo.host ?: return@launch
                        if (host.isAnyLocalAddress || host.isLoopbackAddress || host.isMulticastAddress) return@launch
                        val port = serviceInfo.port
                        if (port !in 1..65_535) return@launch
                        val trusted = runCatching { knownPeer(publicId) }.getOrDefault(false)
                        if (!trusted || generation != currentGeneration || !enabled) return@launch

                        endpoints[publicId] = LanPeer(publicId, host, port)
                        serviceToPeer[serviceInfo.serviceName] = publicId
                        publishPeers()
                    }
                    resolveNext(currentGeneration)
                }
            })
        } catch (_: Exception) {
            resolvingNames.remove(candidate.serviceName)
            resolving.set(false)
            resolveNext(currentGeneration)
        }
    }

    private fun publishPeers() {
        val current = endpoints.values.sortedBy { it.publicId }
        _peers.value = current
        _status.value = if (current.isEmpty()) NearbyLanStatus.Searching else NearbyLanStatus.Active
    }

    private suspend fun acceptLoop(server: ServerSocket, currentGeneration: Long) {
        while (currentCoroutineContext().isActive && currentGeneration == generation && !server.isClosed) {
            val socket = try {
                server.accept()
            } catch (_: SocketException) {
                break
            } catch (_: IOException) {
                if (server.isClosed) break else continue
            }
            if (!activeSockets.tryAcquire()) {
                runCatching { socket.close() }
                continue
            }
            scope.launch(Dispatchers.IO) {
                try {
                    receiveFrame(socket)
                } finally {
                    activeSockets.release()
                }
            }
        }
    }

    private suspend fun receiveFrame(socket: Socket) = withContext(Dispatchers.IO) {
        socket.use { connection ->
            connection.soTimeout = socketTimeoutMs
            val publicId = ownPublicId ?: return@withContext
            NearbyHandshakeProtocol.receive(
                connection.getInputStream(),
                connection.getOutputStream(),
                publicId,
                sign,
                knownPeer,
                onWire,
            )
        }
    }

    override fun isReady(): Boolean = enabled && serverSocket?.isBound == true && endpoints.isNotEmpty()

    override suspend fun send(toPeer: String, payload: ByteArray): SendOutcome {
        if (!LanFramePolicy.isValidLength(payload.size)) return SendOutcome.Rejected("local frame too large")
        val peer = endpoints[toPeer] ?: return SendOutcome.Unavailable
        val publicId = ownPublicId ?: return SendOutcome.Unavailable
        if (!enabled) return SendOutcome.Unavailable
        return withContext(Dispatchers.IO) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(peer.host, peer.port), connectTimeoutMs)
                    socket.soTimeout = socketTimeoutMs
                    val remote = NearbyHandshakeProtocol.send(
                        socket.getInputStream(),
                        socket.getOutputStream(),
                        publicId,
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

    private companion object {
        const val SERVICE_TYPE = "_relyra._tcp."
        const val TXT_PUBLIC_ID = "peer"
        const val MAX_CONCURRENT_SOCKETS = 4
    }
}
