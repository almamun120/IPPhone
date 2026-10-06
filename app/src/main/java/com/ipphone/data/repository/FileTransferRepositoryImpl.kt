package com.ipphone.data.repository

import com.ipphone.data.local.database.dao.FileTransferDao
import com.ipphone.data.local.database.entity.TransferFileEntity
import com.ipphone.data.mapper.toDomain
import com.ipphone.data.mapper.toEntity
import com.ipphone.domain.model.FileTransfer
import com.ipphone.domain.model.TransferStatus
import com.ipphone.domain.repository.FileTransferRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileTransferRepositoryImpl @Inject constructor(
    private val fileTransferDao: FileTransferDao
) : FileTransferRepository {
    
    override fun getTransferById(transferId: String): Flow<FileTransfer?> {
        return combine(
            fileTransferDao.getTransferByIdFlow(transferId),
            fileTransferDao.getFilesForTransferFlow(transferId)
        ) { transfer, files ->
            transfer?.toDomain(files.map { it.toDomain() })
        }
    }
    
    override suspend fun getTransferByIdOnce(transferId: String): FileTransfer? {
        val transfer = fileTransferDao.getTransferById(transferId) ?: return null
        val files = fileTransferDao.getFilesForTransfer(transferId)
        return transfer.toDomain(files.map { it.toDomain() })
    }
    
    override fun getActiveTransfers(): Flow<List<FileTransfer>> {
        return fileTransferDao.getActiveTransfers().map { transfers ->
            transfers.map { transfer ->
                val files = fileTransferDao.getFilesForTransfer(transfer.id)
                transfer.toDomain(files.map { it.toDomain() })
            }
        }
    }
    
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun getTransfersForPeer(peerId: String): Flow<List<FileTransfer>> {
        return fileTransferDao.getTransfersForPeer(peerId).flatMapLatest { entities ->
            if (entities.isEmpty()) return@flatMapLatest flowOf(emptyList<FileTransfer>())
            
            val transferFlows = entities.map { entity ->
                fileTransferDao.getFilesForTransferFlow(entity.id).map { files ->
                    entity.toDomain(files.map { it.toDomain() })
                }
            }
            combine(transferFlows) { it.toList() }
        }
    }
    
    override suspend fun saveTransfer(transfer: FileTransfer) {
        fileTransferDao.insertTransferWithFiles(
            transfer.toEntity(),
            transfer.files.map { it.toEntity() }
        )
    }
    
    override suspend fun updateStatus(transferId: String, status: TransferStatus) {
        fileTransferDao.updateTransferStatus(transferId, status.name)
    }

    override suspend fun markAsStarted(transferId: String, status: TransferStatus) {
        fileTransferDao.markAsStarted(transferId, status.name)
    }

    override suspend fun updateTransferProgress(transferId: String, transferred: Long, currentFile: Int) {
        fileTransferDao.updateTransferProgress(transferId, transferred, currentFile)
    }
    
    override suspend fun updateFileProgress(fileId: String, transferred: Long, status: String) {
        fileTransferDao.updateFileProgress(fileId, transferred, status)
    }
    
    override suspend fun updateFilePath(fileId: String, path: String) {
        val file = fileTransferDao.getTransferFileById(fileId) ?: return
        fileTransferDao.updateTransferFile(file.copy(filePath = path))
    }
    
    override suspend fun markAsCompleted(transferId: String) {
        fileTransferDao.markAsCompleted(transferId)
    }
    
    override suspend fun markAsFailed(transferId: String, error: String) {
        fileTransferDao.markAsFailed(transferId, error)
    }
    
    override suspend fun deleteTransfer(transferId: String) {
        fileTransferDao.deleteTransfer(transferId)
    }
}
