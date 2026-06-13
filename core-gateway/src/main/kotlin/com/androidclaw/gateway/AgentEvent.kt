package com.androidclaw.gateway

import com.androidclaw.llm.ChatMessage
import com.androidclaw.llm.ToolCall
import com.androidclaw.tools.ToolResult

/** Events surfaced to the UI while a turn runs. */
sealed interface AgentEvent {
    data class TextDelta(val text: String) : AgentEvent
    data class ToolStarted(val call: ToolCall) : AgentEvent
    data class ToolFinished(val call: ToolCall, val result: ToolResult) : AgentEvent
    /** Emitted at the start of each tool-use iteration (1-based). */
    data class IterationUpdate(val current: Int, val max: Int) : AgentEvent
    /** Terminal: full wire-format conversation including this turn. */
    data class TurnComplete(val messages: List<ChatMessage>) : AgentEvent
    data class TurnFailed(val message: String) : AgentEvent
    /** Terminal: the per-turn action cap was reached; history is still valid and continuable. */
    data class TurnLimitReached(val messages: List<ChatMessage>, val max: Int) : AgentEvent
}
