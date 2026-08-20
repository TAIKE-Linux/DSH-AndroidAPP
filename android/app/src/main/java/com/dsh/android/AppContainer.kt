package com.dsh.android

import android.app.Application
import com.dsh.android.data.remote.ConnectionManager
import com.dsh.android.data.remote.DshApiClient
import com.dsh.android.data.store.ServerConfig
import com.dsh.android.data.store.ServerConfigStore
import com.dsh.android.data.store.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Manual DI container. One Binding = one connected DSH server (gateway),
 * holding its API client, downlink connection manager, and session store.
 */
class AppContainer(private val app: Application) {

    val serverStore = ServerConfigStore(app)

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        // LAN targets either answer fast or are gone: a short connect timeout
        // means quick failure feedback and a faster reconnect cycle.
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    // Unary RPCs (history/list/prompt/…) get a hard wall-clock cap: a wedged
    // upstream must surface as a timeout instead of hanging the UI forever.
    // WebSockets deliberately do NOT use callTimeout (long-lived streams).
    private val rpcClient: OkHttpClient = httpClient.newBuilder()
        .callTimeout(60, TimeUnit.SECONDS)
        .build()

    // File uploads stream big bodies; give them a separate, generous cap.
    private val uploadClient: OkHttpClient = httpClient.newBuilder()
        .callTimeout(10, TimeUnit.MINUTES)
        .build()

    data class Binding(
        val config: ServerConfig,
        val api: DshApiClient,
        val connection: ConnectionManager,
        val store: SessionStore,
    )

    private val _active = MutableStateFlow<Binding?>(null)
    val active: StateFlow<Binding?> = _active.asStateFlow()

    /** Bind to a gateway: builds the API client, connection manager and store, then starts them. */
    fun bind(server: ServerConfig) {
        unbind()
        val api = DshApiClient(server.baseUrl.trimEnd('/'), server.token, rpcClient, uploadClient)
        val connection = ConnectionManager(api, httpClient)
        val store = SessionStore(api, connection, appScope)
        val binding = Binding(server, api, connection, store)
        _active.value = binding
        store.start()
        connection.start(appScope)
    }

    fun unbind() {
        val binding = _active.value ?: return
        binding.connection.stop()
        binding.store.stop()
        _active.value = null
    }
}
