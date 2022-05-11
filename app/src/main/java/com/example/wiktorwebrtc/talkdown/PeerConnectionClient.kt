package com.example.wiktorwebrtc.talkdown

import android.content.Context
import android.os.Environment
import android.util.Log
import com.example.wiktorwebrtc.helper.IConnect
import org.webrtc.*
import org.webrtc.PeerConnection.*
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.util.*
import java.util.concurrent.Executors
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.collections.ArrayList


class PeerConnectionClient(
    private val rootEglBase: EglBase,
    private val appContext: Context,
    private val peerConnectionParameters: PeerConnectionParameters,
    private val events: PeerConnectionEvents
) {

    companion object {
        const val VIDEO_TRACK_ID = "ARDAMSv0"
        const val AUDIO_TRACK_ID = "ARDAMSa0"
        const val VIDEO_TRACK_TYPE = "video"
        private const val TAG = "PCClient"
        private val executor = Executors.newSingleThreadExecutor()
        private const val VIDEO_FLEXFEC_FIELDTRIAL =
            "WebRTC-FlexFEC-03-Advertised/Enabled/WebRTC-FlexFEC-03/Enabled/"
        private const val VIDEO_VP8_INTEL_HW_ENCODER_FIELDTRIAL = "WebRTC-IntelVP8/Enabled/"
        private const val DISABLE_WEBRTC_AGC_FIELDTRIAL =
            "WebRTC-Audio-MinimizeResamplingOnMobile/Enabled/"
        private const val VIDEO_CODEC_VP8 = "VP8"
        private const val VIDEO_CODEC_VP9 = "VP9"
        private const val VIDEO_CODEC_H264 = "H264"
        private const val VIDEO_CODEC_H264_BASELINE = "H264 Baseline"
        private const val VIDEO_CODEC_H264_HIGH = "H264 High"
        private const val VIDEO_CODEC_AV1 = "AV1"
        const val AUDIO_CODEC_OPUS = "opus"
        const val AUDIO_CODEC_ISAC = "ISAC"
        private const val VIDEO_CODEC_PARAM_START_BITRATE = "x-google-start-bitrate"
        private const val AUDIO_CODEC_PARAM_BITRATE = "maxaveragebitrate"
        private const val HD_VIDEO_WIDTH = 1280
        private const val HD_VIDEO_HEIGHT = 720
        private const val BPS_IN_KBPS = 1000
        private const val AUDIO_ECHO_CANCELLATION_CONSTRAINT = "googEchoCancellation"
        private const val AUDIO_AUTO_GAIN_CONTROL_CONSTRAINT = "googAutoGainControl"
        private const val AUDIO_HIGH_PASS_FILTER_CONSTRAINT = "googHighpassFilter"
        private const val AUDIO_NOISE_SUPPRESSION_CONSTRAINT = "googNoiseSuppression"

    }

    private val pcObserver = PCObserver()
    private val sdpObserver = SDPObserver()
    private val statsTimer = Timer() // not sure if that will be required

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var preferIsac = false
    private var videoCapturerStopped = false
    private var isError = false
    private var localRender: VideoSink? = null
    private var remoteSinks: List<VideoSink?>? = null
    private var signalingParameters: RTCClient.SignalingParameters? = null
    private var videoWidth = 0
    private var videoHeight = 0
    private var videoFps = 0
    private var audioConstraints: MediaConstraints? = null
    private var sdpMediaConstraints: MediaConstraints? = null

    // Queued remote ICE candidates are consumed only after both local and
    // remote descriptions are set. Similarly local ICE candidates are sent to
    // remote peer after both local and remote description are set.
    private var queuedRemoteCandidates: ArrayList<IceCandidate>? = null
    private var isInitiator = true

    // either offer or answer description
    var localDescription: SessionDescription? = null
    private var videoCapturer: VideoCapturer? = null

    // enableVideo is set to true if video should be rendered and sent.
    // Default set to false since we do not need in our case
    private var renderVideo = false
    private var localVideoTrack: VideoTrack? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var localVideoSender: RtpSender? = null

    // enableAudio is set to true if audio should be sent.
    private var enableAudio = true
    private var localAudioTrack: AudioTrack? = null
    private var dataChannel: DataChannel? = null
    private var dataChannelEnabled = false

    init {
        dataChannelEnabled = peerConnectionParameters.dataChannelParameters != null
        val fieldTrials = getFieldTrials(peerConnectionParameters)
        executor.execute {
            Log.d(TAG, "Initialize WebRTC. Field trials: $fieldTrials");
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(appContext)
                    .setFieldTrials(fieldTrials)
                    .setEnableInternalTracer(true)
                    .createInitializationOptions()
            )
        }
    }

    data class PeerConnectionParameters(
        val videoCallEnabled: Boolean = false,
        val loopback: Boolean = false,
        val tracing: Boolean,
        val videoWidth: Int = 0,
        val videoHeight: Int = 0,
        val videoFps: Int = 0,
        val videoMaxBitrate: Int = 0,
        val videoCodec: String = "",
        val videoCodecHwAcceleration: Boolean = false,
        val videoFlexfecEnabled: Boolean = false,
        val audioStartBitrate: Int,
        val audioCodec: String?,
        val noAudioProcessing: Boolean = false,
        val useOpenSLES: Boolean,
        val disableBuiltInAEC: Boolean,
        val disableBuiltInAGC: Boolean,
        val disableBuiltInNS: Boolean,
        val disableWebRtcAGCAndHPF: Boolean,
        val enableRtcEventLog: Boolean,
        val dataChannelParameters: DataChannelParameters?
    )

    // not needed for setup the basic session
    data class DataChannelParameters(
        val ordered: Boolean,
        val maxRetransmitTimeMs: Int,
        val maxRetransmits: Int,
        val protocol: String,
        val negotiated: Boolean,
        val id: Int
    )

    /**
     * Peer connection events.
     */
    interface PeerConnectionEvents {
        /**
         * Callback fired once local SDP is created and set.
         */
        fun onLocalDescription(sdp: SessionDescription)

        /**
         * Callback fired once local Ice candidate is generated.
         */
        fun onIceCandidate(candidate: IceCandidate)

        /**
         * Callback fired once local ICE candidates are removed.
         */
        fun onIceCandidatesRemoved(candidates: Array<IceCandidate?>?)

        /**
         * Callback fired once connection is established (IceConnectionState is
         * CONNECTED).
         */
        fun onIceConnected()

        /**
         * Callback fired once connection is disconnected (IceConnectionState is
         * DISCONNECTED).
         */
        fun onIceDisconnected()

        /**
         * Callback fired once DTLS connection is established (PeerConnectionState
         * is CONNECTED).
         */
        fun onConnected()

        /**
         * Callback fired once DTLS connection is disconnected (PeerConnectionState
         * is DISCONNECTED).
         */
        fun onDisconnected()

        /**
         * Callback fired once peer connection is closed.
         */
        fun onPeerConnectionClosed()

        /**
         * Callback fired once peer connection statistics is ready.
         */
        fun onPeerConnectionStatsReady(report: RTCStatsReport?)

        /**
         * Callback fired once peer connection error happened.
         */
        fun onPeerConnectionError(description: String?)
    }

    /**
     * This function should only be called once.
     */
    fun createPeerConnectionFactory(options: PeerConnectionFactory.Options) {
        if (factory != null) {
            throw IllegalStateException("PeerConnectionFactory has already been constructed");
        }
        executor.execute { createPeerConnectionFactoryInternal(options) }
    }

    fun createPeerConnection(
        localRender: VideoSink?, remoteSink: VideoSink?,
        videoCapturer: VideoCapturer?, signalingParameters: RTCClient.SignalingParameters?
    ) {
        if (peerConnectionParameters.videoCallEnabled && videoCapturer == null) {
            Log.w(TAG, "Video call enabled but no video capturer provided.")
        }
        createPeerConnection(
            localRender, Collections.singletonList(remoteSink), videoCapturer, signalingParameters
        )
    }

    private fun createPeerConnection(
        localRender: VideoSink?,
        remoteSinks: List<VideoSink?>,
        videoCapturer: VideoCapturer?,
        signalingParameters: RTCClient.SignalingParameters?
    ) {
        if (peerConnectionParameters == null) {
            Log.e(TAG, "Creating peer connection without initializing factory.")
            return
        }
        this.localRender = localRender
        this.remoteSinks = remoteSinks
        this.videoCapturer = videoCapturer
        this.signalingParameters = signalingParameters
        executor.execute {
            try {
                createMediaConstraintsInternal()
                createPeerConnectionInternal()
            } catch (e: java.lang.Exception) {
                reportError("Failed to create peer connection: " + e.message)
                throw e
            }
        }
    }

    fun close() {
        executor.execute(::closeInternal)
    }

    private fun isVideoCallEnabled(): Boolean {
        return peerConnectionParameters.videoCallEnabled && videoCapturer != null
    }

    private fun createPeerConnectionFactoryInternal(options: PeerConnectionFactory.Options) {
        isError = false

        if (peerConnectionParameters.tracing) {
            PeerConnectionFactory.startInternalTracingCapture(
                Environment.getExternalStorageDirectory().absolutePath + File.separator + "webrtc-trace.txt"
            )
        }

        // Check if ISAC is used by default.
        preferIsac =
            peerConnectionParameters.audioCodec != null && peerConnectionParameters.audioCodec.equals(
                AUDIO_CODEC_ISAC
            )

        val adm: AudioDeviceModule = createJavaAudioDevice()

        // Create peer connection factory.
        if (options != null) {
            Log.d(TAG, "Factory networkIgnoreMask option: " + options.networkIgnoreMask)
        }
        val enableH264HighProfile =
            VIDEO_CODEC_H264_HIGH == peerConnectionParameters.videoCodec
        val encoderFactory: VideoEncoderFactory
        val decoderFactory: VideoDecoderFactory
        if (peerConnectionParameters.videoCodecHwAcceleration) {
            encoderFactory = DefaultVideoEncoderFactory(
                rootEglBase.eglBaseContext, true, enableH264HighProfile
            )
            decoderFactory = DefaultVideoDecoderFactory(rootEglBase.eglBaseContext)
        } else {
            encoderFactory = SoftwareVideoEncoderFactory()
            decoderFactory = SoftwareVideoDecoderFactory()
        }
        // Disable encryption for loopback calls.
        if (peerConnectionParameters.loopback) {
            options.disableEncryption = true
        }
        factory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
        Log.d(TAG, "Peer connection factory created.")
        adm.release()
    }

    private fun createJavaAudioDevice(): AudioDeviceModule {
        if (!peerConnectionParameters.useOpenSLES) {
            // webRTC for mobile does not support it yet!!!
            Log.w(TAG, "External OpenSLES ADM not implemented yet.");
        }

        // Set audio record error callback (not needed because we do not support recording)
        val audioRecordErrorCallback: AudioRecordErrorCallback = object : AudioRecordErrorCallback {
            override fun onWebRtcAudioRecordInitError(errorMessage: String) {
                Log.e(TAG, "onWebRtcAudioRecordInitError: $errorMessage")
                reportError(errorMessage)
            }

            override fun onWebRtcAudioRecordStartError(
                errorCode: AudioRecordStartErrorCode, errorMessage: String
            ) {
                Log.e(TAG, "onWebRtcAudioRecordStartError: $errorCode. $errorMessage")
                reportError(errorMessage)
            }

            override fun onWebRtcAudioRecordError(errorMessage: String) {
                Log.e(TAG, "onWebRtcAudioRecordError: $errorMessage")
                reportError(errorMessage)
            }
        }

        val audioTrackErrorCallback: AudioTrackErrorCallback = object : AudioTrackErrorCallback {
            override fun onWebRtcAudioTrackInitError(errorMessage: String) {
                Log.e(TAG, "onWebRtcAudioTrackInitError: $errorMessage")
                reportError(errorMessage)
            }

            override fun onWebRtcAudioTrackStartError(
                errorCode: AudioTrackStartErrorCode, errorMessage: String
            ) {
                Log.e(TAG, "onWebRtcAudioTrackStartError: $errorCode. $errorMessage")
                reportError(errorMessage)
            }

            override fun onWebRtcAudioTrackError(errorMessage: String) {
                Log.e(TAG, "onWebRtcAudioTrackError: $errorMessage")
                reportError(errorMessage)
            }
        }

        // Set audio record state callbacks.
        val audioRecordStateCallback: AudioRecordStateCallback = object : AudioRecordStateCallback {
            override fun onWebRtcAudioRecordStart() {
                Log.i(TAG, "Audio recording starts")
            }

            override fun onWebRtcAudioRecordStop() {
                Log.i(TAG, "Audio recording stops")
            }
        }

        // Set audio track state callbacks.
        val audioTrackStateCallback: AudioTrackStateCallback = object : AudioTrackStateCallback {
            override fun onWebRtcAudioTrackStart() {
                Log.i(TAG, "Audio playout starts")
            }

            override fun onWebRtcAudioTrackStop() {
                Log.i(TAG, "Audio playout stops")
            }
        }

        return builder(appContext)
            .setUseHardwareAcousticEchoCanceler(!peerConnectionParameters.disableBuiltInAEC)
            .setUseHardwareNoiseSuppressor(!peerConnectionParameters.disableBuiltInNS)
            .setAudioRecordErrorCallback(audioRecordErrorCallback)
            .setAudioTrackErrorCallback(audioTrackErrorCallback)
            .setAudioRecordStateCallback(audioRecordStateCallback)
            .setAudioTrackStateCallback(audioTrackStateCallback)
            .createAudioDeviceModule()
    }

    private fun createMediaConstraintsInternal() {
        // Create audio constraints.
        audioConstraints = MediaConstraints()
        // added for audio performance measurements
        if (peerConnectionParameters.noAudioProcessing) {
            Log.d(TAG, "Disabling audio processing")
            audioConstraints?.mandatory?.add(
                MediaConstraints.KeyValuePair(AUDIO_ECHO_CANCELLATION_CONSTRAINT, "false")
            )
            audioConstraints?.mandatory?.add(
                MediaConstraints.KeyValuePair(AUDIO_AUTO_GAIN_CONTROL_CONSTRAINT, "false")
            )
            audioConstraints?.mandatory?.add(
                MediaConstraints.KeyValuePair(AUDIO_HIGH_PASS_FILTER_CONSTRAINT, "false")
            )
            audioConstraints?.mandatory?.add(
                MediaConstraints.KeyValuePair(AUDIO_NOISE_SUPPRESSION_CONSTRAINT, "false")
            )
        }

        // Create SDP constraints.
        sdpMediaConstraints = MediaConstraints()
        sdpMediaConstraints?.mandatory?.add(
            MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true")
        )
        sdpMediaConstraints?.mandatory?.add(
            MediaConstraints.KeyValuePair(
                "OfferToReceiveVideo", java.lang.Boolean.toString(isVideoCallEnabled())
            )
        )
    }

    private fun createPeerConnectionInternal() {
        if (factory == null || isError) {
            Log.e(TAG, "Peerconnection factory is not created");
            return;
        }
        Log.d(TAG, "Create peer connection.");


        queuedRemoteCandidates = ArrayList()
        val rtcConfig = RTCConfiguration(signalingParameters?.iceServers)
        // TCP candidates are only useful when connecting to a server that supports
        // ICE-TCP.
        rtcConfig.tcpCandidatePolicy = TcpCandidatePolicy.DISABLED
        rtcConfig.bundlePolicy = BundlePolicy.MAXBUNDLE
        rtcConfig.rtcpMuxPolicy = RtcpMuxPolicy.REQUIRE
        rtcConfig.continualGatheringPolicy = ContinualGatheringPolicy.GATHER_CONTINUALLY
        // Use ECDSA encryption.
        rtcConfig.keyType = KeyType.ECDSA
        rtcConfig.sdpSemantics = SdpSemantics.UNIFIED_PLAN
        peerConnection = factory?.createPeerConnection(rtcConfig, pcObserver)

        if (dataChannelEnabled) {
            val init = DataChannel.Init()
            init.ordered = peerConnectionParameters.dataChannelParameters!!.ordered
            init.negotiated = peerConnectionParameters.dataChannelParameters.negotiated
            init.maxRetransmits = peerConnectionParameters.dataChannelParameters.maxRetransmits
            init.maxRetransmitTimeMs =
                peerConnectionParameters.dataChannelParameters.maxRetransmitTimeMs
            init.id = peerConnectionParameters.dataChannelParameters.id
            init.protocol = peerConnectionParameters.dataChannelParameters.protocol
            dataChannel = peerConnection!!.createDataChannel("ApprtcDemo data", init)
        }
        isInitiator = false

        Logging.enableLogToDebugOutput(Logging.Severity.LS_INFO)

        val mediaStreamLabels = Collections.singletonList("ARDAMS")
        peerConnection!!.addTrack(createAudioTrack(), mediaStreamLabels);
        Log.d(TAG, "Peer connection created.");
    }

    private fun closeInternal() {
        Log.d(TAG, "Closing peer connection.")
        statsTimer.cancel()
        if (dataChannel != null) {
            dataChannel!!.dispose()
            dataChannel = null
        }
        if (peerConnection != null) {
            peerConnection!!.dispose()
            peerConnection = null
        }
        Log.d(TAG, "Closing audio source.")
        if (audioSource != null) {
            audioSource?.dispose()
            audioSource = null
        }
        Log.d(TAG, "Stopping capture.")
        if (videoCapturer != null) {
            try {
                videoCapturer?.stopCapture()
            } catch (e: InterruptedException) {
                throw RuntimeException(e)
            }
            videoCapturerStopped = true
            videoCapturer?.dispose()
            videoCapturer = null
        }
        Log.d(TAG, "Closing video source.")
        if (videoSource != null) {
            videoSource?.dispose()
            videoSource = null
        }
        if (surfaceTextureHelper != null) {
            surfaceTextureHelper?.dispose()
            surfaceTextureHelper = null
        }
        localRender = null
        remoteSinks = null
        Log.d(TAG, "Closing peer connection factory.")
        if (factory != null) {
            factory?.dispose()
            factory = null
        }
        rootEglBase.release()
        Log.d(TAG, "Closing peer connection done.")
        events.onPeerConnectionClosed()
        PeerConnectionFactory.stopInternalTracingCapture()
        PeerConnectionFactory.shutdownInternalTracer()
    }

    /** method for mute/unmute mic like on Google Meet */
    fun setAudioEnabled(enable: Boolean) {
        executor.execute {
            enableAudio = enable
            localAudioTrack?.setEnabled(enableAudio)
        }
    }

    // ********************************************** ///
    // ******** MOST IMPORTANT METHODS HERE ********* ///
    // ********************************************** ///

    fun createOffer() {
        executor.execute {
            Log.e("NewClassTAG",peerConnection.toString())
            if (peerConnection != null && !isError) {
                Log.d(TAG, "PC Create OFFER")
                isInitiator = true
                peerConnection!!.createOffer(sdpObserver, sdpMediaConstraints)
            }
        }
    }

    fun createTurnAndStun(){
        executor.execute{
            if (peerConnection != null && !isError) {
            //    peerConnection!!.crea
            }
        }
    }

    fun createAnswer() {
        executor.execute {
            if (peerConnection != null && !isError) {
                Log.d(TAG, "PC create ANSWER")
                isInitiator = false
                peerConnection!!.createAnswer(sdpObserver, sdpMediaConstraints)
            }
        }
    }

    fun addRemoteIceCandidate(candidate: IceCandidate) {
        executor.execute {
            if (peerConnection != null && !isError) {
                if (queuedRemoteCandidates != null) {
                    queuedRemoteCandidates?.add(candidate)
                } else {
                    peerConnection!!.addIceCandidate(candidate)
                }
            }
        }
    }

    fun removeRemoteIceCandidates(candidates: Array<IceCandidate?>?) {
        executor.execute {
            if (peerConnection == null || isError) {
                return@execute
            }
            // Drain the queued remote candidates if there is any so that
            // they are processed in the proper order.
            drainCandidates()
            peerConnection!!.removeIceCandidates(candidates)
        }
    }

    fun setRemoteDescription(desc: SessionDescription) {
        executor.execute {
            if (peerConnection == null || isError) {
                return@execute
            }
            var sdp = desc.description
            if (preferIsac) {
                sdp = preferCodec(sdp, AUDIO_CODEC_ISAC, true)
            }
            if (peerConnectionParameters.audioStartBitrate > 0) {
                sdp = setStartBitrate(
                    AUDIO_CODEC_OPUS, false, sdp, peerConnectionParameters.audioStartBitrate
                )
            }
            Log.d(TAG, "Set remote SDP.")
            val sdpRemote = SessionDescription(desc.type, sdp)
            peerConnection!!.setRemoteDescription(sdpObserver, sdpRemote)
        }
    }

    fun reportError(errorMessage: String) {
        Log.e(TAG, "Peerconnection error: $errorMessage")
        executor.execute {
            if (!isError) {
                events.onPeerConnectionError(errorMessage)
                isError = true
            }
        }
    }

    private fun createAudioTrack(): AudioTrack? {
        audioSource = factory!!.createAudioSource(audioConstraints)
        localAudioTrack = factory!!.createAudioTrack(AUDIO_TRACK_ID, audioSource)
        localAudioTrack?.setEnabled(enableAudio)
        return localAudioTrack
    }

    private fun getFieldTrials(peerConnectionParameters: PeerConnectionParameters): String? {
        var fieldTrials = ""
        if (peerConnectionParameters.videoFlexfecEnabled) {
            fieldTrials += VIDEO_FLEXFEC_FIELDTRIAL
            Log.d(TAG, "Enable FlexFEC field trial.")
        }
        fieldTrials += VIDEO_VP8_INTEL_HW_ENCODER_FIELDTRIAL
        if (peerConnectionParameters.disableWebRtcAGCAndHPF) {
            fieldTrials += DISABLE_WEBRTC_AGC_FIELDTRIAL
            Log.d(TAG, "Disable WebRTC AGC field trial.")
        }
        return fieldTrials
    }

    // not sure if that will be needed,
    // let's keep it here in case backend team would like to set
    // specific bitrate for audio
    private fun setStartBitrate(
        codec: String, isVideoCodec: Boolean, sdp: String, bitrateKbps: Int
    ): String? {
        val lines = sdp.split("\r\n").toTypedArray()
        var rtpmapLineIndex = -1
        var sdpFormatUpdated = false
        var codecRtpMap: String? = null
        // Search for codec rtpmap in format
        // a=rtpmap:<payload type> <encoding name>/<clock rate> [/<encoding parameters>]
        var regex = "^a=rtpmap:(\\d+) $codec(/\\d+)+[\r]?$"
        var codecPattern: Pattern = Pattern.compile(regex)
        for (i in lines.indices) {
            val codecMatcher: Matcher = codecPattern.matcher(lines[i])
            if (codecMatcher.matches()) {
                codecRtpMap = codecMatcher.group(1)
                rtpmapLineIndex = i
                break
            }
        }
        if (codecRtpMap == null) {
            Log.w(TAG, "No rtpmap for $codec codec")
            return sdp
        }
        Log.d(TAG, "Found " + codec + " rtpmap " + codecRtpMap + " at " + lines[rtpmapLineIndex])
        // Check if a=fmtp string already exist in remote SDP for this codec and
        // update it with new bitrate parameter.
        regex = "^a=fmtp:$codecRtpMap \\w+=\\d+.*[\r]?$"
        codecPattern = Pattern.compile(regex)
        for (i in lines.indices) {
            val codecMatcher: Matcher = codecPattern.matcher(lines[i])
            if (codecMatcher.matches()) {
                Log.d(TAG, "Found " + codec + " " + lines[i])
                if (isVideoCodec) {
                    lines[i] += "; $VIDEO_CODEC_PARAM_START_BITRATE=$bitrateKbps"
                } else {
                    lines[i] += "; " + AUDIO_CODEC_PARAM_BITRATE + "=" + bitrateKbps * 1000
                }
                Log.d(TAG, "Update remote SDP line: " + lines[i])
                sdpFormatUpdated = true
                break
            }
        }
        val newSdpDescription = StringBuilder()
        for (i in lines.indices) {
            newSdpDescription.append(lines[i]).append("\r\n")
            // Append new a=fmtp line if no such line exist for a codec.
            if (!sdpFormatUpdated && i == rtpmapLineIndex) {
                var bitrateSet: String
                bitrateSet = if (isVideoCodec) {
                    "a=fmtp:$codecRtpMap $VIDEO_CODEC_PARAM_START_BITRATE=$bitrateKbps"
                } else {
                    ("a=fmtp:" + codecRtpMap + " " + AUDIO_CODEC_PARAM_BITRATE + "="
                            + bitrateKbps * 1000)
                }
                Log.d(TAG, "Add remote SDP line: $bitrateSet")
                newSdpDescription.append(bitrateSet).append("\r\n")
            }
        }
        return newSdpDescription.toString()
    }


    /** Returns the line number containing "m=audio|video", or -1 if no such line exists.  */
    private fun findMediaDescriptionLine(isAudio: Boolean, sdpLines: Array<String>): Int {
        val mediaDescription = if (isAudio) "m=audio " else "m=video "
        for (i in sdpLines.indices) {
            if (sdpLines[i].startsWith(mediaDescription)) {
                return i
            }
        }
        return -1
    }

    private fun joinString(
        s: Iterable<CharSequence?>, delimiter: String, delimiterAtEnd: Boolean
    ): String {
        val iter = s.iterator()
        if (!iter.hasNext()) {
            return ""
        }
        val buffer = StringBuilder(iter.next())
        while (iter.hasNext()) {
            buffer.append(delimiter).append(iter.next())
        }
        if (delimiterAtEnd) {
            buffer.append(delimiter)
        }
        return buffer.toString()
    }

    private fun movePayloadTypesToFront(
        preferredPayloadTypes: List<String?>, mLine: String
    ): String? {
        // The format of the media description line should be: m=<media> <port> <proto> <fmt> ...
        val origLineParts: List<String> = mLine.split(" ")
        if (origLineParts.size <= 3) {
            Log.e(TAG, "Wrong SDP media description format: $mLine")
            return null
        }
        val header = origLineParts.subList(0, 3)
        val unpreferredPayloadTypes: ArrayList<String> =
            ArrayList(origLineParts.subList(3, origLineParts.size))
        unpreferredPayloadTypes.removeAll(preferredPayloadTypes)
        // Reconstruct the line with `preferredPayloadTypes` moved to the beginning of the payload
        // types.
        val newLineParts: MutableList<String?> = ArrayList()
        newLineParts.addAll(header)
        newLineParts.addAll(preferredPayloadTypes)
        newLineParts.addAll(unpreferredPayloadTypes)
        return joinString(newLineParts, " ", false /* delimiterAtEnd */)
    }

    private fun preferCodec(sdp: String, codec: String, isAudio: Boolean): String {
        val lines = sdp.split("\r\n").toTypedArray()
        val mLineIndex = findMediaDescriptionLine(isAudio, lines)
        if (mLineIndex == -1) {
            Log.w(TAG, "No mediaDescription line, so can't prefer $codec")
            return sdp
        }
        // A list with all the payload types with name `codec`. The payload types are integers in the
        // range 96-127, but they are stored as strings here.
        val codecPayloadTypes: MutableList<String?> = ArrayList()
        // a=rtpmap:<payload type> <encoding name>/<clock rate> [/<encoding parameters>]
        val codecPattern = Pattern.compile("^a=rtpmap:(\\d+) $codec(/\\d+)+[\r]?$")
        for (line in lines) {
            val codecMatcher = codecPattern.matcher(line)
            if (codecMatcher.matches()) {
                codecPayloadTypes.add(codecMatcher.group(1))
            }
        }
        if (codecPayloadTypes.isEmpty()) {
            Log.w(TAG, "No payload types with name $codec")
            return sdp
        }
        val newMLine = movePayloadTypesToFront(codecPayloadTypes, lines[mLineIndex]) ?: return sdp
        Log.d(TAG, "Change media description from: " + lines[mLineIndex] + " to " + newMLine)
        lines[mLineIndex] = newMLine
        return joinString(lines.toList(), "\r\n", true /* delimiterAtEnd */)
    }

    private fun drainCandidates() {
        if (queuedRemoteCandidates != null) {
            Log.d(TAG, "Add " + queuedRemoteCandidates!!.size + " remote candidates")
            for (candidate in queuedRemoteCandidates!!) {
                peerConnection!!.addIceCandidate(candidate)
            }
            queuedRemoteCandidates = null
        }
    }

    // observe ICE & stream changes and react accordingly.
    private inner class PCObserver : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            executor.execute { events.onIceCandidate(candidate) }
        }

        override fun onIceCandidatesRemoved(candidates: Array<IceCandidate?>?) {
            executor.execute { events.onIceCandidatesRemoved(candidates) }
        }

        override fun onSignalingChange(newState: SignalingState) {
            Log.d(TAG, "SignalingState: $newState")
        }

        override fun onIceConnectionChange(newState: IceConnectionState) {
            executor.execute {
                Log.d(TAG, "IceConnectionState: $newState")
                when (newState) {
                    IceConnectionState.CONNECTED -> {
                        events.onIceConnected()
                    }
                    IceConnectionState.DISCONNECTED -> {
                        events.onIceDisconnected()
                    }
                    IceConnectionState.FAILED -> {
                        reportError("ICE connection failed.")
                    }
                }
            }
        }

        override fun onConnectionChange(newState: PeerConnectionState) {
            executor.execute {
                Log.d(TAG, "PeerConnectionState: $newState")
                when (newState) {
                    PeerConnectionState.CONNECTED -> {
                        events.onConnected()
                    }
                    PeerConnectionState.DISCONNECTED -> {
                        events.onDisconnected()
                    }
                    PeerConnectionState.FAILED -> {
                        reportError("DTLS connection failed.")
                    }
                }
            }
        }

        override fun onIceGatheringChange(newState: IceGatheringState) {
            Log.d(TAG, "IceGatheringState: $newState")
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {
            Log.d(TAG, "IceConnectionReceiving changed to $receiving")
        }

        override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) {
            Log.d(TAG, "Selected candidate pair changed because: $event")
        }

        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onDataChannel(dc: DataChannel) {
            Log.d(TAG, "New Data channel " + dc.label())
            if (!dataChannelEnabled) return
            dc.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) {
                    Log.d(
                        TAG,
                        "Data channel buffered amount changed: " + dc.label() + ": " + dc.state()
                    )
                }

                override fun onStateChange() {
                    Log.d(TAG, "Data channel state changed: " + dc.label() + ": " + dc.state())
                }

                override fun onMessage(buffer: DataChannel.Buffer) {
                    if (buffer.binary) {
                        Log.d(TAG, "Received binary msg over $dc")
                        return
                    }
                    val data: ByteBuffer = buffer.data
                    val bytes = ByteArray(data.capacity())
                    data.get(bytes)
                    val strData = String(bytes, Charset.forName("UTF-8"))
                    Log.d(TAG, "Got msg: $strData over $dc")
                }
            })
        }

        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<MediaStream>) {}
        fun onRemoveTrack(receiver: RtpReceiver?) {}
    }

    // Implementation detail: handle offer creation/signaling and answer setting,
    // as well as adding remote ICE candidates once the answer SDP is set.
    private inner class SDPObserver : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {
            Log.e(TAG, "onCreateSuccess")
            if (localDescription != null) {
                reportError("Multiple SDP create.")
                return
            }
            var sdp = desc.description
            if (preferIsac) {
                sdp = preferCodec(sdp, AUDIO_CODEC_ISAC, true)
            }
            val newDesc = SessionDescription(desc.type, sdp)
            localDescription = newDesc
            executor.execute {
                if (peerConnection != null && !isError) {
                    Log.d(TAG, "Set local SDP from " + desc.type)
                    peerConnection!!.setLocalDescription(sdpObserver, newDesc)
                }
            }
        }

        override fun onSetSuccess() {
            Log.e(TAG, "onSetSuccess")
            executor.execute {
                if (peerConnection == null || isError) {
                    return@execute
                }
                if (isInitiator) {
                    // For offering peer connection we first create offer and set
                    // local SDP, then after receiving answer set remote SDP.
                    if (peerConnection!!.remoteDescription == null) {
                        // We've just set our local SDP so time to send it.
                        Log.d(TAG, "Local SDP set succesfully")

                        events.onLocalDescription(localDescription!!)
                    } else {
                        // We've just set remote description, so drain remote
                        // and send local ICE candidates.
                        Log.d(TAG, "Remote SDP set succesfully")
                        drainCandidates()
                    }
                } else {
                    // For answering peer connection we set remote SDP and then
                    // create answer and set local SDP.
                    if (peerConnection!!.localDescription != null) {
                        // We've just set our local SDP so time to send it, drain
                        // remote and send local ICE candidates.
                        Log.d(TAG, "Local SDP set succesfully")
                        events.onLocalDescription(localDescription!!)
                        drainCandidates()
                    } else {
                        // We've just set remote SDP - do nothing for now -
                        // answer will be created soon.
                        Log.d(TAG, "Remote SDP set succesfully")
                    }
                }
            }
        }

        override fun onCreateFailure(error: String) {
            reportError("createSDP error: $error")
        }

        override fun onSetFailure(error: String) {
            reportError("setSDP error: $error")
        }
    }
}