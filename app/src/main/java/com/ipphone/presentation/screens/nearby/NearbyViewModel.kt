package com.ipphone.presentation.screens.nearby

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ipphone.data.local.datastore.PreferencesManager
import com.ipphone.domain.repository.UserRepository
import com.ipphone.network.connection.ConnectionManager
import com.ipphone.network.discovery.DiscoveryManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class NearbyUiState(
    val isScanning: Boolean = false,
    val scanProgress: Float = 0f,
    val nearbyDevices: List<NearbyDevice> = emptyList(),
    val error: String? = null
)

@HiltViewModel
class NearbyViewModel @Inject constructor(
    private val discoveryManager: DiscoveryManager,
    private val connectionManager: ConnectionManager,
    private val userRepository: UserRepository,
    private val preferencesManager: PreferencesManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(NearbyUiState())
    val uiState: StateFlow<NearbyUiState> = _uiState.asStateFlow()

    init {
        observeDiscoveredDevices()
    }

    private fun observeDiscoveredDevices() {
        viewModelScope.launch {
            combine(
                discoveryManager.discoveredDevices,
                discoveryManager.isDiscovering,
                discoveryManager.discoveryProgress,
                userRepository.getBlockedUsers()
            ) { devices, isDiscovering, progress, blockedUsers ->
                val blockedIds = blockedUsers.map { it.uuid }.toSet()

                val nearbyDevices = devices.values
                    .filter { it.deviceId != null }
                    .mapNotNull { device ->
                        val deviceId = device.deviceId ?: return@mapNotNull null
                        if (blockedIds.contains(deviceId)) {
                            return@mapNotNull null
                        }
                        NearbyDevice(
                            deviceId   = deviceId,
                            deviceName = device.deviceName ?: "Unknown Device",
                            profileImage = device.profileImage,
                            ipAddress  = device.host?.hostAddress ?: "",
                            port       = device.port
                        )
                    }
                    .distinctBy { it.deviceId }

                NearbyUiState(
                    isScanning = isDiscovering,
                    scanProgress = progress,
                    nearbyDevices = nearbyDevices
                )
            }.collect { newState ->
                _uiState.value = newState
            }
        }
    }

    fun onDeviceClicked(device: NearbyDevice) {
        viewModelScope.launch {
            val existing = userRepository.getUserByIdOnce(device.deviceId)
            if (existing == null) {
                userRepository.saveUser(
                    com.ipphone.domain.model.User(
                        uuid = device.deviceId,
                        deviceName = device.deviceName,
                        profileImage = device.profileImage,
                        ipAddress = device.ipAddress,
                        tcpPort = device.port,
                        createdAt = System.currentTimeMillis()
                    )
                )
            } else {
                // Update IP/Port if they changed
                userRepository.updatePresence(device.deviceId, device.ipAddress, device.port)
            }
        }
    }

    fun startScan() {
        discoveryManager.clearDiscoveredDevices()
        discoveryManager.startDiscovery()
    }

    fun stopScan() {
        discoveryManager.stopDiscovery()
    }

    override fun onCleared() {
        super.onCleared()
        discoveryManager.stopDiscovery()
    }
}
