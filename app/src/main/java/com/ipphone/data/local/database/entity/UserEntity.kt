package com.ipphone.data.local.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey
    @ColumnInfo(name = "uuid")
    val uuid: String,
    
    @ColumnInfo(name = "device_name")
    val deviceName: String,
    
    @ColumnInfo(name = "profile_image")
    val profileImage: String?,
    
    @ColumnInfo(name = "ip_address")
    val ipAddress: String?,
    
    @ColumnInfo(name = "tcp_port")
    val tcpPort: Int,
    
    @ColumnInfo(name = "pairing_state")
    val pairingState: String,
    
    @ColumnInfo(name = "is_blocked")
    val isBlocked: Boolean = false,
    
    @ColumnInfo(name = "blocked_at")
    val blockedAt: Long?,
    
    @ColumnInfo(name = "paired_at")
    val pairedAt: Long?,
    
    @ColumnInfo(name = "last_seen")
    val lastSeen: Long?,
    
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)
