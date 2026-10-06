package com.ipphone.data.mapper

import com.ipphone.data.local.database.entity.*
import com.ipphone.domain.model.*

// User Mappers
fun UserEntity.toDomain(isOnline: Boolean = false): User = User(
    uuid = uuid,
    deviceName = deviceName,
    profileImage = profileImage,
    ipAddress = ipAddress,
    tcpPort = tcpPort,
    pairingState = PairingState.valueOf(pairingState),
    isBlocked = isBlocked,
    blockedAt = blockedAt,
    pairedAt = pairedAt,
    lastSeen = lastSeen,
    isOnline = isOnline,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun User.toEntity(): UserEntity = UserEntity(
    uuid = uuid,
    deviceName = deviceName,
    profileImage = profileImage,
    ipAddress = ipAddress,
    tcpPort = tcpPort,
    pairingState = pairingState.name,
    isBlocked = isBlocked,
    blockedAt = blockedAt,
    pairedAt = pairedAt,
    lastSeen = lastSeen,
    createdAt = createdAt,
    updatedAt = updatedAt
)

// Message Mappers
fun MessageEntity.toDomain(): Message = Message(
    id = id,
    conversationId = conversationId,
    senderId = senderId,
    receiverId = receiverId,
    content = content,
    messageType = MessageType.valueOf(messageType),
    mediaPath = mediaPath,
    mediaSize = mediaSize,
    thumbnail = thumbnail,
    replyToId = replyToId,
    status = MessageStatus.valueOf(status),
    isEdited = isEdited,
    isDeleted = isDeleted,
    deletedFor = deletedFor?.let { DeletedFor.valueOf(it) },
    createdAt = createdAt,
    editedAt = editedAt,
    deliveredAt = deliveredAt,
    readAt = readAt
)

fun Message.toEntity(): MessageEntity = MessageEntity(
    id = id,
    conversationId = conversationId,
    senderId = senderId,
    receiverId = receiverId,
    content = content,
    messageType = messageType.name,
    mediaPath = mediaPath,
    mediaSize = mediaSize,
    thumbnail = thumbnail,
    replyToId = replyToId,
    status = status.name,
    isEdited = isEdited,
    isDeleted = isDeleted,
    deletedFor = deletedFor?.name,
    createdAt = createdAt,
    editedAt = editedAt,
    deliveredAt = deliveredAt,
    readAt = readAt
)

// FileTransfer Mappers
fun FileTransferEntity.toDomain(files: List<TransferFile> = emptyList()): FileTransfer = FileTransfer(
    id = id,
    messageId = messageId,
    peerId = peerId,
    direction = TransferDirection.valueOf(direction),
    status = TransferStatus.valueOf(status),
    totalSize = totalSize,
    transferred = transferred,
    fileCount = fileCount,
    currentFile = currentFile,
    savePath = savePath,
    errorMessage = errorMessage,
    files = files,
    startedAt = startedAt,
    completedAt = completedAt,
    createdAt = createdAt
)

fun FileTransfer.toEntity(): FileTransferEntity = FileTransferEntity(
    id = id,
    messageId = messageId,
    peerId = peerId,
    direction = direction.name,
    status = status.name,
    totalSize = totalSize,
    transferred = transferred,
    fileCount = fileCount,
    currentFile = currentFile,
    savePath = savePath,
    errorMessage = errorMessage,
    startedAt = startedAt,
    completedAt = completedAt,
    createdAt = createdAt
)

fun TransferFileEntity.toDomain(): TransferFile = TransferFile(
    id = id,
    transferId = transferId,
    fileName = fileName,
    filePath = filePath,
    relativePath = relativePath,
    fileSize = fileSize,
    mimeType = mimeType,
    isDirectory = isDirectory,
    checksum = checksum,
    transferred = transferred,
    status = TransferFileStatus.valueOf(status),
    indexOrder = indexOrder
)

fun TransferFile.toEntity(): TransferFileEntity = TransferFileEntity(
    id = id,
    transferId = transferId,
    fileName = fileName,
    filePath = filePath,
    relativePath = relativePath,
    fileSize = fileSize,
    mimeType = mimeType,
    isDirectory = isDirectory,
    checksum = checksum,
    transferred = transferred,
    status = status.name,
    indexOrder = indexOrder
)

// CallLog Mappers
fun CallLogEntity.toDomain(peerName: String = "", peerImage: String? = null): CallLog = CallLog(
    id = id,
    peerId = peerId,
    peerName = peerName,
    peerImage = peerImage,
    callType = CallType.valueOf(callType),
    direction = CallDirection.valueOf(direction),
    status = CallStatus.valueOf(status),
    duration = duration,
    startedAt = startedAt,
    endedAt = endedAt,
    createdAt = createdAt
)

fun CallLog.toEntity(): CallLogEntity = CallLogEntity(
    id = id,
    peerId = peerId,
    callType = callType.name,
    direction = direction.name,
    status = status.name,
    duration = duration,
    startedAt = startedAt,
    endedAt = endedAt,
    createdAt = createdAt
)
