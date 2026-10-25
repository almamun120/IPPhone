package com.ipphone.presentation.screens.filemanager

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.ipphone.presentation.navigation.Screen
import com.ipphone.utils.toFormattedDate
import com.ipphone.utils.toFormattedDuration
import com.ipphone.utils.toFormattedSize

private fun BrowserTab.icon(): ImageVector = when (this) {
    BrowserTab.PHOTOS -> Icons.Default.Image
    BrowserTab.VIDEOS -> Icons.Default.Videocam
    BrowserTab.MUSIC  -> Icons.Default.MusicNote
    BrowserTab.DOCS   -> Icons.Default.Description
    BrowserTab.APPS   -> Icons.Default.Android
}

/** Runtime permissions a tab needs before MediaStore returns anything. */
private fun BrowserTab.requiredPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        when (this) {
            BrowserTab.PHOTOS -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            BrowserTab.VIDEOS -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
            BrowserTab.MUSIC  -> arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
            BrowserTab.DOCS, BrowserTab.APPS -> emptyArray()
        }
    } else {
        when (this) {
            BrowserTab.APPS -> emptyArray()
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerScreen(
    userId: String,
    navController: NavController,
    viewModel: FileManagerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val selectedKeys = remember(uiState.selectedFiles) { uiState.selectedFiles.mapTo(HashSet()) { it.uri } }

    val systemPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.addFilesToSelection(context, uris)
    }

    var permissionTick by remember { mutableIntStateOf(0) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionTick++
        viewModel.reload()
    }

    LaunchedEffect(userId) { viewModel.setTargetUser(userId) }

    val needed = uiState.tab.requiredPermissions()
    val permissionOk = remember(uiState.tab, permissionTick) {
        needed.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column {
                        Text("Send files", style = MaterialTheme.typography.titleMedium)
                        if (uiState.targetName.isNotBlank()) {
                            Text(
                                text = "to ${uiState.targetName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { systemPicker.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "Browse all files")
                    }
                }
            )
        },
        bottomBar = {
            if (uiState.selectedFiles.isNotEmpty()) {
                SelectionBar(
                    files = uiState.selectedFiles,
                    totalSize = uiState.totalSelectedSize,
                    sending = uiState.isLoading,
                    onRemove = viewModel::removeFromSelection,
                    onClear = viewModel::clearSelection,
                    onSend = {
                        val transferId = viewModel.startTransfer()
                        if (transferId.isNotEmpty()) {
                            navController.navigate(Screen.Transfer.createRoute(transferId))
                        }
                    }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            ScrollableTabRow(
                selectedTabIndex = uiState.tab.ordinal,
                edgePadding = 8.dp
            ) {
                BrowserTab.values().forEach { tab ->
                    Tab(
                        selected = uiState.tab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        icon = { Icon(tab.icon(), contentDescription = null) },
                        text = { Text(tab.title) }
                    )
                }
            }

            when {
                !permissionOk -> PermissionPrompt(
                    onGrant = { permissionLauncher.launch(needed) }
                )

                uiState.itemsLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }

                uiState.items.isEmpty() -> EmptyState(
                    tab = uiState.tab,
                    onBrowse = { systemPicker.launch(arrayOf("*/*")) }
                )

                else -> {
                    val allSelected = uiState.items.all { it.uri in selectedKeys }
                    val selectedHere = uiState.items.count { it.uri in selectedKeys }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (selectedHere > 0) "$selectedHere of ${uiState.items.size} selected"
                                   else "${uiState.items.size} items",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = viewModel::toggleSelectAll) {
                            Text(if (allSelected) "Deselect all" else "Select all")
                        }
                    }

                    if (uiState.tab == BrowserTab.PHOTOS || uiState.tab == BrowserTab.VIDEOS) {
                        MediaGrid(
                            items = uiState.items,
                            selectedKeys = selectedKeys,
                            onToggle = viewModel::toggleItem
                        )
                    } else {
                        FileList(
                            items = uiState.items,
                            tab = uiState.tab,
                            selectedKeys = selectedKeys,
                            onToggle = viewModel::toggleItem
                        )
                    }
                }
            }
        }
    }
}

// ── Photos / videos: square thumbnail grid ───────────────────────────────────

@Composable
private fun MediaGrid(
    items: List<BrowserItem>,
    selectedKeys: Set<String>,
    onToggle: (BrowserItem) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 104.dp),
        contentPadding = PaddingValues(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(items = items, key = { it.key }) { item ->
            MediaCell(
                item = item,
                selected = item.uri in selectedKeys,
                onClick = { onToggle(item) }
            )
        }
    }
}

@Composable
private fun MediaCell(item: BrowserItem, selected: Boolean, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
    ) {
        AsyncImage(
            model = item.uri,
            contentDescription = item.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        if (item.mimeType?.startsWith("video") == true) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f)))
                    )
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = if (item.durationMs > 0) item.durationMs.toFormattedDuration()
                               else item.size.toFormattedSize(),
                        color = Color.White,
                        fontSize = 11.sp
                    )
                }
            }
        }

        if (selected) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(primary.copy(alpha = 0.28f))
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(if (selected) primary else Color.Black.copy(alpha = 0.25f)),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

// ── Music / documents / apps: list ───────────────────────────────────────────

@Composable
private fun FileList(
    items: List<BrowserItem>,
    tab: BrowserTab,
    selectedKeys: Set<String>,
    onToggle: (BrowserItem) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 12.dp)
    ) {
        items(items = items, key = { it.key }) { item ->
            FileRow(
                item = item,
                tab = tab,
                selected = item.uri in selectedKeys,
                onClick = { onToggle(item) }
            )
        }
    }
}

@Composable
private fun FileRow(item: BrowserItem, tab: BrowserTab, selected: Boolean, onClick: () -> Unit) {
    val container = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                    else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(container)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            if (item.icon != null) {
                Image(
                    bitmap = item.icon,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = fileIcon(item, tab),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val details = buildString {
                append(item.size.toFormattedSize())
                if (item.durationMs > 0) append(" · ").append(item.durationMs.toFormattedDuration())
                if (item.dateMs > 0) append(" · ").append(item.dateMs.toFormattedDate())
            }
            Text(
                text = details,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Checkbox(checked = selected, onCheckedChange = null)
    }
}

private fun fileIcon(item: BrowserItem, tab: BrowserTab): ImageVector {
    val mime = item.mimeType.orEmpty()
    return when {
        tab == BrowserTab.MUSIC || mime.startsWith("audio") -> Icons.Default.MusicNote
        mime == "application/pdf"                           -> Icons.Default.PictureAsPdf
        mime.contains("zip") || mime.contains("rar") || mime.contains("7z") ||
            mime.contains("tar") || mime.contains("compressed") -> Icons.Default.FolderZip
        mime.startsWith("text")                             -> Icons.Default.Article
        else                                                -> Icons.Default.Description
    }
}

// ── Empty / permission states ────────────────────────────────────────────────

@Composable
private fun EmptyState(tab: BrowserTab, onBrowse: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            tab.icon(),
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Text("Nothing found in ${tab.title}", style = MaterialTheme.typography.titleMedium)
        if (tab == BrowserTab.DOCS) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Android may hide documents from apps. Use “Browse all files” to pick them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onBrowse) {
            Icon(Icons.Default.FolderOpen, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Browse all files")
        }
    }
}

@Composable
private fun PermissionPrompt(onGrant: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Lock,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))
        Text("Allow access to your files", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Needed to show your photos, videos and music here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onGrant) { Text("Allow") }
    }
}

// ── Bottom selection tray ────────────────────────────────────────────────────

@Composable
private fun SelectionBar(
    files: List<SelectedFile>,
    totalSize: Long,
    sending: Boolean,
    onRemove: (SelectedFile) -> Unit,
    onClear: () -> Unit,
    onSend: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shadowElevation = 12.dp,
        tonalElevation = 3.dp
    ) {
        Column(modifier = Modifier.navigationBarsPadding()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items = files, key = { it.uri }) { file ->
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { onRemove(file) },
                        contentAlignment = Alignment.Center
                    ) {
                        val mime = file.mimeType.orEmpty()
                        if (mime.startsWith("image") || mime.startsWith("video")) {
                            AsyncImage(
                                model = file.uri,
                                contentDescription = file.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                imageVector = when {
                                    mime.startsWith("audio") -> Icons.Default.MusicNote
                                    mime == "application/vnd.android.package-archive" -> Icons.Default.Android
                                    else -> Icons.Default.InsertDriveFile
                                },
                                contentDescription = file.name,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.55f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Remove",
                                tint = Color.White,
                                modifier = Modifier.size(11.dp)
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${files.size} selected",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = totalSize.toFormattedSize(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onClear) { Text("Clear") }
                Spacer(Modifier.width(4.dp))
                Button(
                    onClick = onSend,
                    enabled = !sending,
                    shape = RoundedCornerShape(24.dp),
                    contentPadding = PaddingValues(horizontal = 28.dp, vertical = 12.dp)
                ) {
                    Icon(Icons.Default.Send, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Send")
                }
            }
        }
    }
}
