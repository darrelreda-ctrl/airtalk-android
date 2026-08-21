package com.airtalk.app.net

import android.os.Handler
import android.os.Looper
import com.airtalk.app.auth.TokenManager
import com.airtalk.app.model.FilterConfig
import com.airtalk.app.model.Messages
import com.airtalk.app.model.TurnCredential
import com.airtalk.app.util.DebugLog
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.util.concurrent.TimeUnit

interface SignalingListener {
    fun onSocketConnected()
    fun onSocketClosed(reason: String, retrying: Boolean)
    fun onUserStatus(sessionId: String, clientId: String, acl: String, loggedIn: Boolean)
    fun onOnlineCount(count: Int)
    fun onInit(turn: TurnCredential)
    fun onRemoteSdp(remoteClientId: String, turn: TurnCredential?, country: String?, sdp: SessionDescription)
    fun onRemoteCandidate(candidate: IceCandidate)
    fun onRemoteHangUp()
    fun onCancelReconnection(clientId: String)
    fun onConfigUpdate(strict: Boolean)
    fun onKicked() // server sent PAGE_REFRESH (bad/expired token)
}

/**
 * Transport + protocol dispatch for wss://api.airtalk.live/signaling.
 * All listener callbacks are delivered on the main thread.
 */
class SignalingClient(
    private val tokenProvider: () -> String,
    private val filterProvider: () -> FilterConfig
) : WebSocketListener() {

    @Volatile
    var listener: SignalingListener? = null

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val main = Handler(Looper.getMainLooper())
    private var socket: WebSocket? = null
    private var intentionalClose = false
    private var reconnectAttempts = 0
    private var reconnectScheduled = false

    private val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

    @Synchronized
    fun connect() {
        if (socket != null) return
        intentionalClose = false
        reconnectAttempts = 0
        connectInternal()
    }

    private fun connectInternal() {
        // Guarantee a single live socket: close any previous one first.
        socket?.let { s -> try { s.close(1000, "replacing") } catch (e: Exception) {} }
        try {
            val url = "wss://api.airtalk.live/signaling?token=" + java.net.URLEncoder.encode(tokenProvider(), "UTF-8")
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Cookie", "artlk_ui_version=0.0.2")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            socket = http.newWebSocket(request, this)
        } catch (e: Exception) {
            scheduleReconnect()
        }
    }

    @Synchronized
    fun disconnect() {
        intentionalClose = true
        reconnectScheduled = false
        main.removeCallbacks(reconnectRunnable)
        socket?.close(1000, "bye")
        socket = null
    }

    @Synchronized
    fun send(text: String): Boolean {
        val ws = socket
        val ok = ws != null && ws.send(text)
        DebugLog.append("WS", "SEND ok=$ok ${text.take(160)}")
        return ok
    }

    fun sendFilterUpdate() = send(Messages.filterUpdate(filterProvider()))
    fun sendStatusUpdate(status: String) = send(Messages.statusUpdate(status))

    // ---------- WebSocketListener ----------

    override fun onOpen(webSocket: WebSocket, response: Response) {
        reconnectAttempts = 0
        DebugLog.append("WS", "OPEN")
        main.post {
            sendFilterUpdate()
            listener?.onSocketConnected()
        }
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        main.post { handleMessage(text) }
    }

    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        webSocket.close(1000, null)
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        main.post {
            if (webSocket === socket) socket = null
            val retry = code != 3401 && code != 3403
            DebugLog.append("WS", "CLOSE code=$code reason='$reason' retry=$retry")
            if (!intentionalClose) {
                listener?.onSocketClosed("closed:$code", retry)
                scheduleReconnect()
            }
        }
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        main.post {
            if (webSocket === socket) socket = null
            DebugLog.append("WS", "FAILURE ${t.message}")
            if (!intentionalClose) {
                listener?.onSocketClosed(t.message ?: "failure", true)
                scheduleReconnect()
            }
        }
    }

    private val reconnectRunnable = Runnable {
        reconnectScheduled = false
        // Tear down any dangling socket, then wait a beat so the server releases
        // the prior session before we open a new one (avoids "Duplicate session").
        socket?.let { s -> try { s.close(1000, "replacing") } catch (e: Exception) {} }
        main.postDelayed({ connectInternal() }, 1500)
    }

    private fun scheduleReconnect() {
        if (intentionalClose || reconnectScheduled) return
        reconnectScheduled = true
        val delay = minOf(2000L * (1 shl minOf(reconnectAttempts, 3)), 15000L)
        reconnectAttempts++
        main.postDelayed(reconnectRunnable, delay)
    }

    // ---------- protocol ----------

    private fun handleMessage(raw: String) {
        try {
        DebugLog.append("MSG", "RECV ${raw.take(200)}")
        if (raw == "PING") {
            send("PONG")
            DebugLog.append("MSG", "PING -> sent PONG")
            return
        }
        val msg = try { JSONObject(raw) } catch (e: Exception) { return }
        when (val type = msg.optString("messageType")) {
            "USER_STATUS" -> listener?.onUserStatus(
                msg.optString("sessionId"),
                msg.optString("clientId"),
                msg.optString("acl"),
                msg.optBoolean("loggedIn")
            )
            "ONLINE_MEMBERS" -> listener?.onOnlineCount(msg.optInt("count"))
            "PING" -> send("PONG")
            "CONFIG_UPDATE" -> listener?.onConfigUpdate(msg.optInt("strict") == 1)
            "INIT" -> {
                val t = msg.optJSONObject("turnCredential")
                listener?.onInit(
                    if (t != null) TurnCredential(t.optString("username"), t.optString("password"))
                    else TurnCredential("", "")
                )
            }
            "SDP" -> handleSdp(msg)
            "CANDIDATE" -> {
                val c = msg.optJSONObject("content") ?: return
                val sdp = c.optString("candidate")
                val mid = c.optString("sdpMid")
                val mline = c.optInt("sdpMLineIndex")
                listener?.onRemoteCandidate(IceCandidate(mid, mline, sdp))
            }
            "HANG_UP" -> listener?.onRemoteHangUp()
            "CANCEL_RECONNECTION" -> listener?.onCancelReconnection(msg.optString("clientId"))
            "PAGE_REFRESH" -> {
                // token rejected: try to refresh it, then reconnect
                TokenManager.ensureFreshToken()
                listener?.onKicked()
                scheduleReconnect()
            }
            "CALL_REQUEST" -> {
                // v1: no callback-call UI; politely decline so the caller is not left hanging
                val id = msg.optString("clientId")
                if (id.isNotBlank()) send(Messages.callResponse(id, "DECLINED"))
            }
            // v1 ignores: TEXT_CHAT, FRIEND*, CALL_RESPONSE, USER_QUERY_RESPONSE, AUTH, ONLINE_MEMBERS extras
        }
        } catch (e: Exception) {
            DebugLog.append("MSG", "DISPATCH EXCEPTION ${e.message}\n${e.stackTraceToString()}")
        }
    }

    private fun handleSdp(msg: JSONObject) {
        val content = msg.optJSONObject("content") ?: return
        val type = content.optString("type")
        val sdp = content.optString("sdp")
        if (sdp.isBlank()) return
        val turn = msg.optJSONObject("turnCredential")?.let {
            TurnCredential(it.optString("username"), it.optString("password"))
        }
        listener?.onRemoteSdp(
            msg.optString("remoteClientId"),
            turn,
            msg.optString("country").ifBlank { null },
            SessionDescription(
                if (type == "answer") SessionDescription.Type.ANSWER else SessionDescription.Type.OFFER,
                sdp
            )
        )
    }
}