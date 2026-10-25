package com.ipphone.domain.model

import java.util.UUID

data class User(
    val uuid: String = UUID.randomUUID().toString(),
    val deviceName: String,
    val profileImage: String? = null,
    val ipAddress: String? = null,
    val tcpPort: Int = 8765,
    val isBlocked: Boolean = false,
    val blockedAt: Long? = null,
    val lastSeen: Long? = null,
    val isOnline: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class DeviceInfo(
    val uuid: String,
    val deviceName: String,
    val profileImage: String?,
    val ipAddress: String,
    val tcpPort: Int
)
