package com.dsh.android.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dsh.android.AppContainer
import com.dsh.android.DshApp
import com.dsh.android.data.remote.ConnectionState
import com.dsh.android.data.remote.LanScanState
import com.dsh.android.data.remote.LanScanner
import com.dsh.android.data.store.ServerConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

data class ServerUiState(
    val servers: List<ServerConfig> = emptyList(),
    val activeServerId: String? = null,
    val connectionState: ConnectionState? = null,
    val bindingServerName: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ServersViewModel(app: Application) : AndroidViewModel(app) {

    private val container: AppContainer get() = (getApplication<DshApp>()).container

    /** LAN scanner: user-triggered discovery of dsh-remote-gateway instances. */
    val scanner = LanScanner(getApplication())
    val scanState: StateFlow<LanScanState> = scanner.state

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    val uiState: StateFlow<ServerUiState> = combine(
        container.serverStore.servers,
        container.serverStore.activeServerId,
        container.active.flatMapLatest { binding ->
            binding?.connection?.state ?: flowOf(ConnectionState.STOPPED)
        },
    ) { servers, activeId, connState ->
        val binding = container.active.value
        ServerUiState(
            servers = servers,
            activeServerId = activeId,
            connectionState = if (binding == null) null else connState,
            bindingServerName = binding?.config?.name,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ServerUiState())

    fun saveServer(name: String, baseUrl: String, token: String, allowRemoteAnswers: Boolean) {
        viewModelScope.launch {
            _busy.value = true
            // 兜底规范化：缺少协议前缀时补 http://（对话框已做实时补全，这里是第二道防线）
            val trimmedUrl = baseUrl.trim().trimEnd('/')
            val normalizedUrl = if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(trimmedUrl)) {
                trimmedUrl
            } else {
                "http://$trimmedUrl"
            }
            val server = ServerConfig(
                id = UUID.randomUUID().toString(),
                name = name.trim().ifBlank { normalizedUrl },
                baseUrl = normalizedUrl,
                token = token.trim(),
                allowRemoteAnswers = allowRemoteAnswers,
            )
            container.serverStore.upsert(server)
            container.serverStore.setActive(server.id)
            connect(server)
            _busy.value = false
        }
    }

    fun connect(server: ServerConfig) {
        viewModelScope.launch {
            _busy.value = true
            container.serverStore.setActive(server.id)
            container.bind(server)
            _busy.value = false
        }
    }

    fun disconnect() {
        container.unbind()
    }

    fun startScan() {
        scanner.startScan(viewModelScope)
    }

    fun cancelScan() {
        scanner.cancel()
    }

    fun dismissScan() {
        if (!scanner.state.value.active) scanner.cancel()
    }

    fun removeServer(id: String) {
        viewModelScope.launch {
            if (container.active.value?.config?.id == id) container.unbind()
            container.serverStore.remove(id)
        }
    }

    /** Re-bind on app start if a server was active last time. */
    suspend fun restoreActive() {
        val activeId = container.serverStore.activeServerId.first() ?: return
        if (container.active.value != null) return
        val server = container.serverStore.servers.first().find { it.id == activeId } ?: return
        container.serverStore.setActive(server.id)
        container.bind(server)
    }
}
