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
import com.ipphone.network.wifidirect.P2pRole
import com.ipphone.presentation.navigation.Screen

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
    val role by viewModel.role.collectAsState()
    val group by viewModel.group.collectAsState()
    val message by viewModel.message.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.discoverPeers()
    }

    val info = connectionInfo
    val connected = info?.groupFormed == true
    val iAmOwner = connected && info?.isGroupOwner == true

    // Group Owners first: those are the phones a Client should connect to.
    val sortedPeers = peers.sortedWith(
        compareByDescending<WifiP2pDevice> { it.isGroupOwner }.thenBy { it.deviceName.lowercase() }
    )

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
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!isWifiP2pEnabled) {
                item {
                    Text(
                        "Wi-Fi P2P is disabled. Please enable Wi-Fi.",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            if (!locationEnabled) {
                item {
                    Text(
                        "Location services are disabled. Wi-Fi Direct needs Location to be ON.",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            // ── Role picker ────────────────────────────────────────────────
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("This phone should be…", style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = role == P2pRole.GROUP_OWNER,
                                onClick = { viewModel.setRole(P2pRole.GROUP_OWNER) },
                                label = { Text("Group Owner") }
                            )
                            FilterChip(
                                selected = role == P2pRole.CLIENT,
                                onClick = { viewModel.setRole(P2pRole.CLIENT) },
                                label = { Text("Client") }
                            )
                            FilterChip(
                                selected = role == P2pRole.AUTO,
                                onClick = { viewModel.setRole(P2pRole.AUTO) },
                                label = { Text("Auto") }
                            )
                        }
                        Text(
                            text = when (role) {
                                P2pRole.GROUP_OWNER ->
                                    "Creates the group. Other phones connect to this one. Use it on the phone that should stay in the middle."
                                P2pRole.CLIENT ->
                                    "Joins a group made by another phone and never becomes the owner. The other phone must be set to Group Owner."
                                P2pRole.AUTO ->
                                    "Android picks the owner at random — you cannot tell who it will be."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        when (role) {
                            P2pRole.GROUP_OWNER -> Button(
                                onClick = { viewModel.startAsGroupOwner() },
                                enabled = !iAmOwner,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text(if (iAmOwner) "Group is running" else "Create group (I am the Group Owner)") }

                            P2pRole.CLIENT -> Button(
                                onClick = { viewModel.startAsClient() },
                                enabled = !(connected && !iAmOwner),
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Search for Group Owners") }

                            P2pRole.AUTO -> Unit
                        }
                    }
                }
            }

            message?.let {
                item {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // ── Current connection ─────────────────────────────────────────
            if (info != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (!connected) {
                                Text("Connecting…", style = MaterialTheme.typography.titleMedium)
                            } else {
                                Text(
                                    text = if (iAmOwner) "You are the Group Owner" else "You are a Client",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                info.groupOwnerAddress?.hostAddress?.let {
                                    Text("Group Owner IP: $it")
                                }
                                group?.let { g ->
                                    g.networkName?.let { Text("Network: $it") }
                                    if (iAmOwner) {
                                        g.passphrase?.let { Text("Password: $it") }
                                        val clients = g.clientList.toList()
                                        Text(
                                            if (clients.isEmpty()) "No phones connected yet"
                                            else "Connected: " + clients.joinToString { c ->
                                                c.deviceName.ifEmpty { c.deviceAddress }
                                            }
                                        )
                                    } else {
                                        g.owner?.let { Text("Owner: ${it.deviceName.ifEmpty { it.deviceAddress }}") }
                                    }
                                }
                                Text(
                                    "IPPhone looks for the other phone automatically — it will appear in Nearby.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = {
                                        navController.navigate(Screen.Nearby.route) { launchSingleTop = true }
                                    }) { Text("Open Nearby") }
                                    OutlinedButton(onClick = { viewModel.disconnect() }) { Text("Disconnect") }
                                }
                            }
                        }
                    }
                }
            }

            // ── Discovered devices ─────────────────────────────────────────
            item {
                Text(
                    text = if (isDiscovering) "Searching for devices…" else "Discovered devices",
                    style = MaterialTheme.typography.titleLarge
                )
            }

            if (sortedPeers.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isDiscovering) CircularProgressIndicator()
                        else Text("No devices found. Tap refresh to scan.")
                    }
                }
            } else {
                items(sortedPeers, key = { it.deviceAddress }) { device ->
                    PeerItem(device = device) { viewModel.connect(device) }
                }
            }
        }
    }
}

@Composable
fun PeerItem(device: WifiP2pDevice, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(device.deviceName.ifEmpty { "Unknown Device" }) },
        supportingContent = {
            Text(
                device.deviceAddress + if (device.isGroupOwner) "  ·  Group Owner" else ""
            )
        },
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
