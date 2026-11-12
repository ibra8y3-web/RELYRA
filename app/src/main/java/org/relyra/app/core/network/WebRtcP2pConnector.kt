package org.relyra.app.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.relyra.app.core.crypto.IdentityCrypto
import org.relyra.app.core.messaging.SendOutcome
import org.relyra.app.core.messaging.P2pSignalKind
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class IceServerConfig(
    val url: String,
    val username: String = "",
    val credential: String = "",
) {
    fun isValid(): Boolean {
        val usesTurn = url.startsWith("turn:") || url.startsWith("turns:")
        val hasUsername = username.isNotBlank()
        val hasCredential = credential.isNotBlank()
        val credentialsValid = if (usesTurn) hasUsername && hasCredential else !hasUsername && !hasCredential
        return url.length in 1..512 &&
            (url.startsWith("stun:") || url.startsWith("stuns:") ||
                url.startsWith("turn:") || url.startsWith("turns:")) &&
            !url.any { it.isWhitespace() || it.isISOControl() } &&
            username.length <= 256 && credential.length <= 512 &&
            !username.any { it.isISOControl() } && !credential.any { it.isISOControl() } &&
            credentialsValid
    }
}

enum class InternetPeerState { NEW, CONNECTING, CONNECTED, DISCONNECTED, FAILED }

data class InternetPeerSession(
    val peerId: String,
    val type: String,
    val sdp: String,
    val issuedAt: Long,
    val sessionId: String,
)

data class IncomingP2pOffer(
    val peerId: String,
    val sessionId: String,
    val signedSignal: String,
    val receivedAtMillis: Long,
)

/**
 * Signed, short-lived WebRTC signaling envelope. For automatic rendezvous it is carried inside
 * RELYRA's end-to-end encrypted control envelope; manual fallback text still needs a private channel.
 */
object InternetSignalCodec {
    private const val DOMAIN = "RELYRA-WEBRTC-SIGNAL-V1"
    private const val MAX_SDP_CHARS = 20_000
    private const val MAX_ENCODED_SIGNAL_CHARS = 48_000
    private const val MAX_AGE_SECONDS = 10 * 60L

    fun encode(
        fromId: String,
        toId: String,
        type: String,
        sdp: String,
        issuedAt: Long,
        sessionId: String,
        sign: (ByteArray) -> ByteArray?,
    ): String? {
        val from = fromId.trim().lowercase()
        val to = toId.trim().lowercase()
        val canonicalSession = runCatching { UUID.fromString(sessionId).toString() }.getOrNull() ?: return null
        if (canonicalSession != sessionId.lowercase() ||
            IdentityCrypto.parsePublicId(from) == null ||
            IdentityCrypto.parsePublicId(to) == null ||
            from == to || type !in setOf("offer", "answer") ||
            sdp.isBlank() || sdp.length > MAX_SDP_CHARS
        ) return null
        val signature = sign(transcript(from, to, type, sdp, issuedAt, canonicalSession)) ?: return null
        if (signature.size != 64) return null
        val encoded = JSONObject()
            .put("v", 1)
            .put("from", from)
            .put("to", to)
            .put("type", type)
            .put("sdp", sdp)
            .put("issuedAt", issuedAt)
            .put("sessionId", canonicalSession)
            .put("sig", Base64.getUrlEncoder().withoutPadding().encodeToString(signature))
            .toString()
        return encoded.takeIf { it.length <= MAX_ENCODED_SIGNAL_CHARS }
    }

    fun decode(
        encoded: String,
        ownId: String,
        expectedPeerId: String,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): InternetPeerSession? {
        if (encoded.length !in 1..MAX_ENCODED_SIGNAL_CHARS) return null
        return try {
            val json = JSONObject(encoded)
            if (json.getInt("v") != 1) return null
            val from = json.getString("from").lowercase()
            val to = json.getString("to").lowercase()
            val type = json.getString("type")
            val sdp = json.getString("sdp")
            val issuedAt = json.getLong("issuedAt")
            val sessionId = json.getString("sessionId").lowercase()
            val canonicalSession = runCatching { UUID.fromString(sessionId).toString() }.getOrNull() ?: return null
            if (canonicalSession != sessionId ||
                to != ownId.lowercase() || from != expectedPeerId.lowercase() ||
                type !in setOf("offer", "answer") || sdp.isBlank() ||
                sdp.length > MAX_SDP_CHARS || issuedAt > nowSeconds + 60 ||
                nowSeconds - issuedAt > MAX_AGE_SECONDS
            ) return null
            val publicKey = IdentityCrypto.parsePublicId(from) ?: return null
            val signature = Base64.getUrlDecoder().decode(json.getString("sig"))
            if (signature.size != 64 ||
                !IdentityCrypto.verify(publicKey, signature, transcript(from, to, type, sdp, issuedAt, sessionId))
            ) return null
            InternetPeerSession(from, type, sdp, issuedAt, sessionId)
        } catch (_: Exception) {
            null
        }
    }

    private fun transcript(
        from: String,
        to: String,
        type: String,
        sdp: String,
        issuedAt: Long,
        sessionId: String,
    ): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeUTF(DOMAIN)
            out.writeUTF(from)
            out.writeUTF(to)
            out.writeUTF(type)
            out.writeUTF(sessionId)
            out.writeLong(issuedAt)
            out.writeUTF(sdp)
        }
        bytes.toByteArray()
    }
}

/**
 * Direct Internet transport backed by WebRTC DataChannels (ICE/STUN/TURN + DTLS).
 *
 * Signed offers and answers are delivered in RELYRA E2EE control envelopes over an enabled route.
 * Incoming offers require explicit user approval. RELYRA message bytes remain end-to-end encrypted
 * before entering the DataChannel. TURN is supported when the user supplies a TURN URI and credentials.
 */
class WebRtcP2pConnector(
    context: Context,
    private val ownId: suspend () -> String?,
    private val sign: (ByteArray) -> ByteArray?,
    private val knownPeer: suspend (String) -> Boolean,
    private val onWire: suspend (ByteArray) -> Boolean,
    private val scope: CoroutineScope,
    private val sendSignal: suspend (String, P2pSignalKind, String) -> SendOutcome = { _, _, _ -> SendOutcome.Unavailable },
    private val refreshIceServers: suspend () -> List<IceServerConfig>? = { null },
) : Connector {
    override val name: String = "Internet P2P (WebRTC)"

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val peers = ConcurrentHashMap<String, Peer>()
    private val pendingAcks = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val approvedPeers = ConcurrentHashMap.newKeySet<String>()
    private val reconnecting = ConcurrentHashMap.newKeySet<String>()
    private val reconnectJobs = ConcurrentHashMap<String, kotlinx.coroutines.Job>()
    private val recoveryAttempts = ConcurrentHashMap<String, AtomicInteger>()
    private val states = MutableStateFlow<Map<String, InternetPeerState>>(emptyMap())
    private val _incomingOffers = MutableStateFlow<Map<String, IncomingP2pOffer>>(emptyMap())
    val peerStates: StateFlow<Map<String, InternetPeerState>> = states
    val incomingOffers: StateFlow<Map<String, IncomingP2pOffer>> = _incomingOffers
    private val initialized = AtomicBoolean(false)
    private val defaultIceServers = listOf(
        IceServerConfig("stun:stun.l.google.com:19302"),
        IceServerConfig("stun:stun.cloudflare.com:3478"),
    )
    @Volatile private var iceServers: List<IceServerConfig> = defaultIceServers
    @Volatile private var manualIceServersConfigured = false
    @Volatile private var networkCallbackRegistered = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scope.launch(Dispatchers.IO) {
                delay(1_000)
                peers.forEach { (peerId, peer) ->
                    val ice = peer.connection.iceConnectionState()
                    if (ice == PeerConnection.IceConnectionState.DISCONNECTED ||
                        ice == PeerConnection.IceConnectionState.FAILED ||
                        peer.channel?.state() != DataChannel.State.OPEN
                    ) {
                        scheduleRecovery(peerId, peer)
                    }
                }
            }
        }

        override fun onLost(network: Network) {
            peers.forEach { (peerId, _) ->
                states.value = states.value + (peerId to InternetPeerState.DISCONNECTED)
            }
        }
    }

    init {
        networkCallbackRegistered = runCatching {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
            true
        }.getOrDefault(false)
    }

    private data class Peer(
        val sessionId: String,
        val connection: PeerConnection,
        @Volatile var channel: DataChannel? = null,
        val gathering: CompletableDeferred<Unit> = CompletableDeferred(),
        val receiveMutex: Mutex = Mutex(),
        val sendMutex: Mutex = Mutex(),
    )

    fun setIceServers(servers: List<IceServerConfig>): Boolean {
        if (servers.size > 12 || servers.any { !it.isValid() }) return false
        iceServers = servers.toList()
        manualIceServersConfigured = true
        return true
    }

    fun setManagedIceServers(servers: List<IceServerConfig>): Boolean {
        if (servers.size > 12 || servers.any { !it.isValid() }) return false
        iceServers = servers.toList()
        manualIceServersConfigured = false
        return true
    }

    fun resetIceServers() {
        manualIceServersConfigured = false
        iceServers = defaultIceServers
    }

    private suspend fun refreshManagedIceServers() {
        if (manualIceServersConfigured) return
        val managed = try {
            refreshIceServers()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } ?: return
        if (managed.isNotEmpty() && managed.size <= 10 && managed.all(IceServerConfig::isValid)) {
            iceServers = defaultIceServers + managed
        }
    }

    private fun factory(): PeerConnectionFactory {
        if (initialized.compareAndSet(false, true)) {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(appContext).createInitializationOptions()
            )
        }
        return FactoryHolder.get(appContext)
    }

    private object FactoryHolder {
        @Volatile private var instance: PeerConnectionFactory? = null
        @Synchronized fun get(context: Context): PeerConnectionFactory {
            return instance ?: PeerConnectionFactory.builder().createPeerConnectionFactory().also { instance = it }
        }
    }

    private fun createPeer(peerId: String, sessionId: String): Peer? {
        if (peers[peerId] == null && peers.size >= MAX_PEER_SESSIONS) return null
        val servers = iceServers.map { cfg ->
            val builder = PeerConnection.IceServer.builder(cfg.url)
            if (cfg.username.isNotBlank()) builder.setUsername(cfg.username).setPassword(cfg.credential)
            builder.createIceServer()
        }
        val config = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        lateinit var peer: Peer
        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                if (peers[peerId] !== peer) return
                val mapped = when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED ->
                        if (peer.channel?.state() == DataChannel.State.OPEN) InternetPeerState.CONNECTED else InternetPeerState.CONNECTING
                    PeerConnection.IceConnectionState.CHECKING -> InternetPeerState.CONNECTING
                    PeerConnection.IceConnectionState.DISCONNECTED -> InternetPeerState.DISCONNECTED
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.CLOSED -> InternetPeerState.FAILED
                    else -> InternetPeerState.NEW
                }
                states.value = states.value + (peerId to mapped)
                if (mapped == InternetPeerState.CONNECTED) recoveryAttempts.remove(peerId)
                if (state == PeerConnection.IceConnectionState.DISCONNECTED ||
                    state == PeerConnection.IceConnectionState.FAILED
                ) {
                    scheduleRecovery(peerId, peer)
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) peer.gathering.complete(Unit)
            }
            override fun onIceCandidate(candidate: IceCandidate) = Unit
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
            override fun onAddStream(stream: org.webrtc.MediaStream) = Unit
            override fun onRemoveStream(stream: org.webrtc.MediaStream) = Unit
            override fun onDataChannel(channel: DataChannel) {
                attachChannel(peerId, peer, channel)
            }
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: org.webrtc.RtpReceiver, streams: Array<out org.webrtc.MediaStream>) = Unit
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) = Unit
            override fun onStandardizedIceConnectionChange(newState: PeerConnection.IceConnectionState) = Unit
        }
        val connection = factory().createPeerConnection(config, observer) ?: return null
        peer = Peer(sessionId, connection)
        peers.put(peerId, peer)?.let { closePeer(it) }
        states.value = states.value + (peerId to InternetPeerState.NEW)
        return peer
    }

    suspend fun createOffer(peerId: String): String? {
        val me = ownId() ?: return null
        val targetId = peerId.trim().lowercase()
        if (IdentityCrypto.parsePublicId(targetId) == null || !knownPeer(targetId)) return null
        refreshManagedIceServers()
        val sessionId = UUID.randomUUID().toString()
        return try {
            val result = safely {
                withContext(Dispatchers.IO) {
                    val peer = createPeer(targetId, sessionId) ?: return@withContext null
                    val init = DataChannel.Init().apply { ordered = true }
                    attachChannel(targetId, peer, peer.connection.createDataChannel("relyra-wire-v1", init))
                    val offer = createDescription(peer.connection, true)
                    setDescription(peer.connection, true, offer)
                    awaitGathering(peer)
                    val local = peer.connection.localDescription?.description ?: return@withContext null
                    InternetSignalCodec.encode(me, targetId, "offer", local, System.currentTimeMillis() / 1000, sessionId, sign)
                }
            }
            if (result == null) retirePeer(targetId)
            result
        } catch (cancelled: CancellationException) {
            retirePeer(targetId)
            throw cancelled
        }
    }

    suspend fun acceptOffer(peerId: String, encodedOffer: String): String? {
        val me = ownId() ?: return null
        val targetId = peerId.trim().lowercase()
        if (!knownPeer(targetId)) return null
        val signal = InternetSignalCodec.decode(encodedOffer, me, targetId) ?: return null
        if (signal.type != "offer") return null
        val pending = _incomingOffers.value[targetId]
        if (pending != null && pending.sessionId != signal.sessionId) return null
        refreshManagedIceServers()
        return try {
            val answer = safely {
                withContext(Dispatchers.IO) {
                    val peer = createPeer(targetId, signal.sessionId) ?: return@withContext null
                    val remote = SessionDescription(SessionDescription.Type.OFFER, signal.sdp)
                    setDescription(peer.connection, false, remote)
                    val answerDescription = createDescription(peer.connection, false)
                    setDescription(peer.connection, true, answerDescription)
                    awaitGathering(peer)
                    val local = peer.connection.localDescription?.description ?: return@withContext null
                    InternetSignalCodec.encode(me, targetId, "answer", local, System.currentTimeMillis() / 1000, signal.sessionId, sign)
                }
            }
            if (answer != null) {
                approvedPeers.add(targetId)
                peers[targetId]?.let { watchConnectionEstablishment(targetId, it) }
                dismissIncomingOffer(targetId)
            } else {
                retirePeer(targetId)
            }
            answer
        } catch (cancelled: CancellationException) {
            retirePeer(targetId)
            throw cancelled
        }
    }

    suspend fun acceptAnswer(peerId: String, encodedAnswer: String): Boolean {
        val me = ownId() ?: return false
        val targetId = peerId.trim().lowercase()
        if (!knownPeer(targetId)) return false
        val signal = InternetSignalCodec.decode(encodedAnswer, me, targetId) ?: return false
        if (signal.type != "answer") return false
        val peer = peers[targetId] ?: return false
        if (signal.sessionId != peer.sessionId) return false
        return try {
            setDescription(peer.connection, false, SessionDescription(SessionDescription.Type.ANSWER, signal.sdp))
            approvedPeers.add(targetId)
            watchConnectionEstablishment(targetId, peer)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    suspend fun requestIncomingOffer(peerId: String, signedOffer: String): Boolean {
        val me = ownId() ?: return false
        val targetId = peerId.trim().lowercase()
        if (!knownPeer(targetId)) return false
        val signal = InternetSignalCodec.decode(signedOffer, me, targetId) ?: return false
        if (signal.type != "offer") return false
        val current = peers[targetId]
        if (current?.sessionId == signal.sessionId) return false
        if (approvedPeers.contains(targetId)) {
            // This identity has already been approved for a direct session. Rebuilding a lost
            // transport does not ask the user to approve the same trusted contact again.
            scope.launch(Dispatchers.IO) {
                val answer = try {
                    acceptOffer(targetId, signedOffer)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                } ?: return@launch
                runCatching { sendSignal(targetId, P2pSignalKind.ANSWER, answer) }
            }
            return true
        }
        val existing = _incomingOffers.value[targetId]
        if (existing?.sessionId == signal.sessionId) return true
        if (existing == null && _incomingOffers.value.size >= MAX_PENDING_OFFERS) return false
        _incomingOffers.value = _incomingOffers.value + (
            targetId to IncomingP2pOffer(targetId, signal.sessionId, signedOffer, System.currentTimeMillis())
        )
        return true
    }

    fun dismissIncomingOffer(peerId: String) {
        val key = peerId.trim().lowercase()
        _incomingOffers.value = _incomingOffers.value - key
    }

    override fun isReady(): Boolean = peers.values.any { it.channel?.state() == DataChannel.State.OPEN }

    override suspend fun send(toPeer: String, payload: ByteArray): SendOutcome {
        val peer = peers[toPeer] ?: return SendOutcome.Unavailable
        return peer.sendMutex.withLock {
            val channel = peer.channel ?: return@withLock SendOutcome.Unavailable
            if (channel.state() != DataChannel.State.OPEN) return@withLock SendOutcome.Unavailable
            val transferId = UUID.randomUUID().toString()
            val frame = WebRtcFrameCodec.encodeMessage(payload, transferId) ?: return@withLock SendOutcome.Unavailable
            if (channel.bufferedAmount() > MAX_BUFFERED_BYTES - frame.remaining()) return@withLock SendOutcome.Unavailable

            val digest = WebRtcFrameCodec.digestKey(WebRtcFrameCodec.digestPayload(payload))
            val ackKey = "$toPeer:$transferId:$digest"
            val candidate = CompletableDeferred<Boolean>()
            val waiter = pendingAcks.putIfAbsent(ackKey, candidate) ?: candidate
            val queued = runCatching { channel.send(DataChannel.Buffer(frame, true)) }.getOrDefault(false)
            if (!queued) {
                pendingAcks.remove(ackKey, waiter)
                return@withLock SendOutcome.Unavailable
            }

            try {
                if (withTimeoutOrNull(ACK_TIMEOUT_MS) { waiter.await() } == true) {
                    SendOutcome.Accepted
                } else {
                    SendOutcome.Unavailable
                }
            } finally {
                pendingAcks.remove(ackKey, waiter)
            }
        }
    }

    fun close(peerId: String) {
        val key = peerId.trim().lowercase()
        approvedPeers.remove(key)
        reconnectJobs.remove(key)?.cancel()
        reconnecting.remove(key)
        recoveryAttempts.remove(key)
        retirePeer(key)
        dismissIncomingOffer(key)
    }

    fun closeAll() {
        reconnectJobs.values.forEach { it.cancel() }
        reconnectJobs.clear()
        reconnecting.clear()
        recoveryAttempts.clear()
        approvedPeers.clear()
        peers.keys.toList().forEach { retirePeer(it) }
        peers.clear()
        pendingAcks.values.forEach { it.complete(false) }
        pendingAcks.clear()
        _incomingOffers.value = emptyMap()
        states.value = emptyMap()
        if (networkCallbackRegistered) {
            runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
            networkCallbackRegistered = false
        }
    }

    private fun retirePeer(peerId: String, expected: Peer? = null) {
        val key = peerId.trim().lowercase()
        val current = peers[key] ?: return
        if (expected != null && current !== expected) return
        if (peers.remove(key, current)) closePeer(current)
        dismissIncomingOffer(key)
        pendingAcks.entries.filter { it.key.startsWith("$key:") }.forEach { entry ->
            entry.value.complete(false)
            pendingAcks.remove(entry.key, entry.value)
        }
        states.value = states.value - key
    }

    private fun scheduleRecovery(peerId: String, failedPeer: Peer) {
        val key = peerId.trim().lowercase()
        if (!approvedPeers.contains(key) || peers[key] !== failedPeer) return
        if (!reconnecting.add(key)) return
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            var expected: Peer? = failedPeer
            val attemptCounter = recoveryAttempts.computeIfAbsent(key) { AtomicInteger(0) }
            try {
                repeat(WebRtcReconnectPolicy.MAX_ATTEMPTS) {
                    if (!approvedPeers.contains(key)) return@launch
                    val attempt = attemptCounter.getAndIncrement()
                    if (attempt >= WebRtcReconnectPolicy.MAX_ATTEMPTS) {
                        states.value = states.value + (key to InternetPeerState.FAILED)
                        return@launch
                    }
                    if (expected != null) {
                        val iceState = expected!!.connection.iceConnectionState()
                        if (expected!!.channel?.state() == DataChannel.State.OPEN &&
                            (iceState == PeerConnection.IceConnectionState.CONNECTED ||
                                iceState == PeerConnection.IceConnectionState.COMPLETED)
                        ) {
                            recoveryAttempts.remove(key)
                            states.value = states.value + (key to InternetPeerState.CONNECTED)
                            return@launch
                        }
                    }
                    if (expected == null) {
                        if (peers.containsKey(key)) return@launch
                    } else if (peers[key] !== expected) {
                        return@launch
                    }

                    if (!awaitInternet()) {
                        delay(WebRtcReconnectPolicy.delayMillis(attempt))
                        return@repeat
                    }

                    expected?.let { retirePeer(key, it) }
                    expected = null
                    val offer = try {
                        createOffer(key)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    val createdPeer = peers[key]
                    expected = createdPeer
                    if (offer == null || createdPeer == null) {
                        if (createdPeer != null) retirePeer(key, createdPeer)
                        expected = null
                        delay(WebRtcReconnectPolicy.delayMillis(attempt))
                        return@repeat
                    }

                    val sent = try {
                        sendSignal(key, P2pSignalKind.OFFER, offer)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        SendOutcome.Unavailable
                    }
                    if (sent == SendOutcome.Accepted || sent == SendOutcome.Relayed) {
                        states.value = states.value + (key to InternetPeerState.CONNECTING)
                        return@launch
                    }
                    retirePeer(key, createdPeer)
                    expected = null
                    delay(WebRtcReconnectPolicy.delayMillis(attempt))
                }
                states.value = states.value + (key to InternetPeerState.FAILED)
            } finally {
                reconnectJobs.remove(key)
                reconnecting.remove(key)
            }
        }
        if (reconnectJobs.putIfAbsent(key, job) == null) {
            job.start()
        } else {
            reconnecting.remove(key)
            job.cancel()
        }
    }

    private fun watchConnectionEstablishment(peerId: String, expectedPeer: Peer) {
        scope.launch(Dispatchers.IO) {
            delay(NEGOTIATION_WATCHDOG_MS)
            if (peers[peerId] !== expectedPeer || !approvedPeers.contains(peerId)) return@launch
            val ice = expectedPeer.connection.iceConnectionState()
            val connected = expectedPeer.channel?.state() == DataChannel.State.OPEN &&
                (ice == PeerConnection.IceConnectionState.CONNECTED ||
                    ice == PeerConnection.IceConnectionState.COMPLETED)
            if (!connected) scheduleRecovery(peerId, expectedPeer)
        }
    }

    private suspend fun awaitInternet(): Boolean {
        repeat(WebRtcReconnectPolicy.NETWORK_WAIT_ATTEMPTS) {
            val active = connectivityManager.activeNetwork
            val capabilities = active?.let(connectivityManager::getNetworkCapabilities)
            if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) return true
            delay(1_000)
        }
        return false
    }

    private fun attachChannel(peerId: String, peer: Peer, channel: DataChannel) {
        if (peers[peerId] !== peer) {
            runCatching { channel.close() }
            runCatching { channel.dispose() }
            return
        }
        peer.channel = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() {
                if (peers[peerId] !== peer) return
                states.value = states.value + (peerId to when (channel.state()) {
                    DataChannel.State.OPEN -> InternetPeerState.CONNECTED
                    DataChannel.State.CONNECTING -> InternetPeerState.CONNECTING
                    DataChannel.State.CLOSING -> InternetPeerState.DISCONNECTED
                    DataChannel.State.CLOSED -> InternetPeerState.FAILED
                })
                if (channel.state() == DataChannel.State.OPEN) {
                    val ice = peer.connection.iceConnectionState()
                    if (ice == PeerConnection.IceConnectionState.CONNECTED ||
                        ice == PeerConnection.IceConnectionState.COMPLETED
                    ) recoveryAttempts.remove(peerId)
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (peers[peerId] !== peer) return
                if (!buffer.binary || buffer.data.remaining() !in WebRtcFrameCodec.ACK_BYTES..MAX_DATA_CHANNEL_MESSAGE) return
                val decoded = WebRtcFrameCodec.decode(buffer.data) ?: return
                when (decoded) {
                    is WebRtcFrameCodec.Frame.Ack -> {
                        val key = "$peerId:${decoded.transferId}:${WebRtcFrameCodec.digestKey(decoded.digest)}"
                        pendingAcks[key]?.complete(decoded.accepted)
                    }
                    is WebRtcFrameCodec.Frame.Message -> {
                        scope.launch(Dispatchers.IO) {
                            val accepted = try {
                                peer.receiveMutex.withLock { onWire(decoded.payload) }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                false
                            }
                            val ack = WebRtcFrameCodec.encodeAck(decoded.transferId, decoded.digest, accepted) ?: return@launch
                            if (channel.state() == DataChannel.State.OPEN) {
                                runCatching { channel.send(DataChannel.Buffer(ack, true)) }
                            }
                        }
                    }
                }
            }
        })
        states.value = states.value + (peerId to InternetPeerState.CONNECTING)
    }

    private suspend fun <T> safely(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun awaitGathering(peer: Peer) {
        try {
            withTimeout(25_000) { peer.gathering.await() }
        } catch (_: TimeoutCancellationException) {
            throw IllegalStateException("ICE candidate gathering timed out")
        }
    }

    private suspend fun createDescription(connection: PeerConnection, offer: Boolean): SessionDescription =
        withTimeout(SDP_CALLBACK_TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
            val observer = object : SdpObserver {
                override fun onCreateSuccess(description: SessionDescription) {
                    if (continuation.isActive) continuation.resume(description)
                }
                override fun onSetSuccess() = Unit
                override fun onCreateFailure(error: String) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error))
                }
                override fun onSetFailure(error: String) = Unit
            }
            if (offer) connection.createOffer(observer, MediaConstraints())
            else connection.createAnswer(observer, MediaConstraints())
            }
        }

    private suspend fun setDescription(
        connection: PeerConnection,
        local: Boolean,
        description: SessionDescription,
    ) = withTimeout(SDP_CALLBACK_TIMEOUT_MS) {
        suspendCancellableCoroutine<Unit> { continuation ->
        val observer = object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) = Unit
            override fun onSetSuccess() {
                if (continuation.isActive) continuation.resume(Unit)
            }
            override fun onCreateFailure(error: String) = Unit
            override fun onSetFailure(error: String) {
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException(error))
            }
        }
        if (local) connection.setLocalDescription(observer, description)
        else connection.setRemoteDescription(observer, description)
        }
    }

    private fun closePeer(peer: Peer) {
        runCatching { peer.channel?.unregisterObserver() }
        runCatching { peer.channel?.close() }
        runCatching { peer.connection.close() }
        runCatching { peer.connection.dispose() }
    }

    companion object {
        const val MAX_DATA_CHANNEL_MESSAGE = 64 * 1024
        private const val MAX_PEER_SESSIONS = 12
        private const val MAX_PENDING_OFFERS = 32
        private const val MAX_BUFFERED_BYTES = 512L * 1024L
        private const val ACK_TIMEOUT_MS = 8_000L
        private const val SDP_CALLBACK_TIMEOUT_MS = 12_000L
        private const val NEGOTIATION_WATCHDOG_MS = 30_000L
    }
}
