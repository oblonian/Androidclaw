package com.androidclaw.app

import com.androidclaw.gateway.AgentEvent
import com.androidclaw.gateway.ConfirmDecision
import com.androidclaw.gateway.Confirmer
import com.androidclaw.llm.ChatMessage
import com.androidclaw.llm.ToolCall
import com.androidclaw.overlay.OverlayAgent
import com.androidclaw.overlay.OverlayBridge
import com.androidclaw.overlay.OverlayDecision
import com.androidclaw.overlay.OverlayReply
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bridges the floating overlay to the same stateless Gateway used by the
 * in-app chat. Keeps its own short conversation history so the overlay
 * supports multi-turn follow-ups; mirrors ChatViewModel's ownership model.
 */
class OverlayAgentImpl(private val container: AppContainer) : OverlayAgent {

    private var conversation: List<ChatMessage> = emptyList()

    /** Routes each CONFIRM action to the overlay's step-through buttons. */
    private val confirmer = Confirmer { call ->
        if (!container.settings.stepThrough) return@Confirmer ConfirmDecision.Proceed
        when (val decision = OverlayBridge.confirmHandler?.invoke(describe(call))) {
            OverlayDecision.Proceed, null -> ConfirmDecision.Proceed
            OverlayDecision.Back -> ConfirmDecision.Back
            OverlayDecision.Stop -> ConfirmDecision.Cancel
            is OverlayDecision.Chat -> ConfirmDecision.Chat(decision.note)
        }
    }

    override fun runTurn(userText: String): Flow<OverlayReply> = flow {
        val gateway = container.gateway(confirmer)
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

    private fun describe(call: ToolCall): String {
        fun arg(key: String) = call.input[key]?.let {
            runCatching { it.jsonPrimitive.content }.getOrNull()
        }?.takeIf { it.isNotBlank() }
        return when (call.name) {
            "ui_action" -> when (arg("action")) {
                "tap" -> "Tap \"${arg("target") ?: "?"}\""
                "type" -> "Type \"${arg("text") ?: ""}\"" + (arg("target")?.let { " into \"$it\"" } ?: "")
                "scroll" -> "Scroll ${arg("direction") ?: "down"}"
                "back" -> "Press Back"
                "home" -> "Go Home"
                "recents" -> "Open Recents"
                else -> "Do ${arg("action") ?: "action"}"
            }
            else -> call.name + (arg("app")?.let { " \"$it\"" } ?: "")
        }
    }
}
