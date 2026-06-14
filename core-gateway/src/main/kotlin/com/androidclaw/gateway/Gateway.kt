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
import com.androidclaw.tools.SCREEN_MARKER
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

        // [maxIterations] is the user-facing *action* budget. Perception
        // (read_screen) is free, so we also keep a hard ceiling on total
        // round-trips to guarantee termination if the agent only ever reads.
        var actions = 0
        val hardCap = maxIterations * 3

        repeat(hardCap) {
            var stopReason = StopReason.OTHER
            val text = StringBuilder()
            val toolCalls = mutableListOf<ToolCall>()

            try {
                provider.stream(LlmRequest(systemPrompt, pruneScreenHistory(messages), schemas)).collect { event ->
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
                        // Count the action before running it (perception is exempt).
                        if (tools.countsTowardLimit(call.name)) actions++
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

            if (actions > 0) emit(AgentEvent.IterationUpdate(actions, maxIterations))
            if (actions >= maxIterations) {
                emit(AgentEvent.TurnLimitReached(messages, maxIterations))
                return@flow
            }
        }

        // Reached the hard round-trip ceiling (e.g. an all-perception loop).
        emit(AgentEvent.TurnLimitReached(messages, maxIterations))
    }

    /**
     * Collapses every folded screen capture in the history except the most
     * recent one. Screens arrive tagged with [SCREEN_MARKER] — from read_screen
     * and from the post-action screen that ui_action / open_app return — and the
     * full dump is large, so keeping every one would blow the prompt up as a turn
     * grows. Only the LLM-bound copy is trimmed; [messages] keeps the originals
     * for the caller's persisted history. The action outcome before the marker
     * is preserved so the model still sees what each step did.
     */
    private fun pruneScreenHistory(messages: List<ChatMessage>): List<ChatMessage> {
        // (messageIndex, blockIndex) of every tool_result carrying a screen.
        val located = buildList {
            messages.forEachIndexed { mi, msg ->
                if (msg.role != Role.USER) return@forEachIndexed
                msg.content.forEachIndexed { bi, block ->
                    if (block is ContentBlock.ToolResult && SCREEN_MARKER in block.content) {
                        add(mi to bi)
                    }
                }
            }
        }
        if (located.size <= 1) return messages

        val stale = located.dropLast(1).toSet() // keep only the latest verbatim
        return messages.mapIndexed { mi, msg ->
            if (msg.role != Role.USER) return@mapIndexed msg
            var changed = false
            val newContent = msg.content.mapIndexed { bi, block ->
                if ((mi to bi) in stale && block is ContentBlock.ToolResult) {
                    changed = true
                    val outcome = block.content.substringBefore(SCREEN_MARKER)
                    block.copy(content = outcome + SCREEN_MARKER + "[earlier screen omitted]")
                } else {
                    block
                }
            }
            if (changed) msg.copy(content = newContent) else msg
        }
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
