package com.ipphone.presentation.screens.calllogs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ipphone.domain.model.CallLog
import com.ipphone.domain.repository.CallRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CallLogsUiState(
    val isLoading: Boolean = true,
    val callLogs: List<CallLog> = emptyList()
)

@HiltViewModel
class CallLogsViewModel @Inject constructor(
    private val callRepository: CallRepository
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(CallLogsUiState())
    val uiState: StateFlow<CallLogsUiState> = _uiState.asStateFlow()
    
    init {
        loadCallLogs()
    }
    
    private fun loadCallLogs() {
        viewModelScope.launch {
            callRepository.getAllCallLogs()
                .collect { logs ->
                    _uiState.update { state ->
                        state.copy(
                            isLoading = false,
                            callLogs = logs
                        )
                    }
                }
        }
    }
    
    fun deleteCallLog(callId: String) {
        viewModelScope.launch {
            callRepository.deleteCallLog(callId)
        }
    }
    
    fun clearAllCallLogs() {
        viewModelScope.launch {
            _uiState.value.callLogs.forEach { log ->
                callRepository.deleteCallLog(log.id)
            }
        }
    }
}
