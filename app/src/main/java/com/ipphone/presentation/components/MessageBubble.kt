package com.ipphone.presentation.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import coil.decode.VideoFrameDecoder
import coil.request.videoFrameMillis
import com.ipphone.domain.model.FileTransfer
import com.ipphone.domain.model.Message
import com.ipphone.domain.model.MessageStatus
import com.ipphone.domain.model.MessageType
import com.ipphone.presentation.theme.MessageIncoming
import com.ipphone.presentation.theme.MessageIncomingDark
import com.ipphone.presentation.theme.MessageOutgoing
import com.ipphone.presentation.theme.MessageOutgoingDark
import com.ipphone.domain.model.TransferStatus
import com.ipphone.domain.model.TransferDirection

/**
 * ──────────────────────────────────────────────────────────────────────────
 * BUG FIX — Chat Bug #2: Videos not playable in chat screen
 * BUG FIX — Chat Bug #3: "File not found" errors in chat
 * ──────────────────────────────────────────────────────────────────────────
 */

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: Message,
    isOutgoing: Boolean,
    replyContent: String? = null,
    onLongClick: () -> Unit = {},
    /** Called when the user taps a media item (image/video/file) to view it */
    onMediaTap: ((path: String, mimeType: String) -> Unit)? = null,
    // Audio playback state
    audioProgress: Float = 0f,
    isAudioPlaying: Boolean = false,
    onAudioPlayPause: () -> Unit = {},
    onAudioSeek: (Float) -> Unit = {},
    // P2P File Transfer state
    transfer: FileTransfer? = null,
    onAcceptTransfer: () -> Unit = {},
    onRejectTransfer: () -> Unit = {},
    onCancelTransfer: () -> Unit = {}
) {
    val isDark = isSystemInDarkTheme()
    val bubbleColor = if (isOutgoing) {
        if (isDark) MessageOutgoingDark else MessageOutgoing
    } else {
        if (isDark) MessageIncomingDark else MessageIncoming
    }

    val contentColor = if (isOutgoing) {
        if (isDark) Color.White else Color.Black
    } else {
        if (isDark) Color.White else Color.Black
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = if (isOutgoing) Arrangement.End else Arrangement.Start
    ) {
        Column(horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start) {

            // Optional reply preview
            replyContent?.let { content ->
                Surface(
                    color  = MaterialTheme.colorScheme.surfaceVariant,
                    shape  = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .widthIn(max = 280.dp)
                        .padding(bottom = 2.dp)
                ) {
                    Row(modifier = Modifier.padding(8.dp)) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(32.dp)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text  = content,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Message bubble
            Surface(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .combinedClickable(
                        onClick    = { },
                        onLongClick = onLongClick
                    ),
                color = bubbleColor,
                contentColor = contentColor,
                shape = RoundedCornerShape(
                    topStart    = 16.dp,
                    topEnd      = 16.dp,
                    bottomStart = if (isOutgoing) 16.dp else 4.dp,
                    bottomEnd   = if (isOutgoing) 4.dp  else 16.dp
                ),
                shadowElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    when (message.messageType) {
                        MessageType.IMAGE ->
                            ImageMessageContent(
                                message = message,
                                onTap = {
                                    onMediaTap?.invoke(message.mediaPath ?: "", "image/*")
                                }
                            )

                        MessageType.VIDEO ->
                            VideoMessageContent(
                                message = message,
                                onTap = {
                                    val path = message.mediaPath
                                    if (!path.isNullOrBlank()) {
                                        onMediaTap?.invoke(path, "video/*")
                                    }
                                }
                            )

                        MessageType.AUDIO, MessageType.VOICE_NOTE ->
                            AudioMessageContent(
                                message     = message,
                                progress    = audioProgress,
                                isPlaying   = isAudioPlaying,
                                onPlayPause = onAudioPlayPause,
                                onSeek      = onAudioSeek
                            )

                        MessageType.FILE ->
                            if (transfer != null && (transfer.status == TransferStatus.PENDING || transfer.status == TransferStatus.WAITING_ACCEPTANCE)) {
                                TransferPlaceholder(
                                    transfer   = transfer,
                                    isOutgoing = isOutgoing,
                                    onAccept   = onAcceptTransfer,
                                    onReject   = onRejectTransfer,
                                    onCancel   = onCancelTransfer
                                )
                            } else {
                                FileMessageContent(
                                    message = message,
                                    transfer = transfer,
                                    onTap = {
                                        val path = message.mediaPath
                                        if (!path.isNullOrBlank()) {
                                            onMediaTap?.invoke(path, "*/*")
                                        }
                                    },
                                    onCancelTransfer = onCancelTransfer
                                )
                            }

                        else -> TextMessageContent(message = message)
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    MessageFooter(message = message, isOutgoing = isOutgoing)
                }
            }
        }
    }
}

@Composable
private fun TextMessageContent(message: Message) {
    if (message.isDeleted) {
        Text(
            text      = "This message was deleted",
            style     = MaterialTheme.typography.bodyMedium,
            color     = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
        )
    } else {
        Text(
            text  = message.content ?: "",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun ImageMessageContent(
    message: Message,
    onTap: () -> Unit
) {
    val context = LocalContext.current
    val imageData: Any? = remember(message.mediaPath, message.thumbnail) {
        val path = message.mediaPath
        when {
            path != null && path.startsWith("content://") -> path
            path != null && path.startsWith("file://")   -> path
            path != null -> {
                val f = java.io.File(path)
                if (f.exists()) f else null
            }
            message.thumbnail != null ->
                android.util.Base64.decode(message.thumbnail, android.util.Base64.DEFAULT)
            else -> null
        }
    }

    Column {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clickable(onClick = onTap),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            if (imageData != null) {
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(imageData)
                        .crossfade(true)
                        .build(),
                    contentDescription = null,
                    modifier      = Modifier.fillMaxSize(),
                    contentScale  = ContentScale.Crop,
                    loading = {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    },
                    error = {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.BrokenImage, null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("File unavailable", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                )
            } else {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(Icons.Default.Image, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (message.mediaSize != null) {
                        CircularProgressIndicator(modifier = Modifier.size(64.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }

        message.content?.let { caption ->
            if (caption.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = caption, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun VideoMessageContent(
    message: Message,
    onTap: () -> Unit
) {
    val context = LocalContext.current
    val fileAvailable: Boolean = remember(message.mediaPath) {
        val path = message.mediaPath ?: return@remember false
        try {
            if (path.startsWith("content://")) {
                context.contentResolver.openAssetFileDescriptor(android.net.Uri.parse(path), "r")?.close()
                true
            } else {
                java.io.File(if (path.startsWith("file://")) android.net.Uri.parse(path).path ?: "" else path).exists()
            }
        } catch (_: Exception) { false }
    }

    val decodedThumbnail = remember(message.thumbnail) {
        if (!message.thumbnail.isNullOrBlank()) {
            try { android.util.Base64.decode(message.thumbnail, android.util.Base64.DEFAULT) }
            catch (_: Exception) { null }
        } else null
    }

    val thumbnailData: Any? = remember(decodedThumbnail, message.mediaPath, fileAvailable) {
        decodedThumbnail ?: if (fileAvailable && !message.mediaPath.isNullOrBlank()) {
            val path = message.mediaPath!!
            if (path.startsWith("content://") || path.startsWith("file://")) android.net.Uri.parse(path) else java.io.File(path)
        } else null
    }

    Column {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .then(if (fileAvailable) Modifier.clickable(onClick = onTap) else Modifier),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (thumbnailData != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(thumbnailData)
                            .crossfade(true)
                            .apply {
                                if (fileAvailable && decodedThumbnail == null) {
                                    decoderFactory(VideoFrameDecoder.Factory())
                                    videoFrameMillis(1000)
                                }
                            }
                            .build(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        alpha = 0.7f
                    )
                }
                if (fileAvailable) {
                    Icon(Icons.Default.PlayCircle, "Play video", modifier = Modifier.size(64.dp), tint = Color.White)
                } else if (message.mediaPath != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.BrokenImage, null, modifier = Modifier.size(40.dp), tint = Color.White.copy(alpha = 0.8f))
                        Text("File unavailable", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f))
                    }
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(80.dp), strokeWidth = 2.dp, color = Color.White.copy(alpha = 0.7f))
                    Icon(Icons.Default.PlayCircle, null, modifier = Modifier.size(40.dp), tint = Color.White.copy(alpha = 0.4f))
                }
            }
        }

        message.content?.let { caption ->
            if (caption.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = caption, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun AudioMessageContent(
    message: Message,
    progress: Float,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onPlayPause) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = progress,
            onValueChange = onSeek,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}

@Composable
private fun TransferPlaceholder(
    transfer: FileTransfer,
    isOutgoing: Boolean,
    onAccept: () -> Unit,
    onReject: () -> Unit,
    onCancel: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.InsertDriveFile,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isOutgoing) "Sending files..." else "Incoming files...",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${transfer.fileCount} files • ${formatFileSize(transfer.totalSize)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (isOutgoing) {
            Button(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Cancel Send")
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onReject,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Decline")
                }
                Button(
                    onClick = onAccept,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Accept")
                }
            }
        }
    }
}

@Composable
private fun FileMessageContent(
    message: Message,
    transfer: FileTransfer? = null,
    onTap: () -> Unit,
    onCancelTransfer: () -> Unit = {}
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onTap),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape    = RoundedCornerShape(8.dp),
                color    = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.InsertDriveFile, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = message.content ?: "File", style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                val sizeText = message.mediaSize?.let { formatFileSize(it) } ?: transfer?.totalSize?.let { formatFileSize(it) } ?: ""
                if (sizeText.isNotEmpty()) {
                    Text(text = sizeText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            
            if (transfer != null && (transfer.status == TransferStatus.ACTIVE || transfer.status == TransferStatus.PAUSED)) {
                IconButton(onClick = onCancelTransfer) {
                    Icon(Icons.Default.Close, "Cancel", tint = MaterialTheme.colorScheme.error)
                }
            } else {
                val path = message.mediaPath
                val available = path != null && java.io.File(if (path.startsWith("file://")) android.net.Uri.parse(path).path ?: "" else path).exists()
                if (available) {
                    IconButton(onClick = onTap) {
                        Icon(Icons.Default.OpenInNew, "Open", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        if (transfer != null && (transfer.status == TransferStatus.ACTIVE || transfer.status == TransferStatus.PAUSED)) {
            Spacer(modifier = Modifier.height(8.dp))
            Column {
                LinearProgressIndicator(
                    progress = { transfer.progress },
                    modifier = Modifier.fillMaxWidth().height(4.dp).background(Color.LightGray, RoundedCornerShape(2.dp))
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${(transfer.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (transfer.status == TransferStatus.ACTIVE) {
                        Text(
                            text = "Transferring...",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Text(
                            text = "Paused",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageFooter(message: Message, isOutgoing: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text  = message.createdAt.toFormattedTime(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        if (message.isEdited) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text  = "edited",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
        if (isOutgoing) {
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = when (message.status) {
                    MessageStatus.PENDING   -> Icons.Default.Schedule
                    MessageStatus.SENT      -> Icons.Default.Check
                    MessageStatus.DELIVERED -> Icons.Default.DoneAll
                    MessageStatus.READ      -> Icons.Default.DoneAll
                    MessageStatus.FAILED    -> Icons.Default.Error
                },
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = when (message.status) {
                    MessageStatus.READ   -> MaterialTheme.colorScheme.primary
                    MessageStatus.FAILED -> MaterialTheme.colorScheme.error
                    else                 -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                }
            )
        }
    }
}

private fun Long.toFormattedTime(): String {
    val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(this))
}

private fun formatFileSize(size: Long): String {
    val kb = 1024.0; val mb = kb * 1024; val gb = mb * 1024
    return when {
        size < kb -> "$size B"
        size < mb -> "%.1f KB".format(size / kb)
        size < gb -> "%.1f MB".format(size / mb)
        else      -> "%.2f GB".format(size / gb)
    }
}

