package com.ipphone.domain.model

import java.util.UUID

data class FileTransfer(
    val id: String = UUID.randomUUID().toString(),
    val messageId: String? = null, // Optional link to ChatMessage
    val peerId: String,
    val direction: TransferDirection,
    val status: TransferStatus = TransferStatus.PENDING,
    val totalSize: Long,
    val transferred: Long = 0,
    val fileCount: Int,
    val currentFile: Int = 0,
    val savePath: String? = null,
    val errorMessage: String? = null,
    val files: List<TransferFile> = emptyList(),
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    val progress: Float
        get() = if (totalSize > 0) transferred.toFloat() / totalSize else 0f

    val speed: Long
        get() {
            val elapsed = startedAt?.let { System.currentTimeMillis() - it } ?: 0
            return if (elapsed > 0) (transferred * 1000) / elapsed else 0
        }

    val remainingTime: Long
        get() {
            val remaining = totalSize - transferred
            return if (speed > 0) (remaining * 1000) / speed else 0
        }
}

data class TransferFile(
    val id: String = UUID.randomUUID().toString(),
    val transferId: String,
    val fileName: String,
    val filePath: String,
    val relativePath: String? = null,
    val fileSize: Long,
    val mimeType: String? = null,
    val isDirectory: Boolean = false,
    val checksum: String? = null,
    val transferred: Long = 0,
    val status: TransferFileStatus = TransferFileStatus.PENDING,
    val indexOrder: Int
) {
    val progress: Float
        get() = if (fileSize > 0) transferred.toFloat() / fileSize else 0f
}

enum class TransferDirection {
    INCOMING,
    OUTGOING
}

enum class TransferStatus {
    PENDING,
    WAITING_ACCEPTANCE,
    ACTIVE,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED,
    REJECTED
}

enum class TransferFileStatus {
    PENDING,
    ACTIVE,
    COMPLETED,
    FAILED,
    SKIPPED
}
