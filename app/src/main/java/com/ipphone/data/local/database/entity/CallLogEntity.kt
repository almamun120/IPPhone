package com.ipphone.data.local.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "call_logs",
    foreignKeys = [
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["peer_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["peer_id"]),
        Index(value = ["created_at"])
    ]
)
data class CallLogEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    
    @ColumnInfo(name = "peer_id")
    val peerId: String,
    
    @ColumnInfo(name = "call_type")
    val callType: String,
    
    @ColumnInfo(name = "direction")
    val direction: String,
    
    @ColumnInfo(name = "status")
    val status: String,
    
    @ColumnInfo(name = "duration")
    val duration: Long,
    
    @ColumnInfo(name = "started_at")
    val startedAt: Long?,
    
    @ColumnInfo(name = "ended_at")
    val endedAt: Long?,
    
    @ColumnInfo(name = "created_at")
    val createdAt: Long
)
