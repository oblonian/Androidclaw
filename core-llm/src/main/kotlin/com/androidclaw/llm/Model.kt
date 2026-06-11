package com.androidclaw.llm

import kotlinx.serialization.json.JsonObject

enum class Role { USER, ASSISTANT }

/** One block of message content, mirroring the Anthropic wire format. */
sealed interface ContentBlock {
    data class Text(val text: String) : ContentBlock
    data class ToolUse(val id: String, val name: String, val input: JsonObject) : ContentBlock
    data class ToolResult(val toolUseId: String, val content: String, val isError: Boolean = false) : ContentBlock
}

data class ChatMessage(val role: Role, val content: List<ContentBlock>) {
    companion object {
        fun user(text: String) = ChatMessage(Role.USER, listOf(ContentBlock.Text(text)))
    }
}

/** A tool the model may call. [inputSchema] is a JSON Schema object. */
data class ToolSchema(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

data class ToolCall(val id: String, val name: String, val input: JsonObject)

data class LlmRequest(
    val system: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSchema> = emptyList(),
    val maxTokens: Int = 4096,
)

enum class StopReason { END_TURN, TOOL_USE, MAX_TOKENS, OTHER }

/** Streamed output events from a provider. */
sealed interface LlmEvent {
    data class TextDelta(val text: String) : LlmEvent
    /** Emitted once a tool_use block is fully accumulated. */
    data class ToolCallReady(val call: ToolCall) : LlmEvent
    data class Completed(val stopReason: StopReason) : LlmEvent
}

class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause)
