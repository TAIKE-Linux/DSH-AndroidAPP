package com.dsh.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dsh.android.data.protocol.ContextBreakdown
import com.dsh.android.data.protocol.ContextPressure
import com.dsh.android.data.protocol.TokenUsageProjection
import java.util.Locale

fun fmtTokens(n: Long): String = when {
    n >= 1_000_000 -> String.format(Locale.US, "%.2fM", n / 1_000_000.0)
    n >= 1_000 -> String.format(Locale.US, "%.1fK", n / 1_000.0)
    else -> n.toString()
}

/** Compact live token meter above the composer. */
@Composable
fun TokenBar(
    usage: TokenUsageProjection?,
    pressure: ContextPressure?,
    onOpenDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssistChip(
            onClick = onOpenDetails,
            label = {
                Text(
                    if (usage == null) "tokens: —"
                    else "输入 ${fmtTokens(usage.billedInput)} · 输出 ${fmtTokens(usage.outputTokens)} · 合计 ${fmtTokens(usage.total)}",
                    style = MaterialTheme.typography.labelMedium,
                )
            },
        )
        val occupancy = pressure?.occupancy
        if (occupancy != null) {
            Column(Modifier.weight(1f)) {
                LinearProgressIndicator(
                    progress = { occupancy },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "上下文 ${(occupancy * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TokenUsageSheet(
    usage: TokenUsageProjection?,
    pressure: ContextPressure?,
    breakdown: ContextBreakdown?,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text("Token 消耗", style = MaterialTheme.typography.titleLarge)
            Text(
                "会话累计（来自 DSH session projection tokenUsage，与网页端同一数据源）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            RowStat("未缓存输入", usage?.uncachedInputTokens)
            RowStat("缓存读", usage?.cacheReadTokens)
            RowStat("缓存写", usage?.cacheWriteTokens)
            RowStat("输出", usage?.outputTokens)
            RowStat("合计", usage?.total)
            if (pressure != null) {
                Text("上下文占用", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                RowStat("上次请求压测 (pressureTokens)", pressure.pressureTokens)
                RowStat("下一次请求预计 (projectedTokens)", pressure.projectedTokens)
                RowStat("窗口容量 (contextWindow)", pressure.contextWindow)
            }
            if (breakdown != null) {
                Text("上下文构成（启发式估算）", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                RowStat("系统提示", breakdown.systemTokens)
                RowStat("工具 schema", breakdown.toolsTokens)
                RowStat("消息内容", breakdown.messageTokens)
            }
            if (usage == null) {
                Text(
                    "暂无用量数据：等代理产生第一次请求后自动出现。",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun RowStat(label: String, value: Long?) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value?.let { fmtTokens(it) } ?: "—", style = MaterialTheme.typography.bodyMedium)
    }
}
