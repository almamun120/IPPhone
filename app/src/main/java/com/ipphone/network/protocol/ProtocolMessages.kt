package com.ipphone.network.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val protocolJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
}

@Serializable
sealed class ProtocolMessage {
    abstract val type: String
    abstract val senderId: String
    abstract val timestamp: Long
}

// ============ PAIRING MESSAGES ============

@Serializable
@SerialName("pair_request")
data class PairRequestMessage(
    override val type: String = "pair_request",
    override val senderId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val senderName: String,
    val senderProfile: String? = null
) : ProtocolMessage()

@Serializable
@SerialName("pair_response")
data class PairResponseMessage(
    override val type: String = "pair_response",
    override val senderId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val status: PairResponseStatus,
    val responderName: String? = null,
    val responderProfile: String? = null
) : ProtocolMessage()

@Serializable
enum class PairResponseStatus {
    @SerialName("accepted") ACCEPTED,
    @SerialName("rejected") REJECTED
}

@Serializable
@SerialName("unpair")
data class UnpairMessage(
    override val type: String = "unpair",
    override val senderId: String,
    override val timestamp: Long = System.currentTimeMillis()
) : ProtocolMessage()

// ============ PRESENCE MESSAGES ============

@Serializable
@SerialName("heartbeat")
data class HeartbeatMessage(
    override val type: String = "heartbeat",
    override val senderId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val deviceName: String,
    val tcpPort: Int
) : ProtocolMessage()

// ============ CHAT MESSAGES ============

@Serializable
@SerialName("chat_message")
data class ChatMessage(
    override val type: String = "chat_message",
    override val senderId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val messageId: String,
    val receiverId: String,
    val content: String?,
    val messageType: String,
    val mediaPath: String? = null,
    val mediaSize: Long? = null,
    val thumbnail: String? = null,
    val replyToId: String? = null,
    val replyToContent: String? = null
) : ProtocolMessage()

@Serializable
@SerialName("message_receipt")
data class MessageReceiptMessage(
    override val type: String = "message_receipt",
    override val senderId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val messageId: String,
    val status: String // DELIVERED, READ
) : ProtocolMessage()

@Serializable
@SerialName("message_edit")
data class MessageEditMessage(
    override val type: String = "message_edit",
    override val senderId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val messageId: String,
    val newContent: String
) : ProtocolMessage()

@Serializable
@SerialName("message_delete")
data class MessageDeleteMessage(
    override val type: String = "message_delete",
    override val senderId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val messageId: String,
    val deleteFor: String // ME, EVERYONE
) : ProtocolMessage()

@Serializable
@SerialName("typing_indicator")
data class TypingIndicatorMessage(
    override val type: String = "typing_indicator",
    override val senderId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val isTyping: Boolean
) : ProtocolMessage()

// ============ FILE TRANSFER MESSAGES ============

@Serializable
@SerialName("file_offer")
data class FileOfferMessage(
    override val type: String = "file_offer",
    override val senderId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val transferId: String,
    val messageId: String? = null, // Optional: link to a chat message
    val files: List<FileMetadata>,
    val totalSize: Long
) : ProtocolMessage()

@Serializable
data class FileMetadata(
    val id: String,
    val name: String,
    val relativePath: String?,
    val size: Long,
    val mimeType: String?,
    val isDirectory: Boolean,
    val checksum: String?
)

@Serializable
@SerialName("file_response")
data class FileResponseMessage(
    override val type: String = "file_response",
    override val senderId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val transferId: String,
    val accepted: Boolean,
    val savePath: String? = null,
    val reason: String? = null,
    // TCP port of the receiver's bulk data server (file bytes are streamed there).
    val dataPort: Int = 0
) : ProtocolMessage()

@Serializable
@SerialName("file_chunk")
data class FileChunkMessage(
    override val type: String = "file_chunk",
    override val senderId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val transferId: String,
    val fileId: String,
    val chunkIndex: Int,
    val totalChunks: Int,
    val data: String, // Base64 encoded
    val checksum: String
) : ProtocolMessage()

@Serializable
@SerialName("chunk_ack")
data class ChunkAckMessage(
    override val type: String = "chunk_ack",
    override val senderId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val transferId: String,
    val fileId: String,
    val chunkIndex: Int,
    val status: ChunkStatus
) : ProtocolMessage()

@Serializable
enum class ChunkStatus {
    @SerialName("ok") OK,
    @SerialName("error") ERROR,
    @SerialName("checksum_mismatch") CHECKSUM_MISMATCH
}

@Serializable
@SerialName("transfer_control")
data class TransferControlMessage(
    override val type: String = "transfer_control",
    override val senderId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val transferId: String,
    val action: TransferAction
) : ProtocolMessage()

@Serializable
enum class TransferAction {
    @SerialName("pause") PAUSE,
    @SerialName("resume") RESUME,
    @SerialName("cancel") CANCEL,
    @SerialName("complete") COMPLETE
}

@Serializable
@SerialName("resume_request")
data class ResumeRequestMessage(
    override val type: String = "resume_request",
    override val senderId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val transferId: String
) : ProtocolMessage()

@Serializable
@SerialName("resume_response")
data class ResumeResponseMessage(
    override val type: String = "resume_response",
    override val senderId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val transferId: String,
    val fileProgress: Map<String, Int> // fileId -> lastReceivedChunk
) : ProtocolMessage()

// ============ CALL MESSAGES ============

@Serializable
@SerialName("call_offer")
data class CallOfferMessage(
    override val type: String = "call_offer",
    override val senderId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val callId: String,
    val callType: String, // AUDIO, VIDEO
    val sdpOffer: String
) : ProtocolMessage()

@Serializable
@SerialName("call_answer")
data class CallAnswerMessage(
    override val type: String = "call_answer",
    override val senderId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val callId: String,
    val accepted: Boolean,
    val sdpAnswer: String? = null,
    val reason: String? = null // BUSY, REJECTED
) : ProtocolMessage()

@Serializable
@SerialName("ice_candidate")
data class IceCandidateMessage(
    override val type: String = "ice_candidate",
    override val senderId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val callId: String,
    val candidate: String,
    val sdpMid: String?,
    val sdpMLineIndex: Int
) : ProtocolMessage()

@Serializable
@SerialName("call_end")
data class CallEndMessage(
    override val type: String = "call_end",
    override val senderId: String,
    override val timestamp: Long = System.currentTimeMillis(),
    val callId: String,
    val reason: String // ENDED, CANCELLED, FAILED
) : ProtocolMessage()

// ============ BLOCK MESSAGES ============

@Serializable
@SerialName("blocked")
data class BlockedNotificationMessage(
    override val type: String = "blocked",
    override val senderId: String,
    override val timestamp: Long = System.currentTimeMillis()
) : ProtocolMessage()
