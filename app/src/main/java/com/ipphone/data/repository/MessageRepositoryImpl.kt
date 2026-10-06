package com.ipphone.data.repository

import com.ipphone.data.local.database.dao.MessageDao
import com.ipphone.data.local.datastore.PreferencesManager
import com.ipphone.data.mapper.toDomain
import com.ipphone.data.mapper.toEntity
import com.ipphone.domain.model.DeletedFor
import com.ipphone.domain.model.Message
import com.ipphone.domain.model.MessageStatus
import com.ipphone.domain.repository.MessageRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageRepositoryImpl @Inject constructor(
    private val messageDao: MessageDao,
    private val preferencesManager: PreferencesManager
) : MessageRepository {
    
    override fun getMessagesForConversation(conversationId: String): Flow<List<Message>> {
        return messageDao.getMessagesForConversation(conversationId)
            .map { entities -> entities.map { it.toDomain() } }
    }
    
    override suspend fun getMessageById(messageId: String): Message? {
        return messageDao.getMessageById(messageId)?.toDomain()
    }
    
    override suspend fun getLastMessage(conversationId: String): Message? {
        return messageDao.getLastMessage(conversationId)?.toDomain()
    }
    
    override fun getUnreadCount(conversationId: String): Flow<Int> {
        return kotlinx.coroutines.flow.flow {
            val myId = preferencesManager.getDeviceUuid()
            messageDao.getUnreadCount(conversationId, myId).collect { emit(it) }
        }
    }
    
    override suspend fun getPendingMessages(): List<Message> {
        return messageDao.getPendingMessages().map { it.toDomain() }
    }
    
    override suspend fun getPendingMessagesForReceiver(receiverId: String): List<Message> {
        return messageDao.getPendingMessagesForReceiver(receiverId).map { it.toDomain() }
    }
    
    override suspend fun saveMessage(message: Message) {
        messageDao.insert(message.toEntity())
    }
    
    override suspend fun updateStatus(messageId: String, status: MessageStatus) {
        messageDao.updateStatus(messageId, status.name)
    }
    
    override suspend fun markAsDelivered(messageId: String) {
        messageDao.markAsDelivered(messageId)
    }
    
    override suspend fun markAsRead(messageId: String) {
        messageDao.markAsRead(messageId)
    }
    
    override suspend fun markAllAsRead(conversationId: String) {
        val myId = preferencesManager.getDeviceUuid()
        messageDao.markAllAsRead(conversationId, myId)
    }
    
    override suspend fun editMessage(messageId: String, newContent: String) {
        messageDao.editMessage(messageId, newContent)
    }

    override suspend fun updateMediaPath(messageId: String, path: String) {
        messageDao.updateMediaPath(messageId, path)
    }
    
    override suspend fun deleteMessage(messageId: String, forEveryone: Boolean) {
        val deletedFor = if (forEveryone) DeletedFor.EVERYONE.name else DeletedFor.ME.name
        messageDao.deleteMessage(messageId, deletedFor)
    }
    
    override suspend fun deleteAllForConversation(conversationId: String) {
        messageDao.deleteAllForConversation(conversationId)
    }
}
