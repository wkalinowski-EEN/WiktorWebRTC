package com.example.wiktorwebrtc.talkdown

import com.google.gson.annotations.SerializedName
import java.util.*

data class ServerRequest(
    @SerializedName("destination")
    val socketDestination: SocketDestination,
    @SerializedName("payload")
    val iceServersPayload: IceServersPayload
)

data class SocketDestination(
    val type: String = "service",
    val id: String = "talkdown",
    val routeHint: String = "deadbeef"
)

data class IceServersPayload(
    val eventName: String = "getIceServers",
    val requestId: String = UUID.randomUUID().toString().replace("-", "")
)