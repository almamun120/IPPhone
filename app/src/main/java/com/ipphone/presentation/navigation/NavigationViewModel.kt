package com.ipphone.presentation.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ipphone.domain.repository.CallRepository
import com.ipphone.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Holds the badge counts (missed calls, unread messages, active transfers) that must be
 * visible from every tab. Scoped to the NavHost so it lives for the whole session.
 */
@HiltViewModel
class NavigationViewModel @Inject constructor(
    private val callRepository: CallRepository,
    private val userRepository: UserRepository,
    private val fileTransferRepository: com.ipphone.domain.repository.FileTransferRepository
) : ViewModel() {

    // ── Badge counts ──────────────────────────────────────────

    private val _missedCallCount = MutableStateFlow(0)
    val missedCallCount: StateFlow<Int> = _missedCallCount.asStateFlow()

    private val _unreadMessageCount = MutableStateFlow(0)
    val unreadMessageCount: StateFlow<Int> = _unreadMessageCount.asStateFlow()

    private val _activeTransfers = MutableStateFlow<List<com.ipphone.domain.model.FileTransfer>>(emptyList())
    val activeTransfers: StateFlow<List<com.ipphone.domain.model.FileTransfer>> = _activeTransfers.asStateFlow()

    init {
        observeMissedCalls()
        observeActiveTransfers()
    }

    private fun observeMissedCalls() {
        viewModelScope.launch {
            callRepository.getMissedCallCount().collect { count ->
                _missedCallCount.value = count
            }
        }
    }

    private fun observeActiveTransfers() {
        viewModelScope.launch {
            fileTransferRepository.getActiveTransfers().collect { transfers ->
                _activeTransfers.value = transfers
            }
        }
    }
}
