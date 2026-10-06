package com.ipphone.presentation.screens.profile

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ipphone.data.local.datastore.PreferencesManager
import com.ipphone.utils.ThumbnailUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfileUiState(
    val deviceId: String = "",
    val deviceName: String = "",
    val profileImage: String? = null
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferencesManager: PreferencesManager,
    private val userRepository: com.ipphone.domain.repository.UserRepository
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()
    
    fun loadProfile(userId: String? = null) {
        if (userId == null) {
            loadMyProfile()
        } else {
            loadOtherProfile(userId)
        }
    }
    
    private fun loadMyProfile() {
        viewModelScope.launch {
            val deviceId = preferencesManager.getDeviceUuid()
            val deviceName = preferencesManager.getDeviceName()
            val profileImage = preferencesManager.getProfileImage()
            
            _uiState.update { state ->
                state.copy(
                    deviceId = deviceId,
                    deviceName = deviceName,
                    profileImage = profileImage
                )
            }
        }
        
        viewModelScope.launch {
            preferencesManager.deviceNameFlow.collect { name ->
                _uiState.update { it.copy(deviceName = name) }
            }
        }
        
        viewModelScope.launch {
            preferencesManager.profileImageFlow.collect { image ->
                _uiState.update { it.copy(profileImage = image) }
            }
        }
    }

    private fun loadOtherProfile(userId: String) {
        viewModelScope.launch {
            userRepository.getUserById(userId).collect { user ->
                user?.let { u ->
                    _uiState.update { it.copy(
                        deviceId = u.uuid,
                        deviceName = u.deviceName,
                        profileImage = u.profileImage
                    )}
                }
            }
        }
    }
    
    fun setDeviceName(name: String) {
        viewModelScope.launch {
            preferencesManager.setDeviceName(name)
        }
    }
    
    fun setProfileImage(uriString: String) {
        viewModelScope.launch {
            val uri = Uri.parse(uriString)
            val base64Image = ThumbnailUtils.generateThumbnail(context, uri, "image/jpeg")
            if (base64Image != null) {
                preferencesManager.setProfileImage(base64Image)
            }
        }
    }
}
