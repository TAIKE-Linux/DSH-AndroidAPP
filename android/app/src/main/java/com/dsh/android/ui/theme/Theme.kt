package com.dsh.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// DeepSeek brand blue
val DsBlue = Color(0xFF4D6BFE)
val DsBlueDark = Color(0xFF7B93FF)
val DsBlueContainer = Color(0xFFDDE1FF)
val DsBlueContainerDark = Color(0xFF3A4691)

private val LightColors = lightColorScheme(
    primary = DsBlue,
    onPrimary = Color.White,
    primaryContainer = DsBlueContainer,
    onPrimaryContainer = Color(0xFF131B5C),
    secondary = Color(0xFF5A5D72),
    background = Color(0xFFF9F9FF),
    surface = Color(0xFFF9F9FF),
    surfaceVariant = Color(0xFFE1E1EC),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = DsBlueDark,
    onPrimary = Color(0xFF1A2266),
    primaryContainer = DsBlueContainerDark,
    onPrimaryContainer = DsBlueContainer,
    secondary = Color(0xFFC4C5DD),
    background = Color(0xFF121318),
    surface = Color(0xFF121318),
    surfaceVariant = Color(0xFF2B2B33),
    error = Color(0xFFFFB4AB),
)

@Composable
fun DshTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
