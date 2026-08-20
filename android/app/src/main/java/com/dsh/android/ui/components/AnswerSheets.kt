package com.dsh.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dsh.android.data.protocol.Api
import com.dsh.android.data.store.PendingApproval
import com.dsh.android.data.store.PendingQuestion

/**
 * Approval decision UI for a `approval/requested` frame. The harness's sandbox
 * stack asks before a tool runs (bash/pwsh/fs writes …); answering here maps to
 * the official /api/respond outcome (allowed-once | rejected).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalSheet(
    pending: PendingApproval,
    canAnswer: Boolean,
    onAllow: () -> Unit,
    onReject: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Text(
                    " 需要批准才能继续",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                "电脑上的代理请求运行工具 ${pending.toolName}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            pending.reason?.takeIf { it.isNotBlank() }?.let { reason ->
                Text(
                    reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (canAnswer) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(onClick = onAllow, modifier = Modifier.weight(1f)) { Text("允许一次") }
                    OutlinedButton(onClick = onReject, modifier = Modifier.weight(1f)) { Text("拒绝") }
                }
            } else {
                Text(
                    "远程回答审批默认关闭（安全考虑）。请在“服务器”页打开该服务器的“允许远程审批”开关，或在电脑上的 Harness 页面处理。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

/** Question UI for a `question/requested` frame (the harness's ask-user-question tool). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuestionSheet(
    pending: PendingQuestion,
    canAnswer: Boolean,
    onAnswer: (List<Api.QuestionAnswerSpec>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selections by remember(pending.rpcId) { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var customTexts by remember(pending.rpcId) { mutableStateOf<Map<String, String>>(emptyMap()) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text("代理需要你回答", style = MaterialTheme.typography.titleMedium)
            pending.questions.forEach { q ->
                Column(Modifier.padding(top = 12.dp)) {
                    q.header?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                    Text(q.question, style = MaterialTheme.typography.bodyMedium)
                    q.detail?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
                    }
                    q.options?.let { options ->
                        val selected = selections[q.id] ?: emptyList()
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                            options.forEach { option ->
                                FilterChip(
                                    selected = option.label in selected,
                                    onClick = {
                                        selections = selections + (q.id to when {
                                            option.label in selected -> selected - option.label
                                            q.multiSelect -> selected + option.label
                                            else -> listOf(option.label)
                                        })
                                    },
                                    label = {
                                        Text(option.label + if (option.description != null) " — ${option.description}" else "")
                                    },
                                )
                            }
                        }
                    }
                    if (q.options == null || q.multiSelect) {
                        OutlinedTextField(
                            value = customTexts[q.id] ?: "",
                            onValueChange = { customTexts = customTexts + (q.id to it) },
                            label = { Text("其他回答（可选）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    }
                }
            }
            if (canAnswer) {
                val ready = pending.questions.any { q ->
                    (selections[q.id]?.isNotEmpty() == true) || (!customTexts[q.id].isNullOrBlank())
                }
                Button(
                    onClick = {
                        val answers = pending.questions.map { q ->
                            Api.QuestionAnswerSpec(
                                id = q.id,
                                selected = selections[q.id] ?: emptyList(),
                                custom = customTexts[q.id]?.takeIf { it.isNotBlank() },
                            )
                        }
                        onAnswer(answers)
                    },
                    enabled = ready,
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                ) { Text("提交回答") }
            } else {
                Text(
                    "远程回答已关闭。请在“服务器”页打开“允许远程审批/回答”，或在电脑上处理。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}
