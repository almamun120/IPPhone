package com.ipphone.presentation.screens.paired

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.ipphone.domain.model.User
import com.ipphone.presentation.components.PairedDeviceItem
import com.ipphone.presentation.navigation.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairedScreen(
    navController: NavController,
    viewModel: PairedViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedUser by remember { mutableStateOf<User?>(null) }
    var showOptionsDialog by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recent Chats") }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                uiState.isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                
                uiState.pairedUsers.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "No Recent Chats",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Start a chat from Dialpad or Nearby tab",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                else -> {
                    val users = remember(uiState.pairedUsers) {
                        uiState.pairedUsers.distinctBy { it.uuid }
                    }
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(
                            items = users,
                            key = { it.uuid }
                        ) { user ->
                            PairedDeviceItem(
                                user = user,
                                lastMessage = uiState.lastMessages[user.uuid],
                                isTyping = uiState.typingUsers[user.uuid] ?: false,
                                onClick = {
                                    navController.navigate(Screen.Chat.createRoute(user.uuid))
                                },
                                onLongClick = {
                                    selectedUser = user
                                    showOptionsDialog = true
                                },
                                onFileShareClick = {
                                    navController.navigate(Screen.FileManager.createRoute(user.uuid))
                                },
                                onAudioCallClick = {
                                    navController.navigate(Screen.Call.createRoute(user.uuid, "AUDIO"))
                                },
                                onVideoCallClick = {
                                    navController.navigate(Screen.Call.createRoute(user.uuid, "VIDEO"))
                                }
                            )
                        }
                    }
                }
            }
        }
    }
    
    // Options Dialog
    if (showOptionsDialog && selectedUser != null) {
        AlertDialog(
            onDismissRequest = { showOptionsDialog = false },
            title = { Text(selectedUser!!.deviceName) },
            text = {
                Column {
                    TextButton(
                        onClick = {
                            viewModel.deleteUser(selectedUser!!.uuid)
                            showOptionsDialog = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Delete Chat")
                    }
                    TextButton(
                        onClick = {
                            viewModel.blockUser(selectedUser!!.uuid)
                            showOptionsDialog = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Block", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(
                        onClick = {
                            viewModel.clearChatHistory(selectedUser!!.uuid)
                            showOptionsDialog = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Clear Chat History")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showOptionsDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
