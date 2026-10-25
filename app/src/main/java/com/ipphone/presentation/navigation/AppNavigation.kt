package com.ipphone.presentation.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.outlined.Dialpad
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.material.icons.filled.Sync
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.ipphone.network.calling.CallManager
import com.ipphone.network.filetransfer.FileTransferManager
import com.ipphone.presentation.screens.blocked.BlockedScreen
import com.ipphone.presentation.screens.call.CallScreen
import com.ipphone.presentation.screens.call.IncomingCallScreen
import com.ipphone.presentation.screens.calllogs.CallLogsScreen
import com.ipphone.presentation.screens.chat.ChatScreen
import com.ipphone.presentation.screens.dialpad.DialpadScreen
import com.ipphone.presentation.screens.filemanager.FileManagerScreen
import com.ipphone.presentation.screens.nearby.NearbyScreen
import com.ipphone.presentation.screens.onboarding.OnboardingScreen
import com.ipphone.presentation.screens.contacts.ContactsScreen
import com.ipphone.presentation.screens.diagnostics.DiagnosticsScreen
import com.ipphone.presentation.screens.quickconnect.QuickConnectScreen
import com.ipphone.presentation.screens.profile.ProfileScreen
import com.ipphone.presentation.screens.settings.SettingsScreen
import com.ipphone.presentation.screens.splash.SplashScreen
import com.ipphone.presentation.screens.transfer.TransferScreen
import com.ipphone.presentation.screens.wifidirect.WifiDirectScreen
import com.ipphone.utils.toFormattedSize
import kotlinx.coroutines.launch
import timber.log.Timber

sealed class Screen(val route: String) {
    object Splash      : Screen("splash")
    object Onboarding  : Screen("onboarding")
    object Dialpad     : Screen("dialpad")
    object Nearby      : Screen("nearby")
    object Contacts    : Screen("contacts")
    object Blocked     : Screen("blocked")
    object CallLogs    : Screen("call_logs")
    object Settings    : Screen("settings")
    object UserProfile : Screen("profile/{userId}") {
        fun createRoute(userId: String?) = "profile/${userId ?: "me"}"
    }
    object Chat : Screen("chat/{userId}") {
        fun createRoute(userId: String) = "chat/$userId"
    }
    object FileManager : Screen("file_manager/{userId}") {
        fun createRoute(userId: String) = "file_manager/$userId"
    }
    object Transfer : Screen("transfer/{transferId}") {
        fun createRoute(transferId: String) = "transfer/$transferId"
    }
    object Call : Screen("call/{userId}/{callType}") {
        fun createRoute(userId: String, callType: String) = "call/$userId/$callType"
    }
    object IncomingCall : Screen("incoming_call/{callId}") {
        fun createRoute(callId: String) = "incoming_call/$callId"
    }
    object ActiveTransfers : Screen("active_transfers")
    object QuickConnect    : Screen("quick_connect")
    object Diagnostics     : Screen("diagnostics")
    object WifiDirect      : Screen("wifi_direct")
}

data class BottomNavItem(
    val screen: Screen,
    val label: String,
    val selectedIcon: androidx.compose.ui.graphics.vector.ImageVector,
    val unselectedIcon: androidx.compose.ui.graphics.vector.ImageVector,
    val badgeCount: Int = 0
)

private fun NavHostController.navigateToHomeAfterCall() {
    try {
        navigate(Screen.Contacts.route) {
            popUpTo(0) { inclusive = true }
            launchSingleTop = true
        }
    } catch (e: Exception) {
        Timber.e(e, "navigateToHomeAfterCall failed")
    }
}

@Composable
fun AppNavigation(
    navController: NavHostController = rememberNavController(),
    startDestination: String = Screen.Splash.route,
    callManager: CallManager,
    fileTransferManager: FileTransferManager,
    navigationViewModel: NavigationViewModel = hiltViewModel()
) {
    val navBackStackEntry    by navController.currentBackStackEntryAsState()
    val currentDestination   = navBackStackEntry?.destination

    val missedCallCount      by navigationViewModel.missedCallCount.collectAsState()
    val unreadMessageCount   by navigationViewModel.unreadMessageCount.collectAsState()
    val activeTransfers      by navigationViewModel.activeTransfers.collectAsState()

    val scope = rememberCoroutineScope()

    var lastNavigatedCallId by remember { mutableStateOf<String?>(null) }

    // ── Call state observer ───────────────────────────────────
    LaunchedEffect(Unit) {
        callManager.callState.collect { state ->
            val currentRoute = navController.currentBackStackEntry?.destination?.route
            Timber.d("AppNavigation callState=$state  currentRoute=$currentRoute")

            when (state) {
                is CallManager.CallState.Incoming -> {
                    if (lastNavigatedCallId != state.callId) {
                        lastNavigatedCallId = state.callId
                        navController.navigate(Screen.IncomingCall.createRoute(state.callId)) {
                            launchSingleTop = true
                        }
                    }
                }

                is CallManager.CallState.Outgoing -> {
                    if (currentRoute?.startsWith("call/") != true) {
                        navController.navigate(
                            Screen.Call.createRoute(state.peerId, state.callType.name)
                        ) { launchSingleTop = true }
                    }
                }

                is CallManager.CallState.Connecting -> {
                    if (currentRoute?.contains("incoming_call") == true) {
                        navController.navigate(
                            Screen.Call.createRoute(state.peerId, state.callType.name)
                        ) {
                            popUpTo(Screen.IncomingCall.route) { inclusive = true }
                            launchSingleTop = true
                        }
                    } else if (currentRoute?.startsWith("call/") != true) {
                        navController.navigate(
                            Screen.Call.createRoute(state.peerId, state.callType.name)
                        ) { launchSingleTop = true }
                    }
                }

                is CallManager.CallState.Connected -> {
                    if (currentRoute?.contains("incoming_call") == true) {
                        navController.navigate(
                            Screen.Call.createRoute(state.peerId, state.callType.name)
                        ) {
                            popUpTo(Screen.IncomingCall.route) { inclusive = true }
                            launchSingleTop = true
                        }
                    } else if (currentRoute?.startsWith("call/") != true) {
                        navController.navigate(
                            Screen.Call.createRoute(state.peerId, state.callType.name)
                        ) { launchSingleTop = true }
                    }
                }

                is CallManager.CallState.Ended,
                CallManager.CallState.Idle -> {
                    lastNavigatedCallId = null
                }

                else -> Unit
            }
        }
    }

    // ── In-app file transfer accept dialog ────────────────────
    var pendingTransferOffer by remember {
        mutableStateOf<FileTransferManager.TransferEvent.Offer?>(null)
    }

    LaunchedEffect(Unit) {
        fileTransferManager.transferEvents.collect { event ->
            if (event is FileTransferManager.TransferEvent.Offer && event.messageId == null) {
                pendingTransferOffer = event
            }
        }
    }

    pendingTransferOffer?.let { offer ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Incoming File Transfer") },
            text = {
                Text(
                    "${offer.files.size} file(s) · ${offer.totalSize.toFormattedSize()}\n" +
                            "from a nearby device"
                )
            },
            confirmButton = {
                Button(onClick = {
                    val captured = pendingTransferOffer
                    pendingTransferOffer = null
                    if (captured != null) {
                        navController.navigate(Screen.Transfer.createRoute(captured.transferId)) {
                            launchSingleTop = true
                        }
                        scope.launch {
                            try { fileTransferManager.acceptTransfer(captured.transferId) }
                            catch (e: Exception) { Timber.e(e, "Failed to accept transfer") }
                        }
                    }
                }) { Text("Accept") }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    val captured = pendingTransferOffer
                    pendingTransferOffer = null
                    if (captured != null) {
                        scope.launch {
                            try { fileTransferManager.rejectTransfer(captured.transferId, "Rejected by user") }
                            catch (e: Exception) { Timber.e(e, "Failed to reject transfer") }
                        }
                    }
                }) { Text("Decline") }
            }
        )
    }

    // ── Bottom nav items ──────────────────────────────────────
    val bottomNavItems = listOf(
        BottomNavItem(Screen.Dialpad,  "Dial",     Icons.Filled.Dialpad, Icons.Outlined.Dialpad),
        BottomNavItem(Screen.Nearby,   "Nearby",   Icons.Filled.Radar,   Icons.Outlined.Radar),
        BottomNavItem(Screen.Contacts,   "Chats",    Icons.Filled.People,  Icons.Outlined.People,  unreadMessageCount),
        BottomNavItem(Screen.CallLogs, "Calls",    Icons.Filled.History, Icons.Outlined.History, missedCallCount),
        BottomNavItem(Screen.Settings, "Settings", Icons.Filled.Settings, Icons.Outlined.Settings)
    )

    val bottomNavRoutes = setOf(
        Screen.Dialpad.route, Screen.Nearby.route, Screen.Contacts.route, Screen.CallLogs.route,
        Screen.Settings.route
    )

    val showBottomBar = currentDestination?.route in bottomNavRoutes

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                Column {
                    // Active Transfers Indicator
                    if (activeTransfers.isNotEmpty()) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            onClick = {
                                if (activeTransfers.size == 1) {
                                    navController.navigate(
                                        Screen.Transfer.createRoute(activeTransfers.first().id)
                                    ) { launchSingleTop = true }
                                } else {
                                    navController.navigate(Screen.ActiveTransfers.route) {
                                        launchSingleTop = true
                                    }
                                }
                            }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Sync,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = if (activeTransfers.size == 1) "File transfer in progress..."
                                        else "${activeTransfers.size} transfers in progress...",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                                activeTransfers.firstOrNull()?.let {
                                    Text(
                                        text = "${(it.progress * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        } // closes Surface
                    } // closes if (activeTransfers.isNotEmpty())

                    NavigationBar {
                        bottomNavItems.forEach { item ->
                            val selected = currentDestination?.hierarchy?.any {
                                val route = it.route
                                route == item.screen.route ||
                                        (item.screen == Screen.Contacts && route?.startsWith("chat/") == true)
                            } == true

                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(item.screen.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    BadgedBox(badge = {
                                        if (item.badgeCount > 0) {
                                            Badge {
                                                Text(
                                                    if (item.badgeCount > 99) "99+"
                                                    else item.badgeCount.toString()
                                                )
                                            }
                                        }
                                    }) {
                                        Icon(
                                            imageVector = if (selected) item.selectedIcon
                                            else item.unselectedIcon,
                                            contentDescription = item.label
                                        )
                                    }
                                },
                                label = { Text(item.label) }
                            )
                        } // closes forEach
                    } // closes NavigationBar
                } // closes Column
            } // closes if (showBottomBar)
        } // closes bottomBar lambda
    ) { paddingValues ->
        NavHost(
            navController    = navController,
            startDestination = startDestination,
            modifier         = Modifier.padding(paddingValues),
            enterTransition  = {
                fadeIn(tween(300)) +
                        slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(300))
            },
            exitTransition   = {
                fadeOut(tween(300)) +
                        slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Start, tween(300))
            },
            popEnterTransition = {
                fadeIn(tween(300)) +
                        slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(300))
            },
            popExitTransition  = {
                fadeOut(tween(300)) +
                        slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.End, tween(300))
            }
        ) {
            composable(Screen.Splash.route) {
                SplashScreen(
                    onNavigateToOnboarding = {
                        navController.navigate(Screen.Onboarding.route) {
                            popUpTo(Screen.Splash.route) { inclusive = true }
                        }
                    },
                    onNavigateToMain = {
                        navController.navigate(Screen.Contacts.route) {
                            popUpTo(Screen.Splash.route) { inclusive = true }
                        }
                    }
                )
            }

            composable(Screen.Onboarding.route) {
                OnboardingScreen(onComplete = {
                    navController.navigate(Screen.Contacts.route) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                })
            }

            composable(Screen.Nearby.route)   { NearbyScreen(navController = navController) }
            composable(Screen.Dialpad.route)  { DialpadScreen(navController = navController) }
            composable(Screen.Contacts.route)   { ContactsScreen(navController = navController) }
            composable(Screen.CallLogs.route) { CallLogsScreen(navController = navController) }
            composable(Screen.Blocked.route)  { BlockedScreen(navController = navController) }
            composable(Screen.Settings.route) { SettingsScreen(navController = navController) }
            composable(Screen.WifiDirect.route) { WifiDirectScreen(navController = navController) }

            composable(
                route = Screen.UserProfile.route,
                arguments = listOf(navArgument("userId") { type = NavType.StringType })
            ) { back ->
                val userId = back.arguments?.getString("userId").let { if (it == "me") null else it }
                ProfileScreen(userId = userId, navController = navController)
            }

            composable(
                route     = Screen.Chat.route,
                arguments = listOf(navArgument("userId") { type = NavType.StringType })
            ) { back ->
                val userId = back.arguments?.getString("userId")
                if (userId != null) ChatScreen(userId = userId, navController = navController)
            }

            composable(
                route     = Screen.FileManager.route,
                arguments = listOf(navArgument("userId") { type = NavType.StringType })
            ) { back ->
                val userId = back.arguments?.getString("userId")
                if (userId != null) FileManagerScreen(userId = userId, navController = navController)
            }

            composable(
                route     = Screen.Transfer.route,
                arguments = listOf(navArgument("transferId") { type = NavType.StringType })
            ) { back ->
                val transferId = back.arguments?.getString("transferId")
                if (transferId != null) TransferScreen(transferId = transferId, navController = navController)
            }

            composable(
                route     = Screen.Call.route,
                arguments = listOf(
                    navArgument("userId")   { type = NavType.StringType },
                    navArgument("callType") { type = NavType.StringType }
                )
            ) { back ->
                val userId      = back.arguments?.getString("userId")
                val callTypeArg = back.arguments?.getString("callType") ?: "AUDIO"
                if (userId != null) {
                    CallScreen(userId = userId, callType = callTypeArg, navController = navController)
                }
            }

            composable(
                route     = Screen.IncomingCall.route,
                arguments = listOf(navArgument("callId") { type = NavType.StringType })
            ) { back ->
                val callId = back.arguments?.getString("callId")
                if (callId != null) IncomingCallScreen(callId = callId, navController = navController)
            }

            composable(Screen.QuickConnect.route) { QuickConnectScreen(navController = navController) }
            composable(Screen.Diagnostics.route)  { DiagnosticsScreen(navController = navController) }

            composable(Screen.ActiveTransfers.route) {
                com.ipphone.presentation.screens.transfer.ActiveTransfersScreen(navController = navController)
            }
        } // closes NavHost
    } // closes Scaffold content lambda
} // closes AppNavigation