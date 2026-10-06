package com.ipphone.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ipphone.presentation.theme.Offline
import com.ipphone.presentation.theme.Online

@Composable
fun PresenceIndicator(
    isOnline: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 12.dp,
    borderWidth: Dp = 2.dp,
    borderColor: Color = Color.White
) {
    Box(
        modifier = modifier
            .size(size)
            .background(
                color = if (isOnline) Online else Offline,
                shape = CircleShape
            )
            .border(
                width = borderWidth,
                color = borderColor,
                shape = CircleShape
            )
    )
}
