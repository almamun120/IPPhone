// app/src/main/java/com/wificalling/presentation/screens/call/CallViewModel.kt
package com.ipphone.presentation.screens.call

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ipphone.domain.model.CallType
import com.ipphone.domain.repository.UserRepository
import com.ipphone.network.calling.CallManager
import com.ipphone.service.CallService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import timber.log.Timber
import javax.inject.Inject

data class CallUiState(
    val callState: CallManager.CallState = CallManager.CallState.Idle,
    val peerName: String = "",
    val profileImage: String? = null,
    val callDuration: Long = 0L,
    val isMuted: Boolean = false,
    val isSpeaker: Boolean = false,
    val isCameraOn: Boolean = true,
    val localVideoTrack: org.webrtc.VideoTrack? = null,
    val remoteVideoTrack: org.webrtc.VideoTrack? = null,
    val hasCallBeenInitiated: Boolean = false,
    val callEndReason: String? = null
)

@HiltViewModel
class CallViewModel @Inject constructor(
    private val callManager: CallManager,
    private val peerAvailability: com.ipphone.network.presence.PeerAvailability,
    private val userRepository: UserRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        CallUiState(callState = callManager.callState.value)
    )
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    private var durationJob: kotlinx.coroutines.Job? = null

    init {
        observeCallState()
        observeCallEvents()
        observeTracks()
    }

    private fun observeCallState() {
        viewModelScope.launch {
            callManager.callState.collect { state ->
                _uiState.update { current ->
                    current.copy(
                        callState           = state,
                        hasCallBeenInitiated = current.hasCallBeenInitiated ||
                                (state !is CallManager.CallState.Idle),
                        callEndReason       = when (state) {
                            is CallManager.CallState.Ended -> state.reason
                            is CallManager.CallState.Idle  -> current.callEndReason
                            else                           -> current.callEndReason
                        }
                    )
                }

                when (state) {
                    is CallManager.CallState.Connected -> {
                        startDurationTimer(state.startTime)
                        // CallManager already switched video calls to the loudspeaker
                        // (toggling here used to switch it off again) — just mirror the real state.
                        _uiState.update { it.copy(isSpeaker = callManager.isSpeakerOn()) }
                    }
                    is CallManager.CallState.Outgoing,
                    is CallManager.CallState.Connecting -> {
                        _uiState.update { it.copy(isSpeaker = callManager.isSpeakerOn()) }
                    }
                    is CallManager.CallState.Ended,
                    CallManager.CallState.Idle -> {
                        stopDurationTimer()
                    }
                    else -> {}
                }
            }
        }
    }

    private fun observeTracks() {
        viewModelScope.launch {
            callManager.localVideoTrack.collect { track ->
                _uiState.update { it.copy(localVideoTrack = track) }
            }
        }
        viewModelScope.launch {
            callManager.remoteVideoTrack.collect { track ->
                _uiState.update { it.copy(remoteVideoTrack = track) }
            }
        }
    }

    private fun observeCallEvents() {
        viewModelScope.launch {
            callManager.callEvents.collect { event ->
                when (event) {
                    is CallManager.CallEvent.LocalVideoTrack ->
                        _uiState.update { it.copy(localVideoTrack = event.videoTrack) }
                    is CallManager.CallEvent.RemoteVideoTrack ->
                        _uiState.update { it.copy(remoteVideoTrack = event.videoTrack) }
                    else -> {}
                }
            }
        }
    }

    /**
     * Called by CallScreen when it arrives via navigation but a call is already
     * in progress (receiver side: Connected state when navigated from IncomingCallScreen).
     * Marks the call as initiated so the UI renders correctly without starting a new call.
     */
    fun markCallInitiated() {
        val currentState = callManager.callState.value
        _uiState.update { current ->
            val peerName = when (currentState) {
                is CallManager.CallState.Connected -> current.peerName
                else -> current.peerName
            }
            current.copy(
                callState            = currentState,
                hasCallBeenInitiated = true
            )
        }

        // Load peer name if we don't have it yet
        if (_uiState.value.peerName.isEmpty()) {
            val peerId = callManager.currentPeerId ?: return
            viewModelScope.launch {
                val user = userRepository.getUserByIdOnce(peerId)
                _uiState.update { it.copy(
                    peerName = user?.deviceName ?: "Unknown",
                    profileImage = user?.profileImage
                ) }
            }
        }
    }

    fun startCall(userId: String, callType: CallType) {
        val current = _uiState.value.callState
        if (current !is CallManager.CallState.Idle && current !is CallManager.CallState.Ended) {
            Timber.d("startCall ignored — already in state: $current")
            // Still mark as initiated and load peer name
            markCallInitiated()
            if (_uiState.value.peerName.isEmpty()) {
                viewModelScope.launch {
                    val user = userRepository.getUserByIdOnce(userId)
                    _uiState.update { it.copy(
                        peerName = user?.deviceName ?: "Unknown",
                        profileImage = user?.profileImage
                    ) }
                }
            }
            return
        }

        viewModelScope.launch {
            try {
                // Gate first: if the peer is not reachable nothing at all is started — no
                // foreground service, microphone, camera, call log or contact entry.
                if (!peerAvailability.isOnline(userId)) {
                    val known = userRepository.getUserByIdOnce(userId)
                    _uiState.update {
                        it.copy(
                            peerName             = known?.deviceName ?: userId,
                            profileImage         = known?.profileImage,
                            hasCallBeenInitiated = true
                        )
                    }
                    callManager.reportUnavailable()
                    return@launch
                }

                val user = userRepository.getUserByIdOnce(userId)
                _uiState.update {
                    it.copy(
                        peerName             = user?.deviceName ?: "Unknown",
                        profileImage         = user?.profileImage,
                        hasCallBeenInitiated = true
                    )
                }

                val intent = Intent(context, CallService::class.java).apply {
                    action = CallService.ACTION_OUTGOING_CALL_FOREGROUND
                    putExtra(CallService.EXTRA_PEER_ID, userId)
                    putExtra(CallService.EXTRA_CALL_TYPE, callType.name)
                }
                ContextCompat.startForegroundService(context, intent)

                callManager.startCall(userId, callType)

            } catch (e: Exception) {
                Timber.e(e, "Error starting call to $userId")
                _uiState.update { it.copy(hasCallBeenInitiated = false) }
            }
        }
    }

    fun acceptCall() {
        viewModelScope.launch { callManager.acceptCall() }
    }

    fun rejectCall() {
        viewModelScope.launch { callManager.rejectCall() }
    }

    fun endCall() {
        viewModelScope.launch { callManager.endCall() }
    }

    fun toggleMute() {
        val muted = callManager.toggleMute()
        _uiState.update { it.copy(isMuted = muted) }
    }

    fun toggleSpeaker() {
        val speaker = callManager.toggleSpeaker()
        _uiState.update { it.copy(isSpeaker = speaker) }
    }

    fun toggleCamera() {
        val cameraOn = callManager.toggleCamera()
        _uiState.update { it.copy(isCameraOn = cameraOn) }
    }

    fun switchCamera() = callManager.switchCamera()

    fun getEglBase(): EglBase? = callManager.getEglBase()

    private fun startDurationTimer(startTime: Long) {
        durationJob?.cancel()
        durationJob = viewModelScope.launch {
            while (true) {
                val duration = (System.currentTimeMillis() - startTime) / 1000
                _uiState.update { it.copy(callDuration = duration) }
                delay(1_000)
            }
        }
    }

    private fun stopDurationTimer() {
        durationJob?.cancel()
        durationJob = null
    }

    override fun onCleared() {
        super.onCleared()
        stopDurationTimer()
    }
}
