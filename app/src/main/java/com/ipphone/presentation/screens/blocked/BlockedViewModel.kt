package com.ipphone.presentation.screens.blocked

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ipphone.domain.model.User
import com.ipphone.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BlockedUiState(
    val isLoading: Boolean = true,
    val blockedUsers: List<User> = emptyList()
)

@HiltViewModel
class BlockedViewModel @Inject constructor(
    private val userRepository: UserRepository
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(BlockedUiState())
    val uiState: StateFlow<BlockedUiState> = _uiState.asStateFlow()
    
    init {
        loadBlockedUsers()
    }
    
    private fun loadBlockedUsers() {
        viewModelScope.launch {
            userRepository.getBlockedUsers()
                .collect { users ->
                    _uiState.update { state ->
                        state.copy(
                            isLoading = false,
                            blockedUsers = users
                        )
                    }
                }
        }
    }
    
    fun unblockUser(userId: String) {
        viewModelScope.launch {
            userRepository.unblockUser(userId)
        }
    }
}
