package org.relyra.app.core.network

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.relyra.app.core.crypto.IdentityCrypto
import org.relyra.app.core.messaging.SendOutcome
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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class IceServerConfig(
    val url: String,
    val username: String = "",
    val credential: String = "",
) {
    fun isValid(): Boolean = url.length <= 512 &&
        (url.startsWith("stun:") || url.startsWith("stuns:") ||
            url.startsWith("turn:") || url.startsWith("turns:")) &&
        !url.any { it.isWhitespace() } &&
        ((url.startsWith("turn:") || url.startsWith("turns:")) ==
            (username.isNotBlank() && credential.isNotBlank()))
}

enum class InternetPeerState { NEW, CONNECTING, CONNECTED, DISCONNECTED, FAILED }

data class InternetPeerSession(
    val peerId: String,
    val type: String,
    val sdp: String,
    val issuedAt: Long,
)

/**
 * Signed, short-lived out-of-band WebRTC signaling envelope.
 * The SDP is shared only with the intended contact; it can contain network addresses.
 */
object InternetSignalCodec {
    private const val DOMAIN = "RELYRA-WEBRTC-SIGNAL-V1"
    private const val MAX_SDP_CHARS = 60_000
    private const val MAX_AGE_SECONDS = 10 * 60L

    fun encode(
        fromId: String,
        toId: String,
        type: String,
        sdp: String,
        issuedAt: Long,
        sign: (ByteArray) -> ByteArray?,
    ): String? {
        val from = fromId.trim().lowercase()
        val to = toId.trim().lowercase()
        if (IdentityCrypto.parsePublicId(from) == null ||
            IdentityCrypto.parsePublicId(to) == null ||
            from == to || type !in setOf("offer", "answer") ||
            sdp.isBlank() || sdp.length > MAX_SDP_CHARS
        ) return null
        val signature = sign(transcript(from, to, type, sdp, issuedAt)) ?: return null
        if (signature.size != 64) return null
        return JSONObject()
            .put("v", 1)
            .put("from", from)
            .put("to", to)
            .put("type", type)
            .put("sdp", sdp)
            .put("issuedAt", issuedAt)
            .put("sig", Base64.getUrlEncoder().withoutPadding().encodeToString(signature))
            .toString()
    }

    fun decode(
        encoded: String,
        ownId: String,
        expectedPeerId: String,
        nowSeconds: Long = System.currentTimeMillis() / 1000,
    ): InternetPeerSession? {
        if (encoded.length !in 1..80_000) return null
        return try {
            val json = JSONObject(encoded)
            if (json.getInt("v") != 1) return null
            val from = json.getString("from").lowercase()
            val to = json.getString("to").lowercase()
            val type = json.getString("type")
            val sdp = json.getString("sdp")
            val issuedAt = json.getLong("issuedAt")
            if (to != ownId.lowercase() || from != expectedPeerId.lowercase() ||
                type !in setOf("offer", "answer") || sdp.isBlank() ||
                sdp.length > MAX_SDP_CHARS || issuedAt > nowSeconds + 60 ||
                nowSeconds - issuedAt > MAX_AGE_SECONDS
            ) return null
            val publicKey = IdentityCrypto.parsePublicId(from) ?: return null
            val signature = Base64.getUrlDecoder().decode(json.getString("sig"))
            if (signature.size != 64 ||
                !IdentityCrypto.verify(publicKey, signature, transcript(from, to, type, sdp, issuedAt))
            ) return null
            InternetPeerSession(from, type, sdp, issuedAt)
        } catch (_: Exception) {
            null
        }
    }

    private fun transcript(from: String, to: String, type: String, sdp: String, issuedAt: Long): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeUTF(DOMAIN)
                out.writeUTF(from)
                out.writeUTF(to)
                out.writeUTF(type)
                out.writeLong(issuedAt)
                out.writeUTF(sdp)
            }
            bytes.toByteArray()
        }
}

/**
 * Direct Internet transport backed by WebRTC DataChannels (ICE/STUN/TURN + DTLS).
 *
 * Signaling is intentionally out-of-band in this first integration: exchange the signed offer/answer
 * strings privately with the intended contact. RELYRA message bytes remain end-to-end encrypted before
 * entering the DataChannel. TURN is supported when the user supplies a TURN URI and credentials.
 */
class WebRtcP2pConnector(
    context: Context,
    private val ownId: suspend () -> String?,
    private val sign: (ByteArray) -> ByteArray?,
    private val knownPeer: suspend (String) -> Boolean,
    private val onWire: suspend (ByteArray) -> Boolean,
    private val scope: CoroutineScope,
) : Connector {
    override val name: String = "Internet P2P (WebRTC)"

    private val appContext = context.applicationContext
    private val peers = ConcurrentHashMap<String, Peer>()
    private val states = MutableStateFlow<Map<String, InternetPeerState>>(emptyMap())
    val peerStates: StateFlow<Map<String, InternetPeerState>> = states
    private val initialized = AtomicBoolean(false)
    @Volatile private var iceServers: List<IceServerConfig> = listOf(
        IceServerConfig("stun:stun.l.google.com:19302"),
        IceServerConfig("stun:stun.cloudflare.com:3478"),
    )

    private data class Peer(
        val connection: PeerConnection,
        @Volatile var channel: DataChannel? = null,
        val gathering: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    fun setIceServers(servers: List<IceServerConfig>): Boolean {
        if (servers.size > 12 || servers.any { !it.isValid() }) return false
        iceServers = servers.toList()
        return true
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

    private fun createPeer(peerId: String): Peer? {
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
                val mapped = when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> InternetPeerState.CONNECTED
                    PeerConnection.IceConnectionState.CHECKING -> InternetPeerState.CONNECTING
                    PeerConnection.IceConnectionState.DISCONNECTED -> InternetPeerState.DISCONNECTED
                    PeerConnection.IceConnectionState.FAILED,
                    PeerConnection.IceConnectionState.CLOSED -> InternetPeerState.FAILED
                    else -> InternetPeerState.NEW
                }
                states.value = states.value + (peerId to mapped)
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
        peer = Peer(connection)
        peers.put(peerId, peer)?.let { closePeer(it) }
        states.value = states.value + (peerId to InternetPeerState.NEW)
        return peer
    }

    suspend fun createOffer(peerId: String): String? {
        val me = ownId() ?: return null
        val targetId = peerId.trim().lowercase()
        if (IdentityCrypto.parsePublicId(targetId) == null || !knownPeer(targetId)) return null
        return safely {
            withContext(Dispatchers.IO) {
                val peer = createPeer(targetId) ?: return@withContext null
                val init = DataChannel.Init().apply { ordered = true }
                attachChannel(targetId, peer, peer.connection.createDataChannel("relyra-wire-v1", init))
                val offer = createDescription(peer.connection, true)
                setDescription(peer.connection, true, offer)
                awaitGathering(peer)
                val local = peer.connection.localDescription?.description ?: return@withContext null
                InternetSignalCodec.encode(me, targetId, "offer", local, System.currentTimeMillis() / 1000, sign)
            }
        }.getOrNull()
    }

    suspend fun acceptOffer(peerId: String, encodedOffer: String): String? {
        val me = ownId() ?: return null
        val targetId = peerId.trim().lowercase()
        if (!knownPeer(targetId)) return null
        val signal = InternetSignalCodec.decode(encodedOffer, me, targetId) ?: return null
        if (signal.type != "offer") return null
        return safely {
            withContext(Dispatchers.IO) {
                val peer = createPeer(targetId) ?: return@withContext null
                val remote = SessionDescription(SessionDescription.Type.OFFER, signal.sdp)
                setDescription(peer.connection, false, remote)
                val answer = createDescription(peer.connection, false)
                setDescription(peer.connection, true, answer)
                awaitGathering(peer)
                val local = peer.connection.localDescription?.description ?: return@withContext null
                InternetSignalCodec.encode(me, targetId, "answer", local, System.currentTimeMillis() / 1000, sign)
            }
        }.getOrNull()
    }

    suspend fun acceptAnswer(peerId: String, encodedAnswer: String): Boolean {
        val me = ownId() ?: return false
        val targetId = peerId.trim().lowercase()
        if (!knownPeer(targetId)) return false
        val signal = InternetSignalCodec.decode(encodedAnswer, me, targetId) ?: return false
        if (signal.type != "answer") return false
        val peer = peers[targetId] ?: return false
        return try {
            setDescription(peer.connection, false, SessionDescription(SessionDescription.Type.ANSWER, signal.sdp))
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    override fun isReady(): Boolean = peers.values.any { it.channel?.state() == DataChannel.State.OPEN }

    override suspend fun send(toPeer: String, payload: ByteArray): SendOutcome {
        if (payload.isEmpty() || payload.size > MAX_DATA_CHANNEL_MESSAGE) {
            return SendOutcome.Rejected("WebRTC frame exceeds the supported message size")
        }
        val channel = peers[toPeer]?.channel ?: return SendOutcome.Unavailable
        if (channel.state() != DataChannel.State.OPEN) return SendOutcome.Unavailable
        val accepted = runCatching {
            channel.send(DataChannel.Buffer(ByteBuffer.wrap(payload), true))
        }.getOrDefault(false)
        return if (accepted) SendOutcome.Accepted else SendOutcome.Unavailable
    }

    fun close(peerId: String) {
        peers.remove(peerId)?.let(::closePeer)
        states.value = states.value - peerId
    }

    fun closeAll() {
        peers.values.toList().forEach(::closePeer)
        peers.clear()
        states.value = emptyMap()
    }

    private fun attachChannel(peerId: String, peer: Peer, channel: DataChannel) {
        peer.channel = channel
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit
            override fun onStateChange() = Unit
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (!buffer.binary || buffer.data.remaining() !in 1..MAX_DATA_CHANNEL_MESSAGE) return
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                scope.launch(Dispatchers.IO) {
                    try {
                        onWire(bytes)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        false
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

    private suspend fun setDescription(
        connection: PeerConnection,
        local: Boolean,
        description: SessionDescription,
    ) = suspendCancellableCoroutine<Unit> { continuation ->
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

    private fun closePeer(peer: Peer) {
        runCatching { peer.channel?.unregisterObserver() }
        runCatching { peer.channel?.close() }
        runCatching { peer.connection.close() }
        runCatching { peer.connection.dispose() }
    }

    companion object {
        const val MAX_DATA_CHANNEL_MESSAGE = 64 * 1024
    }
}
