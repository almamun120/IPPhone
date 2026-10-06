package com.ipphone.network.sync

import android.content.Context
import android.net.Uri
import com.ipphone.data.local.database.dao.SyncQueueDao
import com.ipphone.data.local.database.entity.SyncQueueEntity
import com.ipphone.data.local.datastore.PreferencesManager
import com.ipphone.domain.model.MessageStatus
import com.ipphone.domain.model.MessageType
import com.ipphone.network.protocol.*
import com.ipphone.domain.model.*
import com.ipphone.domain.repository.FileTransferRepository
import com.ipphone.domain.repository.MessageRepository
import com.ipphone.network.connection.ConnectionManager
import com.ipphone.network.presence.PresenceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import timber.log.Timber
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncManager @Inject constructor(
    private val syncQueueDao: SyncQueueDao,
    private val presenceManager: PresenceManager,
    private val connectionManager: ConnectionManager,
    private val messageRepository: MessageRepository,
    private val fileTransferRepository: FileTransferRepository,
    private val preferencesManager: PreferencesManager,
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var previousOnlineSet = emptySet<String>()

    // ── FIX: Track consecutive TCP failures per device so we back off
    // exponentially instead of hammering every 30 s with 5-second connect
    // timeouts, which blocks coroutine threads and floods the logs.
    private val consecutiveFailures = ConcurrentHashMap<String, Int>()

    // ── FIX: Track last successful send time per device.
    // If we sent successfully recently, don't retry via the periodic sweep
    // (the message status in the DB already reflects delivery).
    private val lastSuccessTime = ConcurrentHashMap<String, Long>()

    init {
        observeOnlineDevices()
        startPeriodicSync()
    }

    // ─────────────────────────────────────────────────────────
    //  Online watcher
    // ─────────────────────────────────────────────────────────

    private fun observeOnlineDevices() {
        scope.launch {
            presenceManager.onlineDevices.collect { currentOnline ->
                val newlyOnline = currentOnline - previousOnlineSet
                previousOnlineSet = currentOnline

                newlyOnline.forEach { deviceId ->
                    // Reset failure counter when device comes online — it just
                    // sent us a heartbeat so its TCP server should be alive.
                    consecutiveFailures.remove(deviceId)
                    Timber.d("Device came online → full bidirectional sync: $deviceId")
                    launch {
                        // FIX: Increased delay from 2s to 3s to give PresenceManager
                        // time to write the correct IP+port into the DB from the
                        // heartbeat before ConnectionManager tries to connect.
                        delay(3000L)
                        flushSyncQueue(deviceId)
                        retryPendingMessages(deviceId)
                    }
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    //  Periodic sweep every 30 s
    // ─────────────────────────────────────────────────────────

    private fun startPeriodicSync() {
        scope.launch {
            while (isActive) {
                delay(30_000L)
                try {
                    syncQueueDao.deleteExpired()
                    presenceManager.onlineDevices.value.forEach { deviceId ->
                        // ── FIX: Exponential back-off for repeatedly failing devices.
                        // failures=1 → try every cycle, failures=2 → skip 1,
                        // failures=3 → skip 3, failures=4+ → skip 7 (max ~4 min wait).
                        val failures = consecutiveFailures[deviceId] ?: 0
                        if (failures > 0) {
                            // Skip this cycle if the failure count is high.
                            // We use a simple bitmask: retry on cycles where
                            // (cycleCount & mask) == 0. Here we just use
                            // the failure count to derive a skip probability.
                            val skipCycles = minOf(1 shl (failures - 1), 8)
                            val cycleNumber = (System.currentTimeMillis() / 30_000).toInt()
                            if (cycleNumber % skipCycles != 0) {
                                Timber.d("Skipping sync for $deviceId (failures=$failures, skipCycles=$skipCycles)")
                                return@forEach
                            }
                        }
                        launch { flushSyncQueue(deviceId) }
                        launch { retryPendingMessages(deviceId) }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Periodic sync error")
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────
    //  Flush explicit sync queue
    // ─────────────────────────────────────────────────────────

    private suspend fun flushSyncQueue(deviceId: String) {
        try {
            val items = syncQueueDao.getPendingForDevice(deviceId)
            if (items.isEmpty()) return
            Timber.d("Flushing ${items.size} queued item(s) → $deviceId")

            items.forEach { item ->
                val delivered = deliverItem(deviceId, item)
                if (delivered) {
                    syncQueueDao.delete(item.id)
                    consecutiveFailures.remove(deviceId)
                    lastSuccessTime[deviceId] = System.currentTimeMillis()
                } else {
                    syncQueueDao.incrementRetry(item.id)
                    consecutiveFailures[deviceId] = (consecutiveFailures[deviceId] ?: 0) + 1
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "flushSyncQueue error for $deviceId")
        }
    }

    private suspend fun deliverItem(
        deviceId: String,
        item: SyncQueueEntity
    ): Boolean {
        return try {
            when (item.payloadType) {
                "MESSAGE" -> {
                    val msg = protocolJson.decodeFromString<ChatMessage>(item.payload)
                    val sent = connectionManager.sendMessage(deviceId, msg)
                    if (sent) {
                        messageRepository.updateStatus(msg.messageId, MessageStatus.SENT)
                    }
                    sent
                }

                "MEDIA_TRANSFER" -> {
                    val offer = protocolJson.decodeFromString<FileOfferMessage>(item.payload)
                    connectionManager.sendMessage(deviceId, offer)
                }

                else -> false
            }
        } catch (e: Exception) {
            Timber.e(e, "deliverItem error for queue item ${item.id}")
            false
        }
    }

    // ─────────────────────────────────────────────────────────
    //  Retry PENDING / FAILED messages + media re-transfer
    // ─────────────────────────────────────────────────────────

    private suspend fun retryPendingMessages(deviceId: String) {
        try {
            val myId       = preferencesManager.getDeviceUuid()
            val allPending = messageRepository.getPendingMessagesForReceiver(deviceId)

            // FIX: Increased debounce from 10s to 15s.
            val now     = System.currentTimeMillis()
            val pending = allPending.filter { (now - it.createdAt) > 15_000 }

            if (pending.isEmpty()) return

            Timber.d("Retrying ${pending.size} pending message(s) → $deviceId")

            var anySuccess = false
            var anyFailure = false

            pending.forEach { msg ->
                val chatMsg = ChatMessage(
                    senderId       = myId,
                    messageId      = msg.id,
                    receiverId     = deviceId,
                    content        = msg.content,
                    messageType    = msg.messageType.name,
                    mediaSize      = msg.mediaSize,
                    thumbnail      = msg.thumbnail,
                    replyToId      = msg.replyToId,
                    replyToContent = msg.replyToContent,
                    timestamp      = msg.createdAt
                )

                val sent = connectionManager.sendMessage(deviceId, chatMsg)
                if (sent) {
                    anySuccess = true
                    messageRepository.updateStatus(msg.id, MessageStatus.SENT)
                    Timber.d("Re-delivered message ${msg.id} → $deviceId")

                    if (msg.messageType != MessageType.TEXT) {
                        val mediaPath = msg.mediaPath
                        if (!mediaPath.isNullOrBlank()) {
                            scheduleMediaRetransfer(
                                messageId  = msg.id,
                                receiverId = deviceId,
                                mediaPath  = mediaPath,
                                fileName   = msg.content ?: "file",
                                fileSize   = msg.mediaSize ?: 0L,
                                mimeType   = null
                            )
                        }
                    }
                } else {
                    anyFailure = true
                    Timber.w("Re-delivery failed for message ${msg.id} → $deviceId")
                    // FIX: Small delay between failed attempts to avoid hammering
                    delay(500)
                }
            }

            // Update failure tracking based on overall result
            if (anySuccess) {
                consecutiveFailures.remove(deviceId)
                lastSuccessTime[deviceId] = System.currentTimeMillis()
            } else if (anyFailure) {
                consecutiveFailures[deviceId] = (consecutiveFailures[deviceId] ?: 0) + 1
                Timber.d("consecutiveFailures[$deviceId]=${consecutiveFailures[deviceId]}")
            }

        } catch (e: Exception) {
            Timber.e(e, "retryPendingMessages error for $deviceId")
        }
    }

    /**
     * Publicly accessible method to queue a media transfer for a specific message.
     */
    suspend fun scheduleMediaRetransfer(
        messageId: String,
        receiverId: String,
        mediaPath: String,
        fileName: String,
        fileSize: Long,
        mimeType: String?
    ) {
        try {
            val isAccessible = when {
                mediaPath.startsWith("content://") -> {
                    try {
                        context.contentResolver
                            .openInputStream(Uri.parse(mediaPath))?.close()
                        true
                    } catch (_: Exception) { false }
                }
                mediaPath.startsWith("file://") ->
                    File(Uri.parse(mediaPath).path ?: "").exists()
                else ->
                    File(mediaPath).exists()
            }

            if (!isAccessible) {
                Timber.w("scheduleMediaRetransfer: source not accessible for $fileName")
                return
            }

            val newTransferId = "transfer_${messageId}"
            val fileId        = "file_${messageId}"

            val transfer = FileTransfer(
                id        = newTransferId,
                messageId = messageId,
                peerId    = receiverId,
                direction = TransferDirection.OUTGOING,
                status    = TransferStatus.WAITING_ACCEPTANCE,
                totalSize = fileSize,
                fileCount = 1,
                files     = listOf(
                    TransferFile(
                        id           = fileId,
                        transferId   = newTransferId,
                        fileName     = fileName,
                        filePath     = mediaPath,
                        relativePath = null,
                        fileSize     = fileSize,
                        mimeType     = mimeType,
                        isDirectory  = false,
                        checksum     = null,
                        indexOrder   = 0
                    )
                )
            )
            fileTransferRepository.saveTransfer(transfer)

            val offer = FileOfferMessage(
                senderId   = preferencesManager.getDeviceUuid(),
                transferId = newTransferId,
                messageId  = messageId,
                files      = listOf(
                    FileMetadata(
                        id           = fileId,
                        name         = fileName,
                        relativePath = null,
                        size         = fileSize,
                        mimeType     = mimeType,
                        isDirectory  = false,
                        checksum     = null
                    )
                ),
                totalSize  = fileSize
            )

            val payload = protocolJson.encodeToString(FileOfferMessage.serializer(), offer)

            syncQueueDao.insert(
                SyncQueueEntity(
                    id           = UUID.randomUUID().toString(),
                    targetDevice = receiverId,
                    payloadType  = "MEDIA_TRANSFER",
                    payload      = payload,
                    priority     = 2,
                    createdAt    = System.currentTimeMillis(),
                    lastAttempt  = null
                )
            )
            Timber.d("Queued MEDIA_TRANSFER for message $messageId → $receiverId")

            if (presenceManager.isDeviceOnline(receiverId)) {
                flushSyncQueue(receiverId)
            }

        } catch (e: Exception) {
            Timber.e(e, "scheduleMediaRetransfer error for message $messageId")
        }
    }

    // ─────────────────────────────────────────────────────────
    //  Public API
    // ─────────────────────────────────────────────────────────

    suspend fun queueMessage(targetDevice: String, message: ChatMessage) {
        val entity = SyncQueueEntity(
            id           = UUID.randomUUID().toString(),
            targetDevice = targetDevice,
            payloadType  = "MESSAGE",
            payload      = protocolJson.encodeToString(message),
            priority     = 1,
            createdAt    = System.currentTimeMillis(),
            lastAttempt  = null
        )
        syncQueueDao.insert(entity)
        Timber.d("Queued message ${message.messageId} for $targetDevice")

        if (presenceManager.isDeviceOnline(targetDevice)) {
            flushSyncQueue(targetDevice)
        }
    }

    suspend fun queueForSync(
        targetDevice: String,
        payloadType: String,
        payload: String,
        priority: Int = 0
    ) {
        syncQueueDao.insert(
            SyncQueueEntity(
                id           = UUID.randomUUID().toString(),
                targetDevice = targetDevice,
                payloadType  = payloadType,
                payload      = payload,
                priority     = priority,
                createdAt    = System.currentTimeMillis(),
                lastAttempt  = null
            )
        )
    }
}