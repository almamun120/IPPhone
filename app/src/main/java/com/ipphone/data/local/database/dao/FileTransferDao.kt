package com.ipphone.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.ipphone.data.local.database.entity.FileTransferEntity
import com.ipphone.data.local.database.entity.TransferFileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FileTransferDao {
    
    @Query("SELECT * FROM file_transfers WHERE peer_id = :peerId ORDER BY created_at DESC")
    fun getTransfersForPeer(peerId: String): Flow<List<FileTransferEntity>>
    
    @Query("SELECT * FROM file_transfers WHERE status IN ('PENDING', 'ACTIVE', 'PAUSED', 'WAITING_ACCEPTANCE') ORDER BY created_at ASC")
    fun getActiveTransfers(): Flow<List<FileTransferEntity>>
    
    @Query("SELECT * FROM file_transfers WHERE id = :transferId")
    suspend fun getTransferById(transferId: String): FileTransferEntity?
    
    @Query("SELECT * FROM file_transfers WHERE id = :transferId")
    fun getTransferByIdFlow(transferId: String): Flow<FileTransferEntity?>
    
    @Query("SELECT * FROM transfer_files WHERE transfer_id = :transferId ORDER BY index_order ASC")
    suspend fun getFilesForTransfer(transferId: String): List<TransferFileEntity>
    
    @Query("SELECT * FROM transfer_files WHERE transfer_id = :transferId ORDER BY index_order ASC")
    fun getFilesForTransferFlow(transferId: String): Flow<List<TransferFileEntity>>
    
    @Query("SELECT * FROM transfer_files WHERE id = :fileId")
    suspend fun getTransferFileById(fileId: String): TransferFileEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransfer(transfer: FileTransferEntity)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransferFiles(files: List<TransferFileEntity>)
    
    @Transaction
    suspend fun insertTransferWithFiles(transfer: FileTransferEntity, files: List<TransferFileEntity>) {
        insertTransfer(transfer)
        insertTransferFiles(files)
    }
    
    @Update
    suspend fun updateTransfer(transfer: FileTransferEntity)
    
    @Update
    suspend fun updateTransferFile(file: TransferFileEntity)
    
    @Query("UPDATE file_transfers SET status = :status WHERE id = :transferId")
    suspend fun updateTransferStatus(transferId: String, status: String)

    @Query("UPDATE file_transfers SET status = :status, started_at = :startedAt WHERE id = :transferId")
    suspend fun markAsStarted(transferId: String, status: String = "ACTIVE", startedAt: Long = System.currentTimeMillis())

    @Query("UPDATE file_transfers SET transferred = :transferred, current_file = :currentFile WHERE id = :transferId")
    suspend fun updateTransferProgress(transferId: String, transferred: Long, currentFile: Int)
    
    @Query("UPDATE file_transfers SET status = 'COMPLETED', completed_at = :completedAt WHERE id = :transferId")
    suspend fun markAsCompleted(transferId: String, completedAt: Long = System.currentTimeMillis())
    
    @Query("UPDATE file_transfers SET status = 'FAILED', error_message = :errorMessage WHERE id = :transferId")
    suspend fun markAsFailed(transferId: String, errorMessage: String)
    
    @Query("UPDATE transfer_files SET transferred = :transferred, status = :status WHERE id = :fileId")
    suspend fun updateFileProgress(fileId: String, transferred: Long, status: String)
    
    @Query("DELETE FROM file_transfers WHERE id = :transferId")
    suspend fun deleteTransfer(transferId: String)
    
    @Query("DELETE FROM file_transfers WHERE peer_id = :peerId")
    suspend fun deleteAllForPeer(peerId: String)
}
