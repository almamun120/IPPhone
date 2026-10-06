package com.ipphone.network.calling

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import org.webrtc.*
import org.webrtc.audio.AudioDeviceModule
import org.webrtc.audio.JavaAudioDeviceModule
import timber.log.Timber
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebRtcManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    
    private var localAudioTrack: AudioTrack? = null
    private var localVideoTrack: VideoTrack? = null
    private var localAudioSource: AudioSource? = null
    private var localVideoSource: VideoSource? = null
    
    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    
    private var eglBase: EglBase? = null
    private var eglBaseContext: EglBase.Context? = null
    
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private var localVideoRenderer: SurfaceViewRenderer? = null
    private var remoteVideoRenderer: SurfaceViewRenderer? = null
    
    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    
    private var savedAudioMode: Int = AudioManager.MODE_NORMAL
    private var savedIsSpeakerOn: Boolean = false
    private var savedIsMicrophoneMute: Boolean = false
    
    // Callbacks
    var onLocalStream: ((MediaStream) -> Unit)? = null
    var onRemoteStream: ((MediaStream) -> Unit)? = null
    var onIceCandidate: ((IceCandidate) -> Unit)? = null
    var onIceConnectionChange: ((PeerConnection.IceConnectionState) -> Unit)? = null
    var onSignalingChange: ((PeerConnection.SignalingState) -> Unit)? = null
    var onDataChannelMessage: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    
    private var dataChannel: DataChannel? = null
    
    // Audio/Video state
    private var isAudioEnabled = true
    private var isVideoEnabled = true
    private var isSpeakerOn = false
    private var isFrontCamera = true
    
    data class WebRtcConfig(
        val enableVideo: Boolean = false,
        val enableAudio: Boolean = true,
        val videoWidth: Int = 1280,
        val videoHeight: Int = 720,
        val videoFps: Int = 30
    )
    
    fun initialize(): Boolean {
        return try {
            Timber.d("Initializing WebRTC")
            
            // Initialize EGL context
            eglBase = EglBase.create()
            eglBaseContext = eglBase?.eglBaseContext
            
            // Initialize PeerConnectionFactory
            val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .setFieldTrials("WebRTC-H264HighProfile/Enabled/")
                .createInitializationOptions()
            
            PeerConnectionFactory.initialize(initOptions)
            
            // Create audio device module
            val audioDeviceModule = createAudioDeviceModule()
            
            // Create video encoder/decoder factories
            val encoderFactory = DefaultVideoEncoderFactory(
                eglBaseContext,
                true, // enableIntelVp8Encoder
                true  // enableH264HighProfile
            )
            
            val decoderFactory = DefaultVideoDecoderFactory(eglBaseContext)
            
            // Build PeerConnectionFactory
            peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(PeerConnectionFactory.Options().apply {
                    disableEncryption = false
                    disableNetworkMonitor = false
                })
                .setAudioDeviceModule(audioDeviceModule)
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .createPeerConnectionFactory()
            
            Timber.d("WebRTC initialized successfully")
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize WebRTC")
            onError?.invoke("Failed to initialize WebRTC: ${e.message}")
            false
        }
    }
    
    private fun createAudioDeviceModule(): AudioDeviceModule {
        return JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(errorMessage: String) {
                    Timber.e("Audio record init error: $errorMessage")
                }
                override fun onWebRtcAudioRecordStartError(
                    errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode,
                    errorMessage: String
                ) {
                    Timber.e("Audio record start error: $errorMessage")
                }
                override fun onWebRtcAudioRecordError(errorMessage: String) {
                    Timber.e("Audio record error: $errorMessage")
                }
            })
            .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                override fun onWebRtcAudioTrackInitError(errorMessage: String) {
                    Timber.e("Audio track init error: $errorMessage")
                }
                override fun onWebRtcAudioTrackStartError(
                    errorCode: JavaAudioDeviceModule.AudioTrackStartErrorCode,
                    errorMessage: String
                ) {
                    Timber.e("Audio track start error: $errorMessage")
                }
                override fun onWebRtcAudioTrackError(errorMessage: String) {
                    Timber.e("Audio track error: $errorMessage")
                }
            })
            .createAudioDeviceModule()
    }
    
    fun createPeerConnection(config: WebRtcConfig = WebRtcConfig()): Boolean {
        if (peerConnectionFactory == null) {
            Timber.e("PeerConnectionFactory not initialized")
            return false
        }
        
        return try {
            // Save current audio state
            saveAudioState()
            
            // Configure audio for VoIP
            configureAudioForCall()
            
            // ICE servers - for LAN, we don't need STUN/TURN servers
            val iceServers = listOf<PeerConnection.IceServer>()
            
            val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
                candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.ALL
                keyType = PeerConnection.KeyType.ECDSA
                iceTransportsType = PeerConnection.IceTransportsType.ALL
            }
            
            peerConnection = peerConnectionFactory?.createPeerConnection(
                rtcConfig,
                createPeerConnectionObserver()
            )
            
            if (peerConnection == null) {
                Timber.e("Failed to create PeerConnection")
                return false
            }
            
            // Create data channel
            createDataChannel()
            
            // Create and add local media tracks
            createLocalTracks(config)
            
            Timber.d("PeerConnection created successfully")
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to create PeerConnection")
            onError?.invoke("Failed to create connection: ${e.message}")
            false
        }
    }
    
    private fun createPeerConnectionObserver(): PeerConnection.Observer {
        return object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {
                Timber.d("Signaling state changed: $state")
                mainHandler.post { onSignalingChange?.invoke(state) }
            }
            
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Timber.d("ICE connection state changed: $state")
                mainHandler.post { onIceConnectionChange?.invoke(state) }
            }
            
            override fun onIceConnectionReceivingChange(receiving: Boolean) {
                Timber.d("ICE connection receiving: $receiving")
            }
            
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                Timber.d("ICE gathering state: $state")
            }
            
            override fun onIceCandidate(candidate: IceCandidate) {
                Timber.d("New ICE candidate: ${candidate.sdpMid}")
                mainHandler.post { onIceCandidate?.invoke(candidate) }
            }
            
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {
                Timber.d("ICE candidates removed: ${candidates.size}")
            }
            
            override fun onAddStream(stream: MediaStream) {
                Timber.d("Remote stream added: ${stream.id}")
                mainHandler.post { onRemoteStream?.invoke(stream) }
            }
            
            override fun onRemoveStream(stream: MediaStream) {
                Timber.d("Remote stream removed: ${stream.id}")
            }
            
            override fun onDataChannel(channel: DataChannel) {
                Timber.d("Data channel received: ${channel.label()}")
                dataChannel = channel
                setupDataChannelObserver(channel)
            }
            
            override fun onRenegotiationNeeded() {
                Timber.d("Renegotiation needed")
            }
            
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                Timber.d("Track added: ${receiver.track()?.kind()}")
                val track = receiver.track()
                if (track is VideoTrack && streams.isNotEmpty()) {
                    mainHandler.post { onRemoteStream?.invoke(streams[0]) }
                }
            }
            
            override fun onTrack(transceiver: RtpTransceiver) {
                Timber.d("Track received: ${transceiver.receiver.track()?.kind()}")
            }
            
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                Timber.d("Connection state: $newState")
            }
            
            override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) {
                Timber.d("Selected candidate pair changed")
            }
        }
    }
    
    private fun createLocalTracks(config: WebRtcConfig) {
        val factory = peerConnectionFactory ?: return
        val pc = peerConnection ?: return
        
        // Create audio track
        if (config.enableAudio) {
            val audioConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            }
            
            localAudioSource = factory.createAudioSource(audioConstraints)
            localAudioTrack = factory.createAudioTrack("local_audio", localAudioSource)
            localAudioTrack?.setEnabled(true)
            
            pc.addTrack(localAudioTrack, listOf("local_stream"))
            Timber.d("Local audio track added")
        }
        
        // Create video track
        if (config.enableVideo) {
            createVideoTrack(config)
        }
        
        // Create local stream
        val localStream = factory.createLocalMediaStream("local_stream")
        localAudioTrack?.let { localStream.addTrack(it) }
        localVideoTrack?.let { localStream.addTrack(it) }
        
        mainHandler.post { onLocalStream?.invoke(localStream) }
    }
    
    private fun createVideoTrack(config: WebRtcConfig) {
        val factory = peerConnectionFactory ?: return
        val pc = peerConnection ?: return
        
        // Create video capturer
        videoCapturer = createVideoCapturer()
        if (videoCapturer == null) {
            Timber.e("Failed to create video capturer")
            return
        }
        
        // Create surface texture helper
        surfaceTextureHelper = SurfaceTextureHelper.create(
            "CaptureThread",
            eglBaseContext
        )
        
        // Create video source
        localVideoSource = factory.createVideoSource(videoCapturer!!.isScreencast)
        
        // Initialize capturer
        videoCapturer?.initialize(
            surfaceTextureHelper,
            context,
            localVideoSource?.capturerObserver
        )
        
        // Start capturing
        videoCapturer?.startCapture(
            config.videoWidth,
            config.videoHeight,
            config.videoFps
        )
        
        // Create video track
        localVideoTrack = factory.createVideoTrack("local_video", localVideoSource)
        localVideoTrack?.setEnabled(true)
        
        pc.addTrack(localVideoTrack, listOf("local_stream"))
        Timber.d("Local video track added")
    }
    
    private fun createVideoCapturer(): VideoCapturer? {
        // Try Camera2 first
        val camera2Enumerator = Camera2Enumerator(context)
        
        // Prefer front camera
        for (deviceName in camera2Enumerator.deviceNames) {
            if (camera2Enumerator.isFrontFacing(deviceName)) {
                val capturer = camera2Enumerator.createCapturer(deviceName, null)
                if (capturer != null) {
                    isFrontCamera = true
                    return capturer
                }
            }
        }
        
        // Fall back to back camera
        for (deviceName in camera2Enumerator.deviceNames) {
            if (camera2Enumerator.isBackFacing(deviceName)) {
                val capturer = camera2Enumerator.createCapturer(deviceName, null)
                if (capturer != null) {
                    isFrontCamera = false
                    return capturer
                }
            }
        }
        
        // Try Camera1 as fallback
        val camera1Enumerator = Camera1Enumerator(true)
        for (deviceName in camera1Enumerator.deviceNames) {
            if (camera1Enumerator.isFrontFacing(deviceName)) {
                val capturer = camera1Enumerator.createCapturer(deviceName, null)
                if (capturer != null) {
                    isFrontCamera = true
                    return capturer
                }
            }
        }
        
        for (deviceName in camera1Enumerator.deviceNames) {
            val capturer = camera1Enumerator.createCapturer(deviceName, null)
            if (capturer != null) {
                isFrontCamera = false
                return capturer
            }
        }
        
        return null
    }
    
    private fun createDataChannel() {
        val config = DataChannel.Init().apply {
            ordered = true
            negotiated = false
            maxRetransmits = -1
            maxRetransmitTimeMs = -1
        }
        
        dataChannel = peerConnection?.createDataChannel("data", config)
        dataChannel?.let { setupDataChannelObserver(it) }
    }
    
    private fun setupDataChannelObserver(channel: DataChannel) {
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            
            override fun onStateChange() {
                Timber.d("Data channel state: ${channel.state()}")
            }
            
            override fun onMessage(buffer: DataChannel.Buffer) {
                val data = ByteArray(buffer.data.remaining())
                buffer.data.get(data)
                val message = String(data, Charsets.UTF_8)
                mainHandler.post { onDataChannelMessage?.invoke(message) }
            }
        })
    }
    
    // ============ OFFER/ANSWER CREATION ============
    
    fun createOffer(callback: (SessionDescription?) -> Unit) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", 
                if (localVideoTrack != null) "true" else "false"))
        }
        
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                Timber.d("Offer created successfully")
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        Timber.d("Local description set")
                        mainHandler.post { callback(sdp) }
                    }
                    override fun onCreateFailure(error: String?) {
                        Timber.e("Set local description create failed: $error")
                        mainHandler.post { callback(null) }
                    }
                    override fun onSetFailure(error: String?) {
                        Timber.e("Set local description failed: $error")
                        mainHandler.post { callback(null) }
                    }
                }, sdp)
            }
            
            override fun onSetSuccess() {}
            
            override fun onCreateFailure(error: String?) {
                Timber.e("Create offer failed: $error")
                mainHandler.post { callback(null) }
            }
            
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }
    
    fun createAnswer(callback: (SessionDescription?) -> Unit) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo",
                if (localVideoTrack != null) "true" else "false"))
        }
        
        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                Timber.d("Answer created successfully")
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        Timber.d("Local description set")
                        mainHandler.post { callback(sdp) }
                    }
                    override fun onCreateFailure(error: String?) {
                        Timber.e("Set local description create failed: $error")
                        mainHandler.post { callback(null) }
                    }
                    override fun onSetFailure(error: String?) {
                        Timber.e("Set local description failed: $error")
                        mainHandler.post { callback(null) }
                    }
                }, sdp)
            }
            
            override fun onSetSuccess() {}
            
            override fun onCreateFailure(error: String?) {
                Timber.e("Create answer failed: $error")
                mainHandler.post { callback(null) }
            }
            
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }
    
    fun setRemoteDescription(sdp: SessionDescription, callback: (Boolean) -> Unit) {
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            
            override fun onSetSuccess() {
                Timber.d("Remote description set successfully")
                mainHandler.post { callback(true) }
            }
            
            override fun onCreateFailure(error: String?) {
                Timber.e("Set remote description create failed: $error")
                mainHandler.post { callback(false) }
            }
            
            override fun onSetFailure(error: String?) {
                Timber.e("Set remote description failed: $error")
                mainHandler.post { callback(false) }
            }
        }, sdp)
    }
    
    fun addIceCandidate(candidate: IceCandidate): Boolean {
        return try {
            peerConnection?.addIceCandidate(candidate) ?: false
        } catch (e: Exception) {
            Timber.e(e, "Failed to add ICE candidate")
            false
        }
    }
    
    // ============ MEDIA CONTROLS ============
    
    fun toggleAudio(): Boolean {
        isAudioEnabled = !isAudioEnabled
        localAudioTrack?.setEnabled(isAudioEnabled)
        Timber.d("Audio enabled: $isAudioEnabled")
        return isAudioEnabled
    }
    
    fun setAudioEnabled(enabled: Boolean) {
        isAudioEnabled = enabled
        localAudioTrack?.setEnabled(enabled)
    }
    
    fun toggleVideo(): Boolean {
        isVideoEnabled = !isVideoEnabled
        localVideoTrack?.setEnabled(isVideoEnabled)
        Timber.d("Video enabled: $isVideoEnabled")
        return isVideoEnabled
    }
    
    fun setVideoEnabled(enabled: Boolean) {
        isVideoEnabled = enabled
        localVideoTrack?.setEnabled(enabled)
    }
    
    fun toggleSpeaker(): Boolean {
        isSpeakerOn = !isSpeakerOn
        audioManager.isSpeakerphoneOn = isSpeakerOn
        Timber.d("Speaker on: $isSpeakerOn")
        return isSpeakerOn
    }
    
    fun setSpeakerEnabled(enabled: Boolean) {
        isSpeakerOn = enabled
        audioManager.isSpeakerphoneOn = enabled
    }
    
    fun switchCamera() {
        (videoCapturer as? CameraVideoCapturer)?.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFront: Boolean) {
                isFrontCamera = isFront
                Timber.d("Camera switched, isFront: $isFront")
            }
            
            override fun onCameraSwitchError(error: String) {
                Timber.e("Camera switch error: $error")
            }
        })
    }
    
    // ============ RENDERER MANAGEMENT ============
    
    fun initializeRenderer(renderer: SurfaceViewRenderer) {
        try {
            renderer.init(eglBaseContext, null)
            renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            renderer.setEnableHardwareScaler(true)
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize renderer")
        }
    }
    
    fun setLocalRenderer(renderer: SurfaceViewRenderer) {
        localVideoRenderer = renderer
        initializeRenderer(renderer)
        renderer.setMirror(isFrontCamera)
        localVideoTrack?.addSink(renderer)
    }
    
    fun setRemoteRenderer(renderer: SurfaceViewRenderer) {
        remoteVideoRenderer = renderer
        initializeRenderer(renderer)
        renderer.setMirror(false)
    }
    
    fun attachRemoteVideoTrack(videoTrack: VideoTrack) {
        remoteVideoRenderer?.let { renderer ->
            videoTrack.addSink(renderer)
        }
    }
    
    // ============ DATA CHANNEL ============
    
    fun sendDataChannelMessage(message: String): Boolean {
        return try {
            val buffer = DataChannel.Buffer(
                java.nio.ByteBuffer.wrap(message.toByteArray(Charsets.UTF_8)),
                false
            )
            dataChannel?.send(buffer) ?: false
        } catch (e: Exception) {
            Timber.e(e, "Failed to send data channel message")
            false
        }
    }
    
    // ============ AUDIO MANAGEMENT ============
    
    private fun saveAudioState() {
        savedAudioMode = audioManager.mode
        savedIsSpeakerOn = audioManager.isSpeakerphoneOn
        savedIsMicrophoneMute = audioManager.isMicrophoneMute
    }
    
    private fun restoreAudioState() {
        audioManager.mode = savedAudioMode
        audioManager.isSpeakerphoneOn = savedIsSpeakerOn
        audioManager.isMicrophoneMute = savedIsMicrophoneMute
    }
    
    private fun configureAudioForCall() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = false
        audioManager.isMicrophoneMute = false
    }
    
    // ============ CLEANUP ============
    
    fun close() {
        Timber.d("Closing WebRTC")
        
        executor.execute {
            try {
                // Stop video capture
                videoCapturer?.stopCapture()
                videoCapturer?.dispose()
                videoCapturer = null
                
                // Remove sinks
                localVideoTrack?.removeSink(localVideoRenderer)
                localVideoRenderer?.release()
                localVideoRenderer = null
                
                remoteVideoRenderer?.release()
                remoteVideoRenderer = null
                
                // Dispose tracks
                localVideoTrack?.dispose()
                localVideoTrack = null
                
                localAudioTrack?.dispose()
                localAudioTrack = null
                
                // Dispose sources
                localVideoSource?.dispose()
                localVideoSource = null
                
                localAudioSource?.dispose()
                localAudioSource = null
                
                // Dispose surface texture helper
                surfaceTextureHelper?.dispose()
                surfaceTextureHelper = null
                
                // Close data channel
                dataChannel?.close()
                dataChannel?.dispose()
                dataChannel = null
                
                // Close peer connection
                peerConnection?.close()
                peerConnection?.dispose()
                peerConnection = null
                
                // Restore audio state
                mainHandler.post { restoreAudioState() }
                
                Timber.d("WebRTC closed successfully")
            } catch (e: Exception) {
                Timber.e(e, "Error closing WebRTC")
            }
        }
    }
    
    fun dispose() {
        close()
        
        executor.execute {
            try {
                peerConnectionFactory?.dispose()
                peerConnectionFactory = null
                
                eglBase?.release()
                eglBase = null
                eglBaseContext = null
                
                PeerConnectionFactory.shutdownInternalTracer()
                
                Timber.d("WebRTC disposed")
            } catch (e: Exception) {
                Timber.e(e, "Error disposing WebRTC")
            }
        }
    }
    
    // ============ GETTERS ============
    
    fun getEglBaseContext(): EglBase.Context? = eglBaseContext
    fun getEglBase(): EglBase? = eglBase
    fun isAudioEnabled(): Boolean = isAudioEnabled
    fun isVideoEnabled(): Boolean = isVideoEnabled
    fun isSpeakerOn(): Boolean = isSpeakerOn
    fun isFrontCamera(): Boolean = isFrontCamera
    fun getLocalVideoTrack(): VideoTrack? = localVideoTrack
    fun getLocalAudioTrack(): AudioTrack? = localAudioTrack
}
