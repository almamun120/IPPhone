package com.ipphone.presentation.screens.transfer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ipphone.domain.model.FileTransfer
import com.ipphone.domain.repository.FileTransferRepository
import com.ipphone.network.filetransfer.FileTransferManager
import com.ipphone.network.filetransfer.TransferStats
import com.ipphone.network.filetransfer.TransferStatsTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TransferUiState(
    val transfer: FileTransfer? = null,
    val isLoading: Boolean      = true,
    // FIX Issue 10: flag set after files are saved to public Downloads
    val savedToDownloads: Boolean = false,
    // Live speed / ETA numbers (null until the first progress sample arrives)
    val stats: TransferStats? = null
)

@HiltViewModel
class TransferViewModel @Inject constructor(
    private val fileTransferRepository: FileTransferRepository,
    private val fileTransferManager: FileTransferManager,
    private val statsTracker: TransferStatsTracker
) : ViewModel() {

    private val _uiState = MutableStateFlow(TransferUiState())
    val uiState: StateFlow<TransferUiState> = _uiState.asStateFlow()

    private var currentTransferId: String? = null

    fun loadTransfer(transferId: String) {
        currentTransferId = transferId

        viewModelScope.launch {
            statsTracker.stats.collect { all ->
                val mine = all[transferId]
                _uiState.update { state -> if (state.stats == mine) state else state.copy(stats = mine) }
            }
        }

        viewModelScope.launch {
            fileTransferRepository.getTransferById(transferId)
                .collect { transfer ->
                    _uiState.update { state ->
                        state.copy(transfer = transfer, isLoading = false)
                    }
                }
        }
    }

    fun pauseTransfer() {
        currentTransferId?.let { id ->
            fileTransferManager.pauseTransfer(id)
        }
    }

    fun resumeTransfer() {
        currentTransferId?.let { id ->
            viewModelScope.launch { fileTransferManager.resumeTransfer(id) }
        }
    }

    fun cancelTransfer() {
        currentTransferId?.let { id ->
            fileTransferManager.cancelTransfer(id)
        }
    }

    fun retryTransfer() {
        currentTransferId?.let { id ->
            viewModelScope.launch { fileTransferManager.retryTransfer(id) }
        }
    }
}
