package com.ipphone.network.messaging

import android.content.Context
import com.ipphone.data.local.datastore.PreferencesManager
import com.ipphone.domain.model.Message
import com.ipphone.domain.model.MessageStatus
import com.ipphone.domain.model.MessageType
import com.ipphone.domain.repository.MessageRepository
import com.ipphone.domain.repository.UserRepository
import com.ipphone.network.connection.ConnectionManager
import com.ipphone.network.filetransfer.FileTransferManager
import com.ipphone.network.presence.PresenceManager
import com.ipphone.network.protocol.*
import com.ipphone.network.sync.SyncManager
import com.ipphone.utils.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessagingEngine @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val connectionManager: ConnectionManager,
    private val presenceManager: PresenceManager,
    private val messageRepository: MessageRepository,
    private val userRepository: UserRepository,
    private val syncManager: SyncManager,
    private val preferencesManager: PreferencesManager,
    private val notificationHelper: NotificationHelper,
    private val fileTransferManager: FileTransferManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    sealed class MessageEvent {
        data class NewMessage(val message: Message) : MessageEvent()
        data class MessageDelivered(val messageId: String) : MessageEvent()
        data class MessageRead(val messageId: String) : MessageEvent()
        data class MessageEdited(val messageId: String, val newContent: String) : MessageEvent()
        data class MessageDeleted(val messageId: String) : MessageEvent()
        data class TypingIndicator(val peerId: String, val isTyping: Boolean) : MessageEvent()
    }

    private val _messageEvents = MutableSharedFlow<MessageEvent>(extraBufferCapacity = 64)
    val messageEvents: SharedFlow<MessageEvent> = _messageEvents.asSharedFlow()

    // FIX Issue 14: Track which conversation ID is currently open so we can skip
    // showing an OS notification when the user is already reading that chat.
    @Volatile
    var activeConversationId: String? = null

    // FIX Issue 13: Per-peer typing timeout jobs. When a typing indicator arrives
    // we start/reset a 5-second timer. If no further indicator arrives within that
    // window we automatically emit isTyping=false to clear the UI.
    private val typingTimeoutJobs = ConcurrentHashMap<String, Job>()

    init {
        observeIncomingMessages()
    }

    private fun observeIncomingMessages() {
        scope.launch {
            connectionManager.incomingMessages.collect { (senderId, message) ->
                handleIncomingMessage(senderId, message)
            }
        }
    }

    private suspend fun handleIncomingMessage(
        senderId: String,
        protocolMessage: ProtocolMessage
    ) {
        if (userRepository.isBlocked(senderId)) {
            Timber.w("Ignoring message from blocked: $senderId")
            return
        }

        when (protocolMessage) {
            is HeartbeatMessage      -> handleHeartbeat(senderId, protocolMessage)
            is ChatMessage           -> handleChat(senderId, protocolMessage)
            is MessageReceiptMessage -> handleReceipt(protocolMessage)
            is MessageEditMessage    -> handleEdit(protocolMessage)
            is MessageDeleteMessage  -> handleDelete(protocolMessage)
            is TypingIndicatorMessage -> handleTyping(senderId, protocolMessage)
            else -> { }
        }
    }

    private suspend fun handleHeartbeat(senderId: String, msg: HeartbeatMessage) {
        // TCP handshake or keep-alive received. Ensure user exists in DB.
        val existingUser = userRepository.getUserByIdOnce(senderId)
        val now = System.currentTimeMillis()
        if (existingUser == null) {
            val newUser = com.ipphone.domain.model.User(
                uuid = senderId,
                deviceName = msg.deviceName,
                ipAddress = null, // PresenceManager/Discovery will eventually fill this if via UDP
                tcpPort = msg.tcpPort,
                pairingState = com.ipphone.domain.model.PairingState.NONE,
                createdAt = now,
                updatedAt = now,
                lastSeen = now
            )
            userRepository.saveUser(newUser)
            Timber.d("Created new user from TCP handshake: $senderId (${msg.deviceName})")
        } else {
            // Update name and port if changed
            if (existingUser.deviceName != msg.deviceName || existingUser.tcpPort != msg.tcpPort) {
                userRepository.saveUser(existingUser.copy(
                    deviceName = msg.deviceName,
                    tcpPort = msg.tcpPort,
                    updatedAt = now,
                    lastSeen = now
                ))
            } else {
                // Just update last seen
                userRepository.updatePresence(senderId, existingUser.ipAddress ?: "", msg.tcpPort)
            }
        }
    }

    private suspend fun handleChat(senderId: String, msg: ChatMessage) {
        val myId = preferencesManager.getDeviceUuid()

        if (messageRepository.getMessageById(msg.messageId) != null) {
            Timber.d("Duplicate message ignored: ${msg.messageId}")
            return
        }

        // Ensure user exists so they show up in Recent Chats
        val user = userRepository.getUserByIdOnce(senderId)
        if (user == null) {
            val newUser = com.ipphone.domain.model.User(
                uuid = senderId,
                deviceName = "Unknown Device",
                pairingState = com.ipphone.domain.model.PairingState.NONE,
                createdAt = System.currentTimeMillis()
            )
            userRepository.saveUser(newUser)
            Timber.d("Created placeholder user for incoming message: $senderId")
        }

        val messageType = MessageType.valueOf(msg.messageType)

        val message = Message(
            id             = msg.messageId,
            conversationId = senderId,
            senderId       = senderId,
            receiverId     = myId,
            content        = msg.content,
            messageType    = messageType,
            mediaPath      = null,
            mediaSize      = msg.mediaSize,
            thumbnail      = msg.thumbnail,
            replyToId      = msg.replyToId,
            replyToContent = msg.replyToContent,
            status         = MessageStatus.DELIVERED,
            createdAt      = msg.timestamp,
            deliveredAt    = System.currentTimeMillis()
        )

        messageRepository.saveMessage(message)
        Timber.d("Message saved from $senderId: ${msg.messageId}")

        _messageEvents.emit(MessageEvent.NewMessage(message))

        val receipt = MessageReceiptMessage(
            senderId  = myId,
            messageId = msg.messageId,
            status    = "DELIVERED"
        )
        connectionManager.sendMessage(senderId, receipt)

        // FIX Issue 14: Only show a notification if the user is NOT currently viewing
        // this conversation. When the chat screen is open, ChatViewModel calls
        // setActiveConversation() which sets activeConversationId = the peer's ID.
        if (activeConversationId != senderId) {
            val user = userRepository.getUserByIdOnce(senderId)
            notificationHelper.showMessageNotification(
                senderId   = senderId,
                senderName = user?.deviceName ?: "Unknown",
                message    = message
            )
        } else {
            Timber.d("Suppressing notification — chat with $senderId is currently open")
        }
    }

    private suspend fun handleReceipt(receipt: MessageReceiptMessage) {
        when (receipt.status) {
            "DELIVERED" -> {
                messageRepository.markAsDelivered(receipt.messageId)
                _messageEvents.emit(MessageEvent.MessageDelivered(receipt.messageId))
            }
            "READ" -> {
                messageRepository.markAsRead(receipt.messageId)
                _messageEvents.emit(MessageEvent.MessageRead(receipt.messageId))
            }
        }
    }

    private suspend fun handleEdit(edit: MessageEditMessage) {
        messageRepository.editMessage(edit.messageId, edit.newContent)
        _messageEvents.emit(MessageEvent.MessageEdited(edit.messageId, edit.newContent))
    }

    private suspend fun handleDelete(delete: MessageDeleteMessage) {
        messageRepository.deleteMessage(
            delete.messageId,
            forEveryone = delete.deleteFor == "EVERYONE"
        )
        _messageEvents.emit(MessageEvent.MessageDeleted(delete.messageId))
    }

    /**
     * FIX Issue 13: Auto-cancel typing indicator after a timeout.
     *
     * When user A sends isTyping=true, user B's UI shows "typing…".
     * Previously there was no mechanism to cancel this if A stopped typing
     * without sending a message — the "typing…" label stayed forever.
     *
     * Fix: on each isTyping=true, reset a 5-second timeout job. If no
     * further indicator arrives within 5 s, we emit isTyping=false ourselves.
     */
    private suspend fun handleTyping(senderId: String, indicator: TypingIndicatorMessage) {
        _messageEvents.emit(MessageEvent.TypingIndicator(senderId, indicator.isTyping))

        if (indicator.isTyping) {
            // Cancel any previous timeout for this peer and start a fresh one
            typingTimeoutJobs[senderId]?.cancel()
            typingTimeoutJobs[senderId] = scope.launch {
                delay(5_000)
                // Auto-clear typing indicator if sender goes quiet for 5 s
                _messageEvents.emit(MessageEvent.TypingIndicator(senderId, false))
                typingTimeoutJobs.remove(senderId)
                Timber.d("Auto-cleared typing indicator for $senderId after timeout")
            }
        } else {
            // Explicit stop — cancel any pending timeout
            typingTimeoutJobs.remove(senderId)?.cancel()
        }
    }

    // ── Outgoing messages ─────────────────────────────────────

    suspend fun sendTextMessage(
        receiverId: String,
        content: String,
        replyToId: String? = null
    ): Message {
        val myId         = preferencesManager.getDeviceUuid()
        val replyContent = replyToId?.let { messageRepository.getMessageById(it)?.content }

        val message = Message(
            id             = UUID.randomUUID().toString(),
            conversationId = receiverId,
            senderId       = myId,
            receiverId     = receiverId,
            content        = content,
            messageType    = MessageType.TEXT,
            replyToId      = replyToId,
            replyToContent = replyContent,
            status         = MessageStatus.PENDING
        )

        messageRepository.saveMessage(message)

        val chatMessage = ChatMessage(
            senderId       = myId,
            messageId      = message.id,
            receiverId     = receiverId,
            content        = content,
            messageType    = MessageType.TEXT.name,
            replyToId      = replyToId,
            replyToContent = replyContent,
            timestamp      = message.createdAt
        )

        if (presenceManager.isDeviceOnline(receiverId)) {
            val sent = connectionManager.sendMessage(receiverId, chatMessage)
            if (sent) {
                messageRepository.updateStatus(message.id, MessageStatus.SENT)
                return message.copy(status = MessageStatus.SENT)
            }
        }

        syncManager.queueMessage(receiverId, chatMessage)
        return message
    }

    suspend fun sendMediaMessage(
        receiverId: String,
        uri: android.net.Uri,
        fileName: String,
        fileSize: Long,
        mimeType: String?,
        messageType: MessageType,
        durationMs: Long? = null
    ): Message {
        val myId = preferencesManager.getDeviceUuid()

        // 1. Persist file locally first (WhatsApp-style)
        val internalSentDir = com.ipphone.utils.FileUtils.getInternalSentDirectory(context)
        val localFile = com.ipphone.utils.FileUtils.createUniqueFile(internalSentDir, fileName)
        val copied = com.ipphone.utils.FileUtils.copyUriToInternal(context, uri, localFile)
        
        val finalUriString = if (copied) localFile.absolutePath else uri.toString()

        val thumbnail = com.ipphone.utils.ThumbnailUtils.generateThumbnail(context, uri, mimeType)

        val message = Message(
            id             = UUID.randomUUID().toString(),
            conversationId = receiverId,
            senderId       = myId,
            receiverId     = receiverId,
            content        = fileName,
            messageType    = messageType,
            mediaPath      = finalUriString,
            mediaSize      = fileSize,
            thumbnail      = thumbnail,
            status         = MessageStatus.PENDING
        )

        messageRepository.saveMessage(message)

        val chatMessage = ChatMessage(
            senderId    = myId,
            messageId   = message.id,
            receiverId  = receiverId,
            content     = fileName,
            messageType = messageType.name,
            mediaPath   = null,
            mediaSize   = fileSize,
            thumbnail   = thumbnail,
            timestamp   = message.createdAt
        )

        if (presenceManager.isDeviceOnline(receiverId)) {
            val sent = connectionManager.sendMessage(receiverId, chatMessage)
            if (sent) {
                messageRepository.updateStatus(message.id, MessageStatus.SENT)

                val transfer = com.ipphone.domain.model.FileTransfer(
                    id        = "transfer_${message.id}",
                    messageId = message.id,
                    peerId    = receiverId,
                    direction = com.ipphone.domain.model.TransferDirection.OUTGOING,
                    totalSize = fileSize,
                    fileCount = 1,
                    files = listOf(
                        com.ipphone.domain.model.TransferFile(
                            id         = "file_${message.id}",
                            transferId = "transfer_${message.id}",
                            fileName   = fileName,
                            filePath   = finalUriString,
                            fileSize   = fileSize,
                            mimeType   = mimeType,
                            indexOrder = 0
                        )
                    )
                )
                fileTransferManager.startTransfer(transfer)

                return message.copy(status = MessageStatus.SENT, mediaPath = finalUriString)
            }
        }

        syncManager.queueMessage(receiverId, chatMessage)
        
        // For media messages, we also need to queue the file offer immediately.
        // This ensures that when the recipient comes back online, they get both
        // the ChatMessage and the FileOfferMessage.
        if (messageType != MessageType.TEXT) {
            syncManager.scheduleMediaRetransfer(
                messageId  = message.id,
                receiverId = receiverId,
                mediaPath  = finalUriString,
                fileName   = fileName,
                fileSize   = fileSize,
                mimeType   = mimeType
            )
        }
        return message
    }

    suspend fun editMessage(messageId: String, newContent: String) {
        val message = messageRepository.getMessageById(messageId) ?: return
        val myId    = preferencesManager.getDeviceUuid()
        messageRepository.editMessage(messageId, newContent)
        connectionManager.sendMessage(
            message.receiverId,
            MessageEditMessage(senderId = myId, messageId = messageId, newContent = newContent)
        )
    }

    suspend fun deleteMessage(messageId: String, forEveryone: Boolean) {
        val message = messageRepository.getMessageById(messageId) ?: return
        val myId    = preferencesManager.getDeviceUuid()
        messageRepository.deleteMessage(messageId, forEveryone)
        if (forEveryone) {
            connectionManager.sendMessage(
                message.receiverId,
                MessageDeleteMessage(
                    senderId  = myId,
                    messageId = messageId,
                    deleteFor = "EVERYONE"
                )
            )
        }
    }

    suspend fun sendTypingIndicator(receiverId: String, isTyping: Boolean) {
        val myId = preferencesManager.getDeviceUuid()
        connectionManager.sendMessage(
            receiverId,
            TypingIndicatorMessage(senderId = myId, isTyping = isTyping)
        )
    }
}
