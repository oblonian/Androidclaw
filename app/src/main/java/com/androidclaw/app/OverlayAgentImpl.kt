package com.androidclaw.app

import com.androidclaw.gateway.AgentEvent
import com.androidclaw.gateway.ConfirmDecision
import com.androidclaw.gateway.Confirmer
import com.androidclaw.llm.ChatMessage
import com.androidclaw.llm.ContentBlock
import com.androidclaw.llm.Role
import com.androidclaw.llm.ToolCall
import com.androidclaw.overlay.OverlayAgent
import com.androidclaw.overlay.OverlayBridge
import com.androidclaw.overlay.OverlayDecision
import com.androidclaw.overlay.OverlayReply
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.jsonPrimitive

class OverlayAgentImpl(private val container: AppContainer) : OverlayAgent {

    private var conversation: List<ChatMessage> = emptyList()
    private var currentSession: SavedSession? = null

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
        val gateway = container.overlayGateway(confirmer)
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
                is AgentEvent.IterationUpdate ->
                    emit(OverlayReply.IterationUpdate(event.current, event.max))
                is AgentEvent.TurnComplete -> {
                    conversation = event.messages
                    persistSession()
                    emit(OverlayReply.Done)
                }
                is AgentEvent.TurnLimitReached -> {
                    conversation = event.messages
                    persistSession()
                    emit(OverlayReply.LimitReached(event.max))
                }
                is AgentEvent.TurnFailed -> emit(OverlayReply.Failed(event.message))
            }
        }
    }

    override fun continueFromLimit(): Flow<OverlayReply> =
        runTurn("Continue from where you left off.")

    override fun reset() {
        conversation = emptyList()
        currentSession = null
    }

    private fun persistSession() {
        val messages = conversation.mapNotNull { msg ->
            when (msg.role) {
                Role.USER -> {
                    val text = (msg.content.firstOrNull { it is ContentBlock.Text } as? ContentBlock.Text)?.text
                    if (!text.isNullOrBlank()) SavedMessage("user", text) else null
                }
                Role.ASSISTANT -> {
                    val text = msg.content.filterIsInstance<ContentBlock.Text>().joinToString(" ") { it.text }.trim()
                    if (text.isNotBlank()) SavedMessage("assistant", text) else null
                }
            }
        }
        if (messages.isEmpty()) return
        val rawTitle = messages.firstOrNull { it.role == "user" }?.text ?: return
        val title = if (rawTitle.length > 55) "${rawTitle.take(52).trimEnd()}…" else rawTitle
        val session = currentSession?.copy(messages = messages)
            ?: SavedSession(title = title, messages = messages).also { currentSession = it }
        currentSession = session
        container.sessionStore.upsert(session)
    }

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
