package com.ipphone.presentation.screens.dialpad

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.ipphone.presentation.navigation.Screen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialpadScreen(
    navController: NavController,
    viewModel: DialpadViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val digits = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "*", "0", "#")

    Scaffold(
        topBar = { TopAppBar(title = { Text("Direct IP Call") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Display Area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = uiState.digits,
                        style = MaterialTheme.typography.displayMedium.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 36.sp
                        ),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                    if (uiState.digits.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onBackspace() }) {
                            Icon(Icons.Default.Backspace, contentDescription = "Backspace")
                        }
                    }
                }
            }

            // Dialpad Grid
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(bottom = 32.dp)
            ) {
                items(digits) { digit ->
                    DialpadButton(
                        text = digit,
                        subtext = getSubtext(digit),
                        onClick = { viewModel.onDigitClick(digit) }
                    )
                }
            }
        }
    }

    // FIX Issue 3: Use resolvedId (UUID) for all navigation so that
    // calls/chats always target the correct device entry in the DB.
    if (uiState.isOptionsVisible) {
        val targetId = uiState.resolvedId.ifBlank { uiState.digits }

        ModalBottomSheet(
            onDismissRequest = { viewModel.dismissOptions() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    text = "Action for ${uiState.digits}",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                ActionItem(
                    icon = Icons.Default.Phone,
                    label = "Audio Call",
                    onClick = {
                        viewModel.dismissOptions()
                        // FIX: Navigate using targetId (resolved UUID) not raw digits
                        navController.navigate(Screen.Call.createRoute(targetId, "AUDIO"))
                    }
                )
                ActionItem(
                    icon = Icons.Default.Videocam,
                    label = "Video Call",
                    onClick = {
                        viewModel.dismissOptions()
                        navController.navigate(Screen.Call.createRoute(targetId, "VIDEO"))
                    }
                )
                ActionItem(
                    icon = Icons.Default.Chat,
                    label = "Send Message",
                    onClick = {
                        viewModel.dismissOptions()
                        navController.navigate(Screen.Chat.createRoute(targetId))
                    }
                )
                ActionItem(
                    icon = Icons.Default.AttachFile,
                    label = "File Transfer",
                    onClick = {
                        viewModel.dismissOptions()
                        navController.navigate(Screen.FileManager.createRoute(targetId))
                    }
                )
            }
        }
    }
}

@Composable
fun DialpadButton(
    text: String,
    subtext: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.size(80.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold)
            )
            if (subtext.isNotEmpty()) {
                Text(
                    text = subtext,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun ActionItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(16.dp))
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

private fun getSubtext(digit: String): String = when (digit) {
    "2" -> "ABC"
    "3" -> "DEF"
    "4" -> "GHI"
    "5" -> "JKL"
    "6" -> "MNO"
    "7" -> "PQRS"
    "8" -> "TUV"
    "9" -> "WXYZ"
    "*" -> "."
    else -> ""
}