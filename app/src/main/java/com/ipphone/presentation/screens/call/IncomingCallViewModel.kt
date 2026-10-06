// app/src/main/java/com/wificalling/presentation/screens/call/IncomingCallViewModel.kt
package com.ipphone.presentation.screens.call

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ipphone.domain.model.CallType
import com.ipphone.domain.repository.CallRepository
import com.ipphone.domain.repository.UserRepository
import com.ipphone.network.calling.CallManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class IncomingCallUiState(
    val callId: String = "",
    val peerId: String = "",
    val peerName: String = "",
    val peerImage: String? = null,
    val callType: CallType = CallType.AUDIO,
    val isAnswering: Boolean = false,
    val callState: CallManager.CallState = CallManager.CallState.Idle,
    // callEndReason is latched — once set it is never cleared back to null.
    // This survives the Ended → Idle transition so the UI always has a reason
    // to display even after the state has moved on.
    val callEndReason: String? = null,
    // shouldDismiss is the single flag the Screen observes for dismissal.
    // It is set to true the FIRST TIME a terminal state is seen and never
    // flips back to false, eliminating the Ended/Idle race entirely.
    val shouldDismiss: Boolean = false
)

@HiltViewModel
class IncomingCallViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val callManager: CallManager,
    private val callRepository: CallRepository,
    private val userRepository: UserRepository
) : ViewModel() {

    private val callId: String = savedStateHandle.get<String>("callId") ?: ""

    private val _uiState = MutableStateFlow(
        IncomingCallUiState(
            callId    = callId,
            callState = callManager.callState.value
        )
    )
    val uiState: StateFlow<IncomingCallUiState> = _uiState.asStateFlow()

    init {
        loadCallInfo()
        observeCallState()
    }

    private fun observeCallState() {
        viewModelScope.launch {
            callManager.callState.collect { state ->
                _uiState.update { current ->

                    // Once shouldDismiss is true it stays true — no re-entrancy
                    val isTerminal = state is CallManager.CallState.Ended ||
                            (state is CallManager.CallState.Idle && current.callEndReason != null)

                    // Latch the end reason from Ended; preserve it on Idle and
                    // every other state so it is never wiped out mid-transition.
                    val latched = when (state) {
                        is CallManager.CallState.Ended -> state.reason
                        else -> current.callEndReason   // keep whatever was set
                    }

                    current.copy(
                        callState     = state,
                        callEndReason = latched,
                        shouldDismiss = current.shouldDismiss || isTerminal,
                        isAnswering   = when (state) {
                            is CallManager.CallState.Connected,
                            is CallManager.CallState.Ended,
                            is CallManager.CallState.Idle -> false
                            else -> current.isAnswering
                        }
                    )
                }

                Timber.d("IncomingCallVM state → $state  " +
                        "shouldDismiss=${_uiState.value.shouldDismiss}  " +
                        "reason=${_uiState.value.callEndReason}")
            }
        }
    }

    private fun loadCallInfo() {
        viewModelScope.launch {
            var callLog = callRepository.getCallById(callId)
            var retries = 0
            while (callLog == null && retries < 10) {
                delay(50)
                callLog = callRepository.getCallById(callId)
                retries++
            }

            if (callLog != null) {
                val user = userRepository.getUserByIdOnce(callLog.peerId)
                _uiState.update { state ->
                    state.copy(
                        peerId    = callLog.peerId,
                        peerName  = user?.deviceName ?: callLog.peerName,
                        peerImage = user?.profileImage ?: callLog.peerImage,
                        callType  = callLog.callType
                    )
                }
            } else {
                Timber.w("Call log not found for callId=$callId — falling back to CallManager")
                val currentState = callManager.callState.value
                if (currentState is CallManager.CallState.Incoming) {
                    val user = userRepository.getUserByIdOnce(currentState.peerId)
                    _uiState.update { state ->
                        state.copy(
                            peerId    = currentState.peerId,
                            peerName  = user?.deviceName ?: currentState.peerName,
                            peerImage = user?.profileImage,
                            callType  = currentState.callType
                        )
                    }
                }
            }
        }
    }

    fun acceptCall() {
        if (_uiState.value.isAnswering) return
        _uiState.update { it.copy(isAnswering = true) }
        viewModelScope.launch {
            try {
                callManager.acceptCall()
            } catch (e: Exception) {
                Timber.e(e, "Failed to accept call")
                _uiState.update { it.copy(isAnswering = false) }
            }
        }
    }

    fun rejectCall() {
        if (_uiState.value.isAnswering) return
        _uiState.update { it.copy(isAnswering = true) }
        viewModelScope.launch {
            try {
                callManager.rejectCall()
            } catch (e: Exception) {
                Timber.e(e, "Failed to reject call")
                _uiState.update { it.copy(isAnswering = false) }
            }
        }
    }
}
