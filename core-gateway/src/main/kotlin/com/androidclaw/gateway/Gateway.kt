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
import com.androidclaw.tools.PermissionTier
import com.androidclaw.tools.ToolRegistry
import com.androidclaw.tools.ToolResult
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
    private val confirmer: Confirmer = Confirmer.AutoApprove,
) {

    /** Runs one user turn. [history] must already end with the new user message. */
    fun runTurn(history: List<ChatMessage>): Flow<AgentEvent> = flow {
        val messages = history.toMutableList()
        val schemas = tools.schemas()

        repeat(maxIterations) { iteration ->
            emit(AgentEvent.IterationUpdate(iteration + 1, maxIterations))
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

            val results = mutableListOf<ContentBlock.ToolResult>()
            for ((index, call) in toolCalls.withIndex()) {
                // Gate CONFIRM-tier actions through the user (step-through control).
                val decision = if (tools.tierOf(call.name) == PermissionTier.CONFIRM) {
                    confirmer.confirm(call)
                } else {
                    ConfirmDecision.Proceed
                }

                if (decision is ConfirmDecision.Cancel) {
                    // Answer every outstanding tool_use so the history stays valid,
                    // then end the turn.
                    toolCalls.drop(index).forEach {
                        results += ContentBlock.ToolResult(it.id, "Cancelled by user.", isError = false)
                    }
                    messages += ChatMessage(Role.USER, results)
                    emit(AgentEvent.TurnComplete(messages))
                    return@flow
                }

                val result = when (decision) {
                    ConfirmDecision.Proceed -> {
                        emit(AgentEvent.ToolStarted(call))
                        // A buggy tool must not abort the turn (or crash the collector) —
                        // surface the failure to the model as an error result instead.
                        runCatching { tools.execute(call) }
                            .getOrElse { e ->
                                if (e is kotlinx.coroutines.CancellationException) throw e
                                ToolResult("Tool crashed: ${e.message ?: e.javaClass.simpleName}", isError = true)
                            }
                            .also { emit(AgentEvent.ToolFinished(call, it)) }
                    }
                    ConfirmDecision.Back -> ToolResult(
                        "User stepped back — do NOT take this action. Reconsider the previous " +
                            "step or ask the user what to do instead.",
                    )
                    is ConfirmDecision.Chat -> ToolResult(
                        "User did not approve that action and said: \"${decision.note}\". " +
                            "Adjust your plan accordingly; do not take the proposed action.",
                    )
                    ConfirmDecision.Cancel -> error("handled above")
                }
                results += ContentBlock.ToolResult(call.id, result.content, result.isError)
            }
            messages += ChatMessage(Role.USER, results)
        }

        emit(AgentEvent.TurnLimitReached(messages, maxIterations))
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
