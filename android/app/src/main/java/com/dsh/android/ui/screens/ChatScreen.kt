package com.dsh.android.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dsh.android.R
import com.dsh.android.data.remote.ConnectionState
import com.dsh.android.data.remote.friendlyDownReason
import com.dsh.android.data.store.ChatItem
import com.dsh.android.ui.components.ApprovalSheet
import com.dsh.android.ui.components.ChatItemView
import com.dsh.android.ui.components.ModelSheet
import com.dsh.android.ui.components.QuestionSheet
import com.dsh.android.ui.components.TokenBar
import com.dsh.android.ui.components.TokenUsageSheet
import com.dsh.android.ui.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    sessionId: String,
    onBack: () -> Unit,
    viewModel: ChatViewModel = viewModel(key = "chat-$sessionId"),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    var showTokenSheet by remember { mutableStateOf(false) }
    var showModelSheet by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var activeQuestion by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(sessionId) {
        viewModel.open(sessionId)
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // The user is "reading" when the last items are not visible; streaming
    // must not yank the viewport in that case.
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            total == 0 || lastVisible >= total - 2
        }
    }
    val lastItem = ui.items.lastOrNull()
    val lastTextLen = (lastItem as? ChatItem.AssistantBubble)?.text?.length ?: 0
    LaunchedEffect(ui.items.size, lastTextLen) {
        if (ui.items.isNotEmpty() && atBottom) {
            listState.scrollToItem(ui.items.lastIndex)
        }
    }

    // Paging anchor: seq of the topmost visible message before "load older",
    // restored after the older page is prepended so the viewport stays put.
    var anchorSeq by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(ui.loadingOlder) {
        if (!ui.loadingOlder) {
            anchorSeq?.let { seq ->
                val idx = ui.items.indexOfFirst { it.seq == seq }
                if (idx >= 0) listState.scrollToItem(idx)
                anchorSeq = null
            }
        }
    }

    val canAnswer = viewModel.canAnswerRemotely()

    // After an upload finishes, the viewmodel queues a path mention; drop it
    // into the composer so the user can just hit send.
    LaunchedEffect(ui.pendingMention) {
        val mention = ui.pendingMention
        if (!mention.isNullOrBlank()) {
            input = if (input.isBlank()) mention else "$mention\n$input"
            viewModel.consumeMention()
        }
    }

    // System file picker (any type); the content stream is uploaded without
    // ever loading the whole file into memory.
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { viewModel.uploadFile(it) }
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty()) return
        viewModel.send(text)
        input = ""
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            ui.title ?: "新会话",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            when (ui.connectionState) {
                                ConnectionState.CONNECTED ->
                                    if (ui.running) {
                                        if (ui.queuedCount > 0) "任务运行中… · 排队 ${ui.queuedCount} 条"
                                        else "任务运行中…"
                                    } else "已连接"

                                ConnectionState.CONNECTING -> friendlyDownReason(ui.downReason) ?: "连接中…"
                                ConnectionState.RECONNECTING -> "重连中…（${friendlyDownReason(ui.downReason) ?: "自动重试"}）"
                                else -> friendlyDownReason(ui.downReason) ?: "未连接"
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (ui.connectionState == ConnectionState.CONNECTED) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (ui.running) {
                        TextButton(onClick = { viewModel.cancelTurn() }) { Text("停止") }
                    }
                    IconButton(onClick = { showRenameDialog = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = "重命名会话")
                    }
                    TextButton(onClick = { showModelSheet = true; viewModel.loadModels() }) {
                        Text(ui.models?.current?.model ?: "模型")
                    }
                },
            )
        },
        floatingActionButton = {
            if (!atBottom && ui.items.isNotEmpty()) {
                SmallFloatingActionButton(
                    onClick = { scope.launch { listState.scrollToItem(ui.items.lastIndex) } },
                ) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "回到底部")
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .imePadding()
                    .navigationBarsPadding(),
            ) {
                // Pending approvals/questions
                ui.pendingApprovals.firstOrNull()?.let { pending ->
                    if (canAnswer) {
                        ApprovalSheet(
                            pending = pending,
                            canAnswer = true,
                            onAllow = { viewModel.answerApproval(pending, true) },
                            onReject = { viewModel.answerApproval(pending, false) },
                            onDismiss = { },
                        )
                    } else {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                                Text(
                                    " 电脑端等待批准：${pending.toolName}（远程审批未开启）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                            }
                        }
                    }
                }
                ui.pendingQuestions.firstOrNull()?.let { q ->
                    if (canAnswer && activeQuestion != q.rpcId) {
                        LaunchedEffect(q.rpcId) { activeQuestion = q.rpcId }
                    }
                }
                ui.pendingQuestions.firstOrNull { it.rpcId == activeQuestion }?.let { q ->
                    QuestionSheet(
                        pending = q,
                        canAnswer = canAnswer,
                        onAnswer = { answers ->
                            viewModel.answerQuestion(q, answers)
                            activeQuestion = null
                        },
                        onDismiss = { activeQuestion = null },
                    )
                }

                ui.error?.let { error ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                error,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { viewModel.clearError() }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "关闭", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
                ) {
                    // Context is transferred lightly: only the latest turn is
                    // loaded by default; older pages stream in on demand.
                    item(key = "history-pager") {
                        when {
                            ui.loadingOlder -> Box(
                                Modifier.fillMaxWidth().padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            }

                            ui.hasMore -> TextButton(
                                onClick = {
                                    anchorSeq = listState.layoutInfo.visibleItemsInfo
                                        .firstOrNull { it.key != "history-pager" }
                                        ?.key?.toString()?.substringAfterLast('-')?.toLongOrNull()
                                    viewModel.loadOlder()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("加载更早的消息…") }

                            ui.items.isNotEmpty() -> Text(
                                "已显示最近一次提问的内容",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            )
                        }
                    }
                    itemsIndexed(
                        ui.items,
                        key = { _, item -> "${item::class.simpleName}-${item.seq}" },
                        // Same-type items share composition groups: cheaper
                        // diffing during streaming-heavy recompositions.
                        contentType = { _, item -> item::class },
                    ) { _, item ->
                        ChatItemView(item)
                    }
                }

                // Background jobs the session can see (session/jobs snapshot).
                if (ui.jobs.isNotEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ui.jobs.forEach { job ->
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = MaterialTheme.shapes.small,
                            ) {
                                Text(
                                    "${job.label} · ${job.status}",
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                }

                // Phone -> PC file transfer status (streams, never buffered).
                ui.upload?.let { up ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (up.active) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                    Text("正在上传 ${up.name}", style = MaterialTheme.typography.bodySmall)
                                    if (up.total > 0) {
                                        LinearProgressIndicator(
                                            progress = { (up.sent.toFloat() / up.total).coerceIn(0f, 1f) },
                                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                        )
                                    }
                                }
                            } else if (up.error != null) {
                                Text(
                                    "上传失败：${up.error}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.weight(1f),
                                )
                            } else {
                                Text(
                                    "已上传 → ${up.relPath ?: up.name}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (!up.active) {
                                IconButton(onClick = { viewModel.dismissUpload() }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = "关闭", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }

                // Stall hint (informative only; long tool runs are legitimate).
                ui.stallNotice?.let { notice ->
                    Surface(
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                    ) {
                        Text(
                            notice,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }

                TokenBar(
                    usage = ui.tokenUsage,
                    pressure = ui.contextPressure,
                    onOpenDetails = { showTokenSheet = true },
                )

                // Composer: a single rounded field with attach + send inside.
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(28.dp),
                    placeholder = { Text("向电脑上的 Harness 发布任务…") },
                    leadingIcon = {
                        IconButton(
                            onClick = { filePicker.launch("*/*") },
                            enabled = ui.upload?.active != true,
                        ) {
                            Icon(painterResource(R.drawable.ic_attach_file), contentDescription = "上传文件到电脑")
                        }
                    },
                    trailingIcon = {
                        IconButton(
                            onClick = { send() },
                            enabled = input.isNotBlank() && !ui.sending,
                        ) {
                            if (ui.sending) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "发送",
                                    tint = if (input.isNotBlank()) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    minLines = 1,
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                )
            }
        }
    }

    if (showTokenSheet) {
        TokenUsageSheet(
            usage = ui.tokenUsage,
            pressure = ui.contextPressure,
            breakdown = ui.contextBreakdown,
            onDismiss = { showTokenSheet = false },
        )
    }
    if (showModelSheet) {
        ModelSheet(
            models = ui.models,
            onSelect = { provider, model -> viewModel.selectModel(provider, model) },
            onDismiss = { showModelSheet = false; viewModel.clearModels() },
        )
    }
    if (showRenameDialog) {
        var newTitle by remember { mutableStateOf(ui.title ?: "") }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("重命名会话") },
            text = {
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    singleLine = true,
                    placeholder = { Text("会话标题") },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = newTitle.isNotBlank(),
                    onClick = {
                        viewModel.renameSession(newTitle.trim())
                        showRenameDialog = false
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("取消") }
            },
        )
    }
}

fun ChatViewModel.canAnswerRemotely(): Boolean =
    (getApplication<com.dsh.android.DshApp>()).container.active.value?.config?.allowRemoteAnswers == true
