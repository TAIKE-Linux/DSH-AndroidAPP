package com.dsh.android.ui.viewmodel

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dsh.android.AppContainer
import com.dsh.android.DshApp
import com.dsh.android.data.protocol.ContextBreakdown
import com.dsh.android.data.protocol.ContextPressure
import com.dsh.android.data.protocol.JobView
import com.dsh.android.data.protocol.ModelProviderGroup
import com.dsh.android.data.protocol.SessionModels
import com.dsh.android.data.protocol.TokenUsageProjection
import com.dsh.android.data.remote.ConnectionState
import com.dsh.android.data.store.ChatItem
import com.dsh.android.data.store.PendingApproval
import com.dsh.android.data.store.PendingQuestion
import com.dsh.android.data.store.SessionData
import com.dsh.android.data.store.SessionFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/** Live state of the phone -> PC file transfer shown above the composer. */
data class UploadUiState(
    val name: String = "",
    val sent: Long = 0,
    val total: Long = 0,
    val active: Boolean = true,
    val relPath: String? = null,
    val error: String? = null,
)

/** Quiet period before the "no new messages" hint appears (informative only). */
private const val STALL_NOTICE_MS = 120_000L

data class ChatUiState(
    val sessionId: String = "",
    val title: String? = null,
    val items: List<ChatItem> = emptyList(),
    val tokenUsage: TokenUsageProjection? = null,
    val contextPressure: ContextPressure? = null,
    val contextBreakdown: ContextBreakdown? = null,
    val running: Boolean = false,
    val queuedCount: Int = 0,
    val pendingApprovals: List<PendingApproval> = emptyList(),
    val pendingQuestions: List<PendingQuestion> = emptyList(),
    val jobs: List<JobView> = emptyList(),
    val sending: Boolean = false,
    val connectionState: ConnectionState? = null,
    val models: SessionModels? = null,
    val error: String? = null,
    /** Older history exists beyond the loaded window (context on demand). */
    val hasMore: Boolean = false,
    val loadingOlder: Boolean = false,
    val upload: UploadUiState? = null,
    /** Path mention awaiting insertion into the composer after an upload. */
    val pendingMention: String? = null,
    /** Non-intrusive hint when a running turn produced nothing for a while. */
    val stallNotice: String? = null,
    /** Why the streams are down (surfaced from the close/failure reason). */
    val downReason: String? = null,
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val container: AppContainer get() = (getApplication<DshApp>()).container

    private val _ui = MutableStateFlow(ChatUiState())
    val ui: StateFlow<ChatUiState> = _ui.asStateFlow()

    private val _sending = MutableStateFlow(false)
    private var collector: Job? = null
    private var sessionId: String = ""

    fun open(sessionId: String) {
        if (this.sessionId == sessionId) return
        this.sessionId = sessionId
        _ui.value = ChatUiState(sessionId = sessionId)
        collector?.cancel()
        viewModelScope.launch {
            val binding = container.active.value ?: return@launch
            binding.store.openSession(sessionId)
            val data = binding.store.data(sessionId)
            collector = viewModelScope.launch {
                // Streaming bursts one version bump per chunk frame; a full
                // re-fold per frame janks on long histories. Sample to 80ms:
                // the UI stays smooth and still updates ~12x/sec while the
                // latest state always lands (sample emits the newest value).
                data.version.sample(80.milliseconds).collect { rebuild(data) }
            }
            launch {
                binding.connection.state.collect { st ->
                    _ui.value = _ui.value.copy(connectionState = st)
                }
            }
            launch {
                binding.connection.downReason.collect { reason ->
                    _ui.value = _ui.value.copy(downReason = reason)
                }
            }
            launch {
                binding.store.lastError.collect { err ->
                    _ui.value = _ui.value.copy(error = err)
                }
            }
            // Stall hint: a running turn with no new events for a while might
            // be a long tool call or a dropped stream. Inform only — never
            // reconnect on this signal (long bash/pwsh runs are legitimate).
            launch {
                while (true) {
                    delay(20_000)
                    val d = container.active.value?.store?.data(sessionId) ?: break
                    val lastEventAt = d.events.values.maxOfOrNull { it.time } ?: 0L
                    val now = System.currentTimeMillis()
                    val notice =
                        if (d.running.value && lastEventAt > 0 && now - lastEventAt > STALL_NOTICE_MS) {
                            "电脑端已 ${(now - lastEventAt) / 1000} 秒没有新消息（可能在执行长任务，或连接已中断）"
                        } else null
                    _ui.value = _ui.value.copy(stallNotice = notice)
                }
            }
            rebuild(data)
        }
    }

    fun rebuild(data: SessionData) {
        _ui.value = _ui.value.copy(
            title = data.title(),
            items = SessionFolder.fold(data),
            tokenUsage = data.tokenUsage(),
            contextPressure = data.contextPressure(),
            contextBreakdown = data.contextBreakdown(),
            running = data.running.value,
            queuedCount = data.queue.value.size,
            pendingApprovals = data.approvals.values.toList(),
            pendingQuestions = data.questions.values.toList(),
            jobs = data.jobs.value,
            hasMore = data.hasMore.value,
        )
    }

    /** Pull one older history page (prepends to the loaded window). */
    fun loadOlder() {
        if (_ui.value.loadingOlder) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loadingOlder = true)
            container.active.value?.store?.loadOlder(sessionId)
            _ui.value = _ui.value.copy(loadingOlder = false)
        }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _sending.value) return
        viewModelScope.launch {
            _sending.value = true
            _ui.value = _ui.value.copy(sending = true)
            container.active.value?.store?.sendPrompt(sessionId, trimmed)
            _sending.value = false
            _ui.value = _ui.value.copy(sending = false)
        }
    }

    fun cancelTurn() {
        viewModelScope.launch {
            container.active.value?.store?.cancel(sessionId)
        }
    }

    fun answerApproval(pending: PendingApproval, allowed: Boolean) {
        viewModelScope.launch {
            container.active.value?.store?.answerApproval(pending, allowed)
        }
    }

    fun answerQuestion(pending: PendingQuestion, answers: List<com.dsh.android.data.protocol.Api.QuestionAnswerSpec>) {
        viewModelScope.launch {
            container.active.value?.store?.answerQuestion(pending, answers)
        }
    }

    fun loadModels() {
        viewModelScope.launch {
            val binding = container.active.value ?: return@launch
            runCatching { binding.api.models(sessionId) }.onSuccess { models ->
                _ui.value = _ui.value.copy(models = models)
            }
        }
    }

    fun clearModels() {
        _ui.value = _ui.value.copy(models = null)
    }

    fun selectModel(provider: String, model: String) {
        viewModelScope.launch {
            val binding = container.active.value ?: return@launch
            runCatching { binding.api.selectModel(sessionId, provider, model) }.onSuccess {
                _ui.value = _ui.value.copy(models = null)
            }
        }
    }

    fun renameSession(title: String) {
        viewModelScope.launch {
            container.active.value?.store?.rename(sessionId, title)
        }
    }

    fun clearError() {
        _ui.value = _ui.value.copy(error = null)
    }

    // -- Phone -> PC file upload -------------------------------------------

    /** Stream a picked file to the PC; on success a path mention is queued. */
    fun uploadFile(uri: Uri) {
        if (_ui.value.upload?.active == true) return
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val resolver = app.contentResolver
            var name: String? = null
            var size = -1L
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIdx >= 0) name = cursor.getString(nameIdx)
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) size = cursor.getLong(sizeIdx)
                }
            }
            val displayName = name?.takeIf { it.isNotBlank() } ?: "upload-${System.currentTimeMillis()}"
            _ui.value = _ui.value.copy(upload = UploadUiState(name = displayName, total = size))

            val binding = container.active.value
            if (binding == null) {
                _ui.value = _ui.value.copy(
                    upload = UploadUiState(name = displayName, active = false, error = "未连接服务器"),
                )
                return@launch
            }
            // The gateway places the file inside the session's own workspace.
            val sessionCwd = binding.store.sessions.value
                .firstOrNull { it.sessionId == sessionId }?.cwd
            runCatching {
                binding.api.uploadFile(
                    name = displayName,
                    size = size,
                    sessionCwd = sessionCwd,
                    openStream = { resolver.openInputStream(uri) ?: throw java.io.IOException("无法读取所选文件") },
                    onProgress = { sent -> _ui.value = _ui.value.copy(upload = _ui.value.upload?.copy(sent = sent)) },
                )
            }.onSuccess { result ->
                _ui.value = _ui.value.copy(
                    upload = UploadUiState(
                        name = result.name, sent = result.size, total = result.size,
                        active = false, relPath = result.relPath,
                    ),
                    pendingMention = "请查看我刚上传到电脑的文件：${result.relPath}",
                )
            }.onFailure { e ->
                _ui.value = _ui.value.copy(
                    upload = UploadUiState(name = displayName, active = false, error = e.message ?: "上传失败"),
                )
            }
        }
    }

    fun dismissUpload() {
        _ui.value = _ui.value.copy(upload = null)
    }

    fun consumeMention() {
        _ui.value = _ui.value.copy(pendingMention = null)
    }

    fun currentConnectionState(): ConnectionState? {
        val binding = container.active.value ?: return null
        return binding.connection.state.value
    }

    fun modelGroups(): List<ModelProviderGroup> = _ui.value.models?.groups ?: emptyList()
}
