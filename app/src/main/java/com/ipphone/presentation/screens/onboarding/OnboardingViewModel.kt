package com.ipphone.presentation.screens.onboarding

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ipphone.data.local.datastore.PreferencesManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val preferencesManager: PreferencesManager
) : ViewModel() {
    
    private val _deviceName = MutableStateFlow(Build.MODEL)
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()
    
    fun setDeviceName(name: String) {
        _deviceName.value = name
    }
    
    fun completeOnboarding() {
        viewModelScope.launch {
            preferencesManager.setDeviceName(_deviceName.value)
            preferencesManager.setOnboardingCompleted(true)
        }
    }
}
