package com.androidclaw.app

import com.androidclaw.gateway.AgentEvent
import com.androidclaw.llm.ChatMessage
import com.androidclaw.overlay.OverlayAgent
import com.androidclaw.overlay.OverlayReply
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Bridges the floating overlay to the same stateless Gateway used by the
 * in-app chat. Keeps its own short conversation history so the overlay
 * supports multi-turn follow-ups; mirrors ChatViewModel's ownership model.
 */
class OverlayAgentImpl(private val container: AppContainer) : OverlayAgent {

    private var conversation: List<ChatMessage> = emptyList()

    override fun runTurn(userText: String): Flow<OverlayReply> = flow {
        val gateway = container.gateway()
        if (gateway == null) {
            emit(OverlayReply.Failed("Claw isn't set up — open the app and sign in."))
            return@flow
        }
        val history = conversation + ChatMessage.user(userText)
        gateway.runTurn(history).collect { event ->
            when (event) {
                is AgentEvent.TextDelta -> emit(OverlayReply.TextDelta(event.text))
                is AgentEvent.ToolStarted ->
                    emit(OverlayReply.ToolStatus(event.call.name, running = true, isError = false))
                is AgentEvent.ToolFinished ->
                    emit(OverlayReply.ToolStatus(event.call.name, running = false, isError = event.result.isError))
                is AgentEvent.TurnComplete -> {
                    conversation = event.messages
                    emit(OverlayReply.Done)
                }
                is AgentEvent.TurnFailed -> emit(OverlayReply.Failed(event.message))
            }
        }
    }

    /** Drop history (mirrors the app's Clear action). */
    fun reset() { conversation = emptyList() }
}
