package com.dsh.android.data.store

import com.dsh.android.data.protocol.EventData
import com.dsh.android.data.protocol.Frames
import com.dsh.android.data.protocol.HostDescription
import com.dsh.android.data.protocol.HostFrame
import com.dsh.android.data.protocol.JobView
import com.dsh.android.data.protocol.MuxFrame
import com.dsh.android.data.protocol.QuestionItem
import com.dsh.android.data.protocol.QueueItem
import com.dsh.android.data.protocol.RawSessionEvent
import com.dsh.android.data.protocol.SessionSummary
import com.dsh.android.data.protocol.TokenUsageProjection
import com.dsh.android.data.remote.ConnectionManager
import com.dsh.android.data.remote.ConnectionState
import com.dsh.android.data.remote.DshApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * Default history window: enough messages to cover the last user question and
 * its complete answer turn (1 user + tool results + final assistant message +
 * context injections), without transferring the thousands of chunk events that
 * older turns carry. Verified against a real session: 8 messages ≈ 0.3-1.2 MB
 * JSON vs ~6.7 MB for the old 50-message window (-95%), ~23 KB on the wire
 * after gateway gzip.
 */
private const val LAST_TURN_WINDOW = 8

/** Messages transferred per "load older" page. */
private const val OLDER_PAGE_MESSAGES = 10

data class ProjectionCell(val seq: Long, val value: kotlinx.serialization.json.JsonElement)

data class PendingApproval(
    val rpcId: String,
    val sessionId: String,
    val approvalId: String,
    val toolName: String,
    val reason: String?,
)

data class PendingQuestion(
    val rpcId: String,
    val sessionId: String,
    val questions: List<QuestionItem>,
)

/** Per-session live state assembled from history + mux frames. */
class SessionData(val sessionId: String) {
    val events = ConcurrentHashMap<Long, RawSessionEvent>()
    val projections = ConcurrentHashMap<String, ProjectionCell>()
    val approvals = ConcurrentHashMap<String, PendingApproval>()
    val questions = ConcurrentHashMap<String, PendingQuestion>()
    val queue = MutableStateFlow<List<QueueItem>>(emptyList())
    val jobs = MutableStateFlow<List<JobView>>(emptyList())
    val running = MutableStateFlow(false)
    val loaded = MutableStateFlow(false)
    /** Whether older history pages exist beyond the loaded window. */
    val hasMore = MutableStateFlow(false)

    /** Bumped on every mutation so UI folds can recompute. */
    val version = MutableStateFlow(0L)
    val turnStartUsage = ConcurrentHashMap<Int, TokenUsageProjection?>()

    fun bump() {
        version.value += 1
    }

    fun putEvent(event: RawSessionEvent): Boolean {
        val prev = events.put(event.seq, event)
        return prev?.type != event.type || prev.time != event.time
    }

    fun tokenUsage(): TokenUsageProjection? = projections["tokenUsage"]?.value?.let {
        runCatching {
            com.dsh.android.data.protocol.Wire.json.decodeFromJsonElement(TokenUsageProjection.serializer(), it)
        }.getOrNull()
    }

    fun contextPressure(): com.dsh.android.data.protocol.ContextPressure? = projections["contextPressure"]?.value?.let {
        runCatching {
            com.dsh.android.data.protocol.Wire.json.decodeFromJsonElement(com.dsh.android.data.protocol.ContextPressure.serializer(), it)
        }.getOrNull()
    }

    fun contextBreakdown(): com.dsh.android.data.protocol.ContextBreakdown? = projections["contextBreakdown"]?.value?.let {
        runCatching {
            com.dsh.android.data.protocol.Wire.json.decodeFromJsonElement(com.dsh.android.data.protocol.ContextBreakdown.serializer(), it)
        }.getOrNull()
    }

    fun title(): String? = projections["title"]?.value?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    fun maxSeq(): Long = events.keys.maxOrNull() ?: -1L
}

/**
 * One connected DSH server's whole client-side state: session summaries,
 * per-session event logs, projections (token usage/context), pending
 * approvals/questions, background jobs, and the mux/host frame consumer loop.
 */
class SessionStore(
    private val api: DshApiClient,
    private val connection: ConnectionManager,
    private val scope: CoroutineScope,
) {
    private val _hostDescription = MutableStateFlow<HostDescription?>(null)
    val hostDescription: StateFlow<HostDescription?> = _hostDescription.asStateFlow()

    private val _sessions = MutableStateFlow<List<SessionSummary>>(emptyList())
    val sessions: StateFlow<List<SessionSummary>> = _sessions.asStateFlow()

    /** Conversations the user removed (archived on the PC); hidden from lists. */
    val archivedSessionIds = MutableStateFlow<Set<String>>(emptySet())

    /** Last transient error surfaced to the UI (cleared by the viewmodel). */
    val lastError = MutableStateFlow<String?>(null)

    private val sessionsData = ConcurrentHashMap<String, SessionData>()
    private var collectorJob: Job? = null
    private val refreshMutex = kotlinx.coroutines.sync.Mutex()

    fun data(sessionId: String): SessionData =
        sessionsData.getOrPut(sessionId) { SessionData(sessionId) }

    fun start() {
        collectorJob = scope.launch {
            connection.frames.collect { env ->
                val mux = Frames.parseMux(env.payload)
                if (mux is MuxFrame.Unknown && env.method.startsWith("host/")) {
                    handleHost(Frames.parseHost(env.payload))
                } else {
                    handleMux(env.rpcId, mux)
                }
            }
        }
        scope.launch {
            connection.state.collect { state ->
                if (state == ConnectionState.CONNECTED) {
                    refreshHostDescription()
                    refreshSessions()
                    refreshArchived()
                }
            }
        }
    }

    fun stop() {
        collectorJob?.cancel()
        collectorJob = null
    }

    /** Surface an error and, on RPC timeouts, force-rebuild the streams. */
    private fun noteFailure(e: Throwable) {
        lastError.value = e.message
        if (e is com.dsh.android.data.protocol.RpcBusinessException && e.code == "timeout") {
            connection.reconnect()
        }
    }

    suspend fun refreshHostDescription() {
        runCatching { api.describe() }.onSuccess { _hostDescription.value = it }
    }

    /** Seed the hidden-conversation set from workspace.list on (re)connect. */
    suspend fun refreshArchived() {
        runCatching { api.workspaceArchived() }.onSuccess { archived ->
            archivedSessionIds.value = archived
            _sessions.value = _sessions.value.filterNot { it.sessionId in archived }
        }.onFailure { noteFailure(it) }
    }

    suspend fun refreshSessions() = refreshMutex.withLock {
        runCatching { api.listSessions() }.onSuccess { list ->
            _sessions.value = list
            for (s in list) {
                val d = data(s.sessionId)
                if (!d.running.value) d.running.value = s.running
                s.projections?.values?.let { values ->
                    val obj = values as? kotlinx.serialization.json.JsonObject ?: return@let
                    for ((key, v) in obj) {
                        val cell = d.projections[key]
                        if (cell == null || s.projections.asOfSeq > cell.seq) {
                            d.projections[key] = ProjectionCell(s.projections.asOfSeq, v)
                        }
                    }
                }
                d.bump()
            }
        }.onFailure { noteFailure(it) }
    }

    /**
     * Load only the tail window covering the latest turn (the last question
     * plus its full answer, including any in-flight streaming tail). Older
     * context is transferred on demand via [loadOlder] — a history page can
     * carry thousands of chunk events, so the default stays lightweight.
     */
    suspend fun openSession(sessionId: String, maxMessages: Int = LAST_TURN_WINDOW) {
        val d = data(sessionId)
        if (d.loaded.value) return
        runCatching { api.history(sessionId, maxMessages = maxMessages) }.onSuccess { page ->
            var changed = false
            for (entry in page.events) {
                if (d.putEvent(entry.event)) changed = true
            }
            page.projections?.let { block ->
                val obj = block.values as? kotlinx.serialization.json.JsonObject
                if (obj != null) {
                    for ((key, v) in obj) {
                        val cell = d.projections[key]
                        if (cell == null || block.asOfSeq > cell.seq) {
                            d.projections[key] = ProjectionCell(block.asOfSeq, v)
                            changed = true
                        }
                    }
                }
            }
            d.hasMore.value = page.hasMore
            d.loaded.value = true
            if (changed) d.bump()
        }.onFailure { noteFailure(it) }
    }

    /** Prepend one page of older history, anchored at the earliest known seq. */
    suspend fun loadOlder(sessionId: String) {
        val d = data(sessionId)
        if (!d.loaded.value) return
        val anchor = d.events.keys.minOrNull() ?: return
        runCatching { api.history(sessionId, beforeSeq = anchor, maxMessages = OLDER_PAGE_MESSAGES) }.onSuccess { page ->
            var changed = false
            for (entry in page.events) {
                if (d.putEvent(entry.event)) changed = true
            }
            val hadMore = d.hasMore.value
            d.hasMore.value = page.hasMore
            if (changed || d.hasMore.value != hadMore) d.bump()
        }.onFailure { noteFailure(it) }
    }

    suspend fun sendPrompt(sessionId: String, text: String) {
        runCatching { api.prompt(sessionId, text) }
            .onFailure { noteFailure(it) }
    }

    suspend fun cancel(sessionId: String) {
        runCatching { api.cancel(sessionId) }
            .onFailure { noteFailure(it) }
    }

    suspend fun rename(sessionId: String, title: String) {
        runCatching { api.rename(sessionId, title) }
            .onFailure { noteFailure(it) }
    }

    /** Remove one conversation from the list (durable archive on the PC). */
    suspend fun archiveSession(sessionId: String) {
        runCatching { api.archiveSession(sessionId) }.onSuccess {
            // The host also pushes host/archived-sessions-changed; remove
            // locally right away for instant feedback.
            archivedSessionIds.value = archivedSessionIds.value + sessionId
            _sessions.value = _sessions.value.filterNot { it.sessionId == sessionId }
        }.onFailure { noteFailure(it) }
    }

    suspend fun answerApproval(pending: PendingApproval, allowed: Boolean) {
        val payload = com.dsh.android.data.protocol.Api.approvalAnswer(
            pending.sessionId, pending.approvalId, allowed,
        )
        runCatching { api.respond(pending.rpcId, payload) }.onSuccess { accepted ->
            if (accepted) data(pending.sessionId).approvals.remove(pending.rpcId)
        }.onFailure { noteFailure(it) }
    }

    suspend fun answerQuestion(pending: PendingQuestion, answers: List<com.dsh.android.data.protocol.Api.QuestionAnswerSpec>) {
        val payload = com.dsh.android.data.protocol.Api.questionAnswer(
            pending.sessionId, answers,
        )
        runCatching { api.respond(pending.rpcId, payload) }.onSuccess { accepted ->
            if (accepted) data(pending.sessionId).questions.remove(pending.rpcId)
        }.onFailure { noteFailure(it) }
    }

    // -- Frame handling -------------------------------------------------------

    private fun handleHost(frame: HostFrame) {
        when (frame) {
            is HostFrame.SessionAdded -> {
                val list = _sessions.value.toMutableList()
                if (list.none { it.sessionId == frame.sessionId }) {
                    list.add(
                        SessionSummary(
                            sessionId = frame.sessionId,
                            updatedAt = System.currentTimeMillis(),
                            blank = frame.blank,
                            parentSessionId = frame.parentSessionId,
                            origin = frame.origin,
                            cwd = frame.cwd,
                            agentPreset = frame.agentPreset,
                        ),
                    )
                    _sessions.value = list.sortedByDescending { it.updatedAt }
                }
            }

            is HostFrame.SessionRemoved -> {
                _sessions.value = _sessions.value.filterNot { it.sessionId == frame.sessionId }
            }

            is HostFrame.SessionStatus -> {
                data(frame.sessionId).running.value = frame.running
                _sessions.value = _sessions.value.map {
                    if (it.sessionId == frame.sessionId) it.copy(running = frame.running) else it
                }
            }

            is HostFrame.AgentError -> {
                lastError.value = frame.message
            }

            is HostFrame.ArchivedSessionsChanged -> {
                archivedSessionIds.value = frame.archivedSessionIds.toSet()
                val archived = frame.archivedSessionIds.toSet()
                _sessions.value = _sessions.value.filterNot { it.sessionId in archived }
            }

            is HostFrame.Unknown -> Unit
        }
    }

    private fun handleMux(rpcId: String, frame: MuxFrame) {
        when (frame) {
            is MuxFrame.SessionEvent -> {
                val d = data(frame.sessionId)
                when (frame.event.type) {
                    "turn/start" -> {
                        EventData.turn(frame.event)?.let { turn ->
                            d.turnStartUsage[turn] = d.tokenUsage()
                        }
                    }

                    "turn/end" -> Unit
                }
                val changed = d.putEvent(frame.event)
                if (changed) d.bump()
            }

            is MuxFrame.Subscribed -> {
                val d = data(frame.sessionId)
                val known = d.maxSeq()
                if (known >= 0 && frame.lastSeq > known) {
                    // Gap: history missed events while disconnected — refetch the
                    // lightweight tail window (older pages stay on demand).
                    scope.launch {
                        runCatching { api.history(frame.sessionId, maxMessages = LAST_TURN_WINDOW) }.onSuccess { page ->
                            var changed = false
                            for (entry in page.events) if (d.putEvent(entry.event)) changed = true
                            d.hasMore.value = page.hasMore
                            if (changed) d.bump()
                        }
                    }
                }
            }

            is MuxFrame.ApprovalRequested -> {
                data(frame.sessionId).approvals[rpcId] = PendingApproval(
                    rpcId = rpcId,
                    sessionId = frame.sessionId,
                    approvalId = frame.approvalId,
                    toolName = frame.toolName,
                    reason = frame.reason,
                )
                data(frame.sessionId).bump()
            }

            is MuxFrame.ApprovalResolved -> {
                val d = data(frame.sessionId)
                for (entry in d.approvals.entries.toList()) {
                    if (entry.value.approvalId == frame.approvalId) d.approvals.remove(entry.key)
                }
                d.bump()
            }

            is MuxFrame.QuestionRequested -> {
                data(frame.sessionId).questions[rpcId] = PendingQuestion(
                    rpcId = rpcId,
                    sessionId = frame.sessionId,
                    questions = frame.questions,
                )
                data(frame.sessionId).bump()
            }

            is MuxFrame.QuestionResolved -> {
                data(frame.sessionId).questions.remove(frame.questionRpcId)
                data(frame.sessionId).bump()
            }

            is MuxFrame.Queue -> {
                data(frame.sessionId).queue.value = frame.items
            }

            is MuxFrame.Jobs -> {
                data(frame.sessionId).jobs.value = frame.jobs
            }

            is MuxFrame.Projection -> {
                val d = data(frame.sessionId)
                val cell = d.projections[frame.key]
                if (cell == null || frame.seq >= cell.seq) {
                    d.projections[frame.key] = ProjectionCell(frame.seq, frame.value)
                    d.bump()
                }
            }

            is MuxFrame.StreamError -> {
                lastError.value = frame.error.message
            }

            is MuxFrame.Unknown -> Unit
        }
    }
}
