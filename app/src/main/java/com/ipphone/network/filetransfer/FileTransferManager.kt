package com.ipphone.network.filetransfer

import com.ipphone.domain.model.FileTransfer
import com.ipphone.domain.repository.FileTransferRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileTransferManager @Inject constructor(
    private val chatTransferManager: ChatTransferManager,
    private val liveTransferManager: LiveTransferManager,
    private val fileTransferRepository: FileTransferRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _transferEvents = MutableSharedFlow<TransferEvent>(extraBufferCapacity = 128)
    val transferEvents: SharedFlow<TransferEvent> = _transferEvents.asSharedFlow()

    sealed class TransferEvent {
        data class Offer(
            val transferId: String,
            val senderId: String,
            val files: List<com.ipphone.network.protocol.FileMetadata>,
            val totalSize: Long,
            val messageId: String? = null
        ) : TransferEvent()
        data class Progress(val transferId: String, val transferred: Long, val total: Long) : TransferEvent()
        data class Completed(val transferId: String) : TransferEvent()
        data class Failed(val transferId: String, val error: String) : TransferEvent()
        data class Cancelled(val transferId: String) : TransferEvent()
    }

    init {
        forwardEvents()
    }

    private fun forwardEvents() {
        // Forward from LiveTransferManager
        scope.launch {
            liveTransferManager.transferEvents.collect { event ->
                when (event) {
                    is LiveTransferManager.TransferEvent.OfferReceived -> {
                        _transferEvents.emit(TransferEvent.Offer(event.transferId, event.senderId, event.files, event.totalSize, null))
                    }
                    is LiveTransferManager.TransferEvent.Progress -> {
                        _transferEvents.emit(TransferEvent.Progress(event.transferId, event.totalBytesSent, event.totalSize))
                    }
                    is LiveTransferManager.TransferEvent.Completed -> {
                        _transferEvents.emit(TransferEvent.Completed(event.transferId))
                        scope.launch {
                            val transfer = fileTransferRepository.getTransferByIdOnce(event.transferId)
                            if (transfer != null && transfer.direction == com.ipphone.domain.model.TransferDirection.INCOMING) {
                                saveTransferToDownloads(event.transferId)
                            }
                        }
                    }
                    is LiveTransferManager.TransferEvent.Failed -> {
                        _transferEvents.emit(TransferEvent.Failed(event.transferId, event.error))
                    }
                }
            }
        }

        // Note: ChatTransferManager events are usually handled via DB/SyncManager,
        // but we can forward them if UI needs real-time progress for chat too.
    }

    fun startTransfer(transfer: FileTransfer) {
        if (transfer.messageId != null) {
            chatTransferManager.enqueueTransfer(transfer)
        } else {
            liveTransferManager.startTransfer(transfer)
        }
    }

    suspend fun acceptTransfer(transferId: String) {
        val transfer = fileTransferRepository.getTransferByIdOnce(transferId) ?: return
        if (transfer.messageId != null) {
            chatTransferManager.acceptTransfer(transferId)
        } else {
            liveTransferManager.acceptTransfer(transferId)
        }
    }

    suspend fun rejectTransfer(transferId: String, reason: String? = null) {
        val transfer = fileTransferRepository.getTransferByIdOnce(transferId) ?: return
        if (transfer.messageId != null) {
            chatTransferManager.rejectTransfer(transferId, reason)
        } else {
            liveTransferManager.rejectTransfer(transferId, reason)
        }
    }

    suspend fun saveTransferToDownloads(transferId: String) {
        val context = chatTransferManager.getContext()
        val transfer = fileTransferRepository.getTransferByIdOnce(transferId) ?: return

        transfer.files.forEach { file ->
            val sourcePath = file.filePath
            if (sourcePath.isBlank()) return@forEach

            val sourceFile = File(sourcePath)
            if (!sourceFile.exists()) {
                Timber.w("saveTransferToDownloads: source missing — $sourcePath")
                return@forEach
            }

            try {
                val mimeType = file.mimeType ?: "application/octet-stream"
                val contentValues = android.content.ContentValues().apply {
                    put(
                        android.provider.MediaStore.MediaColumns.DISPLAY_NAME,
                        com.ipphone.utils.FileUtils.safeReceivedName(file.fileName, file.mimeType, file.id)
                    )
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        put(
                            android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                            android.os.Environment.DIRECTORY_DOWNLOADS + "/WifiCalling"
                        )
                        put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                }

                val collection =
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q)
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
                    else
                        android.provider.MediaStore.Files.getContentUri("external")

                val destUri = context.contentResolver.insert(collection, contentValues)
                    ?: return@forEach

                context.contentResolver.openOutputStream(destUri)?.use { out ->
                    sourceFile.inputStream().use { it.copyTo(out) }
                }

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    val update = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    context.contentResolver.update(destUri, update, null, null)
                }

                Timber.d("Saved ${file.fileName} to Downloads/WifiCalling")
            } catch (e: Exception) {
                Timber.e(e, "Failed to save ${file.fileName} to Downloads")
            }
        }
    }

    fun pauseTransfer(transferId: String) {
        scope.launch {
            val transfer = fileTransferRepository.getTransferByIdOnce(transferId) ?: return@launch
            if (transfer.messageId == null) {
                liveTransferManager.pauseTransfer(transferId)
            }
        }
    }

    fun resumeTransfer(transferId: String) {
        scope.launch {
            val transfer = fileTransferRepository.getTransferByIdOnce(transferId) ?: return@launch
            if (transfer.messageId == null) {
                liveTransferManager.resumeTransfer(transferId)
            }
        }
    }

    fun cancelTransfer(transferId: String) {
        scope.launch {
            val transfer = fileTransferRepository.getTransferByIdOnce(transferId) ?: return@launch
            if (transfer.messageId == null) {
                liveTransferManager.cancelTransfer(transferId)
            }
        }
    }

    fun retryTransfer(transferId: String) {
        scope.launch {
            val transfer = fileTransferRepository.getTransferByIdOnce(transferId) ?: return@launch
            startTransfer(transfer)
        }
    }
}

