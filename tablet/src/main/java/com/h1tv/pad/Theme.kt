package com.h1tv.pad

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFFE53950)
val Bg = Color(0xFF0F0F13)
val CardBg = Color(0xFF1C1C22)
val Gray = Color(0xFFA0A0AA)

@Composable
fun PadTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color.White,
            secondaryContainer = Accent.copy(alpha = 0.25f),
            onSecondaryContainer = Color.White,
            background = Bg,
            onBackground = Color.White,
            surface = Bg,
            onSurface = Color.White,
            surfaceVariant = CardBg,
            onSurfaceVariant = Gray,
            surfaceContainer = Color(0xFF17171C),
        ),
        content = content,
    )
}
