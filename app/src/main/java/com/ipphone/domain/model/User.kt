package com.ipphone.domain.model

import java.util.UUID

data class User(
    val uuid: String = UUID.randomUUID().toString(),
    val deviceName: String,
    val profileImage: String? = null,
    val ipAddress: String? = null,
    val tcpPort: Int = 8765,
    val pairingState: PairingState = PairingState.NONE,
    val isBlocked: Boolean = false,
    val blockedAt: Long? = null,
    val pairedAt: Long? = null,
    val lastSeen: Long? = null,
    val isOnline: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

enum class PairingState {
    NONE,
    PENDING_SENT,      // We sent a request
    PENDING_RECEIVED,  // We received a request
    PAIRED
}

data class DeviceInfo(
    val uuid: String,
    val deviceName: String,
    val profileImage: String?,
    val ipAddress: String,
    val tcpPort: Int
)
