package com.ipphone.network.filetransfer

import android.content.Context
import com.ipphone.data.local.datastore.PreferencesManager
import com.ipphone.domain.model.*
import com.ipphone.domain.repository.FileTransferRepository
import com.ipphone.network.connection.ConnectionManager
import com.ipphone.network.filetransfer.engine.FileTransferEngine
import com.ipphone.network.protocol.*
import com.ipphone.utils.Constants
import com.ipphone.utils.FileUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LiveTransferManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: FileTransferEngine,
    private val connectionManager: ConnectionManager,
    private val fileTransferRepository: FileTransferRepository,
    private val preferencesManager: PreferencesManager,
    private val userDao: com.ipphone.data.local.database.dao.UserDao,
    private val statsTracker: TransferStatsTracker
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _transferEvents = MutableSharedFlow<TransferEvent>(extraBufferCapacity = 64)
    val transferEvents = _transferEvents.asSharedFlow()

    // Track bytes received per file in-memory for real-time progress
    private val inMemoryBytesReceived = ConcurrentHashMap<String, AtomicLong>()
    // Map fileId -> (transferId, fileSize) for progress calculation
    private val fileMetaMap = ConcurrentHashMap<String, Pair<String, Long>>()
    // Map fileId -> finalized save path
    private val fileSavePaths = ConcurrentHashMap<String, String>()
    // Track which transferIds are active for chunk routing (avoids DB lookup race)
    private val activeTransferIds = ConcurrentHashMap<String, Boolean>()

    sealed class TransferEvent {
        data class OfferReceived(
            val transferId: String,
            val senderId: String,
            val files: List<FileMetadata>,
            val totalSize: Long
        ) : TransferEvent()
        data class Progress(
            val transferId: String,
            val totalBytesSent: Long,
            val totalSize: Long
        ) : TransferEvent()
        data class Completed(val transferId: String) : TransferEvent()
        data class Failed(val transferId: String, val error: String) : TransferEvent()
    }

    init {
        observeEngineEvents()
        observeIncomingMessages()
    }

    private fun observeEngineEvents() {
        scope.launch {
            engine.engineEvents.collect { event ->
                when (event) {
                    is FileTransferEngine.EngineEvent.Progress ->
                        updateProgress(event.transferId, event.fileId, event.bytesSent)
                    is FileTransferEngine.EngineEvent.FileCompleted ->
                        finalizeFile(event.transferId, event.fileId, event.path)
                    is FileTransferEngine.EngineEvent.Error ->
                        handleError(event.transferId, event.fileId, event.message)
                    else -> {}
                }
            }
        }
    }

    private fun observeIncomingMessages() {
        scope.launch {
            connectionManager.incomingMessages.collect { (senderId, message) ->
                when {
                    message is FileOfferMessage && message.messageId == null ->
                        handleLiveFileOffer(senderId, message)

                    message is FileResponseMessage -> handleLiveFileResponse(senderId, message)

                    message is TransferControlMessage -> handleTransferControl(senderId, message)
                }
            }
        }
    }

    /**
     * Decides where an incoming file is written. Called by the engine when the sender's
     * data stream arrives. File names coming from the peer are always sanitised.
     */
    private suspend fun resolveSavePath(transferId: String, fileId: String): String? {
        fileSavePaths[fileId]?.let { return it }

        val transfer = fileTransferRepository.getTransferByIdOnce(transferId) ?: return null
        val file = transfer.files.firstOrNull { it.id == fileId } ?: return null

        val preferredDir = preferencesManager.getDownloadPath()?.let { File(it) }
        val saveDir = if (preferredDir != null && preferredDir.exists()) {
            preferredDir
        } else {
            FileUtils.getDownloadsDirectory(context)
        }
        val name = FileUtils.safeReceivedName(file.fileName, file.mimeType, file.id)
        val path = FileUtils.createUniqueFile(saveDir, name).absolutePath
        fileSavePaths[fileId] = path
        return path
    }

    private suspend fun handleTransferControl(senderId: String, message: TransferControlMessage) {
        when (message.action) {
            TransferAction.PAUSE -> {
                fileTransferRepository.updateStatus(message.transferId, TransferStatus.PAUSED)
                engine.pauseTransfer(message.transferId)
            }
            TransferAction.RESUME -> {
                fileTransferRepository.updateStatus(message.transferId, TransferStatus.ACTIVE)
                val transfer = fileTransferRepository.getTransferByIdOnce(message.transferId) ?: return
                if (transfer.direction == TransferDirection.OUTGOING) {
                    val nextFile = transfer.files.firstOrNull { it.status != TransferFileStatus.COMPLETED }
                    if (nextFile != null) {
                        val startChunk = (nextFile.transferred / Constants.MAX_CHUNK_SIZE).toInt()
                        engine.sendFile(senderId, transfer.id, nextFile.id, nextFile.filePath, nextFile.fileSize, startChunk)
                    }
                }
            }
            TransferAction.CANCEL -> {
                fileTransferRepository.updateStatus(message.transferId, TransferStatus.FAILED)
                engine.cancelTransfer(message.transferId)
                cleanupTransferState(message.transferId)
            }
            else -> {}
        }
    }

    fun pauseTransfer(transferId: String) {
        scope.launch {
            val transfer = fileTransferRepository.getTransferByIdOnce(transferId) ?: return@launch
            engine.pauseTransfer(transferId)
            fileTransferRepository.updateStatus(transferId, TransferStatus.PAUSED)
            connectionManager.sendMessage(transfer.peerId, TransferControlMessage(
                senderId = preferencesManager.getDeviceUuid(),
                transferId = transferId,
                action = TransferAction.PAUSE
            ))
        }
    }

    fun resumeTransfer(transferId: String) {
        scope.launch {
            val transfer = fileTransferRepository.getTransferByIdOnce(transferId) ?: return@launch
            fileTransferRepository.updateStatus(transferId, TransferStatus.ACTIVE)

            connectionManager.sendMessage(transfer.peerId, TransferControlMessage(
                senderId = preferencesManager.getDeviceUuid(),
                transferId = transferId,
                action = TransferAction.RESUME
            ))

            if (transfer.direction == TransferDirection.OUTGOING) {
                val nextFile = transfer.files.firstOrNull { it.status != TransferFileStatus.COMPLETED }
                if (nextFile != null) {
                    val startChunk = (nextFile.transferred / Constants.MAX_CHUNK_SIZE).toInt()
                    engine.sendFile(transfer.peerId, transfer.id, nextFile.id, nextFile.filePath, nextFile.fileSize, startChunk)
                }
            }
        }
    }

    fun cancelTransfer(transferId: String) {
        scope.launch {
            val transfer = fileTransferRepository.getTransferByIdOnce(transferId) ?: return@launch
            engine.cancelTransfer(transferId)
            fileTransferRepository.updateStatus(transferId, TransferStatus.FAILED)
            cleanupTransferState(transferId)

            connectionManager.sendMessage(transfer.peerId, TransferControlMessage(
                senderId = preferencesManager.getDeviceUuid(),
                transferId = transferId,
                action = TransferAction.CANCEL
            ))
        }
    }

    private fun cleanupTransferState(transferId: String) {
        activeTransferIds.remove(transferId)
        lastDbWrite.remove(transferId)
        statsTracker.remove(transferId)
        engine.releaseTransfer(transferId)
        fileMetaMap.keys.filter { fileId -> fileMetaMap[fileId]?.first == transferId }.forEach {
            fileMetaMap.remove(it)
            inMemoryBytesReceived.remove(it)
            fileSavePaths.remove(it)
        }
    }

    fun startTransfer(transfer: FileTransfer) {
        scope.launch {
            fileTransferRepository.saveTransfer(transfer)
            fileTransferRepository.updateStatus(transfer.id, TransferStatus.WAITING_ACCEPTANCE)

            val offer = FileOfferMessage(
                senderId   = preferencesManager.getDeviceUuid(),
                transferId = transfer.id,
                messageId  = null,
                files      = transfer.files.map { it.toMetadata() },
                totalSize  = transfer.totalSize
            )

            if (!connectionManager.sendMessage(transfer.peerId, offer)) {
                fileTransferRepository.markAsFailed(transfer.id, "Failed to send offer")
                _transferEvents.emit(TransferEvent.Failed(transfer.id, "Connection failed"))
            }
        }
    }

    suspend fun acceptTransfer(transferId: String) {
        val transfer = fileTransferRepository.getTransferByIdOnce(transferId) ?: return

        // FIX: Register transfer as active BEFORE sending response so that
        // incoming chunks are never dropped due to timing race.
        activeTransferIds[transferId] = true

        transfer.files.forEach { file ->
            inMemoryBytesReceived[file.id] = AtomicLong(0L)
            fileMetaMap[file.id]           = Pair(transferId, file.fileSize)
        }

        // FIX: Update DB status to ACTIVE before sending response.
        // Previously markAsStarted was called after sendMessage which meant
        // the DB still showed WAITING_ACCEPTANCE when first chunks arrived,
        // causing the fallback DB check to also fail.
        fileTransferRepository.markAsStarted(transferId, TransferStatus.ACTIVE)

        // Start the bulk data server and tell the sender where to stream the bytes.
        engine.registerIncoming(transferId) { fileId -> resolveSavePath(transferId, fileId) }
        val dataPort = engine.ensureDataServer()
        if (dataPort <= 0) {
            fileTransferRepository.markAsFailed(transferId, "Cannot open data port")
            activeTransferIds.remove(transferId)
            connectionManager.sendMessage(
                transfer.peerId,
                FileResponseMessage(
                    senderId   = preferencesManager.getDeviceUuid(),
                    transferId = transferId,
                    accepted   = false,
                    reason     = "Receiver cannot open a data port"
                )
            )
            _transferEvents.emit(TransferEvent.Failed(transferId, "Cannot open data port"))
            return
        }

        val response = FileResponseMessage(
            senderId   = preferencesManager.getDeviceUuid(),
            transferId = transferId,
            accepted   = true,
            dataPort   = dataPort
        )
        connectionManager.sendMessage(transfer.peerId, response)

        Timber.d("Accepted live transfer $transferId — ready for ${transfer.files.size} file(s)")
    }

    suspend fun rejectTransfer(transferId: String, reason: String? = null) {
        val transfer = fileTransferRepository.getTransferByIdOnce(transferId) ?: return
        val response = FileResponseMessage(
            senderId   = preferencesManager.getDeviceUuid(),
            transferId = transferId,
            accepted   = false,
            reason     = reason
        )
        fileTransferRepository.markAsFailed(transferId, reason ?: "Rejected by user")
        connectionManager.sendMessage(transfer.peerId, response)
    }

    private suspend fun handleLiveFileOffer(senderId: String, offer: FileOfferMessage) {
        val transfer = FileTransfer(
            id        = offer.transferId,
            messageId = null,
            peerId    = senderId,
            direction = TransferDirection.INCOMING,
            status    = TransferStatus.WAITING_ACCEPTANCE,
            totalSize = offer.totalSize,
            fileCount = offer.files.size,
            files     = offer.files.mapIndexed { index, meta ->
                meta.toDomain(offer.transferId, index)
            }
        )

        fileTransferRepository.saveTransfer(transfer)
        _transferEvents.emit(
            TransferEvent.OfferReceived(offer.transferId, senderId, offer.files, offer.totalSize)
        )

        if (preferencesManager.isAutoAcceptFiles()) {
            acceptTransfer(offer.transferId)
        }
    }

    private suspend fun handleLiveFileResponse(senderId: String, response: FileResponseMessage) {
        val transfer = fileTransferRepository.getTransferByIdOnce(response.transferId) ?: return
        if (transfer.messageId != null) return

        if (response.accepted) {
            engine.setPeerDataPort(transfer.id, response.dataPort)
            fileTransferRepository.markAsStarted(transfer.id, TransferStatus.ACTIVE)

            // Send files sequentially — start with the first pending file
            val firstFile = transfer.files.firstOrNull { it.status == TransferFileStatus.PENDING }
            if (firstFile != null) {
                Timber.d("Sending first file ${firstFile.fileName} for transfer ${transfer.id}")
                engine.sendFile(senderId, transfer.id, firstFile.id, firstFile.filePath, firstFile.fileSize)
            } else {
                Timber.w("No pending files found for transfer ${transfer.id}")
            }
        } else {
            fileTransferRepository.markAsFailed(transfer.id, response.reason ?: "Rejected")
            _transferEvents.emit(
                TransferEvent.Failed(transfer.id, response.reason ?: "Rejected")
            )
        }
    }

    private val lastDbWrite = ConcurrentHashMap<String, Long>()

    private suspend fun updateProgress(
        transferId: String,
        fileId: String,
        bytesSent: Long
    ) {
        inMemoryBytesReceived.getOrPut(fileId) { AtomicLong(0L) }.set(bytesSent)

        val transfer = fileTransferRepository.getTransferByIdOnce(transferId) ?: return
        if (transfer.messageId != null) return

        val totalSent = transfer.files.sumOf { file ->
            when {
                file.id == fileId -> bytesSent
                file.status == TransferFileStatus.COMPLETED -> file.fileSize
                else -> inMemoryBytesReceived[file.id]?.get() ?: file.transferred
            }
        }.coerceAtMost(transfer.totalSize)

        // Throttled database write (at most once per second per transfer).
        val now = System.currentTimeMillis()
        val last = lastDbWrite[transferId] ?: 0L
        if (now - last >= Constants.PROGRESS_DB_INTERVAL_MS) {
            lastDbWrite[transferId] = now
            fileTransferRepository.updateFileProgress(
                fileId, bytesSent, TransferFileStatus.ACTIVE.name
            )
            fileTransferRepository.updateTransferProgress(
                transferId, totalSent, transfer.currentFile
            )
        }

        statsTracker.update(transferId, totalSent, transfer.totalSize)
        _transferEvents.emit(
            TransferEvent.Progress(transferId, totalSent, transfer.totalSize)
        )
    }

    private suspend fun finalizeFile(transferId: String, fileId: String, path: String) {
        val transfer = fileTransferRepository.getTransferByIdOnce(transferId) ?: return
        if (transfer.messageId != null) return

        // Outgoing files are often content:// URIs, whose File(path).length() is 0 — use the
        // declared size there; incoming files are real local files.
        val actualSize = if (transfer.direction == TransferDirection.OUTGOING) {
            transfer.files.firstOrNull { it.id == fileId }?.fileSize ?: 0L
        } else {
            File(path).length()
        }

        fileTransferRepository.updateFileProgress(
            fileId, actualSize, TransferFileStatus.COMPLETED.name
        )
        fileTransferRepository.updateFilePath(fileId, path)

        inMemoryBytesReceived.remove(fileId)
        fileMetaMap.remove(fileId)
        fileSavePaths.remove(fileId)

        val refreshed = fileTransferRepository.getTransferByIdOnce(transferId) ?: return
        val totalReceived = refreshed.files.sumOf { f ->
            if (f.id == fileId) actualSize else f.transferred
        }
        fileTransferRepository.updateTransferProgress(
            transferId, totalReceived, refreshed.currentFile
        )

        val allDone = refreshed.files.all { f ->
            f.id == fileId || f.status == TransferFileStatus.COMPLETED
        }

        if (allDone) {
            fileTransferRepository.markAsCompleted(transferId)
            activeTransferIds.remove(transferId)
            lastDbWrite.remove(transferId)
            statsTracker.complete(transferId)
            engine.releaseTransfer(transferId)
            _transferEvents.emit(TransferEvent.Completed(transferId))
            Timber.d("Transfer $transferId completed. All files saved.")
        } else {
            // Queue logic: Start next file if this is the sender
            if (refreshed.direction == TransferDirection.OUTGOING) {
                val nextFile = refreshed.files.firstOrNull { it.status == TransferFileStatus.PENDING }
                if (nextFile != null) {
                    Timber.d("Starting next file ${nextFile.fileName} for transfer $transferId")
                    engine.sendFile(refreshed.peerId, transferId, nextFile.id, nextFile.filePath, nextFile.fileSize)
                }
            }
        }
    }

    private suspend fun handleError(transferId: String, fileId: String?, message: String) {
        val transfer = fileTransferRepository.getTransferByIdOnce(transferId) ?: return
        if (transfer.messageId != null) return
        fileId?.let {
            inMemoryBytesReceived.remove(it)
            fileMetaMap.remove(it)
            fileSavePaths.remove(it)
        }
        activeTransferIds.remove(transferId)
        lastDbWrite.remove(transferId)
        statsTracker.remove(transferId)
        engine.releaseTransfer(transferId)
        fileTransferRepository.markAsFailed(transferId, message)
        _transferEvents.emit(TransferEvent.Failed(transferId, message))
    }

    private fun FileMetadata.toDomain(transferId: String, index: Int) = TransferFile(
        id           = id,
        transferId   = transferId,
        fileName     = name,
        filePath     = "",
        relativePath = relativePath,
        fileSize     = size,
        mimeType     = mimeType,
        isDirectory  = isDirectory,
        checksum     = checksum,
        indexOrder   = index
    )

    private fun TransferFile.toMetadata() = FileMetadata(
        id           = id,
        name         = fileName,
        relativePath = relativePath,
        size         = fileSize,
        mimeType     = mimeType,
        isDirectory  = isDirectory,
        checksum     = checksum
    )
}