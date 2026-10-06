package com.ipphone.data.local.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "file_transfers",
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["peer_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["peer_id"]),
        Index(value = ["status"]),
        Index(value = ["created_at"])
    ]
)
data class FileTransferEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    
    @ColumnInfo(name = "message_id")
    val messageId: String?,
    
    @ColumnInfo(name = "peer_id")
    val peerId: String,
    
    @ColumnInfo(name = "direction")
    val direction: String,
    
    @ColumnInfo(name = "status")
    val status: String,
    
    @ColumnInfo(name = "total_size")
    val totalSize: Long,
    
    @ColumnInfo(name = "transferred")
    val transferred: Long,
    
    @ColumnInfo(name = "file_count")
    val fileCount: Int,
    
    @ColumnInfo(name = "current_file")
    val currentFile: Int,
    
    @ColumnInfo(name = "save_path")
    val savePath: String?,
    
    @ColumnInfo(name = "error_message")
    val errorMessage: String?,
    
    @ColumnInfo(name = "started_at")
    val startedAt: Long?,
    
    @ColumnInfo(name = "completed_at")
    val completedAt: Long?,
    
    @ColumnInfo(name = "created_at")
    val createdAt: Long
)

@Entity(
    tableName = "transfer_files",
    foreignKeys = [
        ForeignKey(
            entity = FileTransferEntity::class,
            parentColumns = ["id"],
            childColumns = ["transfer_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["transfer_id"])
    ]
)
data class TransferFileEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    
    @ColumnInfo(name = "transfer_id")
    val transferId: String,
    
    @ColumnInfo(name = "file_name")
    val fileName: String,
    
    @ColumnInfo(name = "file_path")
    val filePath: String,
    
    @ColumnInfo(name = "relative_path")
    val relativePath: String?,
    
    @ColumnInfo(name = "file_size")
    val fileSize: Long,
    
    @ColumnInfo(name = "mime_type")
    val mimeType: String?,
    
    @ColumnInfo(name = "is_directory")
    val isDirectory: Boolean,
    
    @ColumnInfo(name = "checksum")
    val checksum: String?,
    
    @ColumnInfo(name = "transferred")
    val transferred: Long,
    
    @ColumnInfo(name = "status")
    val status: String,
    
    @ColumnInfo(name = "index_order")
    val indexOrder: Int
)
