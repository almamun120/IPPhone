package com.ipphone.presentation.screens.filemanager

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import coil.compose.AsyncImage
import com.ipphone.presentation.navigation.Screen
import com.ipphone.utils.toFormattedSize

data class FileCategory(
    val name: String,
    val icon: ImageVector,
    val mimeType: String?
)

val fileCategories = listOf(
    FileCategory("Images", Icons.Default.Image, "image/*"),
    FileCategory("Videos", Icons.Default.VideoLibrary, "video/*"),
    FileCategory("Audio", Icons.Default.AudioFile, "audio/*"),
    FileCategory("Documents", Icons.Default.Description, "application/*"),
    FileCategory("All Files", Icons.Default.Folder, "*/*")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileManagerScreen(
    userId: String,
    navController: NavController,
    viewModel: FileManagerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    
    var selectedCategory by remember { mutableStateOf<FileCategory?>(null) }
    
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.addFilesToSelection(context, uris)
        }
    }
    
    LaunchedEffect(userId) {
        viewModel.setTargetUser(userId)
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { 
                        if (selectedCategory != null) {
                            selectedCategory = null
                        } else {
                            navController.popBackStack()
                        }
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { 
                    Text(
                        if (selectedCategory != null) selectedCategory!!.name 
                        else "Select Files"
                    ) 
                },
                actions = {
                    if (uiState.selectedFiles.isNotEmpty()) {
                        TextButton(onClick = { viewModel.clearSelection() }) {
                            Text("Clear")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (uiState.selectedFiles.isNotEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "${uiState.selectedFiles.size} items selected",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = uiState.totalSelectedSize.toFormattedSize(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        
                        Button(
                            onClick = {
                                val transferId = viewModel.startTransfer()
                                navController.navigate(Screen.Transfer.createRoute(transferId))
                            }
                        ) {
                            Icon(Icons.Default.Send, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Send")
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (selectedCategory == null) {
                // Category Grid
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(fileCategories) { category ->
                        CategoryCard(
                            category = category,
                            onClick = {
                                selectedCategory = category
                                filePickerLauncher.launch(arrayOf(category.mimeType ?: "*/*"))
                            }
                        )
                    }
                }
            }
            
            // Selected Files List
            if (uiState.selectedFiles.isNotEmpty()) {
                Divider()
                
                Text(
                    text = "Selected Files",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp)
                )
                
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = uiState.selectedFiles,
                        key = { it.uri }
                    ) { file ->
                        SelectedFileItem(
                            file = file,
                            onRemove = { viewModel.removeFromSelection(file) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CategoryCard(
    category: FileCategory,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.2f)
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = category.icon,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = category.name,
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

data class SelectedFile(
    val uri: String,
    val name: String,
    val size: Long,
    val mimeType: String?,
    val thumbnail: String? = null
)

@Composable
fun SelectedFileItem(
    file: SelectedFile,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail or icon
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                val thumbModel = remember(file.thumbnail) {
                    val thumb = file.thumbnail
                    if (thumb.isNullOrEmpty()) {
                        null
                    } else if (thumb.startsWith("content://") || thumb.startsWith("file://")) {
                        thumb
                    } else {
                        try {
                            android.util.Base64.decode(thumb, android.util.Base64.DEFAULT)
                        } catch (_: Exception) {
                            null
                        }
                    }
                }

                if (thumbModel as Any? != null) {
                    AsyncImage(
                        model = thumbModel!!,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = when {
                            file.mimeType?.startsWith("image") == true -> Icons.Default.Image
                            file.mimeType?.startsWith("video") == true -> Icons.Default.VideoFile
                            file.mimeType?.startsWith("audio") == true -> Icons.Default.AudioFile
                            else -> Icons.Default.InsertDriveFile
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1
                )
                Text(
                    text = file.size.toFormattedSize(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
