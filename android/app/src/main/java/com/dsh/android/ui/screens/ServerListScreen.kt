package com.dsh.android.ui.screens

import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dsh.android.DshApp
import com.dsh.android.data.remote.ConnectionState
import com.dsh.android.data.store.ServerConfig
import com.dsh.android.ui.components.BackgroundDialog
import com.dsh.android.ui.viewmodel.ServersViewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerListScreen(
    onOpenSessions: () -> Unit,
    onOpenChat: (String) -> Unit,
    viewModel: ServersViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scan by viewModel.scanState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val backgroundStore = (context.applicationContext as DshApp).container.backgroundStore
    val background by backgroundStore.current.collectAsStateWithLifecycle()
    var showAddDialog by remember { mutableStateOf(false) }
    var prefillUrl by remember { mutableStateOf<String?>(null) }
    var showScanDialog by remember { mutableStateOf(false) }
    var showQrConnectDialog by remember { mutableStateOf(false) }
    var showBackgroundDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ServerConfig?>(null) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("DSH 服务器") },
                actions = {
                    TextButton(onClick = { showQrConnectDialog = true }) { Text("扫码") }
                    TextButton(onClick = { showBackgroundDialog = true }) { Text("背景") }
                    IconButton(onClick = {
                        viewModel.startScan()
                        showScanDialog = true
                    }) {
                        Icon(Icons.Default.Search, contentDescription = "扫描局域网")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "添加服务器")
            }
        },
    ) { padding ->
        if (state.servers.isEmpty()) {
            EmptyServers(Modifier.fillMaxSize().padding(padding))
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.servers, key = { it.id }) { server ->
                    val isActive = server.id == state.activeServerId
                    ServerCard(
                        server = server,
                        isActive = isActive,
                        connectionState = if (isActive) state.connectionState else null,
                        busy = false,
                        onConnect = { viewModel.connectAndOpen(server, onOpenChat) },
                        onOpen = onOpenSessions,
                        onDelete = { pendingDelete = server },
                    )
                }
            }
        }
    }

    if (showScanDialog) {
        ScanDialog(
            scan = scan,
            savedServers = state.servers,
            onPick = { result ->
                showScanDialog = false
                // If this gateway is already saved (token known), connect in
                // one tap; otherwise prefill the add dialog for the token.
                val saved = state.servers.firstOrNull { it.baseUrl.trimEnd('/') == result.baseUrl }
                if (saved != null) {
                    viewModel.connectAndOpen(saved, onOpenChat)
                } else {
                    prefillUrl = result.baseUrl
                    showAddDialog = true
                }
            },
            onPickPublic = { publicUrl ->
                // Gateway advertises a WAN URL (e.g. NAT-traversal tunnel):
                // prefill the add dialog with it so the user can connect from
                // any network after entering the token.
                showScanDialog = false
                prefillUrl = publicUrl
                showAddDialog = true
            },
            onCancel = {
                viewModel.cancelScan()
                showScanDialog = false
            },
            onDismiss = {
                if (!scan.active) showScanDialog = false
            },
        )
    }

    if (showAddDialog) {
        AddServerDialog(
            initialUrl = prefillUrl,
            onDismiss = {
                showAddDialog = false
                prefillUrl = null
            },
            onSave = { name, url, token, allowAnswers, allowSelfSigned ->
                viewModel.saveServer(name, url, token, allowAnswers, allowSelfSigned, onOpenChat)
                showAddDialog = false
                prefillUrl = null
            },
        )
    }

    if (showBackgroundDialog) {
        BackgroundDialog(
            current = background,
            onDismiss = { showBackgroundDialog = false },
            onSelect = { backgroundStore.set(it) },
        )
    }

    if (showQrConnectDialog) {
        QrConnectDialog(
            onDismiss = { showQrConnectDialog = false },
            onConnect = { uri ->
                if (viewModel.connectDeepLink(uri, onOpenChat)) true else false
            },
        )
    }

    pendingDelete?.let { server ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除服务器") },
            text = { Text("确定删除 ${server.name}（${server.baseUrl}）吗？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeServer(server.id)
                    pendingDelete = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ServerCard(
    server: ServerConfig,
    isActive: Boolean,
    connectionState: ConnectionState?,
    busy: Boolean,
    onConnect: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.surface,
        ),
        onClick = {
            if (isActive) onOpen() else onConnect()
        },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val dotColor = when {
                !isActive -> MaterialTheme.colorScheme.outlineVariant
                connectionState == ConnectionState.CONNECTED -> Color(0xFF16A34A)
                connectionState == ConnectionState.CONNECTING -> MaterialTheme.colorScheme.primary
                connectionState == ConnectionState.RECONNECTING -> Color(0xFFF59E0B)
                else -> MaterialTheme.colorScheme.error
            }
            androidx.compose.foundation.layout.Box(
                Modifier.size(12.dp).background(dotColor, CircleShape),
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(server.name, style = MaterialTheme.typography.titleMedium)
                Text(server.baseUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (isActive && connectionState != null) {
                    Text(
                        when (connectionState) {
                            ConnectionState.CONNECTED -> "已连接"
                            ConnectionState.CONNECTING -> "连接中…"
                            ConnectionState.RECONNECTING -> "重连中…"
                            ConnectionState.STOPPED -> "未连接"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else if (!isActive) {
                    Text("点击连接", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (isActive && busy) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (isActive) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "打开", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EmptyServers(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Settings, contentDescription = null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("还没有服务器", style = MaterialTheme.typography.titleMedium)
        Text(
            "在电脑上启动 DeepSeek Harness（dsh web）和远程网关后：\n\n" +
                "· 点右上角 🔍 自动扫描局域网发现电脑\n" +
                "· 或点右下角 + 手动输入电脑 IP\n\n" +
                "1. 电脑：dsh web\n" +
                "2. 电脑：进入 gateway 目录执行 npm install 与 node src/index.js\n" +
                "3. 手机：连接同一 WiFi，扫描后填入网关 token",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun ScanDialog(
    scan: com.dsh.android.data.remote.LanScanState,
    savedServers: List<ServerConfig>,
    onPick: (com.dsh.android.data.remote.LanScanResult) -> Unit,
    onPickPublic: (String) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("扫描局域网") },
        text = {
            Column {
                if (scan.error != null) {
                    Text(scan.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                } else if (scan.active) {
                    LinearProgressIndicator(
                        progress = { if (scan.total > 0) scan.scanned.toFloat() / scan.total else 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "正在探测 ${scan.total} 个 IP:端口（含自定义端口 8743/8744）…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                } else if (scan.results.isEmpty()) {
                    Text(
                        "未发现 dsh-remote-gateway。请确认：\n" +
                            "1. 电脑已运行 dsh web 与网关（默认端口 8742）\n" +
                            "2. 手机与电脑在同一 WiFi/网段\n" +
                            "3. 路由器未开启 AP 隔离\n\n" +
                            "也可以点右下角 + 手动输入电脑 IP。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (scan.results.isNotEmpty()) {
                    Text(
                        "发现 ${scan.results.size} 个网关（点「连接」直接连，或「使用」新添加）：",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                    scan.results.forEach { result ->
                        val saved = savedServers.firstOrNull { it.baseUrl.trimEnd('/') == result.baseUrl }
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(result.baseUrl, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "dsh-remote-gateway v${result.version} · ${result.latencyMs}ms" +
                                        (saved?.let { " · 已保存（${it.name}）" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (result.publicUrl != null) {
                                    Text(
                                        "远程访问：${result.publicUrl}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            if (saved != null) {
                                TextButton(onClick = { onPick(result) }) { Text("连接") }
                            } else {
                                TextButton(onClick = { onPick(result) }) { Text("使用") }
                            }
                            if (result.publicUrl != null) {
                                TextButton(onClick = { onPickPublic(result.publicUrl) }) { Text("远程") }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) { Text(if (scan.active) "取消" else "关闭") }
        },
    )
}

/** 输入补全：以数字开头或含点号的主机/IP 自动补上 http:// 前缀（不干扰手输 https://）。 */
private fun normalizeUrlInput(input: String): String {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return ""
    val lower = trimmed.lowercase()
    if (lower.startsWith("http://") || lower.startsWith("https://")) return trimmed
    val hostLike = Regex("""^(\d|[A-Za-z0-9-]+\.)[A-Za-z0-9.-]*(:[0-9]{1,5})?$""")
    return if (hostLike.matches(trimmed)) "http://$trimmed" else trimmed
}

@Composable
private fun QrConnectDialog(
    onDismiss: () -> Unit,
    onConnect: (String) -> Boolean,
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf<String?>(null) }

    // Real in-app camera scanner (zxing-android-embedded CaptureActivity).
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (!contents.isNullOrBlank()) {
            if (onConnect(contents.trim())) {
                onDismiss()
            } else {
                feedback = "无法解析该二维码内容"
            }
        }
    }

    fun readClipboard() {
        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = cm?.primaryClip
        if (clip != null && clip.itemCount > 0) {
            text = clip.getItemAt(0).coerceToText(context).toString()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("扫码连接") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "点击下方按钮直接调用手机摄像头，扫描电脑端「远程网关」页的二维码即可连接。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(
                    onClick = {
                        scanner.launch(
                            ScanOptions()
                                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                .setPrompt("对准电脑上的二维码")
                                .setBeepEnabled(false)
                                .setOrientationLocked(false),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("📷 打开摄像头扫码") }
                Text(
                    "或粘贴连接串：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = { readClipboard() }) { Text("从剪贴板读取") }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("连接串") },
                    placeholder = { Text("dsh-gateway://connect?u=…&t=…") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                feedback?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = text.isNotBlank(),
                onClick = {
                    if (onConnect(text)) {
                        onDismiss()
                    } else {
                        feedback = "解析失败：请确认是 dsh-gateway://connect 连接串"
                    }
                },
            ) { Text("连接") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

@Composable
private fun AddServerDialog(
    initialUrl: String? = null,
    onDismiss: () -> Unit,
    onSave: (name: String, url: String, token: String, allowAnswers: Boolean, allowSelfSigned: Boolean) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf(initialUrl ?: "") }
    var token by remember { mutableStateOf("") }
    var allowAnswers by remember { mutableStateOf(false) }
    var allowSelfSigned by remember { mutableStateOf(false) }
    val https = url.trim().startsWith("https://", ignoreCase = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加服务器") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("名称（可选）") }, singleLine = true)
                OutlinedTextField(
                    url,
                    { input -> url = normalizeUrlInput(input) },
                    label = { Text("网关地址") },
                    placeholder = { Text("192.168.1.10:8742（自动补全 http://）") },
                    singleLine = true,
                )
                OutlinedTextField(token, { token = it }, label = { Text("Token") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("允许远程审批/回答", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "让手机可以批准沙箱操作、回答代理提问（安全敏感，建议仅可信网络开启）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(allowAnswers, { allowAnswers = it })
                }
                if (https) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("信任自签名证书", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "网关自动生成 HTTPS 证书时需开启；仅建议在可信家庭网络使用（token 仍会加密传输）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(allowSelfSigned, { allowSelfSigned = it })
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = url.startsWith("http://") || url.startsWith("https://"),
                onClick = { onSave(name, url, token, allowAnswers, allowSelfSigned) },
            ) { Text("保存并连接") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
