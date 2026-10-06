package com.ipphone.domain.model

import java.util.UUID

data class CallLog(
    val id: String = UUID.randomUUID().toString(),
    val peerId: String,
    val peerName: String,
    val peerImage: String?,
    val callType: CallType,
    val direction: CallDirection,
    val status: CallStatus,
    val duration: Long = 0,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)

enum class CallType {
    AUDIO,
    VIDEO
}

enum class CallDirection {
    INCOMING,
    OUTGOING
}

enum class CallStatus {
    RINGING,
    CONNECTING,
    CONNECTED,
    ENDED,
    MISSED,
    REJECTED,
    CANCELLED,
    FAILED,
    BUSY
}
