package com.example.wiktorwebrtc.talkdown

import com.google.gson.annotations.SerializedName
import org.webrtc.SessionDescription

data class SdpOffer(
    @SerializedName("destination")
    val destination: Destination,
    @SerializedName("payload")
    val payload: Payload
)

data class Destination(
    @SerializedName("type")
    val type: String = "service",
    @SerializedName("id")
    val id: String = "talkdown",
    @SerializedName("routeHint")
    val routeHint: String = "deadbeef"
)

data class Payload(
    @SerializedName("eventName")
    val eventName: String = "createSession",
    val eventData: EventData
)

data class EventData(
    @SerializedName("sessionId")
    val sessionId: String = "sessionId",
    @SerializedName("speakerId")
    val speakerId: String = "speakerId",
    @SerializedName("sessionDescription")
    val sessionDescription: SessionDescription? = null
)