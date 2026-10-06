package com.ipphone.domain.model

import java.util.UUID

data class Message(
    val id: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val senderId: String,
    val receiverId: String,
    val content: String?,
    val messageType: MessageType = MessageType.TEXT,
    val mediaPath: String? = null,
    val mediaSize: Long? = null,
    val thumbnail: String? = null,
    val replyToId: String? = null,
    val replyToContent: String? = null,
    val status: MessageStatus = MessageStatus.PENDING,
    val isEdited: Boolean = false,
    val isDeleted: Boolean = false,
    val deletedFor: DeletedFor? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val editedAt: Long? = null,
    val deliveredAt: Long? = null,
    val readAt: Long? = null
)

enum class MessageType {
    TEXT,
    IMAGE,
    VIDEO,
    AUDIO,
    FILE,
    VOICE_NOTE
}

enum class MessageStatus {
    PENDING,
    SENT,
    DELIVERED,
    READ,
    FAILED
}

enum class DeletedFor {
    ME,
    EVERYONE
}

data class Conversation(
    val peerId: String,
    val peerName: String,
    val peerImage: String?,
    val lastMessage: Message?,
    val unreadCount: Int,
    val isOnline: Boolean
)
