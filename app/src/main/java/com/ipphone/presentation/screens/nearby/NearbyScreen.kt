package com.ipphone.presentation.screens.nearby

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import com.ipphone.presentation.navigation.Screen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.ipphone.presentation.components.NearbyDeviceItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyScreen(
    navController: NavController,
    viewModel: NearbyViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nearby Devices") },
                actions = {
                    IconButton(onClick = { navController.navigate(Screen.QuickConnect.route) }) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = "Quick Connect (QR)")
                    }
                    if (uiState.isScanning) {
                        IconButton(onClick = { viewModel.stopScan() }) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop Scan")
                        }
                    } else {
                        IconButton(onClick = { viewModel.startScan() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Start Scan")
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
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Scanning progress
                if (uiState.isScanning) {
                    LinearProgressIndicator(
                        progress = uiState.scanProgress,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                
                when {
                    uiState.nearbyDevices.isEmpty() && !uiState.isScanning -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "No Devices Found",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Tap the refresh icon to scan for nearby devices on your network.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(onClick = { viewModel.startScan() }) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Scan for Devices")
                            }
                        }
                    }
                    
                    else -> {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(
                                items = uiState.nearbyDevices,
                                key = { it.deviceId }
                            ) { device ->
                                NearbyDeviceItem(
                                    deviceName = device.deviceName,
                                    deviceId = device.deviceId,
                                    profileImage = device.profileImage,
                                    onChatClick = {
                                        viewModel.onDeviceClicked(device)
                                        navController.navigate(Screen.Chat.createRoute(device.deviceId))
                                    },
                                    onAudioCallClick = {
                                        viewModel.onDeviceClicked(device)
                                        navController.navigate(Screen.Call.createRoute(device.deviceId, "AUDIO"))
                                    },
                                    onVideoCallClick = {
                                        viewModel.onDeviceClicked(device)
                                        navController.navigate(Screen.Call.createRoute(device.deviceId, "VIDEO"))
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

data class NearbyDevice(
    val deviceId: String,
    val deviceName: String,
    val profileImage: String?,
    val ipAddress: String,
    val port: Int
)

