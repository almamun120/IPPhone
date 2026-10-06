package com.ipphone.presentation.screens.calllogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.ipphone.domain.model.CallDirection
import com.ipphone.domain.model.CallLog
import com.ipphone.domain.model.CallStatus
import com.ipphone.domain.model.CallType
import com.ipphone.presentation.navigation.Screen
import com.ipphone.utils.toFormattedDuration
import com.ipphone.utils.toRelativeTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallLogsScreen(
    navController: NavController,
    viewModel: CallLogsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Call History") },
                actions = {
                    if (uiState.callLogs.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clearAllCallLogs() }) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Clear All")
                        }
                    }
                }
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
                
                uiState.callLogs.isEmpty() -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.History,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No Call History",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Your call history will appear here",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                else -> {
                    val logs = remember(uiState.callLogs) {
                        uiState.callLogs.distinctBy { "${it.id}_${it.createdAt}" }
                    }
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(
                            items = logs,
                            key = { "${it.id}_${it.createdAt}" }
                        ) { callLog ->
                            CallLogItem(
                                callLog = callLog,
                                onClick = {
                                    navController.navigate(
                                        Screen.Call.createRoute(
                                            callLog.peerId,
                                            callLog.callType.name
                                        )
                                    )
                                },
                                onDelete = { viewModel.deleteCallLog(callLog.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallLogItem(
    callLog: CallLog,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            Surface(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            // Call Info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = callLog.peerName,
                    style = MaterialTheme.typography.titleMedium
                )
                
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Direction icon
                    Icon(
                        imageVector = when {
                            callLog.status == CallStatus.MISSED -> Icons.Default.CallMissed
                            callLog.direction == CallDirection.INCOMING -> Icons.Default.CallReceived
                            else -> Icons.Default.CallMade
                        },
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = when (callLog.status) {
                            CallStatus.MISSED -> Color.Red
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    
                    Spacer(modifier = Modifier.width(4.dp))
                    
                    // Call type icon
                    Icon(
                        imageVector = if (callLog.callType == CallType.VIDEO) 
                            Icons.Default.Videocam else Icons.Default.Phone,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    // Time
                    Text(
                        text = callLog.createdAt.toRelativeTime(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    // Duration (if connected)
                    if (callLog.duration > 0) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "(${(callLog.duration * 1000).toFormattedDuration()})",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            // Call again button
            IconButton(onClick = onClick) {
                Icon(
                    imageVector = if (callLog.callType == CallType.VIDEO) 
                        Icons.Default.Videocam else Icons.Default.Phone,
                    contentDescription = "Call",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
    
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Call Log") },
            text = { Text("Are you sure you want to delete this call log?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete()
                        showDeleteDialog = false
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
