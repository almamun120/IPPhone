package com.ipphone.presentation.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ipphone.data.local.datastore.PreferencesManager
import com.ipphone.utils.getDeviceIpAddress
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val deviceId: String = "",
    val deviceName: String = "",
    val ipAddress: String? = null,
    val autoAcceptFiles: Boolean = false,
    val downloadPath: String? = null,
    val notificationSound: Boolean = true,
    val notificationVibrate: Boolean = true
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesManager: PreferencesManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        // Load one-shot values
        viewModelScope.launch {
            val deviceId      = preferencesManager.getDeviceUuid()
            val deviceName    = preferencesManager.getDeviceName()
            val ipAddress     = context.getDeviceIpAddress()
            // FIX: load autoAcceptFiles and downloadPath as one-shot on init
            val autoAccept    = preferencesManager.isAutoAcceptFiles()
            val downloadPath  = preferencesManager.getDownloadPath()

            _uiState.update { state ->
                state.copy(
                    deviceId     = deviceId,
                    deviceName   = deviceName,
                    ipAddress    = ipAddress,
                    autoAcceptFiles = autoAccept,
                    downloadPath = downloadPath
                )
            }
        }

        // Observe reactive flows
        viewModelScope.launch {
            preferencesManager.deviceNameFlow.collect { name ->
                _uiState.update { it.copy(deviceName = name) }
            }
        }

        // FIX: observe downloadPath flow so UI updates when path changes
        viewModelScope.launch {
            preferencesManager.downloadPathFlow.collect { path ->
                _uiState.update { it.copy(downloadPath = path) }
            }
        }

        viewModelScope.launch {
            preferencesManager.notificationSoundFlow.collect { enabled ->
                _uiState.update { it.copy(notificationSound = enabled) }
            }
        }

        viewModelScope.launch {
            preferencesManager.notificationVibrateFlow.collect { enabled ->
                _uiState.update { it.copy(notificationVibrate = enabled) }
            }
        }
    }

    fun setDeviceName(name: String) {
        viewModelScope.launch {
            preferencesManager.setDeviceName(name)
        }
    }

    fun setAutoAcceptFiles(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.setAutoAcceptFiles(enabled)
            _uiState.update { it.copy(autoAcceptFiles = enabled) }
        }
    }

    fun setNotificationSound(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.setNotificationSound(enabled)
        }
    }

    fun setNotificationVibrate(enabled: Boolean) {
        viewModelScope.launch {
            preferencesManager.setNotificationVibrate(enabled)
        }
    }

    fun setDownloadPath(path: String) {
        viewModelScope.launch {
            preferencesManager.setDownloadPath(path)
            // FIX: also update state immediately so UI doesn't wait for flow emission
            _uiState.update { it.copy(downloadPath = path) }
        }
    }
}
