package com.dsh.android.ui.theme

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.dsh.android.data.store.AppBackground

/** Cosmetic backgrounds offered by the picker. */
val BackgroundPresets = listOf(
    AppBackground(kind = "none"),
    AppBackground(kind = "solid", color = 0xFF0F172A),
    AppBackground(kind = "solid", color = 0xFF1E293B),
    AppBackground(kind = "solid", color = 0xFF4D6BFE),
    AppBackground(kind = "gradient", color = 0xFF0F172A, color2 = 0xFF1E3A8A),
    AppBackground(kind = "gradient", color = 0xFF312E81, color2 = 0xFF831843),
)

/**
 * Draws the user-selected background behind the app content. Screens declare a
 * transparent Scaffold container so this shows through; cards/surfaces keep
 * their own colors for readability.
 */
@Composable
fun AppBackground(background: AppBackground, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        when (background.kind) {
            "solid" -> Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(background.color ?: 0xFF121318)),
            )

            "gradient" -> Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(background.color ?: 0xFF0F172A),
                                Color(background.color2 ?: 0xFF1E3A8A),
                            ),
                        ),
                    ),
            )

            "image" -> background.imagePath?.let { path ->
                val bitmap = remember(path) {
                    runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                    // Dark scrim keeps foreground text legible on any photo.
                    Box(Modifier.fillMaxSize().background(Color(0x66000000)))
                }
            }
        }
        content()
    }
}
