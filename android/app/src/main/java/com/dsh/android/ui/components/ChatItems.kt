package com.dsh.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.dsh.android.data.protocol.TokenUsage
import com.dsh.android.data.store.ChatItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())

fun formatTime(epochMs: Long): String = timeFmt.format(Date(epochMs))

fun formatTokenDelta(usage: TokenUsage?): String? {
    if (usage == null) return null
    val input = usage.inputTokens + (usage.cacheReadTokens ?: 0) + (usage.cacheWriteTokens ?: 0)
    return "本轮 ▲${fmtTokens(input)} ▼${fmtTokens(usage.outputTokens)} tokens"
}

@Composable
fun ChatItemView(item: ChatItem) {
    when (item) {
        is ChatItem.UserBubble -> UserBubbleView(item)
        is ChatItem.AssistantBubble -> AssistantBubbleView(item)
        is ChatItem.ToolCard -> ToolCardView(item)
        is ChatItem.TodoCard -> TodoCardView(item)
        is ChatItem.TurnMarker -> TurnMarkerView(item)
        is ChatItem.Notice -> NoticeView(item)
    }
}

@Composable
private fun UserBubbleView(item: ChatItem.UserBubble) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.End) {
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(max = 320.dp)) {
            Box(
                Modifier
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp, 4.dp, 16.dp, 16.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    item.text,
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                formatTime(item.time),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun AssistantBubbleView(item: ChatItem.AssistantBubble) {
    var reasoningOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        // DeepSeek-web style avatar + name header on the left.
        Box(
            Modifier
                .size(28.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "DS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "DeepSeek Harness",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (item.streaming) {
                    StreamingDots(Modifier.padding(start = 8.dp))
                }
            }
            if (item.reasoning.isNotBlank()) {
                Row(
                    Modifier
                        .padding(top = 4.dp)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "思考过程",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 4.dp).alpha(0.9f),
                    )
                    Icon(
                        if (reasoningOpen) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                AnimatedVisibility(reasoningOpen) {
                    Text(
                        item.reasoning,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                            .padding(8.dp),
                    )
                }
            }
            if (item.text.isNotBlank()) {
                val bubbleModifier = Modifier
                    .padding(top = 4.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                if (item.streaming) {
                    // Streaming tail: render plain text. Re-parsing markdown on
                    // every delta makes long streams jank; the final
                    // assistant/message re-renders the full markdown once.
                    Text(item.text, modifier = bubbleModifier, style = MaterialTheme.typography.bodyMedium)
                } else {
                    MarkdownText(item.text, modifier = bubbleModifier)
                }
            }
            item.usage?.let { usage ->
                Text(
                    "本次调用 ▲${fmtTokens(usage.billedInput)} ▼${fmtTokens(usage.outputTokens)} tokens",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun StreamingDots(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "dots")
    val alpha by transition.animateFloat(
        initialValue = 0.2f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "dotsAlpha",
    )
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(3) {
            Box(
                Modifier
                    .size(4.dp)
                    .alpha(alpha)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
        }
    }
}

@Composable
private fun ToolCardView(item: ChatItem.ToolCard) {
    var open by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
        onClick = { open = !open },
    ) {
        Column(Modifier.animateContentSize().padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.running) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                } else if (item.isError) {
                    Icon(Icons.Default.Warning, contentDescription = "error", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                } else {
                    Icon(Icons.Default.CheckCircle, contentDescription = "ok", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Text(
                    " 工具 ${item.name}",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(open) {
                Column(Modifier.padding(top = 8.dp)) {
                    if (item.arguments.isNotBlank()) {
                        Text("参数", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            item.arguments,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = 2.dp),
                            maxLines = 10,
                        )
                    }
                    if (!item.resultText.isNullOrBlank()) {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Text("结果", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            item.resultText,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(top = 2.dp),
                            maxLines = 16,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TodoCardView(item: ChatItem.TodoCard) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("任务清单", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            for (todo in item.todos) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (icon, tint) = when (todo.status) {
                        "completed" -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary
                        "in_progress" -> Icons.Default.KeyboardArrowUp to Color(0xFFF59E0B)
                        else -> Icons.Default.KeyboardArrowDown to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Icon(icon, contentDescription = todo.status, Modifier.size(16.dp), tint = tint)
                    Text(
                        todo.content,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 6.dp),
                        color = if (todo.status == "completed") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@Composable
private fun TurnMarkerView(item: ChatItem.TurnMarker) {
    val (label, color) = when (item.reasonKind) {
        null -> "第 ${item.turn} 轮 · 进行中" to MaterialTheme.colorScheme.primary
        "completed" -> "第 ${item.turn} 轮 · 完成" to MaterialTheme.colorScheme.primary
        "aborted" -> "第 ${item.turn} 轮 · 已停止" to MaterialTheme.colorScheme.error
        "error" -> "第 ${item.turn} 轮 · 出错" to MaterialTheme.colorScheme.error
        "blocked" -> "第 ${item.turn} 轮 · 被阻止" to MaterialTheme.colorScheme.error
        "max-tokens" -> "第 ${item.turn} 轮 · 达到输出上限" to MaterialTheme.colorScheme.error
        "interrupted" -> "第 ${item.turn} 轮 · 中断" to MaterialTheme.colorScheme.error
        else -> "第 ${item.turn} 轮 · ${item.reasonKind}" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        HorizontalDivider(Modifier.padding(bottom = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = color)
            formatTokenDelta(item.turnUsage)?.let { delta ->
                Text(
                    "  ·  $delta",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun NoticeView(item: ChatItem.Notice) {
    Text(
        item.text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        maxLines = 3,
    )
}
