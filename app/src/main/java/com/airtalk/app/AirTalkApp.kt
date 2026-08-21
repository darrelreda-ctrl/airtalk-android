package com.airtalk.app

import android.app.Application
import com.airtalk.app.auth.TokenManager
import com.airtalk.app.model.FilterConfig
import com.airtalk.app.net.SignalingClient
import com.airtalk.app.rtc.CallListener
import com.airtalk.app.rtc.WebRtcManager
import com.airtalk.app.util.DebugLog

object FilterStore {
    @Volatile
    var config: FilterConfig = FilterConfig()
}

class AirTalkApp : Application() {

    lateinit var signaling: SignalingClient
        private set
    lateinit var callManager: WebRtcManager
        private set

    override fun onCreate() {
        super.onCreate()
        TokenManager.init(this)
        DebugLog.init(this)
        signaling = SignalingClient(
            tokenProvider = { TokenManager.getToken() },
            filterProvider = { FilterStore.config }
        )
        callManager = WebRtcManager(this, signaling, object : CallListener {})
        signaling.listener = callManager
        callManager.initialize()
        signaling.connect()
    }
}