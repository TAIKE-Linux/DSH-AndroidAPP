package com.dsh.android.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dsh.android.data.protocol.SessionModels

/** Model picker over `session.models` (advisory directory, same as the web UI). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ModelSheet(
    models: SessionModels?,
    onSelect: (provider: String, model: String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text("选择模型", style = MaterialTheme.typography.titleLarge)
            if (models == null) {
                Text("加载中…", modifier = Modifier.padding(vertical = 16.dp))
                return@Column
            }
            Text(
                "当前：${models.current.provider} / ${models.current.model}" +
                    if (models.routable) "" else "（不可路由：该 provider 当前无适配器服务）",
                style = MaterialTheme.typography.bodySmall,
                color = if (models.routable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            models.groups.forEach { group ->
                Text(
                    group.name,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                )
                group.models.forEach { model ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        RadioButton(
                            selected = group.id == models.current.provider && model.id == models.current.model,
                            onClick = { onSelect(group.id, model.id) },
                        )
                        Column(Modifier.padding(start = 4.dp)) {
                            Text(model.name.ifBlank { model.id }, style = MaterialTheme.typography.bodyMedium)
                            if (!model.description.isNullOrBlank()) {
                                Text(model.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
            }
            models.failures.forEach { failure ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Text(
                        " ${failure.name}: ${failure.message}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}
