package com.dsh.android.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dsh.android.DshApp
import com.dsh.android.data.store.AppBackground
import com.dsh.android.ui.theme.BackgroundPresets

@Composable
fun BackgroundDialog(
    current: AppBackground,
    onDismiss: () -> Unit,
    onSelect: (AppBackground) -> Unit,
) {
    val context = LocalContext.current
    val store = (context.applicationContext as DshApp).container.backgroundStore
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { onSelect(store.saveImage(context, it)) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义背景") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "选择预设配色，或从相册选一张图片作为背景。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    BackgroundPresets.forEach { preset ->
                        Swatch(
                            preset = preset,
                            selected = sameBackground(current, preset),
                            onClick = { onSelect(preset) },
                        )
                    }
                }
                TextButton(
                    onClick = { picker.launch("image/*") },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("从相册选择图片…") }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("完成") }
        },
    )
}

private fun sameBackground(a: AppBackground, b: AppBackground): Boolean =
    a.kind == b.kind && a.color == b.color && a.color2 == b.color2

@Composable
private fun Swatch(preset: AppBackground, selected: Boolean, onClick: () -> Unit) {
    val fill: Modifier = when (preset.kind) {
        "solid" -> Modifier.background(Color(preset.color ?: 0xFF121318))
        "gradient" -> Modifier.background(
            Brush.verticalGradient(
                listOf(Color(preset.color ?: 0xFF0F172A), Color(preset.color2 ?: 0xFF1E3A8A)),
            ),
        )
        else -> Modifier.background(Color.Transparent)
    }
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .then(fill)
            .then(
                if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape)
                else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (preset.kind == "none") {
            Text("无", style = MaterialTheme.typography.labelSmall)
        }
    }
}
