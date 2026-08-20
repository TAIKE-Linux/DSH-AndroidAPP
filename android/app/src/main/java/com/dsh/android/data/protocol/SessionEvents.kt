package com.dsh.android.data.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long

/**
 * Raw session-log event as it arrives inside a `session/event` mux frame or a
 * `session.history` entry (official contract: @deepseek-ai/dsh-session/types).
 * `data` is kept as a JsonElement so plugin-added event types survive parsing;
 * consumers decode the payloads they understand and skip the rest.
 */
@Serializable
data class RawSessionEvent(
    val type: String,
    val seq: Long,
    val time: Long,
    val data: JsonElement = JsonNull,
    val ignorable: Boolean = false,
    @SerialName("surfaceOp") val surfaceOp: JsonElement? = null,
    @SerialName("sourceEventSeqs") val sourceEventSeqs: List<Long>? = null,
)

@Serializable
data class TurnData(val turn: Int)

@Serializable
data class StepData(val turn: Int, val step: Int)

@Serializable
data class TodoItem(val content: String, val status: String)

@Serializable
data class TodoWriteData(val todos: List<TodoItem>)

@Serializable
data class TurnEndData(
    val turn: Int,
    val reason: JsonElement = JsonNull,
) {
    val reasonKind: String get() = reason.jsonObject["kind"]?.jsonPrimitive?.content ?: "unknown"
}

@Serializable
data class ToolCallData(
    val turn: Int,
    val step: Int,
    @SerialName("callId") val callId: String,
    val name: String,
    val arguments: String,
)

@Serializable
data class TokenUsage(
    @SerialName("inputTokens") val inputTokens: Long = 0,
    @SerialName("outputTokens") val outputTokens: Long = 0,
    @SerialName("cacheReadTokens") val cacheReadTokens: Long? = null,
    @SerialName("cacheWriteTokens") val cacheWriteTokens: Long? = null,
    @SerialName("reasoningTokens") val reasoningTokens: Long? = null,
) {
    val billedInput: Long get() = inputTokens + (cacheReadTokens ?: 0) + (cacheWriteTokens ?: 0)
    val total: Long get() = billedInput + outputTokens
}

@Serializable
data class MessageJson(
    val id: String,
    val role: String,
    val content: List<JsonElement> = emptyList(),
    val source: JsonElement = JsonNull,
)

@Serializable
data class AssistantMessageData(
    val turn: Int,
    val step: Int,
    val message: MessageJson,
    val usage: TokenUsage? = null,
)

@Serializable
data class ToolResultData(
    val turn: Int,
    val step: Int,
    val message: MessageJson,
    val error: JsonElement? = null,
    val meta: JsonElement? = null,
)

@Serializable
data class ChunkData(
    val turn: Int,
    val step: Int,
    val chunk: JsonElement,
)

/** One decoded assistant stream chunk (text/reasoning/tool-argument deltas). */
data class StreamDelta(
    val chunkType: String, // block-start | text-delta | reasoning-delta | tool-call-delta | block-end | usage | finish
    val index: Int = -1,
    val text: String = "",
    val callId: String? = null,
    val callName: String? = null,
    val argumentsDelta: String = "",
)

fun ChunkData.delta(): StreamDelta {
    val obj = chunk.jsonObject
    return StreamDelta(
        chunkType = obj["type"]?.jsonPrimitive?.content ?: "unknown",
        index = obj["index"]?.jsonPrimitive?.int ?: -1,
        text = obj["text"]?.jsonPrimitive?.content ?: "",
        callId = obj["id"]?.jsonPrimitive?.content,
        callName = obj["name"]?.jsonPrimitive?.content,
        argumentsDelta = obj["argumentsDelta"]?.jsonPrimitive?.content ?: "",
    )
}

/** Extract the plain visible text from a content-block list. */
fun List<JsonElement>.visibleText(): String = buildString {
    for (block in this@visibleText) {
        val obj = block.jsonObject
        if (obj["type"]?.jsonPrimitive?.content == "text") {
            append(obj["text"]?.jsonPrimitive?.content ?: "")
        }
    }
}

/** Extract reasoning text from a content-block list. */
fun List<JsonElement>.reasoningText(): String = buildString {
    for (block in this@reasoningText) {
        val obj = block.jsonObject
        if (obj["type"]?.jsonPrimitive?.content == "reasoning") {
            append(obj["text"]?.jsonPrimitive?.content ?: "")
        }
    }
}

/** Tool-call blocks of an assistant message. */
data class ToolCallRef(val id: String, val name: String, val arguments: String)

fun List<JsonElement>.toolCalls(): List<ToolCallRef> = mapNotNull { block ->
    val obj = block.jsonObject
    if (obj["type"]?.jsonPrimitive?.content == "tool-call") {
        ToolCallRef(
            id = obj["id"]?.jsonPrimitive?.content ?: "",
            name = obj["name"]?.jsonPrimitive?.content ?: "",
            arguments = obj["arguments"]?.jsonPrimitive?.content ?: "",
        )
    } else null
}

/** Tool-result content of a tool/result message (first tool-result block). */
fun List<JsonElement>.toolResultText(): String {
    for (block in this) {
        val obj = block.jsonObject
        if (obj["type"]?.jsonPrimitive?.content == "tool-result") {
            val content = obj["content"]
            if (content is JsonArray) {
                return content.mapNotNull { c -> c.jsonObject["text"]?.jsonPrimitive?.content }.joinToString("\n")
            }
        }
    }
    return ""
}

/** Decode a raw event's data payload, tolerating unknown/plugin event shapes. */
object EventData {
    inline fun <reified T> decode(event: RawSessionEvent): T? {
        val deserializer = Wire.json.serializersModule.serializer<T>()
        return runCatching { Wire.json.decodeFromJsonElement(deserializer, event.data) }.getOrNull()
    }

    fun turn(event: RawSessionEvent): Int? = runCatching { Wire.json.decodeFromJsonElement(TurnData.serializer(), event.data).turn }.getOrNull()
    fun step(event: RawSessionEvent): Pair<Int, Int>? = runCatching { Wire.json.decodeFromJsonElement(StepData.serializer(), event.data).let { it.turn to it.step } }.getOrNull()
    fun turnEnd(event: RawSessionEvent): TurnEndData? = decode<TurnEndData>(event)
    fun todos(event: RawSessionEvent): TodoWriteData? = decode<TodoWriteData>(event)
    fun toolCall(event: RawSessionEvent): ToolCallData? = decode<ToolCallData>(event)
    fun toolResult(event: RawSessionEvent): ToolResultData? = decode<ToolResultData>(event)
    fun assistantMessage(event: RawSessionEvent): AssistantMessageData? = decode<AssistantMessageData>(event)
    fun userMessage(event: RawSessionEvent): MessageJson? = decode<MessageJson>(event)
    fun chunk(event: RawSessionEvent): ChunkData? = decode<ChunkData>(event)
}
