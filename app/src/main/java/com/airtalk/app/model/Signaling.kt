package com.airtalk.app.model

import org.json.JSONArray
import org.json.JSONObject

object MsgType {
    const val FILTER_UPDATE = "FILTER_UPDATE"
    const val STATUS_UPDATE = "STATUS_UPDATE"
    const val SDP = "SDP"
    const val CANDIDATE = "CANDIDATE"
    const val ESTABLISHED = "ESTABLISHED"
    const val RECONNECT = "RECONNECT"
    const val REPORT = "REPORT"
    const val CLIENT_ERROR = "CLIENT_ERROR"
    const val CALL_RESPONSE = "CALL_RESPONSE"
}

object SessionStatus {
    const val FREE = "FREE"
    const val STALE = "STALE"
    const val BUSY = "BUSY"
}

data class FilterConfig(
    val preferredCountries: List<String> = emptyList(),
    val nonPreferredCountries: List<String> = emptyList(),
    val interests: List<String> = emptyList(),
    val preferredGender: String = "ANY",
    val strict: Boolean = false,
    val allowCallback: Boolean = false
)

data class TurnCredential(
    val username: String,
    val password: String
)

object Messages {
    fun filterUpdate(c: FilterConfig): String =
        JSONObject()
            .put("messageType", MsgType.FILTER_UPDATE)
            .put("preferredCountries", JSONArray(c.preferredCountries))
            .put("nonPreferredCountries", JSONArray(c.nonPreferredCountries))
            .put("interests", JSONArray(c.interests))
            .put("preferredGender", c.preferredGender)
            .put("strict", c.strict)
            .put("allowCallback", c.allowCallback)
            .toString()

    fun statusUpdate(status: String): String =
        JSONObject()
            .put("messageType", MsgType.STATUS_UPDATE)
            .put("sessionStatus", status)
            .toString()

    fun sdp(sdpType: String, sessionDescription: org.webrtc.SessionDescription): String =
        JSONObject()
            .put("messageType", MsgType.SDP)
            .put("sdpType", sdpType)
            .put("content", JSONObject().put("type", sessionDescription.type.canonicalForm()).put("sdp", sessionDescription.description))
            .toString()

    fun candidate(candidate: org.webrtc.IceCandidate): String {
        val parts = candidate.sdp.split(" ")
        val ufragIdx = parts.indexOf("ufrag")
        val ufrag = if (ufragIdx >= 0 && ufragIdx + 1 < parts.size) parts[ufragIdx + 1] else JSONObject.NULL
        return JSONObject()
            .put("messageType", MsgType.CANDIDATE)
            .put(
                "content",
                JSONObject()
                    .put("candidate", candidate.sdp)
                    .put("sdpMid", candidate.sdpMid)
                    .put("sdpMLineIndex", candidate.sdpMLineIndex)
                    .put("usernameFragment", ufrag)
            )
            .toString()
    }

    fun established(remoteClientId: String): String =
        JSONObject()
            .put("messageType", MsgType.ESTABLISHED)
            .put("remoteClientId", remoteClientId)
            .toString()

    fun report(clientId: String, category: String, reason: String?): String =
        JSONObject()
            .put("messageType", MsgType.REPORT)
            .put("clientId", clientId)
            .put("category", category)
            .put("reason", reason ?: JSONObject.NULL)
            .toString()

    fun callResponse(clientId: String, error: String): String =
        JSONObject()
            .put("messageType", MsgType.CALL_RESPONSE)
            .put("clientId", clientId)
            .put("error", error)
            .toString()

    fun clientError(message: String): String =
        JSONObject()
            .put("messageType", MsgType.CLIENT_ERROR)
            .put("errorMessage", message)
            .toString()
}