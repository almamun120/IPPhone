// app/src/main/java/com/wificalling/presentation/screens/call/CallScreen.kt
package com.ipphone.presentation.screens.call

import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.ipphone.domain.model.CallType
import com.ipphone.network.calling.CallManager
import com.ipphone.utils.toFormattedDuration
import kotlinx.coroutines.delay
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import timber.log.Timber

// Helper — navigate to home clearing the entire back stack so no stale
// call or incoming-call screen can be revealed underneath.
private fun NavController.goHomeAfterCall() {
    try {
        navigate("paired") {
            popUpTo(0) { inclusive = true }
            launchSingleTop = true
        }
    } catch (e: Exception) {
        Timber.e(e, "goHomeAfterCall failed")
    }
}

@Composable
fun CallScreen(
    userId: String,
    callType: String,
    navController: NavController,
    viewModel: CallViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    var localRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    var remoteRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    var hasDismissed by remember { mutableStateOf(false) }

    // Start outgoing call only when truly idle
    LaunchedEffect(userId, callType) {
        val currentState = viewModel.uiState.value.callState
        if (currentState is CallManager.CallState.Idle ||
            currentState is CallManager.CallState.Ended
        ) {
            viewModel.startCall(userId, CallType.valueOf(callType))
        } else {
            Timber.d("CallScreen: skipping startCall, already in state $currentState")
            viewModel.markCallInitiated()
        }
    }

    // ── Dismissal: always go home, never just pop ────────────────────────────
    // Root cause of black screen:
    //   popBackStack() from CallScreen was revealing IncomingCallScreen underneath
    //   because IncomingCallScreen was not always removed from the back stack.
    //   Even after AppNavigation uses popUpTo(IncomingCall) { inclusive=true },
    //   timing races mean IncomingCallScreen can still be present.
    //
    // Fix: on any terminal state, navigate explicitly to "paired" with
    //   popUpTo(0) { inclusive=true } which wipes the entire back stack and
    //   puts Paired on top. This is guaranteed to work regardless of what
    //   was underneath CallScreen.
    LaunchedEffect(uiState.callState) {
        if (hasDismissed) return@LaunchedEffect

        when (uiState.callState) {
            is CallManager.CallState.Ended -> {
                Timber.d("CallScreen: Ended — will go home after delay")
                delay(2000)
                if (!hasDismissed) {
                    hasDismissed = true
                    navController.goHomeAfterCall()
                }
            }

            is CallManager.CallState.Idle -> {
                if (uiState.hasCallBeenInitiated && !hasDismissed) {
                    Timber.d("CallScreen: Idle after call — going home")
                    hasDismissed = true
                    navController.goHomeAfterCall()
                }
            }

            else -> {}
        }
    }

    // Wire up video track sinks
    DisposableEffect(uiState.remoteVideoTrack, remoteRenderer) {
        val track = uiState.remoteVideoTrack
        val renderer = remoteRenderer
        if (track != null && renderer != null) {
            Timber.d("Attaching sink to remote renderer")
            track.addSink(renderer)
        }
        onDispose {
            if (track != null && renderer != null) {
                try { track.removeSink(renderer) } catch (e: Exception) { Timber.w(e) }
            }
        }
    }

    DisposableEffect(uiState.localVideoTrack, localRenderer) {
        val track = uiState.localVideoTrack
        val renderer = localRenderer
        if (track != null && renderer != null) {
            Timber.d("Attaching sink to local renderer")
            track.addSink(renderer)
        }
        onDispose {
            if (track != null && renderer != null) {
                try { track.removeSink(renderer) } catch (e: Exception) { Timber.w(e) }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try { localRenderer?.release() } catch (e: Exception) { Timber.w(e) }
            try { remoteRenderer?.release() } catch (e: Exception) { Timber.w(e) }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A2E))
    ) {
        when (val state = uiState.callState) {

            is CallManager.CallState.Idle -> {
                if (!uiState.hasCallBeenInitiated) {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = Color.White
                    )
                }
            }

            is CallManager.CallState.Outgoing -> {
                OutgoingCallUI(
                    peerName = uiState.peerName,
                    profileImage = uiState.profileImage,
                    callType = state.callType,
                    onEndCall = { viewModel.endCall() }
                )
            }

            is CallManager.CallState.Incoming -> {
                ConnectingCallUI(
                    peerName = uiState.peerName,
                    profileImage = uiState.profileImage
                )
            }

            is CallManager.CallState.Connecting -> {
                ConnectingCallUI(
                    peerName = uiState.peerName,
                    profileImage = uiState.profileImage
                )
            }

            is CallManager.CallState.Connected -> {
                if (state.callType == CallType.VIDEO) {
                    VideoCallUI(
                        eglBase = viewModel.getEglBase(),
                        peerName = uiState.peerName,
                        duration = uiState.callDuration,
                        isMuted = uiState.isMuted,
                        isSpeaker = uiState.isSpeaker,
                        isCameraOn = uiState.isCameraOn,
                        onToggleMute = { viewModel.toggleMute() },
                        onToggleSpeaker = { viewModel.toggleSpeaker() },
                        onToggleCamera = { viewModel.toggleCamera() },
                        onSwitchCamera = { viewModel.switchCamera() },
                        onEndCall = { viewModel.endCall() },
                        onLocalRendererReady = { localRenderer = it },
                        onRemoteRendererReady = { remoteRenderer = it }
                    )
                } else {
                    AudioCallUI(
                        peerName = uiState.peerName,
                        profileImage = uiState.profileImage,
                        duration = uiState.callDuration,
                        isMuted = uiState.isMuted,
                        isSpeaker = uiState.isSpeaker,
                        onToggleMute = { viewModel.toggleMute() },
                        onToggleSpeaker = { viewModel.toggleSpeaker() },
                        onEndCall = { viewModel.endCall() }
                    )
                }
            }

            is CallManager.CallState.Ended -> {
                CallEndedUI(
                    reason = state.reason,
                    onDismiss = {
                        hasDismissed = true
                        navController.goHomeAfterCall()
                    }
                )
            }
        }
    }
}

// ── Sub-composables (unchanged) ───────────────────────────────────────────────

@Composable
fun OutgoingCallUI(
    peerName: String,
    profileImage: String?,
    callType: CallType,
    onEndCall: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(modifier = Modifier.height(64.dp))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                text = peerName,
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (callType == CallType.VIDEO) "Video calling…" else "Calling…",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.7f)
            )
        }

        FloatingActionButton(
            onClick = onEndCall,
            containerColor = Color.Red,
            modifier = Modifier.size(72.dp)
        ) {
            Icon(
                Icons.Default.CallEnd,
                contentDescription = "End Call",
                modifier = Modifier.size(32.dp),
                tint = Color.White
            )
        }

        Spacer(modifier = Modifier.height(48.dp))
    }
}

@Composable
fun IncomingCallUI(
    peerName: String,
    profileImage: String?,
    callType: CallType,
    onAccept: () -> Unit,
    onReject: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(modifier = Modifier.height(64.dp))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                            Icons.Default.Person, null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(peerName, style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                if (callType == CallType.VIDEO) "Incoming video call" else "Incoming call",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.7f)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(64.dp)) {
            FloatingActionButton(
                onClick = onReject,
                containerColor = Color.Red,
                modifier = Modifier.size(72.dp)
            ) {
                Icon(Icons.Default.CallEnd, "Reject", modifier = Modifier.size(32.dp), tint = Color.White)
            }
            FloatingActionButton(
                onClick = onAccept,
                containerColor = Color(0xFF4CAF50),
                modifier = Modifier.size(72.dp)
            ) {
                Icon(
                    if (callType == CallType.VIDEO) Icons.Default.Videocam else Icons.Default.Call,
                    "Accept",
                    modifier = Modifier.size(32.dp),
                    tint = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(48.dp))
    }
}

@Composable
fun ConnectingCallUI(
    peerName: String,
    profileImage: String? = null
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
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

        Spacer(modifier = Modifier.height(32.dp))
        CircularProgressIndicator(color = Color.White)
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            "Connecting to $peerName…",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White
        )
    }
}

@Composable
fun CallEndedUI(reason: String, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.CallEnd,
            null,
            modifier = Modifier.size(64.dp),
            tint = Color.Red
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = when (reason) {
                "REJECTED"          -> "Call rejected"
                "BUSY"              -> "Line busy"
                "No answer"         -> "No answer"
                "User not available" -> "User not available"
                "Connection lost"   -> "Connection lost"
                "Connection failed" -> "Connection failed"
                else                -> "Call ended"
            },
            style = MaterialTheme.typography.titleLarge,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(32.dp))
        OutlinedButton(
            onClick = onDismiss,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
        ) {
            Text("Dismiss")
        }
    }
}

@Composable
fun AudioCallUI(
    peerName: String,
    profileImage: String?,
    duration: Long,
    isMuted: Boolean,
    isSpeaker: Boolean,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onEndCall: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(modifier = Modifier.height(64.dp))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                            Icons.Default.Person, null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(peerName, style = MaterialTheme.typography.headlineMedium, color = Color.White)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                (duration * 1000).toFormattedDuration(),
                style = MaterialTheme.typography.titleLarge,
                color = Color.White.copy(alpha = 0.7f)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            FloatingActionButton(
                onClick = onToggleMute,
                containerColor = if (isMuted) Color.Red else Color.Gray,
                modifier = Modifier.size(56.dp)
            ) {
                Icon(
                    if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                    "Mute",
                    tint = Color.White
                )
            }
            FloatingActionButton(
                onClick = onToggleSpeaker,
                containerColor = if (isSpeaker) MaterialTheme.colorScheme.primary else Color.Gray,
                modifier = Modifier.size(56.dp)
            ) {
                Icon(
                    if (isSpeaker) Icons.Default.VolumeUp else Icons.Default.VolumeDown,
                    "Speaker",
                    tint = Color.White
                )
            }
            FloatingActionButton(
                onClick = onEndCall,
                containerColor = Color.Red,
                modifier = Modifier.size(72.dp)
            ) {
                Icon(
                    Icons.Default.CallEnd,
                    "End Call",
                    modifier = Modifier.size(32.dp),
                    tint = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(48.dp))
    }
}

@Composable
fun VideoCallUI(
    eglBase: EglBase?,
    peerName: String,
    duration: Long,
    isMuted: Boolean,
    isSpeaker: Boolean,
    isCameraOn: Boolean,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onToggleCamera: () -> Unit,
    onSwitchCamera: () -> Unit,
    onEndCall: () -> Unit,
    onLocalRendererReady: (SurfaceViewRenderer) -> Unit,
    onRemoteRendererReady: (SurfaceViewRenderer) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                SurfaceViewRenderer(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    eglBase?.eglBaseContext?.let { init(it, null) }
                    setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                    setEnableHardwareScaler(true)
                    onRemoteRendererReady(this)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .padding(16.dp)
                .size(120.dp, 160.dp)
                .align(Alignment.TopEnd)
                .clip(MaterialTheme.shapes.medium)
        ) {
            AndroidView(
                factory = { ctx ->
                    SurfaceViewRenderer(ctx).apply {
                        eglBase?.eglBaseContext?.let { init(it, null) }
                        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                        setMirror(true)
                        setEnableHardwareScaler(true)
                        setZOrderMediaOverlay(true)
                        onLocalRendererReady(this)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .align(Alignment.TopStart)
        ) {
            Column {
                Text(peerName, style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text(
                    (duration * 1000).toFormattedDuration(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(32.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            FloatingActionButton(
                onClick = onToggleMute,
                containerColor = if (isMuted) Color.Red else Color.White.copy(alpha = 0.3f),
                modifier = Modifier.size(56.dp)
            ) {
                Icon(if (isMuted) Icons.Default.MicOff else Icons.Default.Mic, "Mute", tint = Color.White)
            }
            FloatingActionButton(
                onClick = onToggleCamera,
                containerColor = if (!isCameraOn) Color.Red else Color.White.copy(alpha = 0.3f),
                modifier = Modifier.size(56.dp)
            ) {
                Icon(
                    if (isCameraOn) Icons.Default.Videocam else Icons.Default.VideocamOff,
                    "Camera",
                    tint = Color.White
                )
            }
            FloatingActionButton(
                onClick = onSwitchCamera,
                containerColor = Color.White.copy(alpha = 0.3f),
                modifier = Modifier.size(56.dp)
            ) {
                Icon(Icons.Default.Cameraswitch, "Switch", tint = Color.White)
            }
            FloatingActionButton(
                onClick = onToggleSpeaker,
                containerColor = if (isSpeaker) MaterialTheme.colorScheme.primary
                else Color.White.copy(alpha = 0.3f),
                modifier = Modifier.size(56.dp)
            ) {
                Icon(
                    if (isSpeaker) Icons.Default.VolumeUp else Icons.Default.VolumeDown,
                    "Speaker",
                    tint = Color.White
                )
            }
            FloatingActionButton(
                onClick = onEndCall,
                containerColor = Color.Red,
                modifier = Modifier.size(72.dp)
            ) {
                Icon(
                    Icons.Default.CallEnd,
                    "End Call",
                    modifier = Modifier.size(32.dp),
                    tint = Color.White
                )
            }
        }
    }
}
