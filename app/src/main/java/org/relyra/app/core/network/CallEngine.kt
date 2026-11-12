package org.relyra.app.core.network

import android.content.Context
import android.media.AudioManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.relyra.app.core.messaging.P2pSignalKind
import org.relyra.app.core.messaging.SendOutcome
import org.webrtc.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class CallPhase { IDLE, OUTGOING, INCOMING, CONNECTING, ACTIVE, ENDED, FAILED }
data class CallUiState(
    val peerId: String? = null, val phase: CallPhase = CallPhase.IDLE,
    val video: Boolean = false, val muted: Boolean = false,
    val cameraEnabled: Boolean = true, val speakerEnabled: Boolean = true,
    val startedAtMillis: Long = 0L, val detail: String? = null,
    val incomingSignal: String? = null,
)

/** WebRTC media calls. SDP is identity-signed and carried by RELYRA's E2EE P2P signal envelope. */
class CallEngine(
    private val context: Context,
    private val ownId: suspend () -> String?,
    private val knownPeer: suspend (String) -> Boolean,
    private val sign: (ByteArray) -> ByteArray?,
    private val sendSignal: suspend (String, P2pSignalKind, String) -> SendOutcome,
    @Suppress("UNUSED_PARAMETER") scope: CoroutineScope,
    private val iceServersProvider: suspend () -> List<IceServerConfig> = { emptyList() },
) {
    private val app = context.applicationContext
    private val mutableState = MutableStateFlow(CallUiState())
    val state: StateFlow<CallUiState> = mutableState
    private val localVideoMutable = MutableStateFlow<VideoTrack?>(null)
    val localVideo: StateFlow<VideoTrack?> = localVideoMutable
    private val remoteVideoMutable = MutableStateFlow<VideoTrack?>(null)
    val remoteVideo: StateFlow<VideoTrack?> = remoteVideoMutable
    private val sessions = ConcurrentHashMap<String, Session>()
    private val egl by lazy { EglBase.create() }
    private val factory by lazy {
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions())
        PeerConnectionFactory.builder().createPeerConnectionFactory()
    }

    private data class Session(
        val id: String, val connection: PeerConnection, val video: Boolean,
        var audioSource: AudioSource? = null, var audioTrack: AudioTrack? = null,
        var videoSource: VideoSource? = null, var videoTrack: VideoTrack? = null,
        var capturer: CameraVideoCapturer? = null, var texture: SurfaceTextureHelper? = null,
    )

    fun eglContext(): EglBase.Context = egl.eglBaseContext

    suspend fun start(peerId: String, withVideo: Boolean): Boolean {
        val peer = peerId.trim().lowercase()
        if (mutableState.value.phase !in setOf(CallPhase.IDLE, CallPhase.ENDED, CallPhase.FAILED) ||
            !knownPeer(peer) || ownId() == null) return false
        mutableState.value = CallUiState(peerId = peer, phase = CallPhase.OUTGOING, video = withVideo)
        return try {
            val id = UUID.randomUUID().toString()
            val session = createSession(peer, id, withVideo) ?: error("WebRTC initialization failed")
            val offer = createDescription(session.connection, true)
            setDescription(session.connection, true, offer)
            val sdp = session.connection.localDescription?.description ?: error("Missing local SDP")
            sessions[peer] = session
            val signal = signed(peer, "call_offer", sdp, id) ?: error("Could not sign call offer")
            requireDelivered(sendSignal(peer, P2pSignalKind.CALL_OFFER, signal))
            mutableState.value = mutableState.value.copy(phase = CallPhase.CONNECTING, startedAtMillis = System.currentTimeMillis())
            true
        } catch (t: Throwable) {
            close(peer)
            mutableState.value = CallUiState(peerId = peer, phase = CallPhase.FAILED, video = withVideo, detail = t.message)
            false
        }
    }

    suspend fun receiveOffer(peerId: String, encoded: String) {
        val peer = peerId.trim().lowercase()
        if (!knownPeer(peer) || mutableState.value.phase !in setOf(CallPhase.IDLE, CallPhase.ENDED, CallPhase.FAILED)) return
        val signal = InternetSignalCodec.decode(encoded, ownId() ?: return, peer) ?: return
        if (signal.type != "call_offer") return
        mutableState.value = CallUiState(peerId = peer, phase = CallPhase.INCOMING,
            video = signal.sdp.contains("m=video"), incomingSignal = encoded)
    }

    suspend fun acceptIncoming() {
        val current = mutableState.value
        val peer = current.peerId ?: return
        val offer = current.incomingSignal ?: return
        val decoded = InternetSignalCodec.decode(offer, ownId() ?: return, peer) ?: return
        if (decoded.type != "call_offer") return
        try {
            val session = createSession(peer, decoded.sessionId, current.video) ?: error("WebRTC initialization failed")
            setDescription(session.connection, false, SessionDescription(SessionDescription.Type.OFFER, decoded.sdp))
            val answer = createDescription(session.connection, false)
            setDescription(session.connection, true, answer)
            val sdp = session.connection.localDescription?.description ?: error("Missing answer SDP")
            sessions[peer] = session
            val signal = signed(peer, "call_answer", sdp, decoded.sessionId) ?: error("Could not sign call answer")
            requireDelivered(sendSignal(peer, P2pSignalKind.CALL_ANSWER, signal))
            mutableState.value = current.copy(phase = CallPhase.CONNECTING, incomingSignal = null,
                startedAtMillis = System.currentTimeMillis())
        } catch (t: Throwable) {
            close(peer)
            mutableState.value = current.copy(phase = CallPhase.FAILED, incomingSignal = null, detail = t.message)
        }
    }

    suspend fun receiveAnswer(peerId: String, encoded: String) {
        val peer = peerId.trim().lowercase()
        val decoded = InternetSignalCodec.decode(encoded, ownId() ?: return, peer) ?: return
        val session = sessions[peer] ?: return
        if (decoded.type != "call_answer" || decoded.sessionId != session.id) return
        try {
            setDescription(session.connection, false, SessionDescription(SessionDescription.Type.ANSWER, decoded.sdp))
            mutableState.value = mutableState.value.copy(phase = CallPhase.CONNECTING)
        } catch (t: Throwable) {
            close(peer)
            mutableState.value = mutableState.value.copy(phase = CallPhase.FAILED, detail = t.message)
        }
    }

    suspend fun decline() = sendControl(P2pSignalKind.CALL_DECLINE, "call_decline")
    suspend fun hangUp() = sendControl(P2pSignalKind.CALL_END, "call_end")

    private suspend fun sendControl(kind: P2pSignalKind, type: String) {
        val peer = mutableState.value.peerId ?: return
        val id = sessions[peer]?.id ?: UUID.randomUUID().toString()
        signed(peer, type, "", id)?.let { runCatching { sendSignal(peer, kind, it) } }
        close(peer)
        mutableState.value = CallUiState(peerId = peer, phase = CallPhase.ENDED)
    }

    suspend fun receiveDecline(peerId: String, encoded: String) = receiveControl(peerId, encoded, "call_decline")
    suspend fun receiveEnd(peerId: String, encoded: String) = receiveControl(peerId, encoded, "call_end")

    private suspend fun receiveControl(peerId: String, encoded: String, type: String) {
        val peer = peerId.trim().lowercase()
        val signal = InternetSignalCodec.decode(encoded, ownId() ?: return, peer) ?: return
        if (signal.type != type) return
        val activeId = sessions[peer]?.id
        if (activeId != null && activeId != signal.sessionId) return
        close(peer)
        mutableState.value = CallUiState(peerId = peer, phase = CallPhase.ENDED)
    }

    fun setMuted(value: Boolean) {
        val peer = mutableState.value.peerId ?: return
        sessions[peer]?.audioTrack?.setEnabled(!value)
        mutableState.value = mutableState.value.copy(muted = value)
    }
    fun setCameraEnabled(value: Boolean) {
        val peer = mutableState.value.peerId ?: return
        sessions[peer]?.videoTrack?.setEnabled(value)
        mutableState.value = mutableState.value.copy(cameraEnabled = value)
    }
    fun setSpeakerEnabled(value: Boolean) {
        (app.getSystemService(Context.AUDIO_SERVICE) as AudioManager).apply {
            mode = AudioManager.MODE_IN_COMMUNICATION
            isSpeakerphoneOn = value
        }
        mutableState.value = mutableState.value.copy(speakerEnabled = value)
    }
    fun switchCamera() { sessions[mutableState.value.peerId]?.capturer?.switchCamera(null) }

    private suspend fun createSession(peer: String, id: String, video: Boolean): Session? {
        val ice = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
        ) + iceServersProvider().filter { it.isValid() }.map {
            val b = PeerConnection.IceServer.builder(it.url)
            if (it.username.isNotBlank()) b.setUsername(it.username).setPassword(it.credential)
            b.createIceServer()
        }
        val config = PeerConnection.RTCConfiguration(ice.take(14)).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
        lateinit var session: Session
        val observer = object : PeerConnection.Observer {
            override fun onSignalingChange(s: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {
                if (mutableState.value.peerId != peer) return
                when (s) {
                    PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED ->
                        mutableState.value = mutableState.value.copy(phase = CallPhase.ACTIVE, detail = null)
                    PeerConnection.IceConnectionState.DISCONNECTED ->
                        mutableState.value = mutableState.value.copy(phase = CallPhase.CONNECTING, detail = "Connection interrupted")
                    PeerConnection.IceConnectionState.FAILED, PeerConnection.IceConnectionState.CLOSED -> {
                        mutableState.value = mutableState.value.copy(phase = CallPhase.FAILED, detail = "Connection failed")
                        close(peer)
                    }
                    else -> Unit
                }
            }
            override fun onIceConnectionReceivingChange(b: Boolean) = Unit
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) = Unit
            override fun onIceCandidate(c: IceCandidate) = Unit
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) = Unit
            override fun onAddStream(s: MediaStream) = Unit
            override fun onRemoveStream(s: MediaStream) = Unit
            override fun onDataChannel(c: DataChannel) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                (receiver.track() as? VideoTrack)?.let { it.setEnabled(true); remoteVideoMutable.value = it }
                (receiver.track() as? AudioTrack)?.setEnabled(true)
            }
            override fun onTrack(transceiver: RtpTransceiver) {
                (transceiver.receiver.track() as? VideoTrack)?.let { it.setEnabled(true); remoteVideoMutable.value = it }
                (transceiver.receiver.track() as? AudioTrack)?.setEnabled(true)
            }
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState) = Unit
            override fun onStandardizedIceConnectionChange(s: PeerConnection.IceConnectionState) = Unit
        }
        val pc = factory.createPeerConnection(config, observer) ?: return null
        session = Session(id, pc, video)
        val audioSource = factory.createAudioSource(MediaConstraints())
        val audioTrack = factory.createAudioTrack("relyra-audio-$id", audioSource)
        session.audioSource = audioSource; session.audioTrack = audioTrack
        pc.addTrack(audioTrack, listOf("relyra-call-$id"))
        if (video) {
            val cameras = Camera2Enumerator(app)
            val name = cameras.deviceNames.firstOrNull { cameras.isFrontFacing(it) } ?: cameras.deviceNames.firstOrNull()
                ?: error("No camera is available")
            val capturer = cameras.createCapturer(name, null) as? CameraVideoCapturer ?: error("Camera capture unavailable")
            val texture = SurfaceTextureHelper.create("RELYRA-camera", egl.eglBaseContext)
            val source = factory.createVideoSource(false)
            capturer.initialize(texture, app, source.capturerObserver)
            capturer.startCapture(1280, 720, 24)
            val track = factory.createVideoTrack("relyra-video-$id", source)
            session.capturer = capturer; session.texture = texture; session.videoSource = source; session.videoTrack = track
            pc.addTrack(track, listOf("relyra-call-$id"))
            localVideoMutable.value = track
        }
        (app.getSystemService(Context.AUDIO_SERVICE) as AudioManager).apply {
            mode = AudioManager.MODE_IN_COMMUNICATION; isSpeakerphoneOn = true
        }
        return session
    }

    private suspend fun signed(peer: String, type: String, sdp: String, id: String): String? =
        InternetSignalCodec.encode(ownId() ?: return null, peer, type, sdp, System.currentTimeMillis() / 1000, id, sign)

    private suspend fun createDescription(pc: PeerConnection, offer: Boolean): SessionDescription = withTimeout(12_000) {
        suspendCancellableCoroutine { c ->
            val o = object : SdpObserver {
                override fun onCreateSuccess(d: SessionDescription) { if (c.isActive) c.resume(d) }
                override fun onSetSuccess() = Unit
                override fun onCreateFailure(e: String) { if (c.isActive) c.resumeWithException(IllegalStateException(e)) }
                override fun onSetFailure(e: String) = Unit
            }
            if (offer) pc.createOffer(o, MediaConstraints()) else pc.createAnswer(o, MediaConstraints())
        }
    }
    private suspend fun setDescription(pc: PeerConnection, local: Boolean, d: SessionDescription) = withTimeout(12_000) {
        suspendCancellableCoroutine<Unit> { c ->
            val o = object : SdpObserver {
                override fun onCreateSuccess(d: SessionDescription) = Unit
                override fun onSetSuccess() { if (c.isActive) c.resume(Unit) }
                override fun onCreateFailure(e: String) = Unit
                override fun onSetFailure(e: String) { if (c.isActive) c.resumeWithException(IllegalStateException(e)) }
            }
            if (local) pc.setLocalDescription(o, d) else pc.setRemoteDescription(o, d)
        }
    }
    private fun requireDelivered(outcome: SendOutcome) {
        check(outcome is SendOutcome.Accepted || outcome is SendOutcome.Relayed) { "Call signaling could not be delivered" }
    }
    private fun close(peer: String) {
        sessions.remove(peer)?.let { s ->
            runCatching { s.capturer?.stopCapture() }; runCatching { s.capturer?.dispose() }
            runCatching { s.videoTrack?.dispose() }; runCatching { s.videoSource?.dispose() }
            runCatching { s.texture?.dispose() }; runCatching { s.audioTrack?.dispose() }
            runCatching { s.audioSource?.dispose() }; runCatching { s.connection.close() }
            runCatching { s.connection.dispose() }
        }
        localVideoMutable.value = null; remoteVideoMutable.value = null
        (app.getSystemService(Context.AUDIO_SERVICE) as AudioManager).apply {
            isSpeakerphoneOn = false; mode = AudioManager.MODE_NORMAL
        }
    }
    fun dispose() {
        sessions.keys.toList().forEach(::close)
        runCatching { factory.dispose() }; runCatching { egl.release() }
    }
}
