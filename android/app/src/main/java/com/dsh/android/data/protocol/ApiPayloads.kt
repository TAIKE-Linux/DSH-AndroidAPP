package com.dsh.android.data.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// ---------------------------------------------------------------------------
// Response models
// ---------------------------------------------------------------------------
@Serializable
data class SessionSummary(
    @SerialName("sessionId") val sessionId: String,
    @SerialName("updatedAt") val updatedAt: Long = 0,
    val running: Boolean = false,
    val blank: Boolean = false,
    @SerialName("parentSessionId") val parentSessionId: String? = null,
    val origin: String? = null,
    val cwd: String? = null,
    val agentPreset: String? = null,
    val projections: SessionProjectionsBlock? = null,
)

@Serializable
data class SessionProjectionsBlock(
    @SerialName("asOfSeq") val asOfSeq: Long = 0,
    val values: JsonElement = JsonNull,
) {
    private val obj: JsonObject? get() = values as? JsonObject

    val title: String?
        get() = obj?.get("title")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    val tokenUsage: TokenUsageProjection?
        get() = obj?.get("tokenUsage")?.let { runCatching { Wire.json.decodeFromJsonElement(TokenUsageProjection.serializer(), it) }.getOrNull() }

    val contextPressure: ContextPressure?
        get() = obj?.get("contextPressure")?.let { runCatching { Wire.json.decodeFromJsonElement(ContextPressure.serializer(), it) }.getOrNull() }

    val contextBreakdown: ContextBreakdown?
        get() = obj?.get("contextBreakdown")?.let { runCatching { Wire.json.decodeFromJsonElement(ContextBreakdown.serializer(), it) }.getOrNull() }
}

@Serializable
data class HistoryEntry(
    val event: RawSessionEvent,
    val view: JsonElement? = null,
)

@Serializable
data class HistoryPage(
    val events: List<HistoryEntry> = emptyList(),
    val hasMore: Boolean = false,
    val projections: SessionProjectionsBlock? = null,
)

@Serializable
data class HostDescription(
    val version: String = "",
    val cwd: String = "",
    val provider: String? = null,
    val model: String? = null,
    @SerialName("attachedSessions") val attachedSessions: Int = 0,
    @SerialName("canOpenPath") val canOpenPath: Boolean = false,
)

@Serializable
data class ModelSelection(
    val provider: String = "",
    val model: String = "",
    val reasoningEffort: String? = null,
)

@Serializable
data class ModelCatalogModel(
    val id: String,
    val name: String = "",
    val description: String? = null,
)

@Serializable
data class ModelProviderGroup(
    val id: String,
    val name: String = "",
    val models: List<ModelCatalogModel> = emptyList(),
)

@Serializable
data class ModelCatalogFailure(
    val id: String,
    val name: String = "",
    val message: String = "",
)

@Serializable
data class SessionModels(
    val current: ModelSelection = ModelSelection(),
    val routable: Boolean = false,
    val groups: List<ModelProviderGroup> = emptyList(),
    val failures: List<ModelCatalogFailure> = emptyList(),
)

// ---------------------------------------------------------------------------
// Request payload builders (official RpcMethodMap methods the app drives)
// ---------------------------------------------------------------------------
object Api {
    fun empty(): JsonElement = buildJsonObject { }

    fun sessionList(cursor: String? = null): JsonElement = buildJsonObject {
        if (cursor != null) put("cursor", cursor)
    }

    fun sessionCreate(
        workspaceId: String? = null,
        cwd: String? = null,
        sessionId: String? = null,
        agentPreset: String? = null,
    ): JsonElement = buildJsonObject {
        if (workspaceId != null) put("workspaceId", workspaceId)
        if (cwd != null) put("cwd", cwd)
        if (sessionId != null) put("sessionId", sessionId)
        if (agentPreset != null) put("agentPreset", agentPreset)
    }

    fun sessionHistory(sessionId: String, beforeSeq: Long? = null, maxMessages: Int? = null): JsonElement = buildJsonObject {
        put("sessionId", sessionId)
        if (beforeSeq != null) put("beforeSeq", beforeSeq)
        if (maxMessages != null) put("maxMessages", maxMessages)
    }

    fun sessionPrompt(sessionId: String, text: String, mode: String = "queue", clientTimeZone: String? = null): JsonElement = buildJsonObject {
        put("sessionId", sessionId)
        put("mode", mode)
        put("content", buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", text)
            })
        })
        if (clientTimeZone != null) put("clientTimeZone", clientTimeZone)
    }

    fun sessionCancel(sessionId: String): JsonElement = buildJsonObject {
        put("sessionId", sessionId)
    }

    fun sessionModels(sessionId: String): JsonElement = buildJsonObject {
        put("sessionId", sessionId)
    }

    fun sessionSelectModel(sessionId: String, provider: String, model: String, reasoningEffort: String? = null): JsonElement = buildJsonObject {
        put("sessionId", sessionId)
        put("provider", provider)
        put("model", model)
        if (reasoningEffort != null) put("reasoningEffort", reasoningEffort)
    }

    fun sessionRename(sessionId: String, title: String): JsonElement = buildJsonObject {
        put("sessionId", sessionId)
        put("title", title)
    }

    fun sessionFork(sessionId: String, atSeq: Long? = null): JsonElement = buildJsonObject {
        put("sessionId", sessionId)
        if (atSeq != null) put("atSeq", atSeq)
    }

    fun hostDescribe(): JsonElement = empty()

    /** Approval answer value (POST /api/respond with a client-response echoing the frame rpcId). */
    fun approvalAnswer(sessionId: String, approvalId: String, allowed: Boolean): JsonElement = buildJsonObject {
        put("sessionId", sessionId)
        put("approvalId", approvalId)
        put("outcome", if (allowed) "allowed-once" else "rejected")
    }

    /** One answer entry of a question batch. */
    data class QuestionAnswerSpec(val id: String, val selected: List<String> = emptyList(), val custom: String? = null)

    /** Question answer value: one batch answering every question at once. */
    fun questionAnswer(sessionId: String, answers: List<QuestionAnswerSpec>): JsonElement = buildJsonObject {
        put("sessionId", sessionId)
        put("answer", buildJsonObject {
            put("answers", buildJsonArray {
                for (a in answers) {
                    add(buildJsonObject {
                        put("id", a.id)
                        put("selected", buildJsonArray { for (s in a.selected) add(s) })
                        if (a.custom != null) put("custom", a.custom)
                    })
                }
            })
        })
    }

    fun subagentList(parentSessionId: String): JsonElement = buildJsonObject {
        put("parentSessionId", parentSessionId)
    }

    fun skillList(): JsonElement = empty()

    fun agentPresetList(): JsonElement = empty()
}
