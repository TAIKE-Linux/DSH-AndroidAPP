package com.dsh.android.data.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

// ---------------------------------------------------------------------------
// Mux stream frames (official contract: @deepseek-ai/dsh-host-apiproxy api/events)
// ---------------------------------------------------------------------------
sealed interface MuxFrame {
    val sessionId: String?

    data class SessionEvent(
        override val sessionId: String,
        val event: RawSessionEvent,
        val view: JsonElement? = null,
    ) : MuxFrame

    data class Subscribed(override val sessionId: String, val lastSeq: Long) : MuxFrame

    data class ApprovalRequested(
        override val sessionId: String,
        val approvalId: String,
        val toolName: String,
        val callId: String? = null,
        val reason: String? = null,
    ) : MuxFrame

    data class ApprovalResolved(
        override val sessionId: String,
        val approvalId: String,
        val outcome: String,
    ) : MuxFrame

    data class QuestionRequested(
        override val sessionId: String,
        val questions: List<QuestionItem>,
    ) : MuxFrame

    data class QuestionResolved(
        override val sessionId: String,
        val questionRpcId: String,
        val outcome: String,
    ) : MuxFrame

    data class Queue(override val sessionId: String, val items: List<QueueItem>) : MuxFrame

    data class Jobs(override val sessionId: String, val jobs: List<JobView>) : MuxFrame

    data class Projection(
        override val sessionId: String,
        val key: String,
        val value: JsonElement,
        val seq: Long,
    ) : MuxFrame

    data class StreamError(val error: RpcError) : MuxFrame {
        override val sessionId: String? = null
    }

    data class Unknown(val payload: JsonElement, override val sessionId: String? = null) : MuxFrame
}

@Serializable
data class QuestionItem(
    val id: String,
    val question: String,
    val detail: String? = null,
    val header: String? = null,
    val options: List<QuestionOption>? = null,
    val multiSelect: Boolean = false,
)

@Serializable
data class QuestionOption(val label: String, val description: String? = null)

@Serializable
data class QueueItem(
    val id: String,
    val placement: String,
    val message: JsonElement = JsonNull,
)

@Serializable
data class JobView(
    val id: String,
    val kind: String,
    val label: String,
    val status: String,
    val detail: String? = null,
    @SerialName("startedAt") val startedAt: Long = 0,
    @SerialName("finishedAt") val finishedAt: Long? = null,
)

// ---------------------------------------------------------------------------
// Host stream frames
// ---------------------------------------------------------------------------
sealed interface HostFrame {
    data class SessionAdded(
        val sessionId: String,
        val blank: Boolean,
        val parentSessionId: String? = null,
        val origin: String? = null,
        val cwd: String? = null,
        val agentPreset: String? = null,
    ) : HostFrame

    data class SessionRemoved(val sessionId: String) : HostFrame

    data class SessionStatus(val sessionId: String, val running: Boolean) : HostFrame

    data class AgentError(val sessionId: String, val message: String) : HostFrame

    data class Unknown(val payload: JsonElement) : HostFrame
}

// ---------------------------------------------------------------------------
// Session projections (token usage & context pressure — official contract:
// @deepseek-ai/dsh-token-meter session projections)
// ---------------------------------------------------------------------------
@Serializable
data class TokenUsageProjection(
    @SerialName("uncachedInputTokens") val uncachedInputTokens: Long = 0,
    @SerialName("outputTokens") val outputTokens: Long = 0,
    @SerialName("cacheReadTokens") val cacheReadTokens: Long? = null,
    @SerialName("cacheWriteTokens") val cacheWriteTokens: Long? = null,
) {
    val billedInput: Long get() = uncachedInputTokens + (cacheReadTokens ?: 0) + (cacheWriteTokens ?: 0)
    val total: Long get() = billedInput + outputTokens
}

@Serializable
data class ContextPressure(
    @SerialName("pressureTokens") val pressureTokens: Long? = null,
    @SerialName("projectedTokens") val projectedTokens: Long? = null,
    @SerialName("contextWindow") val contextWindow: Long? = null,
) {
    /** Occupancy 0..1; null when the model route advertises no window or no sample exists. */
    val occupancy: Float? = if (projectedTokens != null && contextWindow != null && contextWindow > 0) {
        (projectedTokens.toFloat() / contextWindow.toFloat()).coerceIn(0f, 1f)
    } else null
}

@Serializable
data class ContextBreakdown(
    @SerialName("systemTokens") val systemTokens: Long? = null,
    @SerialName("toolsTokens") val toolsTokens: Long? = null,
    @SerialName("messageTokens") val messageTokens: Long? = null,
)

// ---------------------------------------------------------------------------
// Frame parsers (tolerant: unknown frame types become Unknown)
// ---------------------------------------------------------------------------
object Frames {
    fun parseMux(payload: JsonElement): MuxFrame {
        val obj = payload.jsonObject
        val type = obj["type"]?.jsonPrimitive?.content ?: return MuxFrame.Unknown(payload)
        val sid = obj["sessionId"]?.jsonPrimitive?.content
        return when (type) {
            "session/event" -> runCatching {
                MuxFrame.SessionEvent(
                    sessionId = sid ?: "",
                    event = Wire.json.decodeFromJsonElement(RawSessionEvent.serializer(), obj["event"] ?: JsonNull),
                    view = obj["view"],
                )
            }.getOrElse { MuxFrame.Unknown(payload) }

            "session/subscribed" -> MuxFrame.Subscribed(
                sessionId = sid ?: "",
                lastSeq = obj["lastSeq"]?.jsonPrimitive?.long ?: 0,
            )

            "approval/requested" -> MuxFrame.ApprovalRequested(
                sessionId = sid ?: "",
                approvalId = obj["approvalId"]?.jsonPrimitive?.content ?: "",
                toolName = obj["toolName"]?.jsonPrimitive?.content ?: "",
                callId = obj["callId"]?.jsonPrimitive?.content,
                reason = obj["reason"]?.jsonPrimitive?.content,
            )

            "approval/resolved" -> MuxFrame.ApprovalResolved(
                sessionId = sid ?: "",
                approvalId = obj["approvalId"]?.jsonPrimitive?.content ?: "",
                outcome = obj["outcome"]?.jsonPrimitive?.content ?: "",
            )

            "question/requested" -> runCatching {
                MuxFrame.QuestionRequested(
                    sessionId = sid ?: "",
                    questions = Wire.json.decodeFromJsonElement(
                        kotlinx.serialization.builtins.ListSerializer(QuestionItem.serializer()),
                        obj["questions"] ?: JsonNull,
                    ),
                )
            }.getOrElse { MuxFrame.Unknown(payload) }

            "question/resolved" -> MuxFrame.QuestionResolved(
                sessionId = sid ?: "",
                questionRpcId = obj["questionRpcId"]?.jsonPrimitive?.content ?: "",
                outcome = obj["outcome"]?.jsonPrimitive?.content ?: "",
            )

            "session/queue" -> runCatching {
                MuxFrame.Queue(
                    sessionId = sid ?: "",
                    items = Wire.json.decodeFromJsonElement(
                        kotlinx.serialization.builtins.ListSerializer(QueueItem.serializer()),
                        obj["items"] ?: JsonNull,
                    ),
                )
            }.getOrElse { MuxFrame.Unknown(payload) }

            "session/jobs" -> runCatching {
                MuxFrame.Jobs(
                    sessionId = sid ?: "",
                    jobs = Wire.json.decodeFromJsonElement(
                        kotlinx.serialization.builtins.ListSerializer(JobView.serializer()),
                        obj["jobs"] ?: JsonNull,
                    ),
                )
            }.getOrElse { MuxFrame.Unknown(payload) }

            "session/projection" -> MuxFrame.Projection(
                sessionId = sid ?: "",
                key = obj["key"]?.jsonPrimitive?.content ?: "",
                value = obj["value"] ?: JsonNull,
                seq = obj["seq"]?.jsonPrimitive?.long ?: 0,
            )

            "stream/error" -> runCatching {
                MuxFrame.StreamError(Wire.json.decodeFromJsonElement(RpcError.serializer(), obj["error"] ?: JsonNull))
            }.getOrElse { MuxFrame.Unknown(payload) }

            else -> MuxFrame.Unknown(payload, sid)
        }
    }

    fun parseHost(payload: JsonElement): HostFrame {
        val obj = payload.jsonObject
        val type = obj["type"]?.jsonPrimitive?.content ?: return HostFrame.Unknown(payload)
        return when (type) {
            "host/session-added" -> HostFrame.SessionAdded(
                sessionId = obj["sessionId"]?.jsonPrimitive?.content ?: "",
                blank = obj["blank"]?.jsonPrimitive?.boolean ?: false,
                parentSessionId = obj["parentSessionId"]?.jsonPrimitive?.content,
                origin = obj["origin"]?.jsonPrimitive?.content,
                cwd = obj["cwd"]?.jsonPrimitive?.content,
                agentPreset = obj["agentPreset"]?.jsonPrimitive?.content,
            )

            "host/session-removed" -> HostFrame.SessionRemoved(obj["sessionId"]?.jsonPrimitive?.content ?: "")

            "host/session-status" -> HostFrame.SessionStatus(
                sessionId = obj["sessionId"]?.jsonPrimitive?.content ?: "",
                running = obj["running"]?.jsonPrimitive?.boolean ?: false,
            )

            "host/agent-error" -> HostFrame.AgentError(
                sessionId = obj["sessionId"]?.jsonPrimitive?.content ?: "",
                message = obj["message"]?.jsonPrimitive?.content ?: "",
            )

            else -> HostFrame.Unknown(payload)
        }
    }
}
