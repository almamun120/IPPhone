package com.ipphone.network.protocol

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

object PacketSerializer {
    
    private const val HEADER_SIZE = 4 // 4 bytes for message length
    private const val MAX_MESSAGE_SIZE = 10 * 1024 * 1024 // 10 MB max
    
    fun serialize(message: ProtocolMessage): ByteArray {
        val json = when (message) {
            is PairRequestMessage -> protocolJson.encodeToString(message)
            is PairResponseMessage -> protocolJson.encodeToString(message)
            is UnpairMessage -> protocolJson.encodeToString(message)
            is HeartbeatMessage -> protocolJson.encodeToString(message)
            is ChatMessage -> protocolJson.encodeToString(message)
            is MessageReceiptMessage -> protocolJson.encodeToString(message)
            is MessageEditMessage -> protocolJson.encodeToString(message)
            is MessageDeleteMessage -> protocolJson.encodeToString(message)
            is TypingIndicatorMessage -> protocolJson.encodeToString(message)
            is FileOfferMessage -> protocolJson.encodeToString(message)
            is FileResponseMessage -> protocolJson.encodeToString(message)
            is FileChunkMessage -> protocolJson.encodeToString(message)
            is ChunkAckMessage -> protocolJson.encodeToString(message)
            is TransferControlMessage -> protocolJson.encodeToString(message)
            is ResumeRequestMessage -> protocolJson.encodeToString(message)
            is ResumeResponseMessage -> protocolJson.encodeToString(message)
            is CallOfferMessage -> protocolJson.encodeToString(message)
            is CallAnswerMessage -> protocolJson.encodeToString(message)
            is IceCandidateMessage -> protocolJson.encodeToString(message)
            is CallEndMessage -> protocolJson.encodeToString(message)
            is BlockedNotificationMessage -> protocolJson.encodeToString(message)
        }
        
        val jsonBytes = json.toByteArray(StandardCharsets.UTF_8)
        val buffer = ByteBuffer.allocate(HEADER_SIZE + jsonBytes.size)
        buffer.putInt(jsonBytes.size)
        buffer.put(jsonBytes)
        
        return buffer.array()
    }
    
    fun deserialize(data: ByteArray): ProtocolMessage? {
        return try {
            // Check for the 4-byte length header and skip it if present
            var startIndex = 0
            if (data.size >= HEADER_SIZE) {
                try {
                    val lengthFromHeader = ByteBuffer.wrap(data, 0, HEADER_SIZE).int
                    // If the header matches the remaining data size and the JSON starts with '{'
                    if (lengthFromHeader == data.size - HEADER_SIZE && data[HEADER_SIZE] == '{'.toByte()) {
                        startIndex = HEADER_SIZE
                    } else if (data[0] != '{'.toByte()) {
                        // Fallback: find the first '{' if it doesn't start with one
                        val foundIndex = data.indexOf('{'.toByte())
                        if (foundIndex != -1) startIndex = foundIndex
                    }
                } catch (e: Exception) {
                    // Fallback for any wrap/int issues
                    val foundIndex = data.indexOf('{'.toByte())
                    if (foundIndex != -1) startIndex = foundIndex
                }
            }

            val json = String(data, startIndex, data.size - startIndex, StandardCharsets.UTF_8).trim()
            if (json.isEmpty()) return null
            
            val jsonObject = protocolJson.decodeFromString<JsonObject>(json)
            val type = jsonObject["type"]?.jsonPrimitive?.content ?: return null
            
            when (type) {
                "pair_request" -> protocolJson.decodeFromString<PairRequestMessage>(json)
                "pair_response" -> protocolJson.decodeFromString<PairResponseMessage>(json)
                "unpair" -> protocolJson.decodeFromString<UnpairMessage>(json)
                "heartbeat" -> protocolJson.decodeFromString<HeartbeatMessage>(json)
                "chat_message" -> protocolJson.decodeFromString<ChatMessage>(json)
                "message_receipt" -> protocolJson.decodeFromString<MessageReceiptMessage>(json)
                "message_edit" -> protocolJson.decodeFromString<MessageEditMessage>(json)
                "message_delete" -> protocolJson.decodeFromString<MessageDeleteMessage>(json)
                "typing_indicator" -> protocolJson.decodeFromString<TypingIndicatorMessage>(json)
                "file_offer" -> protocolJson.decodeFromString<FileOfferMessage>(json)
                "file_response" -> protocolJson.decodeFromString<FileResponseMessage>(json)
                "file_chunk" -> protocolJson.decodeFromString<FileChunkMessage>(json)
                "chunk_ack" -> protocolJson.decodeFromString<ChunkAckMessage>(json)
                "transfer_control" -> protocolJson.decodeFromString<TransferControlMessage>(json)
                "resume_request" -> protocolJson.decodeFromString<ResumeRequestMessage>(json)
                "resume_response" -> protocolJson.decodeFromString<ResumeResponseMessage>(json)
                "call_offer" -> protocolJson.decodeFromString<CallOfferMessage>(json)
                "call_answer" -> protocolJson.decodeFromString<CallAnswerMessage>(json)
                "ice_candidate" -> protocolJson.decodeFromString<IceCandidateMessage>(json)
                "call_end" -> protocolJson.decodeFromString<CallEndMessage>(json)
                "blocked" -> protocolJson.decodeFromString<BlockedNotificationMessage>(json)
                else -> {
                    Timber.w("Unknown message type: $type")
                    null
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to deserialize message. Data size: ${data.size}")
            null
        }
    }
    
    fun readMessageLength(header: ByteArray): Int {
        if (header.size < HEADER_SIZE) return -1
        return ByteBuffer.wrap(header).int
    }
    
    fun isValidMessageSize(size: Int): Boolean {
        return size in 1..MAX_MESSAGE_SIZE
    }
}
