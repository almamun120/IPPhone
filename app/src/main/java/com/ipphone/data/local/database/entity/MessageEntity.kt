package com.ipphone.data.local.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["conversation_id"]),
        Index(value = ["created_at"]),
        Index(value = ["status"])
    ]
)
data class MessageEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,
    
    @ColumnInfo(name = "sender_id")
    val senderId: String,
    
    @ColumnInfo(name = "receiver_id")
    val receiverId: String,
    
    @ColumnInfo(name = "content")
    val content: String?,
    
    @ColumnInfo(name = "message_type")
    val messageType: String,
    
    @ColumnInfo(name = "media_path")
    val mediaPath: String?,
    
    @ColumnInfo(name = "media_size")
    val mediaSize: Long?,
    
    @ColumnInfo(name = "thumbnail")
    val thumbnail: String?,
    
    @ColumnInfo(name = "reply_to_id")
    val replyToId: String?,
    
    @ColumnInfo(name = "status")
    val status: String,
    
    @ColumnInfo(name = "is_edited")
    val isEdited: Boolean = false,
    
    @ColumnInfo(name = "is_deleted")
    val isDeleted: Boolean = false,
    
    @ColumnInfo(name = "deleted_for")
    val deletedFor: String?,
    
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    
    @ColumnInfo(name = "edited_at")
    val editedAt: Long?,
    
    @ColumnInfo(name = "delivered_at")
    val deliveredAt: Long?,
    
    @ColumnInfo(name = "read_at")
    val readAt: Long?
)
