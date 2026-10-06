package com.ipphone.domain.repository

import com.ipphone.domain.model.FileTransfer
import com.ipphone.domain.model.TransferStatus
import kotlinx.coroutines.flow.Flow

interface FileTransferRepository {
    fun getTransferById(transferId: String): Flow<FileTransfer?>
    suspend fun getTransferByIdOnce(transferId: String): FileTransfer?
    fun getActiveTransfers(): Flow<List<FileTransfer>>
    fun getTransfersForPeer(peerId: String): Flow<List<FileTransfer>>
    suspend fun saveTransfer(transfer: FileTransfer)
    suspend fun updateStatus(transferId: String, status: TransferStatus)
    suspend fun markAsStarted(transferId: String, status: TransferStatus = TransferStatus.ACTIVE)
    suspend fun updateTransferProgress(transferId: String, transferred: Long, currentFile: Int)
    suspend fun updateFileProgress(fileId: String, transferred: Long, status: String)
    suspend fun updateFilePath(fileId: String, path: String)
    suspend fun markAsCompleted(transferId: String)
    suspend fun markAsFailed(transferId: String, error: String)
    suspend fun deleteTransfer(transferId: String)
}
