package com.ipphone.presentation.screens.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ipphone.data.local.datastore.PreferencesManager
import com.ipphone.domain.model.FileTransfer
import com.ipphone.domain.model.Message
import com.ipphone.domain.model.MessageStatus
import com.ipphone.domain.model.MessageType
import com.ipphone.domain.model.TransferStatus
import com.ipphone.domain.model.User
import com.ipphone.domain.repository.FileTransferRepository
import com.ipphone.domain.repository.MessageRepository
import com.ipphone.domain.repository.UserRepository
import com.ipphone.network.connection.ConnectionManager
import com.ipphone.network.filetransfer.FileTransferManager
import com.ipphone.network.messaging.MessagingEngine
import com.ipphone.network.protocol.MessageReceiptMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class ChatUiState(
    val isLoading: Boolean           = true,
    val user: User?                  = null,
    val messages: List<Message>      = emptyList(),
    val myDeviceId: String           = "",
    val isTyping: Boolean            = false,
    val isSendingAttachment: Boolean = false,
    val isUserBlocked: Boolean       = false,          // ← NEW
    val transfers: Map<String, FileTransfer> = emptyMap(),
    val error: String?               = null
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userRepository: UserRepository,
    private val messageRepository: MessageRepository,
    private val fileTransferRepository: FileTransferRepository,
    private val connectionManager: ConnectionManager,
    private val messagingEngine: MessagingEngine,
    private val fileTransferManager: FileTransferManager,
    private val preferencesManager: PreferencesManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var currentUserId: String? = null
    private var transferLoadJob: Job?  = null
    private var lastTypingSentTime = 0L
    private val typingThrottleMs = 3000L
    private var isMeTyping = false

    val voiceRecorder   = VoiceRecorder(context)
    val voiceNotePlayer = VoiceNotePlayer(context)

    init {
        viewModelScope.launch {
            _uiState.update { it.copy(myDeviceId = preferencesManager.getDeviceUuid()) }
        }
        observeEngineEvents()
        observeTransferEvents()
    }

    // ── Transfer events ───────────────────────────────────────

    private fun observeTransferEvents() {
        viewModelScope.launch {
            fileTransferManager.transferEvents.collect {
                val peer = currentUserId ?: return@collect
                refreshTransfers(peer)
            }
        }
    }

    // ── Messaging engine events ───────────────────────────────

    private fun observeEngineEvents() {
        viewModelScope.launch {
            messagingEngine.messageEvents.collect { event ->
                val peer = currentUserId ?: return@collect
                when (event) {
                    is MessagingEngine.MessageEvent.NewMessage -> {
                        val msg = event.message
                        if (msg.conversationId == peer || msg.senderId == peer) {
                            sendReadReceipt(msg.id, msg.senderId)
                        }
                    }
                    is MessagingEngine.MessageEvent.TypingIndicator -> {
                        if (event.peerId == peer) {
                            _uiState.update { it.copy(isTyping = event.isTyping) }
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    // ── Active conversation ───────────────────────────────────

    private fun setActiveConversation()   { messagingEngine.activeConversationId = currentUserId }
    private fun clearActiveConversation() { messagingEngine.activeConversationId = null }

    // ── Load chat ─────────────────────────────────────────────

    fun loadChat(userId: String) {
        currentUserId = userId
        setActiveConversation()

        // User profile + block state
        viewModelScope.launch {
            userRepository.getUserById(userId).collect { user ->
                _uiState.update { it.copy(user = user, isUserBlocked = user?.isBlocked == true) }
            }
        }

        // Messages
        viewModelScope.launch {
            messageRepository.getMessagesForConversation(userId).collect { messages ->
                _uiState.update { it.copy(isLoading = false, messages = messages) }
                val unread = messages.filter {
                    it.senderId == userId && it.status != MessageStatus.READ
                }
                if (unread.isNotEmpty()) {
                    messageRepository.markAllAsRead(userId)
                    unread.forEach { msg -> sendReadReceipt(msg.id, userId) }
                }
            }
        }

        refreshTransfers(userId)
    }

    private fun refreshTransfers(userId: String) {
        transferLoadJob?.cancel()
        transferLoadJob = viewModelScope.launch {
            fileTransferRepository.getTransfersForPeer(userId).collect { transfers ->
                val transferMap = transfers
                    .filter { it.messageId != null }
                    .associateBy { it.messageId!! }
                _uiState.update { it.copy(transfers = transferMap) }
            }
        }
    }

    // ── Transfer controls ─────────────────────────────────────

    fun acceptTransfer(transferId: String) {
        viewModelScope.launch { fileTransferManager.acceptTransfer(transferId) }
    }

    fun rejectTransfer(transferId: String) {
        viewModelScope.launch { fileTransferManager.rejectTransfer(transferId) }
    }

    fun cancelTransfer(transferId: String) {
        viewModelScope.launch { fileTransferManager.cancelTransfer(transferId) }
    }

    // ── Text message ─────────────────────────────────────────

    fun sendMessage(content: String, replyToId: String? = null) {
        val userId = currentUserId ?: return
        viewModelScope.launch { messagingEngine.sendTextMessage(userId, content, replyToId) }
    }

    // ── Attachments ───────────────────────────────────────────

    private val MAX_FILE_SIZE = 100 * 1024 * 1024L // 100 MB

    fun sendAttachments(uris: List<Uri>, category: AttachmentCategory) {
        val userId = currentUserId ?: return
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSendingAttachment = true, error = null) }
            try {
                val files = ChatAttachmentHelper.resolveFiles(context, uris)
                
                val oversizedFiles = files.filter { it.size > MAX_FILE_SIZE }
                if (oversizedFiles.isNotEmpty()) {
                    val fileNames = oversizedFiles.joinToString(", ") { it.name }
                    _uiState.update { it.copy(error = "Files too large (max 100MB): $fileNames") }
                }

                files.filter { it.size <= MAX_FILE_SIZE }.forEach { attachment ->
                    val msgType = mimeToMessageType(attachment.mimeType, category)
                    messagingEngine.sendMediaMessage(
                        receiverId  = userId,
                        uri         = attachment.uri,
                        fileName    = attachment.name,
                        fileSize    = attachment.size,
                        mimeType    = attachment.mimeType,
                        messageType = msgType,
                        durationMs  = attachment.durationMs
                    )
                }
            } finally {
                _uiState.update { it.copy(isSendingAttachment = false) }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun sendVoiceNote(file: File, durationMs: Long) {
        val userId = currentUserId ?: return
        if (file.length() > MAX_FILE_SIZE) {
            _uiState.update { it.copy(error = "Voice note too large (max 100MB)") }
            return
        }
        viewModelScope.launch {
            messagingEngine.sendMediaMessage(
                receiverId  = userId,
                uri         = Uri.fromFile(file),
                fileName    = file.name,
                fileSize    = file.length(),
                mimeType    = "audio/mp4",
                messageType = MessageType.VOICE_NOTE,
                durationMs  = durationMs
            )
        }
    }

    // ── Edit / Delete ─────────────────────────────────────────

    fun editMessage(messageId: String, newContent: String) {
        viewModelScope.launch { messagingEngine.editMessage(messageId, newContent) }
    }

    fun deleteMessage(messageId: String, forEveryone: Boolean) {
        viewModelScope.launch { messagingEngine.deleteMessage(messageId, forEveryone) }
    }

    fun sendTyping(isTyping: Boolean) {
        val userId = currentUserId ?: return
        val currentTime = System.currentTimeMillis()

        if (isTyping) {
            if (!isMeTyping || currentTime - lastTypingSentTime > typingThrottleMs) {
                isMeTyping = true
                lastTypingSentTime = currentTime
                viewModelScope.launch { messagingEngine.sendTypingIndicator(userId, true) }
            }
        } else {
            if (isMeTyping) {
                isMeTyping = false
                lastTypingSentTime = 0
                viewModelScope.launch { messagingEngine.sendTypingIndicator(userId, false) }
            }
        }
    }

    // ── Block / Unblock ───────────────────────────────────────

    fun blockUser(userId: String) {
        viewModelScope.launch {
            userRepository.blockUser(userId)
            _uiState.update { it.copy(isUserBlocked = true) }
        }
    }

    fun unblockUser(userId: String) {
        viewModelScope.launch {
            userRepository.unblockUser(userId)
            _uiState.update { it.copy(isUserBlocked = false) }
        }
    }

    // ── Clear chat ────────────────────────────────────────────

    fun clearChat(userId: String) {
        viewModelScope.launch {
            messageRepository.deleteAllForConversation(userId)
        }
    }

    // ── Helpers ───────────────────────────────────────────────

    private fun sendReadReceipt(messageId: String, peerId: String) {
        val myId = _uiState.value.myDeviceId
        if (myId.isEmpty()) return
        viewModelScope.launch {
            connectionManager.sendMessage(
                peerId,
                MessageReceiptMessage(senderId = myId, messageId = messageId, status = "READ")
            )
        }
    }

    private fun mimeToMessageType(mime: String?, category: AttachmentCategory): MessageType {
        return when {
            category == AttachmentCategory.IMAGE  -> MessageType.IMAGE
            category == AttachmentCategory.VIDEO  -> MessageType.VIDEO
            category == AttachmentCategory.AUDIO  -> MessageType.AUDIO
            mime?.startsWith("image") == true     -> MessageType.IMAGE
            mime?.startsWith("video") == true     -> MessageType.VIDEO
            mime?.startsWith("audio") == true     -> MessageType.AUDIO
            else                                  -> MessageType.FILE
        }
    }

    override fun onCleared() {
        super.onCleared()
        voiceNotePlayer.stop()
        clearActiveConversation()
    }
}
