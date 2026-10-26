package com.ipphone.presentation.screens.dialpad

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ipphone.network.presence.PeerAvailability
import com.ipphone.network.presence.PeerCheck
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DialpadUiState(
    val digits: String = "",
    val isOptionsVisible: Boolean = false,
    // FIX Issue 3: resolved UUID to use for navigation.
    // When the user dials an IP and presses #, we look up the UUID
    // for that IP so navigation targets the correct entry.
    val resolvedId: String = "",
    /** False when the device is offline but we have history with it (chat only). */
    val peerOnline: Boolean = true,
    val isChecking: Boolean = false,
    /** One-shot message for the snackbar ("Invalid IP address" / "Not available"). */
    val message: String? = null
)

@HiltViewModel
class DialpadViewModel @Inject constructor(
    private val peerAvailability: PeerAvailability
) : ViewModel() {

    private val _uiState = MutableStateFlow(DialpadUiState())
    val uiState: StateFlow<DialpadUiState> = _uiState.asStateFlow()

    fun onDigitClick(digit: String) {
        if (digit == "#") {
            // Resolve the typed IP/digits to a UUID before showing options
            // Replace '*' with '.' internally for IP resolution and navigation
            val internalDigits = _uiState.value.digits.replace('*', '.')
            if (internalDigits.isBlank() || _uiState.value.isChecking) return

            _uiState.value = _uiState.value.copy(isChecking = true, message = null)
            viewModelScope.launch {
                // Nothing (audio, video, chat, file share) is opened until the peer is confirmed.
                when (val check = peerAvailability.checkDialed(internalDigits)) {
                    is PeerCheck.Available -> _uiState.value = _uiState.value.copy(
                        isChecking = false,
                        isOptionsVisible = true,
                        resolvedId = check.peerId,
                        peerOnline = check.online
                    )
                    PeerCheck.Invalid -> _uiState.value = _uiState.value.copy(
                        isChecking = false,
                        message = "Invalid IP address"
                    )
                    PeerCheck.NotAvailable -> _uiState.value = _uiState.value.copy(
                        isChecking = false,
                        message = "Not available"
                    )
                }
            }
            return
        }

        // Keep digits as they are (showing '*' in the display)
        _uiState.value = _uiState.value.copy(
            digits = _uiState.value.digits + digit,
            message = null
        )
    }

    fun messageShown() {
        _uiState.value = _uiState.value.copy(message = null)
    }

    fun onBackspace() {
        if (_uiState.value.digits.isNotEmpty()) {
            _uiState.value = _uiState.value.copy(
                digits = _uiState.value.digits.dropLast(1)
            )
        }
    }

    fun clearDigits() {
        _uiState.value = _uiState.value.copy(digits = "")
    }

    fun dismissOptions() {
        _uiState.value = _uiState.value.copy(isOptionsVisible = false)
    }
}