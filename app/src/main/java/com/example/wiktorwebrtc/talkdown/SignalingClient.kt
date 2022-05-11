package com.example.wiktorwebrtc.talkdown

import android.util.Log
import com.example.wiktorwebrtc.helper.IConnect
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.webrtc.IceCandidate
import org.webrtc.SessionDescription
import java.net.URI

class SignalingClient(
    private val events: RTCClient.SignalingEvents,
    private val iConnect: IConnect
) : RTCClient {

    private val gson = GsonBuilder().setLenient().create()
    private lateinit var webSocketClient: WebSocketClient
    private var servers = emptyArray<String>()

    override fun connectToRoom(
        connectionParameters: RTCClient.RoomConnectionParameters,
        signallingParameters: RTCClient.SignalingParameters
    ) {
        Log.e(TAG, "connectToRoom()")
        // once this event is handled in controller it can start peer connection setup
        events.onConnectedToRoom(signallingParameters)
        webSocketClient = WebSocketClient(URI(signallingParameters.wssUrl))
        webSocketClient.connect()
    }

    override fun requestIceServers(sdp: ServerRequest) {
        Log.e(TAG, "sendOfferSdp()")
        //  emitOffer(sdp)
        webSocketClient.send(gson.toJson(sdp))
    }

    override fun sendAnswerSdp(sdp: SessionDescription) {
        Log.e(TAG, "sendAnswerSdp()")
        emitAnswer(sdp)
    }

    override fun sendLocalIceCandidate(candidate: IceCandidate) {
        Log.e(TAG, "sendLocalIceCandidate()")
        emitIceCandidate(candidate)
    }

    override fun sendLocalIceCandidateRemovals(candidates: Array<IceCandidate?>) {
        // not needed at all; check if backend require this
        Log.e(TAG, "connectToRoom()")
    }

    override fun disconnectFromRoom() {
        Log.e(TAG, "disconnectFromRoom()")
        if (webSocketClient != null) {
            webSocketClient.close()
        }
    }

    private fun processMessage(message: JsonObject) {
        Log.e(TAG, "connectToRoom()")
        when (message.getAsJsonObject("payload").get("eventName").asString) {
            "authourized" -> webSocketClient.send(createSignalingClientMsg())
            "offer" -> {
                val sdp = SessionDescription(
                    SessionDescription.Type.fromCanonicalForm("offer"), message.get("sdp").asString
                )
                events.onRemoteDescription(sdp)
            }
            "answer" -> {
                val sdp = SessionDescription(
                    SessionDescription.Type.fromCanonicalForm("answer"), message.get("sdp").asString
                )
                events.onRemoteDescription(sdp)
            }
            "iceServers" -> {
                Log.e(TAG, "Received iceCandidate: $message")
       /*         val sdp1 = SessionDescription(
                    SessionDescription.Type.fromCanonicalForm("answer"), message.get("sdp").asString
                )*/
             //   events.onRemoteIceCandidate(sdp1)

              //  events.onRemoteIceCandidate(toKotlinCandidate(message))
                // todo close msg
                // todo (maybe) remove ice candidates*/
            }
            else -> {
                Log.e(TAG, "Unknown message id: $message")
            }
        }
    }


    private fun sendOfferSdpLocal() {
        webSocketClient.send(createSignalingClientMsg())
    }

    private fun emitOffer(sdp: SessionDescription) {
        Log.d(TAG, "SDP Offer: " + sdp.description)
        /*     val offer = baseMsg("offer")
             offer.addProperty("sdpOffer", sdp.description)*/

        //  webSocketClient.send(createSignalingClientMsg())
    }

    private fun emitAnswer(sdp: SessionDescription) {
        Log.e(TAG, "SDP Answer: " + sdp.description)
        val offer = baseMsg("answer")
        offer.addProperty("sdpAnswer", sdp.description)
        webSocketClient.send(gson.toJson(offer))
    }

    private fun emitIceCandidate(iceCandidate: IceCandidate) {
        Log.i(TAG, "emitIceCandidate()\n${iceCandidate.sdp}")
        val iceCandidateJson = baseMsg("iceCandidate")
        val actualIceCandidateAsJson = JsonObject()
        actualIceCandidateAsJson.addProperty("sdpMid", iceCandidate.sdpMid)
        actualIceCandidateAsJson.addProperty("sdpMLineIndex", iceCandidate.sdpMLineIndex)
        actualIceCandidateAsJson.addProperty("candidate", iceCandidate.sdp)
        iceCandidateJson.add("candidate", actualIceCandidateAsJson)
        webSocketClient.send(gson.toJson(iceCandidateJson))
    }

    private fun baseMsg(socketId: String): JsonObject {
        Log.w(TAG, "baseMsg()")
        val msg = JsonObject()
        msg.addProperty("id", socketId)
        msg.addProperty("callid", "") // this may change
        return msg
    }

    private fun createSignalingClientMsg(): String {
        Log.w(TAG, "createSignalingClientMsg()")
        val serverRequest = ServerRequest(SocketDestination(), IceServersPayload())
        return gson.toJson(serverRequest)
    }

    private inner class WebSocketClient(
        serverUri: URI
    ) : org.java_websocket.client.WebSocketClient(serverUri) {

        override fun onOpen(handshakedata: ServerHandshake?) {
            Log.e(TAG, "onOpen()")
            //   emitAuth()
            socket.keepAlive = true
            iConnect.authorized()
            // progress bar invisible
        }

        override fun onClose(code: Int, reason: String?, remote: Boolean) {
            Log.e(TAG, "onClose()")
            Log.w(TAG, "code: $code; remote: $remote; reason: $reason")
        }

        override fun onMessage(message: String?) {
            Log.e(TAG, "onMessage" + message.toString())
            processMessage(gson.fromJson(message, JsonObject::class.java))
        }

        override fun onError(e: Exception?) {
            Log.e(TAG, "onError()")
            e?.printStackTrace()
            Log.e(TAG, "Exception", e)
            if (e?.cause != null) {
                Log.e(TAG, "cause: ", e.cause)
            }
        }

        override fun send(text: String?) {
            Log.e(TAG, "send()")
            if (isOpen) {
                super.send(text)
            }
        }
    }

    companion object {
        private const val TAG = "WIKTOR"
    }
}

class NewClass(
    private val events: RTCClient.SignalingEvents,
    val wssUrl: String,
    val peerConnectionClient: PeerConnectionClient
) : RTCClient {
    companion object {
        const val TAG = "NewClassTAG"
    }

    private val gson = GsonBuilder().setLenient().create()
    private val webSocketClient: WsClient = WsClient(URI(wssUrl))

    init {
        webSocketClient.connect()
    }

    private inner class WsClient(
        serverUri: URI
    ) : WebSocketClient(serverUri) {
        override fun onOpen(handshakedata: ServerHandshake?) {
            socket.keepAlive = true
        }

        override fun onMessage(message: String?) {
            Log.e(TAG, "onMessage $message")
            val obj = gson.fromJson(message, JsonObject::class.java)
            val payload = obj.getAsJsonObject("payload")

            when (payload.get("eventName").asString) {
                "authorized" -> {

                }
                "iceServers" -> {
                    // server list is always empty for that moment
                    val servers = payload.getAsJsonObject("eventData").getAsJsonArray("iceServers")
                    peerConnectionClient.createOffer()
                }
            }
        }

        override fun onClose(code: Int, reason: String?, remote: Boolean) {

        }

        override fun onError(ex: Exception?) {
            Log.e(TAG, "onError()", ex)
        }
    }

    override fun connectToRoom(
        connectionParameters: RTCClient.RoomConnectionParameters,
        signallingParameters: RTCClient.SignalingParameters
    ) {
        // once this event is handled in controller it can start peer connection setup
        events.onConnectedToRoom(signallingParameters)
        webSocketClient.connect()
    }

    override fun requestIceServers(sdp: ServerRequest) {
        webSocketClient.send(gson.toJson(sdp))
    }

    override fun sendAnswerSdp(sdp: SessionDescription) {
        Log.e(TAG, "sendAnswerSdp()")
        val offer = SdpOffer(
            Destination(),
            Payload(eventData = EventData(sessionDescription = peerConnectionClient.localDescription))
        )
        webSocketClient.send(gson.toJson(offer))
/*        webSocketClient.send(
            """
            {
        destination: {
            type: 'service',
            id: 'talkdown',
            routeHint: 'deadbeef',
        },
        payload: {
            eventName: 'createSession',
            eventData: {
                sessionId: '0',
                speakerId: 'deadbeec',
                sessionDescription: ${peerConnectionClient.localDescription},
            },
        },
    }
        """.trimIndent()
        )*/
    }

    override fun sendLocalIceCandidate(candidate: IceCandidate) {
/*        Log.e(SignalingClient.TAG, "sendLocalIceCandidate()")
        emitIceCandidate(candidate)*/
    }

    override fun sendLocalIceCandidateRemovals(candidates: Array<IceCandidate?>) {
        TODO("Not yet implemented")
    }

    override fun disconnectFromRoom() {
        TODO("Not yet implemented")
    }

    private fun emitIceCandidate(iceCandidate: IceCandidate) {
/*        Log.i(SignalingClient.TAG, "emitIceCandidate()\n${iceCandidate.sdp}")
        val iceCandidateJson = baseMsg("iceCandidate")
        val actualIceCandidateAsJson = JsonObject()
        actualIceCandidateAsJson.addProperty("sdpMid", iceCandidate.sdpMid)
        actualIceCandidateAsJson.addProperty("sdpMLineIndex", iceCandidate.sdpMLineIndex)
        actualIceCandidateAsJson.addProperty("candidate", iceCandidate.sdp)
        iceCandidateJson.add("candidate", actualIceCandidateAsJson)
        webSocketClient.send(gson.toJson(iceCandidateJson))*/
    }
}