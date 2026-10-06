package com.ipphone.presentation.screens.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.ipphone.R
import java.io.File

/**
 * Convert a raw download-path string (could be a content:// tree URI from SAF
 * or a raw file-system path) into a human-readable label for the Settings row.
 */
private fun friendlyPathLabel(path: String?, context: android.content.Context? = null): String {
    if (path.isNullOrBlank()) return "Default (Downloads folder)"
    return try {
        val uri = Uri.parse(path)
        when (uri.scheme) {
            "content" -> {
                val docName = context?.let {
                    androidx.documentfile.provider.DocumentFile.fromTreeUri(it, uri)?.name
                }
                if (docName != null) return docName
                val encoded = uri.lastPathSegment ?: path
                val decoded = Uri.decode(encoded)
                decoded.substringAfterLast('/').substringAfterLast(':').ifBlank { decoded }
            }
            "file" -> uri.path ?: path
            else   -> File(path).name.ifBlank { path }
        }
    } catch (_: Exception) {
        try { File(path).name.ifBlank { path } } catch (_: Exception) { path }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showEditNameDialog by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                context.contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: Exception) {}
            viewModel.setDownloadPath(uri.toString())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Settings") })
        }
    ) { paddingValues ->
        LazyColumn(
            modifier       = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ── Profile Section ───────────────────────────────
            item {
                Card(
                    modifier  = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                navController.navigate(com.ipphone.presentation.navigation.Screen.UserProfile.createRoute(null))
                            }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Person,
                                    contentDescription = null,
                                    modifier = Modifier.size(40.dp),
                                    tint     = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text  = uiState.deviceName,
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                text  = "Tap to edit name",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Edit",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ── Device Info ───────────────────────────────────
            item {
                Text(
                    text     = "Device Info",
                    style    = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            item {
                SettingsItem(
                    icon     = Icons.Default.Fingerprint,
                    title    = "Device ID",
                    subtitle = uiState.deviceId.take(8) + "…"
                )
            }

            item {
                SettingsItem(
                    icon     = Icons.Default.Wifi,
                    title    = "IP Address",
                    subtitle = uiState.ipAddress ?: "Not connected"
                )
            }

            // ── Network ───────────────────────────────────────
            item {
                Text(
                    text     = "Network",
                    style    = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            item {
                SettingsItem(
                    icon     = Icons.Default.Wifi,
                    title    = "Wi-Fi Settings",
                    subtitle = "Open system Wi-Fi configuration",
                    onClick  = {
                        try {
                            context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                        } catch (_: Exception) {
                            context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                        }
                    }
                )
            }

            item {
                SettingsItem(
                    icon     = Icons.Default.WifiTethering,
                    title    = "Hotspot Settings",
                    subtitle = "Manage mobile hotspot and tethering",
                    onClick  = {
                        try {
                            context.startActivity(Intent("android.settings.TETHER_SETTINGS"))
                        } catch (_: Exception) {
                            context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
                        }
                    }
                )
            }

            item {
                SettingsItem(
                    icon     = Icons.Default.SettingsInputAntenna,
                    title    = "Wi-Fi Direct",
                    subtitle = "Discover & connect devices without a router",
                    onClick  = {
                        navController.navigate(com.ipphone.presentation.navigation.Screen.WifiDirect.route)
                    }
                )
            }

            item {
                SettingsToggleItem(
                    icon            = Icons.Default.NetworkCheck,
                    title           = "Auto-accept files",
                    subtitle        = "Automatically accept incoming file transfers from paired devices",
                    checked         = uiState.autoAcceptFiles,
                    onCheckedChange = { viewModel.setAutoAcceptFiles(it) }
                )
            }

            item {
                SettingsItem(
                    icon     = Icons.Default.Folder,
                    title    = "Download Location",
                    subtitle = friendlyPathLabel(uiState.downloadPath, context),
                    onClick  = { folderPickerLauncher.launch(null) }
                )
            }

            // ── Notifications ─────────────────────────────────
            item {
                Text(
                    text     = "Notifications",
                    style    = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            item {
                SettingsToggleItem(
                    icon            = Icons.Default.NotificationsActive,
                    title           = "Notification Sound",
                    checked         = uiState.notificationSound,
                    onCheckedChange = { viewModel.setNotificationSound(it) }
                )
            }

            item {
                SettingsToggleItem(
                    icon            = Icons.Default.Vibration,
                    title           = "Vibrate",
                    checked         = uiState.notificationVibrate,
                    onCheckedChange = { viewModel.setNotificationVibrate(it) }
                )
            }

            // ── Privacy ───────────────────────────────────────
            item {
                Text(
                    text     = "Privacy",
                    style    = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            item {
                SettingsItem(
                    icon     = Icons.Default.Block,
                    title    = "Blocked Users",
                    subtitle = "Manage users you have blocked",
                    onClick  = { navController.navigate(com.ipphone.presentation.navigation.Screen.Blocked.route) }
                )
            }

            // ── About ─────────────────────────────────────────
            item {
                Text(
                    text     = "About",
                    style    = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )
            }

            item {
                Card(
                    modifier  = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(
                        modifier            = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(modifier = Modifier.height(24.dp))

                        Surface(
                            modifier = Modifier.size(80.dp),
                            shape    = CircleShape,
                            color    = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    painter           = painterResource(id = R.drawable.ic_wifi_calling),
                                    contentDescription = null,
                                    modifier           = Modifier.size(48.dp),
                                    tint               = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(text = "IPPhone", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            text  = "Version 1.3.0",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(24.dp))
                        HorizontalDivider(
                            modifier  = Modifier.padding(horizontal = 16.dp),
                            thickness = 0.5.dp,
                            color     = MaterialTheme.colorScheme.outlineVariant
                        )

                        SettingsRow(
                            icon     = Icons.Default.Person,
                            title    = "Developer",
                            subtitle = "Md. Abdullah Al Mamun"
                        )
                        SettingsRow(
                            icon     = Icons.Default.LocationOn,
                            title    = "Location",
                            subtitle = "Dhaka, Bangladesh"
                        )
                        SettingsRow(
                            icon     = Icons.Default.Email,
                            title    = "Email",
                            subtitle = "mamun995599@gmail.com",
                            onClick  = {
                                try {
                                    context.startActivity(
                                        Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:mamun995599@gmail.com"))
                                    )
                                } catch (_: Exception) {}
                            }
                        )
                        SettingsRow(
                            icon     = Icons.Default.Phone,
                            title    = "Phone",
                            subtitle = "+8801945120109",
                            onClick  = {
                                try {
                                    context.startActivity(
                                        Intent(Intent.ACTION_DIAL, Uri.parse("tel:+8801945120109"))
                                    )
                                } catch (_: Exception) {}
                            }
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }

            item {
                Box(
                    modifier         = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp, bottom = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text  = "Made with ❤️ in Bangladesh",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }

    // ── Edit Name Dialog ──────────────────────────────────────
    if (showEditNameDialog) {
        AlertDialog(
            onDismissRequest = { showEditNameDialog = false },
            title            = { Text("Edit Device Name") },
            text             = {
                OutlinedTextField(
                    value         = newName.ifEmpty { uiState.deviceName },
                    onValueChange = { newName = it },
                    label         = { Text("Device Name") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (newName.isNotBlank()) viewModel.setDeviceName(newName)
                    showEditNameDialog = false
                    newName = ""
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showEditNameDialog = false; newName = "" }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun SettingsItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector        = icon,
                contentDescription = null,
                tint               = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                subtitle?.let {
                    Text(
                        text  = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (onClick != null) {
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun SettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = null,
            tint               = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(
                    text  = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (onClick != null) {
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun SettingsToggleItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector        = icon,
                contentDescription = null,
                tint               = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                subtitle?.let {
                    Text(
                        text  = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}