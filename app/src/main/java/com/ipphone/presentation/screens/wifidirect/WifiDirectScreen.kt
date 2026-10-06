package com.ipphone.presentation.screens.wifidirect

import android.net.wifi.p2p.WifiP2pDevice
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiDirectScreen(
    navController: NavHostController,
    viewModel: WifiDirectViewModel = hiltViewModel()
) {
    val peers by viewModel.peers.collectAsState()
    val isWifiP2pEnabled by viewModel.isWifiP2pEnabled.collectAsState()
    val connectionInfo by viewModel.connectionInfo.collectAsState()
    val isDiscovering by viewModel.isDiscovering.collectAsState()
    val locationEnabled by viewModel.locationEnabled.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.discoverPeers()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Wi-Fi Direct") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.discoverPeers() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            if (!isWifiP2pEnabled) {
                Text(
                    "Wi-Fi P2P is disabled. Please enable Wi-Fi.",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            if (!locationEnabled) {
                Text(
                    "Location services are disabled. Wi-Fi Direct discovery requires Location to be ON.",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            connectionInfo?.let { info ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = if (info.groupFormed) "Connected" else "Connecting...",
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (info.groupFormed) {
                            Text("Group Owner IP: ${info.groupOwnerAddress.hostAddress}")
                            Text("Am I Group Owner: ${if (info.isGroupOwner) "Yes" else "No"}")
                            
                            Button(
                                onClick = { viewModel.disconnect() },
                                modifier = Modifier.padding(top = 8.dp)
                            ) {
                                Text("Disconnect")
                            }
                        }
                    }
                }
            }

            Text(
                text = if (isDiscovering) "Searching for devices..." else "Discovered Devices",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            if (peers.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    if (isDiscovering) {
                        CircularProgressIndicator()
                    } else {
                        Text("No devices found. Tap refresh to scan.")
                    }
                }
            } else {
                LazyColumn {
                    items(peers) { device ->
                        PeerItem(device = device) {
                            viewModel.connect(device)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PeerItem(device: WifiP2pDevice, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(device.deviceName.ifEmpty { "Unknown Device" }) },
        supportingContent = { Text(device.deviceAddress) },
        trailingContent = {
            Text(getDeviceStatus(device.status))
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

fun getDeviceStatus(deviceStatus: Int): String {
    return when (deviceStatus) {
        WifiP2pDevice.AVAILABLE -> "Available"
        WifiP2pDevice.INVITED -> "Invited"
        WifiP2pDevice.CONNECTED -> "Connected"
        WifiP2pDevice.FAILED -> "Failed"
        WifiP2pDevice.UNAVAILABLE -> "Unavailable"
        else -> "Unknown"
    }
}
