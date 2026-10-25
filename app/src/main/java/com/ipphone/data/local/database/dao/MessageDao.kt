package com.ipphone.data.local.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ipphone.data.local.database.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Query("""
        SELECT * FROM messages 
        WHERE conversation_id = :conversationId 
        AND (is_deleted = 0 OR deleted_for != 'ME')
        ORDER BY created_at ASC
    """)
    fun getMessagesForConversation(conversationId: String): Flow<List<MessageEntity>>

    /** The newest [limit] messages of a conversation, returned oldest-first (uses the composite index). */
    @Query("""
        SELECT * FROM (
            SELECT * FROM messages 
            WHERE conversation_id = :conversationId 
            AND (is_deleted = 0 OR deleted_for != 'ME')
            ORDER BY created_at DESC
            LIMIT :limit
        ) ORDER BY created_at ASC
    """)
    fun getRecentMessages(conversationId: String, limit: Int): Flow<List<MessageEntity>>

    @Query("""
        SELECT * FROM messages 
        WHERE conversation_id = :conversationId 
        AND (is_deleted = 0 OR deleted_for != 'ME')
        ORDER BY created_at DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun getMessagesPage(conversationId: String, limit: Int, offset: Int): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getMessageById(messageId: String): MessageEntity?

    @Query("""
        SELECT * FROM messages 
        WHERE conversation_id = :conversationId 
        ORDER BY created_at DESC 
        LIMIT 1
    """)
    suspend fun getLastMessage(conversationId: String): MessageEntity?

    @Query("""
        SELECT COUNT(*) FROM messages 
        WHERE conversation_id = :conversationId 
        AND sender_id != :myId 
        AND status != 'READ'
        AND is_deleted = 0
    """)
    fun getUnreadCount(conversationId: String, myId: String): Flow<Int>

    /**
     * FIX: Include both PENDING and FAILED so re-send logic covers
     * messages that failed on the first attempt too.
     */
    @Query("SELECT * FROM messages WHERE status = 'PENDING' OR status = 'FAILED'")
    suspend fun getPendingMessages(): List<MessageEntity>

    /**
     * FIX: Include FAILED alongside PENDING.
     * This is the query SyncManager uses for bidirectional sync —
     * it must catch every unsent message regardless of whether it
     * previously attempted and failed.
     */
    @Query("""
        SELECT * FROM messages 
        WHERE receiver_id = :receiverId 
        AND (status = 'PENDING' OR status = 'FAILED')
        AND is_deleted = 0
        ORDER BY created_at ASC
    """)
    suspend fun getPendingMessagesForReceiver(receiverId: String): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<MessageEntity>)

    @Update
    suspend fun update(message: MessageEntity)

    @Query("UPDATE messages SET status = :status WHERE id = :messageId")
    suspend fun updateStatus(messageId: String, status: String)

    @Query("UPDATE messages SET media_path = :path WHERE id = :messageId")
    suspend fun updateMediaPath(messageId: String, path: String)

    @Query("UPDATE messages SET status = 'DELIVERED', delivered_at = :deliveredAt WHERE id = :messageId")
    suspend fun markAsDelivered(messageId: String, deliveredAt: Long = System.currentTimeMillis())

    @Query("UPDATE messages SET status = 'READ', read_at = :readAt WHERE id = :messageId")
    suspend fun markAsRead(messageId: String, readAt: Long = System.currentTimeMillis())

    @Query("""
        UPDATE messages 
        SET status = 'READ', read_at = :readAt 
        WHERE conversation_id = :conversationId 
        AND sender_id != :myId 
        AND status != 'READ'
    """)
    suspend fun markAllAsRead(
        conversationId: String,
        myId: String,
        readAt: Long = System.currentTimeMillis()
    )

    @Query("UPDATE messages SET content = :content, is_edited = 1, edited_at = :editedAt WHERE id = :messageId")
    suspend fun editMessage(
        messageId: String,
        content: String,
        editedAt: Long = System.currentTimeMillis()
    )

    @Query("UPDATE messages SET is_deleted = 1, deleted_for = :deletedFor WHERE id = :messageId")
    suspend fun deleteMessage(messageId: String, deletedFor: String)

    @Query("DELETE FROM messages WHERE conversation_id = :conversationId")
    suspend fun deleteAllForConversation(conversationId: String)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteById(messageId: String)
}
