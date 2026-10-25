package com.ipphone.domain.repository

import com.ipphone.domain.model.Message
import com.ipphone.domain.model.MessageStatus
import kotlinx.coroutines.flow.Flow

interface MessageRepository {
    fun getMessagesForConversation(conversationId: String): Flow<List<Message>>
    /** Newest [limit] messages, oldest first — used by the chat screen for windowed loading. */
    fun getRecentMessages(conversationId: String, limit: Int): Flow<List<Message>>
    suspend fun getMessageById(messageId: String): Message?
    suspend fun getLastMessage(conversationId: String): Message?
    fun getUnreadCount(conversationId: String): Flow<Int>
    suspend fun getPendingMessages(): List<Message>
    suspend fun getPendingMessagesForReceiver(receiverId: String): List<Message>
    suspend fun saveMessage(message: Message)
    suspend fun updateStatus(messageId: String, status: MessageStatus)
    suspend fun updateMediaPath(messageId: String, path: String)
    suspend fun markAsDelivered(messageId: String)
    suspend fun markAsRead(messageId: String)
    suspend fun markAllAsRead(conversationId: String)
    suspend fun editMessage(messageId: String, newContent: String)
    suspend fun deleteMessage(messageId: String, forEveryone: Boolean)
    suspend fun deleteAllForConversation(conversationId: String)
}
