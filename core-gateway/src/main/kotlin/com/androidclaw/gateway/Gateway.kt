package com.androidclaw.gateway

import com.androidclaw.llm.ChatMessage
import com.androidclaw.llm.ContentBlock
import com.androidclaw.llm.LlmEvent
import com.androidclaw.llm.LlmException
import com.androidclaw.llm.LlmProvider
import com.androidclaw.llm.LlmRequest
import com.androidclaw.llm.Role
import com.androidclaw.llm.StopReason
import com.androidclaw.llm.ToolCall
import com.androidclaw.tools.ToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The orchestrator: a bounded ReAct loop per SPEC §4.
 *
 * Stateless between turns — callers own the conversation history and pass it
 * in; the loop returns the extended history via [AgentEvent.TurnComplete].
 */
class Gateway(
    private val provider: LlmProvider,
    private val tools: ToolRegistry,
    private val systemPrompt: String,
    private val maxIterations: Int = 8,
) {

    /** Runs one user turn. [history] must already end with the new user message. */
    fun runTurn(history: List<ChatMessage>): Flow<AgentEvent> = flow {
        val messages = history.toMutableList()
        val schemas = tools.schemas()

        repeat(maxIterations) {
            var stopReason = StopReason.OTHER
            val text = StringBuilder()
            val toolCalls = mutableListOf<ToolCall>()

            try {
                provider.stream(LlmRequest(systemPrompt, messages, schemas)).collect { event ->
                    when (event) {
                        is LlmEvent.TextDelta -> {
                            text.append(event.text)
                            emit(AgentEvent.TextDelta(event.text))
                        }
                        is LlmEvent.ToolCallReady -> toolCalls += event.call
                        is LlmEvent.Completed -> stopReason = event.stopReason
                    }
                }
            } catch (e: LlmException) {
                emit(AgentEvent.TurnFailed(e.message ?: "LLM request failed"))
                return@flow
            }

            messages += assistantMessage(text.toString(), toolCalls)

            if (stopReason != StopReason.TOOL_USE || toolCalls.isEmpty()) {
                emit(AgentEvent.TurnComplete(messages))
                return@flow
            }

            val results = toolCalls.map { call ->
                emit(AgentEvent.ToolStarted(call))
                val result = tools.execute(call)
                emit(AgentEvent.ToolFinished(call, result))
                ContentBlock.ToolResult(call.id, result.content, result.isError)
            }
            messages += ChatMessage(Role.USER, results)
        }

        emit(AgentEvent.TurnFailed("Stopped after $maxIterations tool iterations"))
    }

    private fun assistantMessage(text: String, calls: List<ToolCall>): ChatMessage {
        val blocks = buildList {
            if (text.isNotEmpty()) add(ContentBlock.Text(text))
            calls.forEach { add(ContentBlock.ToolUse(it.id, it.name, it.input)) }
        }
        // The API rejects empty content; should not happen, but stay defensive.
        return ChatMessage(Role.ASSISTANT, blocks.ifEmpty { listOf(ContentBlock.Text("…")) })
    }
}
