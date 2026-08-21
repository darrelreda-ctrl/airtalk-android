package com.airtalk.app.rtc

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.airtalk.app.model.Messages
import com.airtalk.app.model.TurnCredential
import com.airtalk.app.net.SignalingClient
import com.airtalk.app.net.SignalingListener
import org.webrtc.SdpObserver
import java.nio.ByteBuffer
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SessionDescription
import com.airtalk.app.util.DebugLog

interface CallListener {
    fun onStateChanged(state: CallState, extra: String = "") {}
    fun onPeerMuted(muted: Boolean) {}
    fun onRemoteHangUp() {}
    fun onSocketStatus(status: String) {}
    fun onOnlineCount(count: Int) {}
}

enum class CallState { IDLE, SEARCHING, CONNECTING, CONNECTED }

/**
 * WebRTC audio call manager. Owns the PeerConnection, audio track, data channel
 * and keepalive. All calls happen on the main thread.
 */
class WebRtcManager(
    private val context: Context,
    private val signaling: SignalingClient,
    var listener: CallListener?
) : SignalingListener {

    companion object {
        private const val DC_LABEL = "message_channel"
        private const val KEEPALIVE_MS = 10_000L
        private const val MSG_KEEPALIVE = "#kplv#"
        private const val MSG_HANG_UP = "#hang_up#"
        private const val MSG_MUTE_ON = "#mute_enabled#"
        private const val MSG_MUTE_OFF = "#mute_disabled#"
        private val STUN = listOf(
            "stun:stun.l.google.com:19302",
            "stun:stun1.l.google.com:19302",
            "stun:stun2.l.google.com:19302"
        )
        private const val TURN_URL = "turn:5.75.164.144:3478"
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var factory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var dataChannel: DataChannel? = null
    private var keepAlive: Runnable? = null

    var remoteClientId: String = ""
        private set
    private var country: String? = null
    var state: CallState = CallState.IDLE
        private set
    private var muted = false
    private var intentionalClose = false
    private var matchLocked = false

    fun initialize() {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)
        factory = PeerConnectionFactory.builder()
            .setOptions(null)
            .createPeerConnectionFactory()
    }

    // ---------- public controls ----------

    fun startSearching() {
        if (state == CallState.SEARCHING || state == CallState.CONNECTING) return
        if (state == CallState.CONNECTED) { hangUp(); return }
        DebugLog.append("RTC", "startSearching state=$state")
        intentionalClose = false
        matchLocked = false
        remoteClientId = ""
        initAudio()
        setState(CallState.SEARCHING)
        signaling.sendStatusUpdate("FREE")
    }

    fun hangUp() {
        intentionalClose = true
        dataChannel?.send(DataChannel.Buffer(ByteBuffer.wrap(MSG_HANG_UP.toByteArray()), false))
        disposePeer()
        setState(CallState.IDLE)
        signaling.sendStatusUpdate("STALE")
    }

    fun toggleMute(): Boolean {
        muted = !muted
        audioTrack?.setEnabled(!muted)
        dataChannel?.send(DataChannel.Buffer(ByteBuffer.wrap((if (muted) MSG_MUTE_ON else MSG_MUTE_OFF).toByteArray()), false))
        return muted
    }

    fun report(reason: String) {
        if (remoteClientId.isNotBlank()) {
            signaling.send(Messages.report(remoteClientId, "INAPPROPRIATE_BEHAVIOR", reason))
        }
    }

    fun dispose() {
        intentionalClose = true
        disposePeer()
        setState(CallState.IDLE)
    }

    // ---------- SignalingListener ----------

    override fun onSocketConnected() {
        listener?.onSocketStatus("connected")
        // if we were searching when the socket dropped, resume the search
        if (state == CallState.SEARCHING) {
            signaling.sendStatusUpdate("FREE")
        }
    }

    override fun onSocketClosed(reason: String, retrying: Boolean) {
        DebugLog.append("RTC", "socketClosed reason=$reason retry=$retrying")
        listener?.onSocketStatus(if (retrying) "reconnecting…" else "disconnected")
        // connection lost while in a call: end it and go idle; re-queue via UI
        disposePeer()
        matchLocked = false
        remoteClientId = ""
        if (state == CallState.CONNECTING || state == CallState.CONNECTED) {
            setState(CallState.IDLE)
            listener?.onStateChanged(CallState.IDLE, "connection lost")
        }
    }

    override fun onUserStatus(sessionId: String, clientId: String, acl: String, loggedIn: Boolean) {}

    override fun onOnlineCount(count: Int) {
        listener?.onOnlineCount(count)
    }

    override fun onInit(turn: TurnCredential) {
        // we are the offerer
        DebugLog.append("RTC", "onInit")
        remoteClientId = ""
        createPeer(turn)
        dataChannel = peerConnection?.createDataChannel(DC_LABEL, DataChannel.Init())
        setupDataChannel(dataChannel)
        addAudioTrack()
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                signaling.send(Messages.sdp("OFFER", sdp))
                setState(CallState.CONNECTING)
            }
            override fun onCreateFailure(error: String) { DebugLog.append("RTC", "SdpObserver onCreateFailure=$error") }
            override fun onSetSuccess() {}
            override fun onSetFailure(error: String) { DebugLog.append("RTC", "SdpObserver onSetFailure=$error") }
        }, mediaConstraints())
    }

    override fun onRemoteSdp(remoteClientId: String, turn: TurnCredential?, country: String?, sdp: SessionDescription) {
        // Commit to the first match; ignore other simultaneous offers until this call ends.
        DebugLog.append("RTC", "onRemoteSdp id=$remoteClientId type=${sdp.type} locked=$matchLocked sigState=${peerConnection?.signalingState()}")
        if (matchLocked && remoteClientId != this.remoteClientId) return

        if (sdp.type == SessionDescription.Type.OFFER) {
            // We are the answerer: lock, set remote offer, then answer.
            if (!matchLocked) {
                matchLocked = true
                this.remoteClientId = remoteClientId
                this.country = country
                if (peerConnection == null) turn?.let { createPeer(it) }
            }
            val pc = peerConnection ?: return
            val st = pc.signalingState()
            if (st != PeerConnection.SignalingState.STABLE && st != PeerConnection.SignalingState.HAVE_REMOTE_OFFER) return
            pc.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(s: SessionDescription) {}
                override fun onCreateFailure(error: String) { DebugLog.append("RTC", "SdpObserver onCreateFailure=$error") }
                override fun onSetSuccess() {
                    addAudioTrack()
                    pc.createAnswer(object : SdpObserver {
                        override fun onCreateSuccess(answer: SessionDescription) {
                            pc.setLocalDescription(object : SdpObserver {
                                override fun onCreateSuccess(s: SessionDescription) {}
                                override fun onCreateFailure(error: String) { DebugLog.append("RTC", "SdpObserver onCreateFailure=$error") }
                                override fun onSetSuccess() {
                                    DebugLog.append("RTC", "send ANSWER to $remoteClientId")
                                    signaling.send(Messages.sdp("ANSWER", answer))
                                    setState(CallState.CONNECTING)
                                }
                                override fun onSetFailure(error: String) { DebugLog.append("RTC", "SdpObserver onSetFailure=$error") }
                            }, answer)
                        }
                        override fun onCreateFailure(error: String) { DebugLog.append("RTC", "SdpObserver onCreateFailure=$error") }
                        override fun onSetSuccess() {}
                        override fun onSetFailure(error: String) { DebugLog.append("RTC", "SdpObserver onSetFailure=$error") }
                    }, mediaConstraints())
                }
                override fun onSetFailure(error: String) { DebugLog.append("RTC", "SdpObserver onSetFailure=$error") }
            }, sdp)
        } else {
            // We are the offerer and this is the answer to our offer.
            val pc = peerConnection ?: return
            if (pc.signalingState() != PeerConnection.SignalingState.HAVE_LOCAL_OFFER) return
            this.remoteClientId = remoteClientId
            pc.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(s: SessionDescription) {}
                override fun onCreateFailure(error: String) { DebugLog.append("RTC", "SdpObserver onCreateFailure=$error") }
                override fun onSetSuccess() {
                    DebugLog.append("RTC", "remote ANSWER applied id=$remoteClientId")
                }
                override fun onSetFailure(error: String) { DebugLog.append("RTC", "SdpObserver onSetFailure=$error") }
            }, sdp)
        }
    }

    override fun onRemoteCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    override fun onRemoteHangUp() {
        DebugLog.append("RTC", "remoteHangUp")
        disposePeer()
        matchLocked = false
        setState(CallState.IDLE)
        listener?.onRemoteHangUp()
        signaling.sendStatusUpdate("STALE")
    }

    override fun onCancelReconnection(clientId: String) {
        if (clientId.isNotBlank() && clientId == remoteClientId) {
            disposePeer()
            matchLocked = false
            setState(CallState.IDLE)
        }
    }

    override fun onConfigUpdate(strict: Boolean) {}

    override fun onKicked() {
        listener?.onSocketStatus("re-authenticating…")
        disposePeer()
        matchLocked = false
        setState(CallState.IDLE)
    }

    // ---------- internals ----------

    private fun createPeer(turn: TurnCredential?) {
        disposePeer()
        DebugLog.append("RTC", "createPeer hasTurn=${turn != null}")
        val servers = STUN.map { PeerConnection.IceServer.builder(it).createIceServer() }.toMutableList()
        if (turn != null) {
            servers.add(PeerConnection.IceServer.builder(TURN_URL).setUsername(turn.username).setPassword(turn.password).createIceServer())
        }
        val config = PeerConnection.RTCConfiguration(servers)
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        peerConnection = factory.createPeerConnection(config, pcObserver)
    }

    private val pcObserver = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            DebugLog.append("RTC", "ICE candidate send mid=${candidate.sdpMid} mline=${candidate.sdpMLineIndex}")
            signaling.send(Messages.candidate(candidate))
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            DebugLog.append("RTC", "ICE state=$state")
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED -> onPeerConnected()
                PeerConnection.IceConnectionState.DISCONNECTED -> Unit // transient
                PeerConnection.IceConnectionState.FAILED,
                PeerConnection.IceConnectionState.CLOSED -> {
                    disposePeer()
                    setState(CallState.IDLE)
                }
                else -> {}
            }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onDataChannel(channel: DataChannel) {
            if (channel.label() == DC_LABEL) {
                dataChannel = channel
                setupDataChannel(channel)
            }
        }
        override fun onRenegotiationNeeded() {}
        override fun onAddStream(stream: org.webrtc.MediaStream) {}
        override fun onRemoveStream(stream: org.webrtc.MediaStream) {}
        override fun onAddTrack(receiver: org.webrtc.RtpReceiver, streams: Array<out org.webrtc.MediaStream>) {}
        override fun onRemoveTrack(receiver: org.webrtc.RtpReceiver) {}
    }

    private fun setupDataChannel(channel: DataChannel?) {
        if (channel == null) return
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                if (channel.state() == DataChannel.State.OPEN) {
                    onPeerConnected()
                }
            }
            override fun onMessage(buffer: DataChannel.Buffer) {
                val data = buffer.data
                val bytes = ByteArray(data.remaining())
                data.get(bytes)
                val text = String(bytes, Charsets.UTF_8)
                when (text) {
                    MSG_HANG_UP -> main.post { onRemoteHangUp() }
                    MSG_MUTE_ON -> main.post { listener?.onPeerMuted(true) }
                    MSG_MUTE_OFF -> main.post { listener?.onPeerMuted(false) }
                }
            }
        })
    }

    private fun onPeerConnected() {
        if (state == CallState.CONNECTED) return
        DebugLog.append("RTC", "peerConnected id=$remoteClientId")
        setState(CallState.CONNECTED)
        signaling.send(Messages.established(remoteClientId))
        startKeepAlive()
    }

    private fun startKeepAlive() {
        stopKeepAlive()
        keepAlive = object : Runnable {
            override fun run() {
                dataChannel?.send(DataChannel.Buffer(ByteBuffer.wrap(MSG_KEEPALIVE.toByteArray()), false))
                main.postDelayed(this, KEEPALIVE_MS)
            }
        }
        main.postDelayed(keepAlive!!, KEEPALIVE_MS)
    }

    private fun stopKeepAlive() {
        keepAlive?.let { main.removeCallbacks(it) }
        keepAlive = null
    }

    private fun initAudio() {
        if (audioTrack != null) return
        val source = factory.createAudioSource(mediaConstraints())
        audioSource = source
        audioTrack = factory.createAudioTrack("airtalk_audio0", source)
    }

    private fun addAudioTrack() {
        initAudio()
        audioTrack?.let { peerConnection?.addTrack(it, listOf("airtalk")) }
    }

    private fun mediaConstraints(): MediaConstraints =
        MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }

    private fun disposePeer() {
        stopKeepAlive()
        dataChannel?.unregisterObserver()
        dataChannel?.close()
        dataChannel = null
        peerConnection?.close()
        peerConnection = null
    }

    private fun setState(s: CallState) {
        if (state == s) return
        state = s
        DebugLog.append("RTC", "setState ${s}")
        listener?.onStateChanged(s)
    }
}