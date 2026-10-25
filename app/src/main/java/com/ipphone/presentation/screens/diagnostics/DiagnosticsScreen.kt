package com.ipphone.presentation.screens.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.ipphone.utils.toFormattedDuration
import com.ipphone.utils.toFormattedSize
import com.ipphone.utils.toTransferSpeed
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    navController: NavController,
    viewModel: DiagnosticsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Live refresh while the screen is open.
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refresh()
            delay(2000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Diagnostics") },
                actions = {
                    IconButton(onClick = {
                        val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clip.setPrimaryClip(ClipData.newPlainText("IPPhone diagnostics", viewModel.buildReport()))
                    }) { Icon(Icons.Default.ContentCopy, contentDescription = "Copy report") }
                    IconButton(onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, viewModel.buildReport())
                        }
                        context.startActivity(Intent.createChooser(send, "Share report"))
                    }) { Icon(Icons.Default.Share, contentDescription = "Share report") }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Section("This phone") {
                    if (state.interfaces.isEmpty()) {
                        Line("Network", "no active interface")
                    } else {
                        state.interfaces.forEach { Line("Address", it) }
                    }
                    Line("TCP / UDP / data port", "${state.tcpPort} / ${state.udpPort} / ${state.dataPort}")
                    Line("Open connections", state.activeConnections.toString())
                    Line("Battery unrestricted", if (state.batteryUnrestricted) "yes" else "NO — may sleep in background")
                }
            }

            if (state.transfers.isNotEmpty()) {
                item {
                    Section("Transfers now") {
                        state.transfers.forEach {
                            Line(
                                it.transferId.take(8),
                                "${it.speedBps.toTransferSpeed()} · ${it.bytesDone.toFormattedSize()} / ${it.totalBytes.toFormattedSize()}"
                            )
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Peers (${state.peers.size})", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = viewModel::testPeers, enabled = !state.testing) {
                        Text(if (state.testing) "Testing…" else "Test speed")
                    }
                }
            }

            if (state.peers.isEmpty()) {
                item {
                    Text(
                        "No peers known yet. Open the Nearby tab to scan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(state.peers, key = { it.uuid }) { peer -> PeerCard(peer) }

            item {
                Section("App health") {
                    Line("Process uptime", (state.uptimeSeconds * 1000).toFormattedDuration())
                    Line("Process starts", state.processStarts.toString())
                    Line("Screen (activity) creates", state.activityCreates.toString())
                    Line("Crashes recorded", state.crashCount.toString())
                    if (state.exitReasons.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text("Why the app restarted before:", style = MaterialTheme.typography.labelLarge)
                        state.exitReasons.forEach {
                            Text("• $it", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            state.lastCrash?.let { crash ->
                item {
                    Section("Last crash") {
                        SelectionContainer {
                            Text(
                                crash.take(3000),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                maxLines = 40,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        TextButton(onClick = viewModel::clearCrash) { Text("Clear") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun PeerCard(peer: PeerDiag) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Circle,
                    contentDescription = null,
                    modifier = Modifier.size(10.dp),
                    tint = if (peer.online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                )
                Spacer(Modifier.width(8.dp))
                Text(peer.name, style = MaterialTheme.typography.titleSmall)
            }
            Line("Address", "${peer.ip.ifBlank { "?" }}:${peer.port}")
            Line("State", buildString {
                append(if (peer.online) "online" else "offline")
                if (peer.connected) append(" · connected")
            })
            Line(
                "Round trip",
                when (val r = peer.rttMs) {
                    null -> "—"
                    -1L -> "unreachable"
                    else -> "$r ms"
                }
            )
        }
    }
}
