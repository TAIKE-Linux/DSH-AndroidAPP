package com.dsh.android.data.remote

import com.dsh.android.data.protocol.ServerRequest
import com.dsh.android.data.protocol.Wire
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlin.random.Random

enum class ConnectionState { CONNECTING, CONNECTED, RECONNECTING, STOPPED }

/** One downstream server-request envelope from either socket. */
data class ServerEnvelope(val rpcId: String, val method: String, val payload: JsonElement)

/** Map a raw stream-failure reason to something a human can act on. */
fun friendlyDownReason(reason: String?): String? = when {
    reason.isNullOrBlank() -> null
    reason.contains("ECONNREFUSED", ignoreCase = true) ||
        reason.contains("refused", ignoreCase = true) ->
        "电脑端 DeepSeek Harness（dsh web）未启动？请检查电脑"

    reason.contains("unauthorized", ignoreCase = true) ||
        reason.contains("401") ->
        "网关 token 校验失败，请检查 Token"

    reason.contains("timeout", ignoreCase = true) ||
        reason.contains("timed out", ignoreCase = true) ->
        "连接超时"

    else -> "连接中断（$reason）"
}

/**
 * Owns the two downlink WebSockets of one DSH server connection
 * (/api/events.mux + /api/events.host — official web-carrier contract).
 * Readiness = both sockets open. Any socket loss rebuilds both streams with
 * exponential backoff, mirroring the official ConnectionController.
 */
class ConnectionManager(
    private val api: DshApiClient,
    private val http: OkHttpClient,
) {
    private val _state = MutableStateFlow(ConnectionState.STOPPED)
    val state: StateFlow<ConnectionState> = _state

    /** Last stream-failure reason (cleared once connected); shown by the UI. */
    private val _downReason = MutableStateFlow<String?>(null)
    val downReason: StateFlow<String?> = _downReason.asStateFlow()

    private val _frames = MutableSharedFlow<ServerEnvelope>(
        extraBufferCapacity = 1024,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val frames: SharedFlow<ServerEnvelope> = _frames

    private val stateMutex = Mutex()
    private var loopJob: Job? = null
    private val running = AtomicBoolean(false)

    /** Sockets of the current generation; closed by [reconnect]. */
    private val activeSockets = java.util.Collections.synchronizedList(mutableListOf<WebSocket>())

    fun start(scope: CoroutineScope) {
        if (!running.compareAndSet(false, true)) return
        loopJob = scope.launch { connectLoop() }
    }

    fun stop() {
        running.set(false)
        loopJob?.cancel()
        loopJob = null
        _state.value = ConnectionState.STOPPED
    }

    /**
     * Force-tear the current stream generation (used after an RPC timeout):
     * closing the sockets makes the loop rebuild both streams immediately.
     */
    fun reconnect() {
        val snapshot = activeSockets.toList()
        activeSockets.clear()
        snapshot.forEach { runCatching { it.close(1000, "reconnect") } }
    }

    private suspend fun setState(next: ConnectionState) = stateMutex.withLock {
        if (_state.value != next) _state.value = next
    }

    private suspend fun connectLoop() {
        var attempt = 0
        while (running.get()) {
            attempt += 1
            setState(if (attempt == 1) ConnectionState.CONNECTING else ConnectionState.RECONNECTING)

            val readyCount = AtomicInteger(0)
            val bothOpen = CompletableDeferred<Unit>()
            val dead = Channel<Unit>(Channel.CONFLATED)
            // The loop uses the shared active-socket list directly so
            // reconnect() (RPC-timeout path) can tear this generation down.
            val sockets: MutableList<WebSocket> = activeSockets
            activeSockets.clear()

            for (path in listOf("/api/events.mux", "/api/events.host")) {
                val ws = openStream(
                    path,
                    onOpen = {
                        if (readyCount.incrementAndGet() >= 2) bothOpen.complete(Unit)
                    },
                    onDead = { dead.trySend(Unit) },
                )
                sockets += ws
            }

            // Readiness = both sockets open. If either dies first (server
            // closed it, auth rejected, network dropped), stop waiting
            // immediately and rebuild instead of burning the whole 20s cap.
            val opened = withTimeoutOrNull(20_000) {
                select {
                    bothOpen.onAwait { true }
                    dead.onReceive { false }
                }
            } == true
            if (opened) {
                attempt = 0
                _downReason.value = null
                setState(ConnectionState.CONNECTED)
                // Generation survives until either stream ends or fails.
                dead.receive()
            }

            sockets.forEach { runCatching { it.close(1000, "rebuild") } }
            if (!running.get()) break

            val backoffMs = min(30_000L, 1000L shl min(attempt, 4)) + Random.nextLong(0, 500)
            delay(backoffMs)
        }
    }

    private fun openStream(path: String, onOpen: () -> Unit, onDead: () -> Unit): WebSocket {
        val wsUrl = api.baseUrl
            .replaceFirst("http://", "ws://")
            .replaceFirst("https://", "wss://") + path
        val request = Request.Builder()
            .url(wsUrl)
            .header("Authorization", "Bearer ${api.token}")
            .build()
        return http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = onOpen()

            override fun onMessage(webSocket: WebSocket, text: String) {
                val envelope = runCatching {
                    Wire.json.decodeFromString(ServerRequest.serializer(), text)
                }.getOrNull() ?: return
                _frames.tryEmit(ServerEnvelope(envelope.rpcId, envelope.method, envelope.payload))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                // The gateway passes the upstream failure (e.g. DSH not
                // running) in the close reason — surface it for the UI.
                if (reason.isNotBlank()) _downReason.value = reason.take(120)
                onDead()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _downReason.value = t.message?.take(120)
                onDead()
            }
        })
    }
}
