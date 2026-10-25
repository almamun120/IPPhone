package com.ipphone.presentation.screens.chat

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.ipphone.presentation.components.MessageBubble
import com.ipphone.domain.model.Message
import com.ipphone.domain.model.MessageStatus
import com.ipphone.domain.model.MessageType
import com.ipphone.presentation.navigation.Screen
import com.ipphone.utils.MediaUtils
import com.ipphone.utils.toFormattedTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*

// ── WhatsApp palette ──────────────────────────────────────────
private val WaGreen     = Color(0xFF25D366)
private val WaDarkGreen = Color(0xFF075E54)
private val WaTeal      = Color(0xFF128C7E)
private val WaChatBg    = Color(0xFFECE5DD)
private val WaTimestamp = Color(0xFF667781)
private val WaCheckRead = Color(0xFF53BDEB)
private val WaTextColor = Color(0xFF111B21)
private val WaInputText = Color(0xFF111B21)

private enum class ChatOverflowAction {
    LIVE_SEND,
    VIEW_PROFILE,
    BLOCK_USER,
    CLEAR_CHAT,
    SEARCH
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    userId: String,
    navController: NavController,
    viewModel: ChatViewModel = hiltViewModel()
) {
    val uiState        by viewModel.uiState.collectAsState()
    var messageText    by remember { mutableStateOf("") }
    val listState      = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val context        = LocalContext.current

    val recordingState by viewModel.voiceRecorder.state.collectAsState()
    val playbackState by viewModel.voiceNotePlayer.state.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) viewModel.voiceRecorder.startRecording()
    }

    var selectedCategory by remember { mutableStateOf<AttachmentCategory?>(null) }
    val attachmentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        selectedCategory?.let { category ->
            if (uris.isNotEmpty()) viewModel.sendAttachments(uris, category)
        }
    }

    var showAttachmentDialog  by remember { mutableStateOf(false) }
    var selectedMessage       by remember { mutableStateOf<Message?>(null) }
    var showMessageOptions    by remember { mutableStateOf(false) }
    var replyToMessage        by remember { mutableStateOf<Message?>(null) }
    var editingMessage        by remember { mutableStateOf<Message?>(null) }

    var showOverflowMenu      by remember { mutableStateOf(false) }
    var showBlockConfirmDialog by remember { mutableStateOf(false) }
    var showClearChatDialog   by remember { mutableStateOf(false) }

    var isSearchActive       by remember { mutableStateOf(false) }
    var searchQuery          by remember { mutableStateOf("") }

    LaunchedEffect(userId) { viewModel.loadChat(userId) }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            viewModel.clearError()
        }
    }

    LaunchedEffect(messageText) {
        if (messageText.isNotBlank()) {
            viewModel.sendTyping(true)
            kotlinx.coroutines.delay(3000)
            viewModel.sendTyping(false)
        } else {
            viewModel.sendTyping(false)
        }
    }

    val filteredMessages = remember(uiState.messages, searchQuery) {
        if (searchQuery.isBlank()) uiState.messages
        else uiState.messages.filter { it.content?.contains(searchQuery, ignoreCase = true) == true }
    }

    val groupedMessages = remember(filteredMessages) {
        groupMessagesByDate(filteredMessages)
    }

    var isFirstLoad by remember(userId) { mutableStateOf(true) }
    // True right after "Load earlier messages": the list grows at the top, so do not jump to the bottom.
    var loadingOlder by remember(userId) { mutableStateOf(false) }
    val showLoadEarlier = uiState.hasMoreMessages && searchQuery.isBlank()
    LaunchedEffect(groupedMessages) {
        if (loadingOlder) {
            loadingOlder = false
            return@LaunchedEffect
        }
        if (groupedMessages.isNotEmpty() && !isSearchActive) {
            val totalItems = groupedMessages.sumOf { it.second.size + 1 } + (if (showLoadEarlier) 1 else 0)
            if (totalItems > 0) {
                if (isFirstLoad) {
                    listState.scrollToItem(totalItems - 1)
                    isFirstLoad = false
                } else {
                    coroutineScope.launch {
                        listState.animateScrollToItem(totalItems - 1)
                    }
                }
            }
        }
    }

    Scaffold(
        containerColor = WaChatBg,
        topBar = {
            Surface(color = WaDarkGreen, shadowElevation = 4.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .height(56.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }

                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(WaTeal)
                            .clickable {
                                navController.navigate(Screen.UserProfile.createRoute(userId))
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        val profileImage = uiState.user?.profileImage
                        val imageModel = remember(profileImage) {
                            if (profileImage.isNullOrEmpty()) {
                                null
                            } else if (profileImage.startsWith("content://") || profileImage.startsWith("file://")) {
                                profileImage
                            } else {
                                try {
                                    android.util.Base64.decode(profileImage, android.util.Base64.DEFAULT)
                                } catch (e: Exception) {
                                    null
                                }
                            }
                        }

                        if (imageModel as Any? != null) {
                            AsyncImage(
                                model = imageModel!!,
                                contentDescription = "Profile Image",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Text(
                                text = uiState.user?.deviceName
                                    ?.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                                color    = Color.White,
                                fontSize = 18.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    if (isSearchActive) {
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Search messages...", color = Color.White.copy(alpha = 0.7f)) },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                cursorColor = Color.White,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            singleLine = true,
                            trailingIcon = {
                                IconButton(onClick = {
                                    isSearchActive = false
                                    searchQuery = ""
                                }) {
                                    Icon(Icons.Default.Close, contentDescription = "Close search", tint = Color.White)
                                }
                            }
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    navController.navigate(Screen.UserProfile.createRoute(userId))
                                }
                        ) {
                            Text(
                                text = uiState.user?.deviceName ?: "Loading…",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White
                            )
                            Text(
                                text = when {
                                    uiState.isTyping -> "typing…"
                                    uiState.user?.isOnline == true -> "online"
                                    else -> "offline"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 12.sp
                            )
                        }

                        IconButton(
                            onClick = { navController.navigate(Screen.Call.createRoute(userId, "VIDEO")) },
                            enabled = uiState.user?.isOnline == true
                        ) {
                            Icon(
                                Icons.Default.Videocam,
                                contentDescription = "Video call",
                                tint = if (uiState.user?.isOnline == true)
                                    Color.White else Color.White.copy(alpha = 0.4f)
                            )
                        }

                        IconButton(
                            onClick = { navController.navigate(Screen.Call.createRoute(userId, "AUDIO")) },
                            enabled = uiState.user?.isOnline == true
                        ) {
                            Icon(
                                Icons.Default.Phone,
                                contentDescription = "Voice call",
                                tint = if (uiState.user?.isOnline == true)
                                    Color.White else Color.White.copy(alpha = 0.4f)
                            )
                        }
                    }

                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "More options",
                                tint = Color.White
                            )
                        }

                        ChatOverflowMenu(
                            expanded          = showOverflowMenu,
                            isUserBlocked     = uiState.isUserBlocked,
                            onDismiss         = { showOverflowMenu = false },
                            onAction          = { action ->
                                showOverflowMenu = false
                                when (action) {
                                    ChatOverflowAction.LIVE_SEND -> {
                                        navController.navigate(Screen.FileManager.createRoute(userId))
                                    }
                                    ChatOverflowAction.VIEW_PROFILE -> {
                                        navController.navigate(
                                            Screen.UserProfile.createRoute(userId)
                                        )
                                    }
                                    ChatOverflowAction.BLOCK_USER -> {
                                        showBlockConfirmDialog = true
                                    }
                                    ChatOverflowAction.CLEAR_CHAT -> {
                                        showClearChatDialog = true
                                    }
                                    ChatOverflowAction.SEARCH -> {
                                        isSearchActive = true
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state               = listState,
                contentPadding      = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (showLoadEarlier) {
                    item(key = "load_earlier") {
                        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            TextButton(onClick = {
                                loadingOlder = true
                                viewModel.loadMoreMessages()
                            }) { Text("Load earlier messages") }
                        }
                    }
                }
                groupedMessages.forEach { (dateLabel, msgs) ->
                    item(key = "date_$dateLabel") {
                        DateSeparator(label = dateLabel)
                    }
                    items(msgs, key = { it.id }) { message ->
                        val transfer = uiState.transfers[message.id]
                        MessageBubble(
                            message      = message,
                            isOutgoing   = message.senderId == uiState.myDeviceId,
                            replyContent = message.replyToContent,
                            onLongClick  = {
                                selectedMessage    = message
                                showMessageOptions = true
                            },
                            onMediaTap = { path, mimeType ->
                                MediaUtils.openFile(context, path, mimeType)
                            },
                            audioProgress = if (playbackState.messageId == message.id) playbackState.progress else 0f,
                            isAudioPlaying = playbackState.isPlaying && playbackState.messageId == message.id,
                            onAudioPlayPause = {
                                message.mediaPath?.let { path ->
                                    val uri = when {
                                        path.startsWith("content://") -> Uri.parse(path)
                                        path.startsWith("file://")    -> Uri.parse(path)
                                        else                          -> Uri.fromFile(File(path))
                                    }
                                    viewModel.voiceNotePlayer.play(message.id, uri)
                                }
                            },
                            onAudioSeek = { viewModel.voiceNotePlayer.seekTo(it) },
                            transfer = transfer,
                            onAcceptTransfer = { transfer?.let { viewModel.acceptTransfer(it.id) } },
                            onRejectTransfer = { transfer?.let { viewModel.rejectTransfer(it.id) } },
                            onCancelTransfer = { transfer?.let { viewModel.cancelTransfer(it.id) } }
                        )
                    }
                }
            }

            replyToMessage?.let { reply ->
                ReplyPreviewBar(message = reply, onCancel = { replyToMessage = null })
            }

            editingMessage?.let { editing ->
                EditPreviewBar(
                    message  = editing,
                    onCancel = { editingMessage = null; messageText = "" }
                )
            }

            WaInputBar(
                text              = messageText,
                onTextChange      = { messageText = it },
                isRecording       = recordingState.isRecording,
                recordingDuration = recordingState.durationMs,
                onCancelRecording = { viewModel.voiceRecorder.cancelRecording() },
                onSend = {
                    if (messageText.isBlank()) {
                        if (recordingState.isRecording) {
                            val file = viewModel.voiceRecorder.stopRecording()
                            if (file != null) {
                                viewModel.sendVoiceNote(file, recordingState.durationMs)
                            }
                        } else {
                            val permission = Manifest.permission.RECORD_AUDIO
                            if (ContextCompat.checkSelfPermission(
                                    context, permission
                                ) == PackageManager.PERMISSION_GRANTED
                            ) {
                                viewModel.voiceRecorder.startRecording()
                            } else {
                                permissionLauncher.launch(permission)
                            }
                        }
                    } else {
                        val trimmed = messageText.trim()
                        if (trimmed.isNotBlank()) {
                            if (editingMessage != null) {
                                viewModel.editMessage(editingMessage!!.id, trimmed)
                                editingMessage = null
                            } else {
                                viewModel.sendMessage(trimmed, replyToMessage?.id)
                                replyToMessage = null
                            }
                            messageText = ""
                        }
                    }
                },
                onAttach = { showAttachmentDialog = true }
            )
        }
    }

    if (showAttachmentDialog) {
        AttachmentDialog(
            onDismiss          = { showAttachmentDialog = false },
            onCategorySelected = { category ->
                selectedCategory     = category
                showAttachmentDialog = false
                attachmentLauncher.launch(ChatAttachmentHelper.categoryMime(category))
            }
        )
    }

    if (showMessageOptions && selectedMessage != null) {
        MessageOptionsDialog(
            message             = selectedMessage!!,
            isOutgoing          = selectedMessage!!.senderId == uiState.myDeviceId,
            onDismiss           = { showMessageOptions = false; selectedMessage = null },
            onReply = {
                replyToMessage     = selectedMessage
                showMessageOptions = false
                selectedMessage    = null
            },
            onEdit = {
                editingMessage     = selectedMessage
                messageText        = selectedMessage?.content ?: ""
                showMessageOptions = false
                selectedMessage    = null
            },
            onDeleteForMe = {
                viewModel.deleteMessage(selectedMessage!!.id, false)
                showMessageOptions = false
                selectedMessage    = null
            },
            onDeleteForEveryone = {
                viewModel.deleteMessage(selectedMessage!!.id, true)
                showMessageOptions = false
                selectedMessage    = null
            },
            onCopy = { showMessageOptions = false; selectedMessage = null }
        )
    }

    if (showBlockConfirmDialog) {
        val isBlocked = uiState.isUserBlocked
        AlertDialog(
            onDismissRequest = { showBlockConfirmDialog = false },
            containerColor   = Color.White,
            shape            = RoundedCornerShape(12.dp),
            title = {
                Text(
                    text  = if (isBlocked) "Unblock User" else "Block User",
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Text(
                    text = if (isBlocked)
                        "Unblock ${uiState.user?.deviceName ?: "this user"}? They will be able to send you messages again."
                    else
                        "Block ${uiState.user?.deviceName ?: "this user"}? They won't be able to send you messages.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (isBlocked) viewModel.unblockUser(userId)
                        else viewModel.blockUser(userId)
                        showBlockConfirmDialog = false
                    }
                ) {
                    Text(
                        text  = if (isBlocked) "Unblock" else "Block",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showBlockConfirmDialog = false }) {
                    Text("Cancel", color = WaTeal)
                }
            }
        )
    }

    if (showClearChatDialog) {
        AlertDialog(
            onDismissRequest = { showClearChatDialog = false },
            containerColor   = Color.White,
            shape            = RoundedCornerShape(12.dp),
            title = {
                Text("Clear Chat", style = MaterialTheme.typography.titleMedium)
            },
            text = {
                Text(
                    "All messages in this chat will be deleted from your device. This cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearChat(userId)
                        showClearChatDialog = false
                    }
                ) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearChatDialog = false }) {
                    Text("Cancel", color = WaTeal)
                }
            }
        )
    }
}

@Composable
private fun ChatOverflowMenu(
    expanded: Boolean,
    isUserBlocked: Boolean,
    onDismiss: () -> Unit,
    onAction: (ChatOverflowAction) -> Unit
) {
    DropdownMenu(
        expanded         = expanded,
        onDismissRequest = onDismiss,
        modifier         = Modifier.background(Color.White)
    ) {
        DropdownMenuItem(
            leadingIcon = { Icon(Icons.Default.AttachFile, null, tint = WaTeal) },
            text    = { Text("Live Send", style = MaterialTheme.typography.bodyMedium) },
            onClick = { onAction(ChatOverflowAction.LIVE_SEND) }
        )
        HorizontalDivider(color = Color(0xFFE0E0E0), thickness = 0.5.dp)
        DropdownMenuItem(
            leadingIcon = { Icon(Icons.Default.Person, null, tint = WaTeal) },
            text    = { Text("View Profile", style = MaterialTheme.typography.bodyMedium) },
            onClick = { onAction(ChatOverflowAction.VIEW_PROFILE) }
        )
        DropdownMenuItem(
            leadingIcon = { Icon(Icons.Default.Search, null, tint = WaTeal) },
            text    = { Text("Search", style = MaterialTheme.typography.bodyMedium) },
            onClick = { onAction(ChatOverflowAction.SEARCH) }
        )
        HorizontalDivider(color = Color(0xFFE0E0E0), thickness = 0.5.dp)
        DropdownMenuItem(
            leadingIcon = { Icon(Icons.Default.DeleteOutline, null, tint = Color(0xFFE53935)) },
            text = { Text("Clear Chat", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFE53935)) },
            onClick = { onAction(ChatOverflowAction.CLEAR_CHAT) }
        )
        DropdownMenuItem(
            leadingIcon = { Icon(if (isUserBlocked) Icons.Default.LockOpen else Icons.Default.Block, null, tint = Color(0xFFE53935)) },
            text = { Text(if (isUserBlocked) "Unblock User" else "Block User", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFE53935)) },
            onClick = { onAction(ChatOverflowAction.BLOCK_USER) }
        )
    }
}

@Composable
private fun DateSeparator(label: String) {
    Box(
        modifier         = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(color = Color(0xFFE1F3FB), shape = RoundedCornerShape(8.dp), shadowElevation = 1.dp) {
            Text(
                text     = label,
                style    = MaterialTheme.typography.labelSmall,
                color    = Color(0xFF54656F),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun ReplyPreviewBar(message: Message, onCancel: () -> Unit) {
    Surface(color = Color.White, shadowElevation = 2.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.width(3.dp).height(36.dp).background(WaTeal))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Replying to message", style = MaterialTheme.typography.labelSmall, color = WaTeal)
                Text(
                    message.content ?: "",
                    style    = MaterialTheme.typography.bodySmall,
                    color    = WaTimestamp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, null, tint = WaTimestamp, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun EditPreviewBar(message: Message, onCancel: () -> Unit) {
    Surface(color = Color(0xFFF0F8FF), shadowElevation = 2.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Edit, null, tint = WaTeal, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Edit message", style = MaterialTheme.typography.labelSmall, color = WaTeal)
                Text(
                    message.content ?: "",
                    style    = MaterialTheme.typography.bodySmall,
                    color    = WaTimestamp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, null, tint = WaTimestamp, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun WaInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onAttach: () -> Unit,
    isRecording: Boolean = false,
    recordingDuration: Long = 0L,
    onCancelRecording: () -> Unit = {}
) {
    var showEmojiPicker by remember { mutableStateOf(false) }

    Surface(color = Color(0xFFF0F2F5)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            Surface(
                modifier        = Modifier.weight(1f),
                color           = Color.White,
                shape           = RoundedCornerShape(24.dp),
                shadowElevation = 1.dp
            ) {
                if (isRecording) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Mic, null, tint = Color.Red, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = formatDuration(recordingDuration), style = MaterialTheme.typography.bodyMedium, color = Color.Red)
                        Spacer(modifier = Modifier.weight(1f))
                        TextButton(onClick = onCancelRecording) { Text("Cancel", color = WaTeal) }
                    }
                } else {
                    Row(
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.Bottom
                    ) {
                        IconButton(onClick = onAttach, modifier = Modifier.size(44.dp).padding(bottom = 2.dp)) {
                            Icon(Icons.Default.AttachFile, null, tint = WaTimestamp, modifier = Modifier.size(22.dp))
                        }
                        TextField(
                            value         = text,
                            onValueChange = onTextChange,
                            modifier      = Modifier.weight(1f),
                            placeholder   = {
                                Text("Message", color = WaTimestamp, style = MaterialTheme.typography.bodyMedium)
                            },
                            textStyle = TextStyle(color = WaInputText, fontSize = 16.sp),
                            colors    = TextFieldDefaults.colors(
                                focusedContainerColor   = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor   = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor  = Color.Transparent,
                                cursorColor             = WaTeal,
                                focusedTextColor        = WaInputText,
                                unfocusedTextColor      = WaInputText
                            ),
                            maxLines        = 5,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
                        )
                        IconButton(onClick = { showEmojiPicker = true }, modifier = Modifier.size(44.dp).padding(bottom = 2.dp)) {
                            Icon(Icons.Default.EmojiEmotions, null, tint = WaTimestamp, modifier = Modifier.size(22.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            FloatingActionButton(
                onClick        = onSend,
                modifier       = Modifier.size(48.dp),
                containerColor = WaGreen,
                contentColor   = Color.White,
                elevation      = FloatingActionButtonDefaults.elevation(2.dp)
            ) {
                Icon(
                    imageVector = when {
                        text.isBlank() && !isRecording -> Icons.Default.Mic
                        isRecording                    -> Icons.Default.Stop
                        else                           -> Icons.Default.Send
                    },
                    contentDescription = null,
                    modifier           = Modifier.size(22.dp)
                )
            }
        }
    }

    if (showEmojiPicker) {
        EmojiPicker(
            onEmojiSelected = { emoji -> onTextChange(text + emoji) },
            onDismiss       = { showEmojiPicker = false }
        )
    }
}

@Composable
private fun EmojiPicker(
    onEmojiSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val emojis = remember {
        listOf(
            "😀","😃","😄","😁","😆","😅","🤣","😂","🙂","🙃","😉","😊",
            "😇","🥰","😍","🤩","😘","😗","😚","😙","🥲","😋","😛","😜",
            "🤪","😝","🤑","🤗","🤭","🤫","🤔","🤐","🤨","😐","😑","😶",
            "😏","😒","🙄","😬","🤥","😌","😔","😪","🤤","😴","😷","🤒",
            "❤️","🧡","💛","💚","💙","💜","🖤","🤍","🤎","💔","❣️","💕",
            "💞","💓","💗","💖","💘","💝","💟",
            "👍","👎","👌","✌️","🤞","🤟","🤘","🤙","👈","👉","👆","👇",
            "☝️","✋","🤚","🖐️","🖖","👋","🤝","🙏","✍️","💪",
            "🎉","🎊","🎈","🎁","🏆","🥇","🥈","🥉","⭐","🌟","✨","💫",
            "🔥","💯","✅","❌","⚠️","📱","💻","⌚","📷","🎵","🎶","🎤"
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton    = {},
        dismissButton    = { TextButton(onClick = onDismiss) { Text("Close", color = WaTeal) } },
        title            = { Text("Select Emoji", style = MaterialTheme.typography.titleMedium) },
        text = {
            LazyVerticalGrid(
                columns        = GridCells.Fixed(8),
                modifier       = Modifier.fillMaxWidth().height(300.dp),
                contentPadding = PaddingValues(4.dp)
            ) {
                items(emojis) { emoji ->
                    TextButton(
                        onClick        = { onEmojiSelected(emoji); onDismiss() },
                        modifier       = Modifier.size(40.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(text = emoji, fontSize = 20.sp, maxLines = 1)
                    }
                }
            }
        }
    )
}

@Composable
private fun AttachmentDialog(
    onDismiss: () -> Unit,
    onCategorySelected: (AttachmentCategory) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton    = {},
        dismissButton    = { TextButton(onClick = onDismiss) { Text("Cancel", color = WaTeal) } },
        title            = { Text("Share") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                AttachmentOption(Icons.Default.Image,       "Gallery")  { onCategorySelected(AttachmentCategory.IMAGE) }
                AttachmentOption(Icons.Default.Videocam,    "Video")    { onCategorySelected(AttachmentCategory.VIDEO) }
                AttachmentOption(Icons.Default.Audiotrack,  "Audio")    { onCategorySelected(AttachmentCategory.AUDIO) }
                AttachmentOption(Icons.Default.Description, "Document") { onCategorySelected(AttachmentCategory.DOCUMENT) }
            }
        }
    )
}

@Composable
private fun AttachmentOption(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = WaTeal)
            Spacer(modifier = Modifier.width(16.dp))
            Text(label, color = Color.Black)
        }
    }
}

@Composable
private fun MessageOptionsDialog(
    message: Message,
    isOutgoing: Boolean,
    onDismiss: () -> Unit,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onDeleteForMe: () -> Unit,
    onDeleteForEveryone: () -> Unit,
    onCopy: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = Color.White,
        shape            = RoundedCornerShape(12.dp),
        title            = null,
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OptionItem(Icons.Default.Reply, "Reply", onClick = onReply)
                if (!message.isDeleted && message.messageType == MessageType.TEXT) {
                    OptionItem(
                        icon  = Icons.Default.ContentCopy,
                        label = "Copy",
                        onClick = {
                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("Message", message.content ?: "")
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                            onCopy()
                        }
                    )
                }
                if (isOutgoing && !message.isDeleted && message.messageType == MessageType.TEXT) {
                    OptionItem(Icons.Default.Edit, "Edit", onClick = onEdit)
                }
                if (isOutgoing && !message.isDeleted) {
                    OptionItem(
                        icon    = Icons.Default.DeleteOutline,
                        label   = "Delete for everyone",
                        tint    = MaterialTheme.colorScheme.error,
                        onClick = onDeleteForEveryone
                    )
                }
                OptionItem(
                    icon    = Icons.Default.DeleteOutline,
                    label   = "Delete for me",
                    tint    = MaterialTheme.colorScheme.error,
                    onClick = onDeleteForMe
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = WaTeal) }
        }
    )
}

@Composable
private fun OptionItem(
    icon: ImageVector,
    label: String,
    tint: Color = Color(0xFF3B4A54),
    onClick: () -> Unit
) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(16.dp))
            Text(label, color = tint, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private fun formatDuration(ms: Long): String {
    val seconds = (ms / 1000) % 60
    val minutes = (ms / (1000 * 60)) % 60
    return String.format("%02d:%02d", minutes, seconds)
}

private fun groupMessagesByDate(messages: List<Message>): List<Pair<String, List<Message>>> {
    if (messages.isEmpty()) return emptyList()
    val keyFmt     = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    val displayFmt = SimpleDateFormat("MMMM d, yyyy", Locale.getDefault())
    val today      = keyFmt.format(Date())
    val yesterday  = keyFmt.format(Date(System.currentTimeMillis() - 86_400_000L))
    return messages
        .groupBy { keyFmt.format(Date(it.createdAt)) }
        .entries
        .sortedBy { it.key }
        .map { (key, msgs) ->
            val label = when (key) {
                today     -> "Today"
                yesterday -> "Yesterday"
                else      -> displayFmt.format(keyFmt.parse(key) ?: Date())
            }
            label to msgs.sortedBy { it.createdAt }
        }
}
