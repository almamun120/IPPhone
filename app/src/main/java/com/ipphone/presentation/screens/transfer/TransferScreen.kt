package com.ipphone.presentation.screens.transfer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.ipphone.domain.model.TransferFile
import com.ipphone.domain.model.TransferFileStatus
import com.ipphone.domain.model.TransferStatus
import com.ipphone.network.filetransfer.TransferStats
import com.ipphone.utils.toFormattedDuration
import com.ipphone.utils.toFormattedSize
import com.ipphone.utils.toTransferSpeed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferScreen(
    transferId: String,
    navController: NavController,
    viewModel: TransferViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    
    LaunchedEffect(transferId) {
        viewModel.loadTransfer(transferId)
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { 
                    Text(
                        when (uiState.transfer?.direction?.name) {
                            "OUTGOING" -> "Sending Files"
                            "INCOMING" -> "Receiving Files"
                            else -> "Transfer"
                        }
                    )
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            uiState.transfer?.let { transfer ->
                // Overall Progress
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Overall Progress",
                                style = MaterialTheme.typography.titleMedium
                            )
                            
                            // Status Icon
                            when (transfer.status) {
                                TransferStatus.COMPLETED -> {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = "Completed",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                TransferStatus.FAILED -> {
                                    Icon(
                                        Icons.Default.Error,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                                TransferStatus.ACTIVE -> {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp
                                    )
                                }
                                else -> {}
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        LinearProgressIndicator(
                            progress = { transfer.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp),
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${(transfer.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "${transfer.transferred.toFormattedSize()} / ${transfer.totalSize.toFormattedSize()}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        
                        val stats = uiState.stats
                        if (transfer.status == TransferStatus.ACTIVE) {
                            Spacer(modifier = Modifier.height(8.dp))
                            TransferStatsRow(
                                stats = stats,
                                fallbackSpeed = transfer.speed
                            )
                        } else if (transfer.status == TransferStatus.COMPLETED && stats?.finished == true) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Done in ${stats.elapsedMs.toFormattedDuration()} · avg ${stats.avgSpeedBps.toTransferSpeed()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Control Buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            when (transfer.status) {
                                TransferStatus.ACTIVE -> {
                                    OutlinedButton(
                                        onClick = { viewModel.pauseTransfer() }
                                    ) {
                                        Icon(Icons.Default.Pause, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Pause")
                                    }
                                    
                                    Button(
                                        onClick = { viewModel.cancelTransfer() },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.error
                                        )
                                    ) {
                                        Icon(Icons.Default.Cancel, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Cancel")
                                    }
                                }
                                
                                TransferStatus.PAUSED -> {
                                    Button(
                                        onClick = { viewModel.resumeTransfer() }
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Resume")
                                    }
                                    
                                    OutlinedButton(
                                        onClick = { viewModel.cancelTransfer() }
                                    ) {
                                        Icon(Icons.Default.Cancel, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Cancel")
                                    }
                                }
                                
                                TransferStatus.COMPLETED -> {
                                    Button(
                                        onClick = { navController.popBackStack() }
                                    ) {
                                        Icon(Icons.Default.Check, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Done")
                                    }
                                }
                                
                                TransferStatus.FAILED -> {
                                    Button(
                                        onClick = { viewModel.retryTransfer() }
                                    ) {
                                        Icon(Icons.Default.Refresh, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Retry")
                                    }
                                }
                                
                                else -> {}
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Files List
                Text(
                    text = "Files (${transfer.currentFile + 1}/${transfer.fileCount})",
                    style = MaterialTheme.typography.titleMedium
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                val files = remember(transfer.files) {
                    transfer.files.distinctBy { it.id }
                }
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = files,
                        key = { it.id }
                    ) { file ->
                        TransferFileItem(file = file)
                    }
                }
            }
        }
    }
}

/** Speed, average speed, ETA and elapsed time of the running transfer. */
@Composable
fun TransferStatsRow(stats: TransferStats?, fallbackSpeed: Long) {
    val speed = stats?.speedBps?.takeIf { it > 0 } ?: fallbackSpeed
    val eta = stats?.etaSeconds ?: -1L
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = speed.toTransferSpeed(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = if (eta >= 0) "ETA ${(eta * 1000).toFormattedDuration()}" else "ETA --:--",
                style = MaterialTheme.typography.bodyMedium
            )
        }
        if (stats != null) {
            Text(
                text = "Avg ${stats.avgSpeedBps.toTransferSpeed()} · Elapsed ${stats.elapsedMs.toFormattedDuration()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun TransferFileItem(
    file: TransferFile
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = when {
                        file.mimeType?.startsWith("image") == true -> Icons.Default.Image
                        file.mimeType?.startsWith("video") == true -> Icons.Default.VideoFile
                        file.mimeType?.startsWith("audio") == true -> Icons.Default.AudioFile
                        file.isDirectory -> Icons.Default.Folder
                        else -> Icons.Default.InsertDriveFile
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                
                Spacer(modifier = Modifier.width(12.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = file.fileName,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1
                    )
                    Text(
                        text = file.fileSize.toFormattedSize(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                // Status Icon
                when (file.status) {
                    TransferFileStatus.COMPLETED -> {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "Completed",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    TransferFileStatus.ACTIVE -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    TransferFileStatus.FAILED -> {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = "Failed",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    TransferFileStatus.PENDING -> {
                        Icon(
                            Icons.Default.Schedule,
                            contentDescription = "Pending",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    TransferFileStatus.SKIPPED -> {
                        Icon(
                            Icons.Default.SkipNext,
                            contentDescription = "Skipped",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            
            if (file.status == TransferFileStatus.ACTIVE) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { file.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${(file.progress * 100).toInt()}% · ${file.transferred.toFormattedSize()} / ${file.fileSize.toFormattedSize()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
