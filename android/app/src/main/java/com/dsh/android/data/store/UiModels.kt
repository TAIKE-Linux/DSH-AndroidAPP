package com.dsh.android.data.store

import com.dsh.android.data.protocol.TodoItem
import com.dsh.android.data.protocol.TokenUsage
import com.dsh.android.data.protocol.ToolCallRef

/** Display model produced by folding a session's raw event log. */
sealed interface ChatItem {
    val seq: Long
    val time: Long

    data class UserBubble(
        override val seq: Long,
        override val time: Long,
        val text: String,
        val fromUser: Boolean,
    ) : ChatItem

    data class AssistantBubble(
        override val seq: Long,
        override val time: Long,
        val text: String,
        val reasoning: String,
        val toolCalls: List<ToolCallRef>,
        val usage: TokenUsage?,
        val streaming: Boolean,
        val turn: Int,
        val step: Int,
    ) : ChatItem

    data class ToolCard(
        override val seq: Long,
        override val time: Long,
        val callId: String,
        val name: String,
        val arguments: String,
        val resultText: String?,
        val isError: Boolean,
        val running: Boolean,
    ) : ChatItem

    data class TodoCard(
        override val seq: Long,
        override val time: Long,
        val todos: List<TodoItem>,
    ) : ChatItem

    data class TurnMarker(
        override val seq: Long,
        override val time: Long,
        val turn: Int,
        val reasonKind: String?,
        /** Token usage this turn consumed (projection delta across the turn). */
        val turnUsage: TokenUsage?,
    ) : ChatItem

    data class Notice(
        override val seq: Long,
        override val time: Long,
        val text: String,
    ) : ChatItem
}
