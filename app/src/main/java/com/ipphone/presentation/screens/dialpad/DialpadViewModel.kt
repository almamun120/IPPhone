package com.ipphone.presentation.screens.dialpad

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ipphone.data.local.database.dao.UserDao
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
    val resolvedId: String = ""
)

@HiltViewModel
class DialpadViewModel @Inject constructor(
    private val userDao: UserDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(DialpadUiState())
    val uiState: StateFlow<DialpadUiState> = _uiState.asStateFlow()

    fun onDigitClick(digit: String) {
        if (digit == "#") {
            // Resolve the typed IP/digits to a UUID before showing options
            // Replace '*' with '.' internally for IP resolution and navigation
            val internalDigits = _uiState.value.digits.replace('*', '.')
            if (internalDigits.isNotBlank()) {
                viewModelScope.launch {
                    val resolved = resolveToUuid(internalDigits)
                    _uiState.value = _uiState.value.copy(
                        isOptionsVisible = true,
                        resolvedId = resolved
                    )
                }
            }
            return
        }

        // Keep digits as they are (showing '*' in the display)
        _uiState.value = _uiState.value.copy(
            digits = _uiState.value.digits + digit
        )
    }

    /**
     * FIX Issue 2 & 3: If the typed string looks like an IP address, look it
     * up in the DB and return the UUID of the matching device.  This ensures
     * that calls / chats started from the Dialpad target the same entry that
     * was created when the device appeared in the Nearby tab.
     *
     * Falls back to the raw typed string when the IP is not yet known.
     */
    private suspend fun resolveToUuid(input: String): String {
        if (!input.matches(Regex("""^(\d{1,3}\.){3}\d{1,3}$"""))) {
            return input // Not an IP — already a UUID or hostname
        }
        return try {
            userDao.getUserByIp(input)?.uuid ?: input
        } catch (e: Exception) {
            input
        }
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