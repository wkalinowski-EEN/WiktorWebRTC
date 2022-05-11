package com.example.wiktorwebrtc.talkdown

import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription


interface RTCClient {

    // todo add bridge esn here if needed
    data class SignalingParameters (
        val iceServers: List<PeerConnection.IceServer> = emptyList(),
        val initiator: Boolean = true,
        val clientId: String = "",
        val wssUrl: String,
        val wssPostUrl: String = "", // not needed prob
        val offerSdp: SessionDescription? = null, // it can be already set if someone is in a room already and backend can sent it by api to shorten negotiation
        val iceCandidates: List<IceCandidate>? = null // // those can be already set if someone is in a room already and backend can sent it by api to shorten negotiation
    )

    data class RoomConnectionParameters(
        val roomUrl: String,
        val roomId: String,
        val loopback: Boolean = false,
        val urlParameters: String
    )

    fun connectToRoom(connectionParameters: RoomConnectionParameters, signallingParameters: SignalingParameters)

    /**
     * Send offer SDP to the other participant.
     */
    fun requestIceServers(sdp: ServerRequest)

    /**
     * Send answer SDP to the other participant.
     */
    fun sendAnswerSdp(sdp: SessionDescription)

    /**
     * Send Ice candidate to the other participant.
     */
    fun sendLocalIceCandidate(candidate: IceCandidate)

    /**
     * Send removed ICE candidates to the other participant.
     */
    fun sendLocalIceCandidateRemovals(candidates: Array<IceCandidate?>)

    /**
     * Disconnect from room.
     */
    fun disconnectFromRoom()

    /**
     * Callback interface for messages delivered on signaling channel.
     *
     *
     * Methods are guaranteed to be invoked on the UI thread of `activity`.
     */
    interface SignalingEvents {
        /**
         * Callback fired once the room's signaling parameters are extracted.
         * But we do not support rooms in our architecture atm
         */
        fun onConnectedToRoom(params: SignalingParameters)

        /**
         * Callback fired once remote SDP is received.
         */
        fun onRemoteDescription(sdp: SessionDescription)

        /**
         * Callback fired once remote Ice candidate is received.
         */
        fun onRemoteIceCandidate(candidate: IceCandidate)

        /**
         * Callback fired once remote Ice candidate removals are received.
         */
        fun onRemoteIceCandidatesRemoved(candidates: Array<IceCandidate?>?)

        fun setStunAndTurn(message: String?)
    }
}