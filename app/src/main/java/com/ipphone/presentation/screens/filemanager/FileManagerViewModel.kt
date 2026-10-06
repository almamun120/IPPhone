package com.ipphone.presentation.screens.filemanager

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ipphone.data.local.datastore.PreferencesManager
import com.ipphone.domain.model.FileTransfer
import com.ipphone.domain.model.TransferDirection
import com.ipphone.domain.model.TransferFile
import com.ipphone.domain.model.TransferStatus
import com.ipphone.domain.repository.FileTransferRepository
import com.ipphone.network.filetransfer.FileTransferManager
import com.ipphone.utils.FileUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

data class FileManagerUiState(
    val targetUserId: String = "",
    val selectedFiles: List<SelectedFile> = emptyList(),
    val totalSelectedSize: Long = 0,
    val isLoading: Boolean = false
)

@HiltViewModel
class FileManagerViewModel @Inject constructor(
    private val fileTransferManager: FileTransferManager,
    private val fileTransferRepository: FileTransferRepository,
    private val preferencesManager: PreferencesManager
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(FileManagerUiState())
    val uiState: StateFlow<FileManagerUiState> = _uiState.asStateFlow()
    
    fun setTargetUser(userId: String) {
        _uiState.update { it.copy(targetUserId = userId) }
    }
    
    fun addFilesToSelection(context: Context, uris: List<Uri>) {
        viewModelScope.launch {
            val newFiles = uris.mapNotNull { uri ->
                val name = FileUtils.getFileName(context, uri) ?: return@mapNotNull null
                val size = FileUtils.getFileSize(context, uri)
                val mimeType = FileUtils.getMimeType(context, uri)
                
                SelectedFile(
                    uri = uri.toString(),
                    name = name,
                    size = size,
                    mimeType = mimeType
                )
            }
            
            val currentFiles = _uiState.value.selectedFiles
            val allFiles = (currentFiles + newFiles).distinctBy { it.uri }
            val totalSize = allFiles.sumOf { it.size }
            
            _uiState.update { state ->
                state.copy(
                    selectedFiles = allFiles,
                    totalSelectedSize = totalSize
                )
            }
        }
    }
    
    fun removeFromSelection(file: SelectedFile) {
        val updatedFiles = _uiState.value.selectedFiles.filter { it.uri != file.uri }
        val totalSize = updatedFiles.sumOf { it.size }
        
        _uiState.update { state ->
            state.copy(
                selectedFiles = updatedFiles,
                totalSelectedSize = totalSize
            )
        }
    }
    
    fun clearSelection() {
        _uiState.update { state ->
            state.copy(
                selectedFiles = emptyList(),
                totalSelectedSize = 0
            )
        }
    }
    
    fun startTransfer(): String {
        val state = _uiState.value
        if (state.isLoading || state.selectedFiles.isEmpty()) return ""
        
        _uiState.update { it.copy(isLoading = true) }
        val transferId = UUID.randomUUID().toString()
        
        viewModelScope.launch {
            try {
                val transferFiles = state.selectedFiles.mapIndexed { index, file ->
                    TransferFile(
                        id = UUID.randomUUID().toString(),
                        transferId = transferId,
                        fileName = file.name,
                        filePath = file.uri,
                        fileSize = file.size,
                        mimeType = file.mimeType,
                        indexOrder = index
                    )
                }
                
                val transfer = FileTransfer(
                    id = transferId,
                    peerId = state.targetUserId,
                    direction = TransferDirection.OUTGOING,
                    status = TransferStatus.PENDING,
                    totalSize = state.totalSelectedSize,
                    fileCount = state.selectedFiles.size,
                    files = transferFiles
                )
                
                fileTransferRepository.saveTransfer(transfer)
                fileTransferManager.startTransfer(transfer)
                
                // Clear selection after successful initiation
                _uiState.update { it.copy(selectedFiles = emptyList(), totalSelectedSize = 0) }
            } catch (e: Exception) {
                Timber.e(e, "Failed to start transfer")
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
        
        return transferId
    }
}
