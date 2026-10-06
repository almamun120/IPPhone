package com.ipphone.network.calling

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.ipphone.service.CallService
import com.ipphone.data.local.datastore.PreferencesManager
import com.ipphone.domain.model.CallDirection
import com.ipphone.domain.model.CallLog
import com.ipphone.domain.model.CallStatus
import com.ipphone.domain.model.CallType
import com.ipphone.domain.repository.CallRepository
import com.ipphone.domain.repository.UserRepository
import com.ipphone.network.connection.ConnectionManager
import com.ipphone.network.protocol.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.webrtc.*
import org.webrtc.audio.JavaAudioDeviceModule
import timber.log.Timber
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connectionManager: ConnectionManager,
    private val callRepository: CallRepository,
    private val userRepository: UserRepository,
    private val preferencesManager: PreferencesManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateMutex = Mutex()

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    private val _localVideoTrackState = MutableStateFlow<VideoTrack?>(null)
    val localVideoTrack: StateFlow<VideoTrack?> = _localVideoTrackState.asStateFlow()

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var localVideoTrackProperty: VideoTrack? = null
    private var localVideoSource: VideoSource? = null
    private var videoCapturer: VideoCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var eglBase: EglBase? = null

    private val _callState = MutableStateFlow<CallState>(CallState.Idle)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    private val _callEvents = MutableSharedFlow<CallEvent>(extraBufferCapacity = 8)
    val callEvents: SharedFlow<CallEvent> = _callEvents.asSharedFlow()

    var currentCallId: String? = null
    var currentPeerId: String? = null
    var currentCallType: CallType? = null
        private set
    private var callStartTime: Long? = null
    private var ringTimeoutJob: Job? = null
    private var durationTrackingJob: Job? = null
    private var iceFailureRetryJob: Job? = null

    @Volatile private var isCleanedUp = false
    private var iceRestartAttempted = false

    // Thread-safe ICE candidate queue
    private val iceCandidateQueue = CopyOnWriteArrayList<IceCandidate>()
    private val remoteDescriptionSet = AtomicBoolean(false)

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val connectivityManager: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // Network callback for hotspot detection
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var boundNetwork: Network? = null

    // =========================================================================
    //  Sealed classes
    // =========================================================================

    sealed class CallState {
        object Idle : CallState()
        data class Outgoing(val peerId: String, val callType: CallType) : CallState()
        data class Incoming(
            val callId: String, val peerId: String,
            val peerName: String, val callType: CallType
        ) : CallState()
        data class Connecting(val peerId: String, val callType: CallType) : CallState()
        data class Connected(
            val peerId: String, val callType: CallType, val startTime: Long
        ) : CallState()
        data class Ended(val reason: String) : CallState()
    }

    sealed class CallEvent {
        data class IncomingCall(
            val callId: String, val peerId: String,
            val peerName: String, val callType: CallType
        ) : CallEvent()
        data class CallAccepted(val callId: String) : CallEvent()
        data class CallRejected(val callId: String, val reason: String) : CallEvent()
        data class CallEnded(val callId: String, val reason: String, val duration: Long) : CallEvent()
        data class CallFailed(val callId: String, val error: String) : CallEvent()
        data class RemoteVideoTrack(val videoTrack: VideoTrack) : CallEvent()
        data class LocalVideoTrack(val videoTrack: VideoTrack?) : CallEvent()
        data class CallMissed(val callId: String) : CallEvent()
    }

    // =========================================================================
    //  Network Detection & Binding (THE FIX)
    // =========================================================================

    /**
     * Detects the correct network interface for hotspot mode.
     * Returns the Network object that WebRTC should bind to.
     */
    private fun detectActiveNetwork(): Network? {
        try {
            // First, try to get the active network
            val activeNetwork = connectivityManager.activeNetwork
            if (activeNetwork != null) {
                val caps = connectivityManager.getNetworkCapabilities(activeNetwork)
                if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                    Timber.d("Active WiFi network found: $activeNetwork")
                    return activeNetwork
                }
            }

            // If active network is not WiFi, enumerate all networks
            val allNetworks = connectivityManager.allNetworks
            for (network in allNetworks) {
                val caps = connectivityManager.getNetworkCapabilities(network) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    Timber.d("Found WiFi network in all networks: $network")
                    return network
                }
            }

            Timber.w("No WiFi network found")
            return null
        } catch (e: Exception) {
            Timber.e(e, "Failed to detect active network")
            return null
        }
    }

    /**
     * Gets the local IP address for the hotspot interface.
     */
    private fun getLocalIpAddress(): String? {
        try {
            @Suppress("DEPRECATION")
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = wm.connectionInfo?.ipAddress ?: 0
            if (ip != 0) {
                return android.text.format.Formatter.formatIpAddress(ip)
            }
        } catch (_: Exception) {}

        // Enumerate network interfaces
        val hotspotPrefixes = listOf("wlan", "ap", "swlan", "wigig", "softap")
        var fallback: String? = null

        try {
            val ifaces = Collections.list(NetworkInterface.getNetworkInterfaces()) ?: return null
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.lowercase()
                val isHotspotIface = hotspotPrefixes.any { name.startsWith(it) }

                for (addr in Collections.list(iface.inetAddresses)) {
                    if (addr !is Inet4Address) continue
                    if (addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                    val ip = addr.hostAddress ?: continue
                    if (isHotspotIface) {
                        Timber.d("Hotspot interface detected: ${iface.name} -> $ip")
                        return ip
                    }
                    if (fallback == null) fallback = ip
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "getLocalIpAddress failed")
        }
        return fallback
    }

    /**
     * Checks if device is in hotspot mode.
     */
    private fun isHotspotHost(): Boolean {
        try {
            @Suppress("DEPRECATION")
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val clientIp = wm.connectionInfo?.ipAddress ?: 0
            if (clientIp != 0) return false

            val hotspotPrefixes = listOf("wlan", "ap", "swlan", "softap")
            val ifaces = Collections.list(NetworkInterface.getNetworkInterfaces()) ?: return false
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.lowercase()
                if (!hotspotPrefixes.any { name.startsWith(it) }) continue
                for (addr in Collections.list(iface.inetAddresses)) {
                    if (addr !is Inet4Address) continue
                    if (addr.isLoopbackAddress || addr.isLinkLocalAddress) continue
                    val ip = addr.hostAddress ?: continue
                    if (ip.isNotEmpty()) {
                        Timber.d("isHotspotHost=true via interface ${iface.name} ip=$ip")
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "isHotspotHost check failed")
        }
        return false
    }

    /**
     * Registers a network callback to detect when WiFi network becomes available.
     * This is crucial for hotspot mode where the network might not be immediately available.
     */
    private fun registerNetworkCallback() {
        try {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    Timber.d("Network available: $network")

                    // Check if this network has our hotspot IP
                    val localIp = getLocalIpAddress()
                    if (localIp != null && isHotspotHost()) {
                        Timber.d("Hotspot network detected, binding WebRTC to $network")
                        boundNetwork = network

                        // Update PeerConnection configuration if already created
                        peerConnection?.let { pc ->
                            updatePeerConnectionNetwork(pc, network)
                        }
                    }
                }

                override fun onLost(network: Network) {
                    super.onLost(network)
                    Timber.d("Network lost: $network")
                    if (boundNetwork == network) {
                        boundNetwork = null
                    }
                }
            }

            connectivityManager.registerNetworkCallback(request, networkCallback!!)
            Timber.d("Network callback registered")
        } catch (e: Exception) {
            Timber.e(e, "Failed to register network callback")
        }
    }

    /**
     * Updates an existing PeerConnection to use a specific network.
     */
    private fun updatePeerConnectionNetwork(pc: PeerConnection, network: Network) {
        try {
            // Get current configuration
            val currentConfig = PeerConnection.RTCConfiguration(buildIceServers()).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                continualGatheringPolicy =
                    PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                iceTransportsType = PeerConnection.IceTransportsType.ALL
                tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
                candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.ALL
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            }

            // Update configuration (this triggers ICE restart)
            val result = pc.setConfiguration(currentConfig)
            Timber.d("PeerConnection network binding updated: $result")

            // Force the connection to use this network at the OS level
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                connectivityManager.bindProcessToNetwork(network)
                Timber.d("Process bound to network: $network")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to update PeerConnection network")
        }
    }

    /**
     * Unbinds process from network.
     */
    private fun unbindNetwork() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                connectivityManager.bindProcessToNetwork(null)
                Timber.d("Process unbound from network")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to unbind network")
        }
    }

    /**
     * ICE server list.
     */
    private fun buildIceServers(): List<PeerConnection.IceServer> {
        return listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
    }

    // =========================================================================
    //  Init
    // =========================================================================

    init {
        initializeWebRTC()
        observeIncomingMessages()
        registerNetworkCallback()
    }

    private fun initializeWebRTC() {
        try {
            eglBase = EglBase.create()

            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .setEnableInternalTracer(true)
                    .setFieldTrials("WebRTC-H264HighProfile/Enabled/")
                    .createInitializationOptions()
            )

            val audioDeviceModule = JavaAudioDeviceModule.builder(context)
                .setUseHardwareAcousticEchoCanceler(true)
                .setUseHardwareNoiseSuppressor(true)
                .createAudioDeviceModule()

            peerConnectionFactory = PeerConnectionFactory.builder()
                .setOptions(PeerConnectionFactory.Options().apply {
                    disableEncryption = false
                    // CRITICAL FIX: Disable NetworkMonitor so it doesn't interfere
                    // We'll manage network binding manually
                    disableNetworkMonitor = true
                    // Don't ignore any network interfaces
                    networkIgnoreMask = 0
                })
                .setAudioDeviceModule(audioDeviceModule)
                .setVideoEncoderFactory(
                    DefaultVideoEncoderFactory(eglBase?.eglBaseContext, true, true)
                )
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase?.eglBaseContext))
                .createPeerConnectionFactory()

            Timber.d("WebRTC initialized | disableNetworkMonitor=true networkIgnoreMask=0")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize WebRTC")
        }
    }

    private fun observeIncomingMessages() {
        scope.launch {
            connectionManager.incomingMessages.collect { (senderId, message) ->
                handleIncomingMessage(senderId, message)
            }
        }
    }

    private suspend fun handleIncomingMessage(senderId: String, message: ProtocolMessage) {
        when (message) {
            is CallOfferMessage -> handleCallOffer(senderId, message)
            is CallAnswerMessage -> handleCallAnswer(senderId, message)
            is IceCandidateMessage -> handleIceCandidate(senderId, message)
            is CallEndMessage -> handleCallEnd(senderId, message)
            else -> {}
        }
    }

    // =========================================================================
    //  PeerConnection creation with network binding
    // =========================================================================

    private fun createPeerConnection(peerId: String) {
        val rtcConfig = PeerConnection.RTCConfiguration(buildIceServers()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy =
                PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            candidateNetworkPolicy = PeerConnection.CandidateNetworkPolicy.ALL
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(
            rtcConfig, buildObserver()
        )

        // CRITICAL FIX: Bind to correct network BEFORE gathering candidates
        val localIp = getLocalIpAddress()
        val isHotspot = isHotspotHost()

        Timber.d("Creating PeerConnection | localIP=$localIp isHotspot=$isHotspot peer=$peerId")

        if (isHotspot && localIp != null) {
            // Detect and bind to the WiFi network
            val network = detectActiveNetwork()
            if (network != null) {
                boundNetwork = network
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        connectivityManager.bindProcessToNetwork(network)
                        Timber.d("✓ Process bound to network $network for hotspot IP $localIp")
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to bind process to network")
                    }
                }
            } else {
                Timber.w("⚠ Hotspot detected but no WiFi network found - candidates may be incorrect")
            }
        }
    }

    private fun buildObserver(): PeerConnection.Observer = object : PeerConnection.Observer {

        override fun onSignalingChange(state: PeerConnection.SignalingState?) {
            Timber.d("Signaling: $state")
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
            Timber.d("ICE → $state")
            scope.launch {
                when (state) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        iceFailureRetryJob?.cancel()
                        val startTime = System.currentTimeMillis()
                        callStartTime = startTime
                        _callState.value = CallState.Connected(
                            peerId = currentPeerId ?: "",
                            callType = currentCallType ?: CallType.AUDIO,
                            startTime = startTime
                        )
                        currentCallId?.let { id ->
                            callRepository.updateCallStatus(
                                id, CallStatus.CONNECTED.name, startTime, 0
                            )
                        }
                        startDurationTracking(startTime)
                    }

                    PeerConnection.IceConnectionState.DISCONNECTED ->
                        Timber.w("ICE disconnected — waiting to recover")

                    PeerConnection.IceConnectionState.FAILED -> {
                        Timber.e("ICE FAILED")
                        scheduleIceRestartOrFail()
                    }

                    PeerConnection.IceConnectionState.CLOSED ->
                        Timber.d("ICE closed")

                    else -> {}
                }
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {}

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
            Timber.d("ICE gathering: $state")
        }

        override fun onIceCandidate(candidate: IceCandidate?) {
            candidate ?: return
            Timber.d("Local candidate: ${candidate.sdp}")
            scope.launch { sendIceCandidate(candidate) }
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}

        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(dataChannel: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<MediaStream>?) {}

        override fun onTrack(transceiver: RtpTransceiver?) {
            val track = transceiver?.receiver?.track() ?: return
            Timber.d("onTrack kind=${track.kind()} id=${track.id()}")
            if (track is VideoTrack) {
                track.setEnabled(true)
                _remoteVideoTrack.value = track
                scope.launch { _callEvents.emit(CallEvent.RemoteVideoTrack(track)) }
            } else if (track is AudioTrack) {
                track.setEnabled(true)
            }
        }
    }

    // =========================================================================
    //  ICE restart
    // =========================================================================

    private fun scheduleIceRestartOrFail() {
        iceFailureRetryJob?.cancel()
        iceFailureRetryJob = scope.launch {
            if (!iceRestartAttempted) {
                iceRestartAttempted = true
                Timber.d("ICE restart attempt")
                delay(500)

                // Re-bind to network if needed
                val isHotspot = isHotspotHost()
                if (isHotspot) {
                    val network = detectActiveNetwork()
                    if (network != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        connectivityManager.bindProcessToNetwork(network)
                        Timber.d("Re-bound to network during ICE restart")
                    }
                }

                peerConnection?.restartIce()

                delay(10_000)
                if (_callState.value !is CallState.Connected) {
                    Timber.e("ICE restart failed — ending call")
                    endCall("Connection failed")
                }
            } else {
                Timber.e("ICE already restarted once — ending call")
                endCall("Connection failed")
            }
        }
    }

    // =========================================================================
    //  Remote description + candidate queue
    // =========================================================================

    private fun setRemoteDescriptionSafe(sdp: SessionDescription) {
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}

            override fun onSetSuccess() {
                Timber.d("Remote SDP set ✓ — draining ${iceCandidateQueue.size} queued candidate(s)")
                remoteDescriptionSet.set(true)
                drainCandidateQueue()
            }

            override fun onCreateFailure(error: String?) =
                Timber.e("setRemoteDesc create fail: $error")

            override fun onSetFailure(error: String?) =
                Timber.e("setRemoteDesc set fail: $error")
        }, sdp)
    }

    private fun drainCandidateQueue() {
        val pc = peerConnection ?: return
        val snapshot = iceCandidateQueue.toList()
        iceCandidateQueue.clear()
        var added = 0
        for (c in snapshot) {
            if (pc.addIceCandidate(c)) added++
            else Timber.w("addIceCandidate failed: ${c.sdp.take(60)}")
        }
        if (snapshot.isNotEmpty())
            Timber.d("Candidate queue drained: $added/${snapshot.size} added")
    }

    // =========================================================================
    //  Outgoing call
    // =========================================================================

    suspend fun startCall(peerId: String, callType: CallType): String {
        val myId = preferencesManager.getDeviceUuid()
        if (peerId == myId) throw IllegalArgumentException("Cannot call yourself")

        val current = _callState.value
        if (current !is CallState.Idle && current !is CallState.Ended)
            throw IllegalStateException("Already in a call")

        val callId = UUID.randomUUID().toString()
        val resolvedPeerId = resolveToUuid(peerId)

        Timber.d("startCall: peer=$resolvedPeerId callType=$callType")

        stateMutex.withLock {
            cleanup()
            isCleanedUp = false
            currentCallId = callId
            currentPeerId = resolvedPeerId
            currentCallType = callType
            remoteDescriptionSet.set(false)
            iceCandidateQueue.clear()
            iceRestartAttempted = false
        }

        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = (callType == CallType.VIDEO)

        _callState.value = CallState.Outgoing(resolvedPeerId, callType)

        var user = userRepository.getUserByIdOnce(resolvedPeerId)
        if (user == null) {
            val nu = com.ipphone.domain.model.User(
                uuid = resolvedPeerId, deviceName = resolvedPeerId,
                pairingState = com.ipphone.domain.model.PairingState.NONE
            )
            userRepository.saveUser(nu); user = nu
        }

        callRepository.saveCallLog(
            CallLog(
                id = callId, peerId = resolvedPeerId,
                peerName = user.deviceName, peerImage = user.profileImage,
                callType = callType, direction = CallDirection.OUTGOING,
                status = CallStatus.RINGING
            )
        )

        try {
            createPeerConnection(resolvedPeerId)
            addLocalTracks()
            createAndSendOffer(callId, resolvedPeerId, callType)
            startRingTimeout(callId)
        } catch (e: Exception) {
            Timber.e(e, "startCall failed")
            endCall("User not available")
        }

        return callId
    }

    private suspend fun resolveToUuid(deviceId: String): String {
        if (!deviceId.matches(Regex("""^(\d{1,3}\.){3}\d{1,3}$"""))) return deviceId
        return try {
            userRepository.getUserByIp(deviceId)?.uuid ?: deviceId
        } catch (e: Exception) {
            deviceId
        }
    }

    private fun startRingTimeout(callId: String) {
        ringTimeoutJob?.cancel()
        ringTimeoutJob = scope.launch {
            delay(60_000)
            if (_callState.value is CallState.Outgoing && currentCallId == callId)
                endCall("No answer")
        }
    }

    // =========================================================================
    //  Offer creation
    // =========================================================================

    private fun createAndSendOffer(
        callId: String,
        peerId: String,
        callType: CallType
    ) {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            if (callType == CallType.VIDEO)
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp ?: return

                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        scope.launch {
                            val myId = preferencesManager.getDeviceUuid()
                            val sent = connectionManager.sendMessage(
                                peerId,
                                CallOfferMessage(
                                    senderId = myId, callId = callId,
                                    callType = callType.name,
                                    sdpOffer = sdp.description
                                )
                            )
                            if (!sent) {
                                Timber.e("Offer send failed")
                                endCall("User not available")
                            } else {
                                Timber.d("Offer sent → $peerId")
                            }
                        }
                    }
                    override fun onCreateFailure(e: String?) = Timber.e("setLocalDesc fail: $e")
                    override fun onSetFailure(e: String?) = Timber.e("setLocalDesc fail: $e")
                }, sdp)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(e: String?) = Timber.e("createOffer fail: $e")
            override fun onSetFailure(e: String?) {}
        }, constraints)
    }

    // =========================================================================
    //  Incoming call
    // =========================================================================

    private suspend fun handleCallOffer(senderId: String, offer: CallOfferMessage) {
        val current = _callState.value
        if (current !is CallState.Idle && current !is CallState.Ended) {
            Timber.d("Busy — rejecting offer from $senderId")
            sendCallAnswerMsg(offer.callId, senderId, false, null, "BUSY")
            return
        }

        Timber.d("Incoming offer from $senderId callId=${offer.callId}")

        var user = userRepository.getUserByIdOnce(senderId)
        if (user == null) {
            val nu = com.ipphone.domain.model.User(
                uuid = senderId, deviceName = senderId,
                pairingState = com.ipphone.domain.model.PairingState.NONE
            )
            userRepository.saveUser(nu); user = nu
        }

        callRepository.saveCallLog(
            CallLog(
                id = offer.callId, peerId = senderId,
                peerName = user.deviceName, peerImage = user.profileImage,
                callType = CallType.valueOf(offer.callType),
                direction = CallDirection.INCOMING, status = CallStatus.RINGING
            )
        )

        stateMutex.withLock {
            cleanup()
            isCleanedUp = false
            currentCallId = offer.callId
            currentPeerId = senderId
            currentCallType = CallType.valueOf(offer.callType)
            remoteDescriptionSet.set(false)
            iceCandidateQueue.clear()
            iceRestartAttempted = false
        }

        _callState.value = CallState.Incoming(
            callId = offer.callId, peerId = senderId,
            peerName = user.deviceName, callType = CallType.valueOf(offer.callType)
        )

        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CallService::class.java).apply {
                    action = CallService.ACTION_INCOMING_CALL_RING
                    putExtra(CallService.EXTRA_CALL_ID, offer.callId)
                    putExtra(CallService.EXTRA_PEER_NAME, user.deviceName)
                    putExtra(CallService.EXTRA_CALL_TYPE, offer.callType)
                }
            )
        } catch (e: Exception) {
            Timber.e(e, "Failed to start CallService")
        }

        createPeerConnection(senderId)
        setRemoteDescriptionSafe(
            SessionDescription(SessionDescription.Type.OFFER, offer.sdpOffer)
        )

        _callEvents.emit(
            CallEvent.IncomingCall(
                callId = offer.callId, peerId = senderId,
                peerName = user.deviceName, callType = CallType.valueOf(offer.callType)
            )
        )
    }

    // =========================================================================
    //  Accept / Reject
    // =========================================================================

    suspend fun acceptCall() {
        ringTimeoutJob?.cancel()
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.isSpeakerphoneOn = (currentCallType == CallType.VIDEO)

        addLocalTracks()
        _callState.value = CallState.Connecting(
            peerId = currentPeerId ?: "", callType = currentCallType ?: CallType.AUDIO
        )

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            if (currentCallType == CallType.VIDEO)
                mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp ?: return

                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        scope.launch {
                            val callId = currentCallId ?: return@launch
                            val peerId = currentPeerId ?: return@launch
                            sendCallAnswerMsg(callId, peerId, true, sdp.description)

                            callRepository.updateCallStatus(
                                callId, CallStatus.CONNECTING.name,
                                System.currentTimeMillis(), 0
                            )
                            _callEvents.emit(CallEvent.CallAccepted(callId))
                        }
                    }
                    override fun onCreateFailure(e: String?) = Timber.e("setLocalDesc fail: $e")
                    override fun onSetFailure(e: String?) = Timber.e("setLocalDesc fail: $e")
                }, sdp)
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(e: String?) = Timber.e("createAnswer fail: $e")
            override fun onSetFailure(e: String?) {}
        }, constraints)
    }

    suspend fun rejectCall() {
        _callEvents.emit(CallEvent.CallRejected(currentCallId ?: "", "User rejected"))
        endCall("REJECTED")
    }

    // =========================================================================
    //  Answer / ICE candidate handlers
    // =========================================================================

    private suspend fun handleCallAnswer(senderId: String, answer: CallAnswerMessage) {
        if (answer.callId != currentCallId) return

        if (!answer.accepted) {
            Timber.d("Call rejected by $senderId: ${answer.reason}")
            endCall(answer.reason ?: "REJECTED", notifyPeer = false)
            return
        }

        Timber.d("Call accepted by $senderId — setting remote answer")
        setRemoteDescriptionSafe(
            SessionDescription(SessionDescription.Type.ANSWER, answer.sdpAnswer)
        )
    }

    private suspend fun handleIceCandidate(senderId: String, message: IceCandidateMessage) {
        if (message.callId != currentCallId) return

        val candidate = IceCandidate(
            message.sdpMid, message.sdpMLineIndex, message.candidate
        )

        if (remoteDescriptionSet.get()) {
            val ok = peerConnection?.addIceCandidate(candidate) ?: false
            Timber.d("Remote candidate added: ok=$ok")
        } else {
            iceCandidateQueue.add(candidate)
            Timber.d("Remote candidate queued: size=${iceCandidateQueue.size}")
        }
    }

    private suspend fun sendCallAnswerMsg(
        callId: String, peerId: String,
        accepted: Boolean, sdp: String?, reason: String? = null
    ) {
        val myId = preferencesManager.getDeviceUuid()
        val sent = connectionManager.sendMessage(
            peerId,
            CallAnswerMessage(
                senderId = myId, callId = callId, accepted = accepted,
                sdpAnswer = sdp, reason = reason ?: if (accepted) null else "REJECTED"
            )
        )
        if (!sent) Timber.e("sendCallAnswer failed callId=$callId")
    }

    private suspend fun sendIceCandidate(candidate: IceCandidate) {
        val peerId = currentPeerId ?: return
        val callId = currentCallId ?: return
        val myId = preferencesManager.getDeviceUuid()
        connectionManager.sendMessage(
            peerId,
            IceCandidateMessage(
                senderId = myId, callId = callId,
                candidate = candidate.sdp,
                sdpMid = candidate.sdpMid,
                sdpMLineIndex = candidate.sdpMLineIndex
            )
        )
    }

    private suspend fun handleCallEnd(senderId: String, message: CallEndMessage) {
        if (message.callId != currentCallId) return
        endCall(message.reason, notifyPeer = false)
    }

    // =========================================================================
    //  End call
    // =========================================================================

    suspend fun endCall(reason: String = "Call ended", notifyPeer: Boolean = true) {
        stateMutex.withLock {
            if (isCleanedUp) return
            isCleanedUp = true

            val peerId = currentPeerId
            val callId = currentCallId
            val startTime = callStartTime

            Timber.d("endCall callId=$callId reason=$reason")

            if (notifyPeer && peerId != null && callId != null) {
                val myId = preferencesManager.getDeviceUuid()
                connectionManager.sendMessage(
                    peerId,
                    CallEndMessage(senderId = myId, callId = callId, reason = reason)
                )
            }

            if (callId != null) {
                val duration = startTime?.let { (System.currentTimeMillis() - it) / 1000 } ?: 0
                val status = when {
                    reason.equals("REJECTED", true) || reason.equals("BUSY", true) ->
                        CallStatus.REJECTED
                    reason == "No answer" -> CallStatus.MISSED
                    reason == "User not available" -> CallStatus.FAILED
                    else -> CallStatus.ENDED
                }
                _callEvents.emit(
                    when (status) {
                        CallStatus.REJECTED -> CallEvent.CallRejected(callId, reason)
                        CallStatus.MISSED -> CallEvent.CallMissed(callId)
                        else -> CallEvent.CallEnded(callId, reason, duration)
                    }
                )
                callRepository.updateCallStatus(
                    callId, status.name, System.currentTimeMillis(), duration
                )
            }
            cleanup()
        }

        _callState.value = CallState.Ended(reason)
        scope.launch {
            delay(1500)
            stateMutex.withLock {
                if (_callState.value is CallState.Ended && isCleanedUp)
                    _callState.value = CallState.Idle
            }
        }
    }

    private fun cleanup() {
        Timber.d("cleanup()")
        try {
            ringTimeoutJob?.cancel(); ringTimeoutJob = null
            durationTrackingJob?.cancel(); durationTrackingJob = null
            iceFailureRetryJob?.cancel(); iceFailureRetryJob = null

            localAudioTrack?.dispose(); localAudioTrack = null
            localVideoTrackProperty?.dispose(); localVideoTrackProperty = null

            try {
                videoCapturer?.stopCapture()
            } catch (_: Exception) {
            }
            videoCapturer?.dispose(); videoCapturer = null
            surfaceTextureHelper?.dispose(); surfaceTextureHelper = null
            localVideoSource?.dispose(); localVideoSource = null

            peerConnection?.dispose(); peerConnection = null

            // Unbind network
            unbindNetwork()
            boundNetwork = null

            currentCallId = null
            currentPeerId = null
            callStartTime = null
            remoteDescriptionSet.set(false)
            iceCandidateQueue.clear()
            iceRestartAttempted = false

            _remoteVideoTrack.value = null
            _localVideoTrackState.value = null

            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false
        } catch (e: Exception) {
            Timber.e(e, "cleanup error")
        }
    }

    // =========================================================================
    //  Local tracks
    // =========================================================================

    private fun addLocalTracks() {
        try {
            if (localAudioTrack != null) return

            localAudioTrack = peerConnectionFactory?.createAudioTrack(
                "audio0", peerConnectionFactory?.createAudioSource(MediaConstraints())
            )
            localAudioTrack?.let { peerConnection?.addTrack(it, listOf("stream0")) }

            if (currentCallType == CallType.VIDEO) {
                surfaceTextureHelper =
                    SurfaceTextureHelper.create("CaptureThread", eglBase?.eglBaseContext)
                localVideoSource = peerConnectionFactory?.createVideoSource(false)
                videoCapturer = createVideoCapturer()
                videoCapturer?.let { cap ->
                    surfaceTextureHelper?.let { h ->
                        cap.initialize(h, context, localVideoSource?.capturerObserver)
                        cap.startCapture(1280, 720, 30)
                    }
                }
                localVideoTrackProperty =
                    peerConnectionFactory?.createVideoTrack("video0", localVideoSource)
                localVideoTrackProperty?.let { track ->
                    _localVideoTrackState.value = track
                    peerConnection?.addTrack(track, listOf("stream0"))
                    scope.launch { _callEvents.emit(CallEvent.LocalVideoTrack(track)) }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "addLocalTracks failed")
        }
    }

    private fun createVideoCapturer(): VideoCapturer? {
        val e = Camera2Enumerator(context)
        for (name in e.deviceNames) if (e.isFrontFacing(name)) return Camera2Capturer(
            context,
            name,
            null
        )
        for (name in e.deviceNames) if (!e.isFrontFacing(name)) return Camera2Capturer(
            context,
            name,
            null
        )
        return null
    }

    // =========================================================================
    //  Public controls
    // =========================================================================

    fun toggleMute(): Boolean {
        val isEnabled = localAudioTrack?.enabled() == true
        val newEnabledState = !isEnabled
        localAudioTrack?.setEnabled(newEnabledState)
        return !newEnabledState // Returns true if now MUTED (disabled)
    }

    fun toggleSpeaker(): Boolean {
        val on = !audioManager.isSpeakerphoneOn
        audioManager.isSpeakerphoneOn = on
        return on
    }

    fun toggleCamera(): Boolean {
        val enabled = localVideoTrackProperty?.enabled() == true
        localVideoTrackProperty?.setEnabled(!enabled)
        return !enabled
    }

    fun switchCamera() {
        (videoCapturer as? Camera2Capturer)?.switchCamera(null)
    }

    fun getEglBase(): EglBase? = eglBase

    private fun startDurationTracking(startTime: Long) {
        durationTrackingJob?.cancel()
        durationTrackingJob = scope.launch { while (true) delay(1000) }
    }

    fun onDestroy() {
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                Timber.e(e, "Failed to unregister network callback")
            }
        }
        cleanup()
    }

    protected fun finalize() {
        onDestroy()
    }
}