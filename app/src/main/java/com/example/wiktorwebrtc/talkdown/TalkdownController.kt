package com.example.wiktorwebrtc.talkdown

import android.util.Log
import androidx.activity.viewModels
import com.example.wiktorwebrtc.LauncherActivity
import com.example.wiktorwebrtc.helper.IConnect
import com.example.wiktorwebrtc.viewmodel.MainViewModel
import org.webrtc.*

class TalkdownController(
    private val activity: LauncherActivity,
    private val isTalkdownAvailable: Boolean,
    private val iConnect: IConnect,
) : RTCClient.SignalingEvents, PeerConnectionClient.PeerConnectionEvents {

    companion object {
        private const val TAG = "TalkdownController"
    }

    private lateinit var peerConnectionClient: PeerConnectionClient
    private lateinit var rtcClient: RTCClient
    private lateinit var signalingParameters: RTCClient.SignalingParameters
    private lateinit var roomConnectionParameters: RTCClient.RoomConnectionParameters
    private lateinit var peerConnectionParameters: PeerConnectionClient.PeerConnectionParameters
    private var isError = false
    private var micEnabled = false // muted at start, enable/disable by clicking button on UI

    private lateinit var newClient: NewClass

    init {
        if (isTalkdownAvailable) {
            val eglBase = EglBase.create()
            rtcClient = SignalingClient(this, iConnect)

            // those are not needed since we do not support "rooms" in our architecture yet (only 1 person can be connected to camera)
            roomConnectionParameters = RTCClient.RoomConnectionParameters("", "", false, "")
            peerConnectionParameters = PeerConnectionClient.PeerConnectionParameters(
                tracing = false,
                audioStartBitrate = 0,
                audioCodec = PeerConnectionClient.AUDIO_CODEC_OPUS,
                useOpenSLES = false,
                disableBuiltInAEC = true,
                disableBuiltInAGC = true,
                disableBuiltInNS = true,
                disableWebRtcAGCAndHPF = true,
                enableRtcEventLog = true,
                dataChannelParameters = null
            )
            signalingParameters = RTCClient.SignalingParameters(
                wssUrl = "ws://10.0.2.2:8080/v3/edge?auth_key=c999~test", // URL OF THE SIGNALING SERVER
            )

            // Create peer connection client.
            peerConnectionClient = PeerConnectionClient(
                eglBase, activity, peerConnectionParameters, this
            )
            val options = PeerConnectionFactory.Options()
            peerConnectionClient.createPeerConnectionFactory(options)
            startCall()
            onConnectToRoomInternal(signalingParameters)
            newClient = NewClass(
                this,
                wssUrl = "ws://10.0.2.2:8080/v3/edge?auth_key=c999~test",
                peerConnectionClient
            )
        }
    }

    private fun startCall() {
        rtcClient.connectToRoom(roomConnectionParameters, signalingParameters)
        //todo in future there will could be an possibility that you should create Audio Manager here
    }

    private fun disconnect() {
        if (isTalkdownAvailable) {
            rtcClient.disconnectFromRoom()
            peerConnectionClient.close()
        }
    }

    fun dispose() {
        disconnect()
    }

    // we are not using params from this callback because we didn't make a call for api to fetch room singling params
    private fun onConnectToRoomInternal(params: RTCClient.SignalingParameters) {
        // most stuff here is null because we do not support video in talkdown
        peerConnectionClient.createPeerConnection(
            null, null, null, signalingParameters
        )
        // we will be always an initiator but I leave this here in case in future reconnect implementation
        // they decide that backend keeps session and try to bombard us with offers
        if (signalingParameters.initiator) {
            // peerConnectionClient.createOffer()
        } else {
            if (params.offerSdp != null) {
                peerConnectionClient.setRemoteDescription(params.offerSdp!!)
                // Answer SDP will be sent to offering client in
                // PeerConnectionEvents.onLocalDescription event
                peerConnectionClient.createAnswer()
                if (params.iceCandidates != null) {
                    for (iceCandidate in params.iceCandidates!!) {
                        peerConnectionClient.addRemoteIceCandidate(iceCandidate)
                    }
                }
            }
        }
    }

    // SINGALING EVENTS
    override fun onConnectedToRoom(params: RTCClient.SignalingParameters) {
        activity.runOnUiThread { onConnectToRoomInternal(params) }
    }

    override fun onRemoteDescription(sdp: SessionDescription) {
        activity.runOnUiThread {
            if (!this::peerConnectionClient.isInitialized) {
                Log.e(TAG, "Received remote SDP but peer connection is not initialized.")
            } else {
                peerConnectionClient.setRemoteDescription(sdp)
                // again, in current talkdown state - that won't happen
                if (!signalingParameters.initiator) {
                    peerConnectionClient.createAnswer();
                }
            }
        }
    }

    override fun onRemoteIceCandidate(candidate: IceCandidate) {
        activity.runOnUiThread {
            if (!this::peerConnectionClient.isInitialized) {
                Log.e(TAG, "Received ICE candidate but peer connection is not initialized..")
            } else peerConnectionClient.addRemoteIceCandidate(candidate)
        }
    }

    // can be left empty; need to check if those events will be coming from backend
    override fun onRemoteIceCandidatesRemoved(candidates: Array<IceCandidate?>?) {
        activity.runOnUiThread {
            Log.i(TAG, "Wanted to remove some ice candidates: $candidates")
            if (!this::peerConnectionClient.isInitialized) {
                Log.e(
                    TAG,
                    "Received ICE candidate removals but peer connection is not initialized.."
                )
            } else peerConnectionClient.removeRemoteIceCandidates(candidates);
        }
    }

    override fun setStunAndTurn(message: String?) {
        Log.i(TAG, "setStunAndTurn()")
        val serverRequest = ServerRequest(SocketDestination(), IceServersPayload())

        newClient.requestIceServers(sdp = serverRequest)

//        rtcClient.sendOfferSdp(serverRequest)
    }

    //// PEER CONNECTION EVENTS

    override fun onLocalDescription(sdp: SessionDescription) {
        //      newClient.sendAnswerSdp(sdp)
        activity.runOnUiThread {

            if (!this::rtcClient.isInitialized) {
                Log.e(TAG, "Singling is not initialized.")
            } else {
                /*     if (signalingParameters.initiator) {
                        //     rtcClient.sendOfferSdp(sdp)
                     } else {
                         newClient.sendAnswerSdp(sdp)
                     }*/
                newClient.sendAnswerSdp(sdp)
            }
        }
    }

    override fun onIceCandidate(candidate: IceCandidate) {
        activity.runOnUiThread {
            if (!this::rtcClient.isInitialized) {
                Log.e(TAG, "Singling is not initialized.")
            } else {
//                rtcClient.sendLocalIceCandidate(candidate)
            }
        }
    }

    // can be empty; check if this is needed with backend while integrating with local env
    override fun onIceCandidatesRemoved(candidates: Array<IceCandidate?>?) {
        activity.runOnUiThread {
            if (!this::rtcClient.isInitialized) {
                Log.e(TAG, "Singling is not initialized.")
            } else {
                rtcClient.sendLocalIceCandidateRemovals(candidates!!)
            }
        }
    }

    override fun onIceConnected() {
        Log.i(TAG, "ICE Connected")
    }

    override fun onIceDisconnected() {
        Log.i(TAG, "ICE Disconnected")
    }

    override fun onConnected() {
        Log.i(TAG, "Successfully started webRTC session")
    }

    override fun onDisconnected() {
        Log.i(TAG, "Disconnected from webRTC session")
        disconnect()
    }

    override fun onPeerConnectionClosed() {
        Log.i(TAG, "PC Closed")
        // not needed at all but in case of reconnecting it can happen that
        // for some reason signaling will be still up
        // so it will be possible to re-establish PC here
    }

    // not needed for testing; not much helpful info out there
    override fun onPeerConnectionStatsReady(report: RTCStatsReport?) {}

    override fun onPeerConnectionError(description: String?) {
        Log.e(TAG, "***** PC Error occured!!! Desc: $description *****")
    }

    /**
     * Call this method in mic button listener
     */
    fun onToggleMic(): Boolean {
        if (!this::peerConnectionClient.isInitialized) {
            Log.e(TAG, "PC is not initialized.")
        } else {
            micEnabled = !micEnabled
            peerConnectionClient.setAudioEnabled(micEnabled)
        }
        return micEnabled
    }
}