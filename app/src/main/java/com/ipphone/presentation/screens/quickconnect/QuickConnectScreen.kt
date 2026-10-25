package com.ipphone.presentation.screens.quickconnect

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.ipphone.network.wifi.QuickConnectState
import com.ipphone.presentation.navigation.Screen
import com.ipphone.utils.QrCode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickConnectScreen(
    navController: NavController,
    viewModel: QuickConnectViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var scannerMessage by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Quick Connect") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Connect two phones without internet, router or Settings. " +
                    "One phone shows a QR code, the other scans it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            when (val s = state) {
                is QuickConnectState.HostActive -> HostCard(s, onStop = viewModel::stopHost)

                is QuickConnectState.HostStarting -> StatusCard(
                    title = "Starting private Wi-Fi…",
                    subtitle = "This takes a few seconds.",
                    busy = true
                )

                is QuickConnectState.Joining -> StatusCard(
                    title = "Joining “${s.ssid}”…",
                    subtitle = "Tap “Connect” if Android asks for permission.",
                    busy = true
                )

                is QuickConnectState.Joined -> {
                    StatusCard(
                        title = "Connected to “${s.ssid}”",
                        subtitle = s.hostAddress?.let { "Other phone: $it" } ?: "Looking for the other phone…",
                        busy = false
                    )
                    Button(
                        onClick = {
                            navController.navigate(Screen.Nearby.route) { launchSingleTop = true }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Radar, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Find devices")
                    }
                    OutlinedButton(onClick = viewModel::leave, modifier = Modifier.fillMaxWidth()) {
                        Text("Disconnect")
                    }
                }

                is QuickConnectState.Error -> {
                    StatusCard(title = "Something went wrong", subtitle = s.message, busy = false, isError = true)
                    OutlinedButton(onClick = viewModel::reset, modifier = Modifier.fillMaxWidth()) {
                        Text("Try again")
                    }
                }

                QuickConnectState.Idle -> Unit
            }

            if (state is QuickConnectState.Idle) {
                ActionCard(
                    icon = Icons.Default.QrCode2,
                    title = "Show QR code",
                    subtitle = "This phone creates a private Wi-Fi for the other phone to join.",
                    button = "Create & show QR",
                    onClick = viewModel::startHost
                )
                ActionCard(
                    icon = Icons.Default.QrCodeScanner,
                    title = "Scan QR code",
                    subtitle = "Scan the QR shown on the other phone to join its Wi-Fi.",
                    button = "Scan",
                    onClick = {
                        scannerMessage = null
                        viewModel.scanAndJoin(context) {
                            scannerMessage = "The built-in scanner is not available. " +
                                "Open your phone's Camera app and scan the QR code — it can join the Wi-Fi directly."
                        }
                    }
                )
            }

            scannerMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            Text(
                "Tip: Location must be ON for the phone that shows the QR code (Android requirement for hotspots).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun HostCard(state: QuickConnectState.HostActive, onStop: () -> Unit) {
    val bitmap = remember(state.qrPayload) { QrCode.encode(state.qrPayload).asImageBitmap() }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Scan this on the other phone", style = MaterialTheme.typography.titleMedium)
            Image(
                bitmap = bitmap,
                contentDescription = "Wi-Fi QR code",
                modifier = Modifier
                    .size(260.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White)
                    .padding(8.dp)
            )
            SelectionContainer {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Wi-Fi: ${state.ssid}", fontWeight = FontWeight.Medium)
                    Text(
                        "Password: ${state.password.ifEmpty { "(none)" }}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            Text(
                "Keep this screen open until the other phone is connected.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            OutlinedButton(onClick = onStop) { Text("Stop sharing") }
        }
    }
}

@Composable
private fun StatusCard(title: String, subtitle: String, busy: Boolean, isError: Boolean = false) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) MaterialTheme.colorScheme.errorContainer
                             else MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
            } else {
                Icon(
                    if (isError) Icons.Default.ErrorOutline else Icons.Default.CheckCircle,
                    contentDescription = null
                )
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ActionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    button: String,
    onClick: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(button) }
        }
    }
}
