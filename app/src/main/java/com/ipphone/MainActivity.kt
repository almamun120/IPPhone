package com.ipphone

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.ipphone.network.calling.CallManager
import com.ipphone.network.filetransfer.FileTransferManager
import com.ipphone.presentation.navigation.AppNavigation
import com.ipphone.presentation.navigation.Screen
import com.ipphone.presentation.theme.IPPhoneTheme
import com.ipphone.service.CallService
import com.ipphone.service.NetworkService
import com.ipphone.utils.AppDiagnostics
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private lateinit var navController: NavHostController

    @Inject
    lateinit var callManager: CallManager

    @Inject
    lateinit var fileTransferManager: FileTransferManager

    // Coroutine scope for suspend calls triggered from intent handling
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            startNetworkService()
        } else {
            Timber.w("Not all permissions granted")
        }
        checkOverlayPermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppDiagnostics.onActivityCreated()

        // Only on a genuine first launch of this activity — not on every re-creation.
        if (savedInstanceState == null) requestRequiredPermissions()

        setContent {
            IPPhoneTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    navController = rememberNavController()

                    LaunchedEffect(Unit) {
                        handleCallIntent(intent)
                        handleTransferIntent(intent)
                    }

                    AppNavigation(
                        navController = navController,
                        callManager = callManager,
                        fileTransferManager = fileTransferManager
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (::navController.isInitialized) {
            handleCallIntent(intent)
            handleTransferIntent(intent)
        }
    }

    private fun handleCallIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("incoming_call", false) == true) {
            val callId = intent.getStringExtra(CallService.EXTRA_CALL_ID)
            Timber.d("MainActivity: Incoming call received - CallId: $callId")

            val currentState = callManager.callState.value
            if (currentState !is CallManager.CallState.Incoming &&
                currentState !is CallManager.CallState.Connecting &&
                currentState !is CallManager.CallState.Connected &&
                navController.currentBackStackEntry?.destination?.route?.startsWith("incoming_call") != true
            ) {
                Timber.d("MainActivity: Ignoring incoming call intent as call is no longer active (state=$currentState)")
                return
            }

            if (callId != null && ::navController.isInitialized) {
                val currentRoute = navController.currentBackStackEntry?.destination?.route
                if (currentRoute?.startsWith("call/") == true) {
                    Timber.d("MainActivity: Already on call screen, ignoring incoming call intent")
                    return
                }

                navController.navigate("incoming_call/$callId") {
                    launchSingleTop = true
                    popUpTo(0) { inclusive = false }
                }
            }
        }

        if (intent?.getBooleanExtra("navigate_to_call", false) == true) {
            val peerId = intent.getStringExtra(CallService.EXTRA_PEER_ID)
            val callType = intent.getStringExtra(CallService.EXTRA_CALL_TYPE)
            Timber.d("MainActivity: Navigate to call - Peer: $peerId, Type: $callType")
            if (peerId != null && callType != null && ::navController.isInitialized) {
                navController.navigate("call/$peerId/$callType") {
                    launchSingleTop = true
                    popUpTo(0) { inclusive = false }
                }
            }
        }
    }

    private fun handleTransferIntent(intent: Intent?) {
        val transferId = intent?.getStringExtra("accept_transfer") ?: return

        Timber.d("MainActivity: Accepting incoming transfer - TransferId: $transferId")

        activityScope.launch {
            try {
                fileTransferManager.acceptTransfer(transferId)
                Timber.d("Transfer $transferId accepted")
            } catch (e: Exception) {
                Timber.e(e, "Failed to accept transfer $transferId")
            }
        }

        if (::navController.isInitialized) {
            navController.navigate(Screen.Transfer.createRoute(transferId)) {
                launchSingleTop = true
            }
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        permissions.add(Manifest.permission.RECORD_AUDIO)
        permissions.add(Manifest.permission.CAMERA)
        permissions.add(Manifest.permission.MANAGE_OWN_CALLS)
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startNetworkService()
            checkOverlayPermission()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startNetworkService() {
        val intent = Intent(this, NetworkService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun checkOverlayPermission() {
        // Ask only once; re-opening Settings on every launch is what kicks the user out of the app.
        val prefs = getSharedPreferences("app_flags", MODE_PRIVATE)
        if (prefs.getBoolean("overlay_prompted", false)) return
        if (!android.provider.Settings.canDrawOverlays(this)) {
            prefs.edit().putBoolean("overlay_prompted", true).apply()
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }
}
