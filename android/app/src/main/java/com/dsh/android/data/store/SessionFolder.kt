package com.dsh.android.data.store

import com.dsh.android.data.protocol.EventData
import com.dsh.android.data.protocol.RawSessionEvent
import com.dsh.android.data.protocol.TokenUsage
import com.dsh.android.data.protocol.delta
import com.dsh.android.data.protocol.reasoningText
import com.dsh.android.data.protocol.toolCalls
import com.dsh.android.data.protocol.toolResultText
import com.dsh.android.data.protocol.visibleText
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Folds one session's raw event log into ordered UI items, mirroring the
 * official web client's surface semantics: surface events (user/message,
 * assistant/message, tool/result) append; assistant chunks stream into a
 * provisional bubble finalized by the assistant/message event; todo/write is
 * last-write-wins; turn markers carry the turn's token-usage delta.
 */
object SessionFolder {

    private class StreamingAcc(
        val turn: Int,
        val step: Int,
        var index: Int = -1,
        var text: String = "",
        var reasoning: String = "",
        var firstSeq: Long = 0,
    )

    fun fold(d: SessionData): List<ChatItem> {
        val items = ArrayList<ChatItem>()
        val streaming = LinkedHashMap<Pair<Int, Int>, StreamingAcc>()
        val turnMarkers = HashMap<Int, Int>()
        val toolCards = HashMap<String, Int>()
        val usageNow = d.tokenUsage()

        for (event in d.events.values.sortedBy { it.seq }) {
            when (event.type) {
                "user/message" -> foldUserMessage(event, items)

                "assistant/chunk" -> foldChunk(event, items, streaming)

                "assistant/message" -> foldAssistantMessage(event, items, streaming)

                "tool/call" -> {
                    val call = EventData.toolCall(event) ?: continue
                    items += ChatItem.ToolCard(
                        seq = event.seq,
                        time = event.time,
                        callId = call.callId,
                        name = call.name,
                        arguments = call.arguments,
                        resultText = null,
                        isError = false,
                        running = true,
                    )
                    toolCards[call.callId] = items.lastIndex
                }

                "tool/result" -> {
                    val result = EventData.toolResult(event) ?: continue
                    val callId = result.callIdOf() ?: continue
                    val idx = toolCards[callId]
                    if (idx != null && idx < items.size && items[idx] is ChatItem.ToolCard) {
                        items[idx] = (items[idx] as ChatItem.ToolCard).copy(
                            resultText = result.message.content.toolResultText(),
                            isError = result.error != null,
                            running = false,
                        )
                    } else {
                        items += ChatItem.ToolCard(
                            seq = event.seq,
                            time = event.time,
                            callId = callId,
                            name = "tool",
                            arguments = "",
                            resultText = result.message.content.toolResultText(),
                            isError = result.error != null,
                            running = false,
                        )
                        toolCards[callId] = items.lastIndex
                    }
                }

                "todo/write" -> {
                    val todos = EventData.todos(event) ?: continue
                    items.removeAll { it is ChatItem.TodoCard }
                    items += ChatItem.TodoCard(seq = event.seq, time = event.time, todos = todos.todos)
                }

                "turn/start" -> {
                    EventData.turn(event)?.let { turn ->
                        items += ChatItem.TurnMarker(
                            seq = event.seq,
                            time = event.time,
                            turn = turn,
                            reasonKind = null,
                            turnUsage = null,
                        )
                        turnMarkers[turn] = items.lastIndex
                    }
                }

                "turn/end" -> {
                    val end = EventData.turnEnd(event) ?: continue
                    val snapshot = d.turnStartUsage[end.turn]
                    val delta = if (usageNow != null && snapshot != null) diff(snapshot, usageNow) else null
                    val idx = turnMarkers[end.turn]
                    if (idx != null && idx < items.size && items[idx] is ChatItem.TurnMarker) {
                        items[idx] = (items[idx] as ChatItem.TurnMarker).copy(
                            reasonKind = end.reasonKind,
                            turnUsage = delta,
                        )
                    } else {
                        items += ChatItem.TurnMarker(
                            seq = event.seq,
                            time = event.time,
                            turn = end.turn,
                            reasonKind = end.reasonKind,
                            turnUsage = delta,
                        )
                    }
                }

                else -> Unit // request/header, step markers, unknown plugin events: not rendered
            }
        }

        // Streaming tails: chunks whose assistant/message has not landed yet.
        for (acc in streaming.values) {
            if (acc.index in items.indices && items[acc.index] is ChatItem.AssistantBubble) {
                items[acc.index] = (items[acc.index] as ChatItem.AssistantBubble).copy(
                    text = acc.text,
                    reasoning = acc.reasoning,
                )
            } else {
                items += ChatItem.AssistantBubble(
                    seq = acc.firstSeq,
                    time = System.currentTimeMillis(),
                    text = acc.text,
                    reasoning = acc.reasoning,
                    toolCalls = emptyList(),
                    usage = null,
                    streaming = true,
                    turn = acc.turn,
                    step = acc.step,
                )
            }
        }

        return items
    }

    private fun foldUserMessage(event: RawSessionEvent, items: MutableList<ChatItem>) {
        val message = EventData.userMessage(event) ?: return
        val sourceKind = message.source.jsonObject["kind"]?.jsonPrimitive?.contentOrNull
        val text = message.content.visibleText()
        if (sourceKind == "user") {
            items += ChatItem.UserBubble(
                seq = event.seq,
                time = event.time,
                text = text,
                fromUser = true,
            )
        } else if (text.isNotBlank()) {
            // Plugin/system context injections (file-change notices, AGENTS.md, …)
            items += ChatItem.Notice(seq = event.seq, time = event.time, text = text)
        }
    }

    private fun foldChunk(
        event: RawSessionEvent,
        items: MutableList<ChatItem>,
        streaming: LinkedHashMap<Pair<Int, Int>, StreamingAcc>,
    ) {
        val chunk = EventData.chunk(event) ?: return
        val key = chunk.turn to chunk.step
        val acc = streaming.getOrPut(key) {
            StreamingAcc(turn = chunk.turn, step = chunk.step, firstSeq = event.seq).also {
                items += ChatItem.AssistantBubble(
                    seq = event.seq,
                    time = event.time,
                    text = "",
                    reasoning = "",
                    toolCalls = emptyList(),
                    usage = null,
                    streaming = true,
                    turn = chunk.turn,
                    step = chunk.step,
                )
                it.index = items.lastIndex
            }
        }
        val delta = chunk.delta()
        when (delta.chunkType) {
            "text-delta" -> acc.text += delta.text
            "reasoning-delta" -> acc.reasoning += delta.text
            else -> Unit
        }
        // Live streaming update (without a final message): touch the placeholder.
        if (acc.index in items.indices && items[acc.index] is ChatItem.AssistantBubble) {
            items[acc.index] = (items[acc.index] as ChatItem.AssistantBubble).copy(
                text = acc.text,
                reasoning = acc.reasoning,
            )
        }
    }

    private fun foldAssistantMessage(
        event: RawSessionEvent,
        items: MutableList<ChatItem>,
        streaming: LinkedHashMap<Pair<Int, Int>, StreamingAcc>,
    ) {
        val message = EventData.assistantMessage(event) ?: return
        val key = message.turn to message.step
        val acc = streaming.remove(key)
        val bubble = ChatItem.AssistantBubble(
            seq = event.seq,
            time = event.time,
            text = message.message.content.visibleText().ifBlank { acc?.text ?: "" },
            reasoning = message.message.content.reasoningText().ifBlank { acc?.reasoning ?: "" },
            toolCalls = message.message.content.toolCalls(),
            usage = message.usage,
            streaming = false,
            turn = message.turn,
            step = message.step,
        )
        if (acc != null && acc.index in items.indices && items[acc.index] is ChatItem.AssistantBubble) {
            items[acc.index] = bubble
        } else {
            items += bubble
        }
    }

    private fun diff(snapshot: com.dsh.android.data.protocol.TokenUsageProjection, now: com.dsh.android.data.protocol.TokenUsageProjection): TokenUsage {
        fun d(a: Long, b: Long) = (a - b).coerceAtLeast(0)
        fun dn(a: Long?, b: Long?): Long? {
            if (a == null || b == null) return null
            return (a - b).coerceAtLeast(0)
        }
        return TokenUsage(
            inputTokens = d(now.uncachedInputTokens, snapshot.uncachedInputTokens),
            outputTokens = d(now.outputTokens, snapshot.outputTokens),
            cacheReadTokens = dn(now.cacheReadTokens, snapshot.cacheReadTokens),
            cacheWriteTokens = dn(now.cacheWriteTokens, snapshot.cacheWriteTokens),
        )
    }

    private fun com.dsh.android.data.protocol.ToolResultData.callIdOf(): String? {
        for (block in message.content) {
            val obj = block.jsonObject
            if (obj["type"]?.jsonPrimitive?.contentOrNull == "tool-result") {
                obj["toolCallId"]?.jsonPrimitive?.contentOrNull?.let { return it }
            }
        }
        return message.source.jsonObject["callId"]?.jsonPrimitive?.contentOrNull
    }
}
