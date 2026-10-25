// app/src/main/java/com/wificalling/presentation/screens/call/IncomingCallScreen.kt
package com.ipphone.presentation.screens.call

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.ipphone.domain.model.CallType
import com.ipphone.network.calling.CallManager
import kotlinx.coroutines.delay

@Composable
fun IncomingCallScreen(
    callId: String,
    navController: NavController,
    viewModel: IncomingCallViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    var hasNavigated by remember { mutableStateOf(false) }

    fun safePop() {
        if (hasNavigated) return
        hasNavigated = true
        try {
            val popped = navController.popBackStack()
            if (!popped) {
                navController.navigate("contacts") {
                    launchSingleTop = true
                    popUpTo(0) { inclusive = true }
                }
            }
        } catch (e: Exception) {
            try {
                navController.navigate("contacts") {
                    launchSingleTop = true
                    popUpTo(0) { inclusive = true }
                }
            } catch (_: Exception) {}
        }
    }

    // ── Connected: IncomingCallScreen must remove itself so CallScreen is
    //   the only call screen on the back stack. If IncomingCallScreen stays,
    //   when CallScreen pops on call-end it reveals this screen → black screen.
    //   AppNavigation also handles this via popUpTo, but we keep this as a safety.
    LaunchedEffect(uiState.callState) {
        if (hasNavigated) return@LaunchedEffect
        if (uiState.callState is CallManager.CallState.Connected ||
            uiState.callState is CallManager.CallState.Connecting
        ) {
            hasNavigated = true
        }
    }

    // ── Terminal: shouldDismiss flag set by VM ───────────────────────────────
    LaunchedEffect(uiState.shouldDismiss) {
        if (uiState.shouldDismiss && !hasNavigated) {
            delay(1200)
            safePop()
        }
    }

    // ── Hard timeout — 2 minutes ─────────────────────────────────────────────
    LaunchedEffect(Unit) {
        delay(120_000)
        safePop()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A2E))
    ) {
        when {
            // ── Terminal: show reason then LaunchedEffect dismisses ──────────
            uiState.shouldDismiss -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.CallEnd,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = Color.Red
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = when (uiState.callEndReason) {
                            "REJECTED"          -> "Call rejected"
                            "BUSY"              -> "Line busy"
                            "No answer"         -> "No answer"
                            "Connection lost"   -> "Connection lost"
                            "Connection failed" -> "Connection failed"
                            "CANCELLED"         -> "Call cancelled"
                            else                -> "Call ended"
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White
                    )
                }
            }

            // ── Connecting / Connected: transitioning to CallScreen ──────────
            uiState.callState is CallManager.CallState.Connecting ||
                    uiState.callState is CallManager.CallState.Connected -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        if (uiState.callState is CallManager.CallState.Connected)
                            "Connected…"
                        else
                            "Connecting…",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                }
            }

            // ── Loading: call info not yet available ─────────────────────────
            uiState.peerName.isEmpty() -> {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.White
                )
            }

            // ── Normal incoming call UI ──────────────────────────────────────
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Spacer(modifier = Modifier.height(64.dp))

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val profileImage = uiState.peerImage
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

                        Surface(
                            modifier = Modifier.size(120.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                if (imageModel as Any? != null) {
                                    coil.compose.AsyncImage(
                                        model = imageModel!!,
                                        contentDescription = "Profile Image",
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.Person,
                                        contentDescription = null,
                                        modifier = Modifier.size(64.dp),
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        Text(
                            text = uiState.peerName,
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color.White
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = when (uiState.callType) {
                                CallType.VIDEO -> "Incoming video call"
                                CallType.AUDIO -> "Incoming call"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }

                    if (!uiState.isAnswering) {
                        Row(horizontalArrangement = Arrangement.spacedBy(64.dp)) {
                            FloatingActionButton(
                                onClick = { viewModel.rejectCall() },
                                containerColor = Color.Red,
                                modifier = Modifier.size(72.dp)
                            ) {
                                Icon(
                                    Icons.Default.CallEnd,
                                    contentDescription = "Reject",
                                    modifier = Modifier.size(32.dp),
                                    tint = Color.White
                                )
                            }

                            FloatingActionButton(
                                onClick = { viewModel.acceptCall() },
                                containerColor = Color(0xFF4CAF50),
                                modifier = Modifier.size(72.dp)
                            ) {
                                Icon(
                                    if (uiState.callType == CallType.VIDEO)
                                        Icons.Default.Videocam
                                    else
                                        Icons.Default.Call,
                                    contentDescription = "Accept",
                                    modifier = Modifier.size(32.dp),
                                    tint = Color.White
                                )
                            }
                        }
                    } else {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(56.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(48.dp))
                }
            }
        }
    }
}
