package com.dsh.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

@Composable
private fun occupancyColor(occupancy: Float): Color = when {
    occupancy < 0.6f -> Color(0xFF16A34A)
    occupancy < 0.8f -> Color(0xFFF59E0B)
    else -> MaterialTheme.colorScheme.error
}

/** Compact live token + context meter above the composer (two-line layout so
 *  the long token chip never squeezes the context bar into wrapping). */
@Composable
fun TokenBar(
    usage: TokenUsageProjection?,
    pressure: ContextPressure?,
    onOpenDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val occupancy = pressure?.occupancy
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AssistChip(
                onClick = onOpenDetails,
                label = {
                    Text(
                        if (usage == null) "tokens: —"
                        else "入 ${fmtTokens(usage.billedInput)} · 出 ${fmtTokens(usage.outputTokens)} · 合计 ${fmtTokens(usage.total)}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
            )
        }
        if (occupancy != null) {
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { occupancy },
                modifier = Modifier.fillMaxWidth(),
                color = occupancyColor(occupancy),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "上下文 ${(occupancy * 100).toInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = occupancyColor(occupancy),
                )
                val remaining = pressure?.remainingTokens
                if (remaining != null) {
                    Text(
                        "剩余 ${fmtTokens(remaining)} tokens",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
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
            Text("上下文与 Token", style = MaterialTheme.typography.titleLarge)
            Text(
                "数据与网页端同源（DSH session projection）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            // -- Context occupancy -------------------------------------------------
            if (pressure != null && pressure.occupancy != null) {
                SectionTitle("上下文占用")
                val occupancy = pressure.occupancy
                LinearProgressIndicator(
                    progress = { occupancy },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    color = occupancyColor(occupancy),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "${(occupancy * 100).toInt()}%",
                        style = MaterialTheme.typography.titleMedium,
                        color = occupancyColor(occupancy),
                    )
                    Text(
                        pressure.projectedTokens?.let { "预计 ${fmtTokens(it)}" } ?: "预计 —",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("窗口 ${fmtTokens(pressure.contextWindow ?: 0)}", style = MaterialTheme.typography.bodySmall)
                    pressure.remainingTokens?.let {
                        Text("剩余 ${fmtTokens(it)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                pressure.pressureTokens?.let {
                    RowStat("上次请求压测", it)
                }
            } else {
                Text(
                    "暂无上下文压测数据：等代理产生第一次请求后自动出现。",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }

            // -- Context breakdown --------------------------------------------------
            if (breakdown != null) {
                SectionTitle("上下文构成（启发式估算）")
                val parts = listOf(
                    Triple("系统提示", breakdown.systemTokens, Color(0xFF4D6BFE)),
                    Triple("工具 schema", breakdown.toolsTokens, Color(0xFF7C3AED)),
                    Triple("消息内容", breakdown.messageTokens, Color(0xFF16A34A)),
                ).filter { (it.second ?: 0) > 0 }
                val total = parts.sumOf { it.second ?: 0 }
                if (parts.isNotEmpty() && total > 0) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp)
                            .height(12.dp),
                    ) {
                        parts.forEach { (_, tokens, color) ->
                            Box(
                                Modifier
                                    .weight(tokens!!.toFloat())
                                    .fillMaxWidth()
                                    .height(12.dp)
                                    .background(color, RoundedCornerShape(50)),
                            )
                            Spacer(Modifier.width(2.dp))
                        }
                    }
                    parts.forEach { (label, tokens, color) ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .width(10.dp)
                                    .height(10.dp)
                                    .background(color, RoundedCornerShape(50)),
                            )
                            Text(
                                "  $label",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            Text(fmtTokens(tokens ?: 0), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else {
                    RowStat("系统提示", breakdown.systemTokens)
                    RowStat("工具 schema", breakdown.toolsTokens)
                    RowStat("消息内容", breakdown.messageTokens)
                }
            }

            // -- Token usage ----------------------------------------------------------
            SectionTitle("Token 消耗（会话累计）")
            RowStat("未缓存输入", usage?.uncachedInputTokens)
            RowStat("缓存读", usage?.cacheReadTokens)
            RowStat("缓存写", usage?.cacheWriteTokens)
            RowStat("输出", usage?.outputTokens)
            RowStat("合计", usage?.total)

            if (usage == null) {
                Text(
                    "暂无用量数据：等代理产生第一次请求后自动出现。",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private val ContextPressure.remainingTokens: Long?
    get() = if (projectedTokens != null && contextWindow != null) (contextWindow - projectedTokens).coerceAtLeast(0) else null

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
    )
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
