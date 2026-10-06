package com.ipphone.utils

object Constants {
    // Network
    const val SERVICE_TYPE = "_wificalling._tcp."
    const val SERVICE_NAME = "IPPhone"
    const val TCP_PORT = 8765
    const val UDP_PORT = 8766

    // Heartbeat
    const val HEARTBEAT_INTERVAL_MS = 5_000L

    // Presence
    const val PRESENCE_TIMEOUT_MS = 15_000L
    const val PRESENCE_GRACE_PERIOD_MS = 5_000L

    // File Transfer
    // Increased chunk size to 2MB for faster LAN transfer
    const val DEFAULT_CHUNK_SIZE = 2097152    // 2 MB
    const val MIN_CHUNK_SIZE     = 65536     // 64 KB
    const val MAX_CHUNK_SIZE     = 2097152    // 2 MB
    const val TRANSFER_TIMEOUT_MS = 60_000L

    // Bulk (binary) data channel used for file transfers.
    // File bytes travel over their own TCP socket as a raw stream (no JSON/Base64),
    // so the control connection stays free for chat, heartbeats and call signalling.
    const val DATA_PORT                 = 8767
    const val STREAM_BUFFER_SIZE        = 512 * 1024        // 512 KB read/write buffer
    const val SOCKET_BUFFER_SIZE        = 4 * 1024 * 1024   // 4 MB socket buffers
    const val STREAM_CONNECT_TIMEOUT_MS = 8_000
    const val PROGRESS_INTERVAL_MS      = 250L
    const val PROGRESS_DB_INTERVAL_MS   = 1_000L
    const val MAX_RETRY_COUNT    = 5

    // Database
    const val DATABASE_NAME    = "wifi_calling_db"
    const val DATABASE_VERSION = 1

    // Preferences
    const val PREF_DEVICE_UUID    = "device_uuid"
    const val PREF_DEVICE_NAME    = "device_name"
    const val PREF_PROFILE_IMAGE  = "profile_image"

    // Notifications
    const val NOTIFICATION_ID_NETWORK  = 1001
    const val NOTIFICATION_ID_TRANSFER = 1002
    const val NOTIFICATION_ID_CALL     = 1003

    // Protocol Version
    const val PROTOCOL_VERSION = 1
}

