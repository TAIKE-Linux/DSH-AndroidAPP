package com.dsh.android.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dsh.android.AppContainer
import com.dsh.android.DshApp
import com.dsh.android.data.protocol.HostDescription
import com.dsh.android.data.protocol.SessionSummary
import com.dsh.android.data.remote.ConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SessionsUiState(
    val sessions: List<SessionSummary> = emptyList(),
    val host: HostDescription? = null,
    val serverName: String = "",
    val error: String? = null,
    val connectionState: ConnectionState? = null,
    val downReason: String? = null,
    val refreshing: Boolean = false,
    val creating: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class SessionsViewModel(app: Application) : AndroidViewModel(app) {

    private val container: AppContainer get() = (getApplication<DshApp>()).container

    private val _busy = MutableStateFlow(Pair(false, false))
    val busy: StateFlow<Pair<Boolean, Boolean>> = _busy.asStateFlow()

    val uiState: StateFlow<SessionsUiState> = container.active.flatMapLatest { binding ->
        if (binding == null) flowOf(SessionsUiState())
        else kotlinx.coroutines.flow.combine(
            binding.store.sessions,
            binding.store.hostDescription,
            binding.store.lastError,
            binding.connection.state,
            binding.connection.downReason,
        ) { sessions, host, error, conn, reason ->
            SessionsUiState(
                sessions = sessions,
                host = host,
                serverName = binding.config.name,
                error = error,
                connectionState = conn,
                downReason = reason,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SessionsUiState())

    fun refresh() {
        viewModelScope.launch {
            _busy.value = true to false
            container.active.value?.store?.refreshSessions()
            container.active.value?.store?.refreshHostDescription()
            _busy.value = false to false
        }
    }

    /** Publish a brand-new session (task workspace) on the PC. Returns the session id. */
    fun newSession(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val binding = container.active.value ?: return@launch
            _busy.value = false to true
            runCatching { binding.api.createSession() }.onSuccess { sessionId ->
                binding.store.refreshSessions()
                onCreated(sessionId)
            }.onFailure { e ->
                binding.store.lastError.tryEmit(e.message)
            }
            _busy.value = false to false
        }
    }

    fun clearError() {
        container.active.value?.store?.let { it.lastError.tryEmit(null) }
    }
}
