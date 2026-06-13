package com.androidclaw.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androidclaw.gateway.AgentEvent
import com.androidclaw.gateway.ConfirmDecision
import com.androidclaw.gateway.Confirmer
import com.androidclaw.llm.ChatMessage
import com.androidclaw.llm.ContentBlock
import com.androidclaw.llm.Role
import com.androidclaw.llm.ToolCall
import com.androidclaw.overlay.OverlayBridge
import com.androidclaw.overlay.OverlayDecision
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive

/** One visual entry in the chat list. */
sealed interface ChatItem {
    data class User(val text: String) : ChatItem
    data class Assistant(val text: String, val streaming: Boolean) : ChatItem
    data class ToolUse(val name: String, val running: Boolean, val isError: Boolean) : ChatItem
    data class Error(val text: String) : ChatItem
    /** The per-turn action cap was hit; the user can continue without losing context. */
    data class LimitReached(val max: Int) : ChatItem
}

class ChatViewModel(private val container: AppContainer) : ViewModel() {

    var items by mutableStateOf<List<ChatItem>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set
    var needsAuth by mutableStateOf(!container.settings.isConfigured)
        private set
    var sessions by mutableStateOf(container.sessionStore.loadAll())
        private set
    var iterationCount by mutableStateOf(0)
        private set

    /** Non-null while a device action is awaiting the user's step-through verdict. */
    var pendingStep by mutableStateOf<String?>(null)
        private set

    /** Wire-format history owned here; the gateway is stateless (SPEC §4). */
    private var conversation: List<ChatMessage> = emptyList()
    private var turnJob: Job? = null
    private var pendingDecision: CompletableDeferred<ConfirmDecision>? = null

    /** Gates each CONFIRM-tier action through the UI when step-through is on. */
    private val confirmer = Confirmer { call ->
        if (!container.settings.stepThrough) return@Confirmer ConfirmDecision.Proceed
        // Prefer overlay (visible on top of any app — YouTube, WhatsApp, etc.)
        val overlayConfirm = OverlayBridge.confirmHandler
        if (overlayConfirm != null) {
            val od = overlayConfirm(describe(call))
            return@Confirmer when (od) {
                OverlayDecision.Proceed -> ConfirmDecision.Proceed
                OverlayDecision.Back -> ConfirmDecision.Back
                OverlayDecision.Stop -> ConfirmDecision.Cancel
                is OverlayDecision.Chat -> ConfirmDecision.Chat(od.note)
            }
        }
        // Fallback: in-app step bar (user is looking at Claw)
        val deferred = CompletableDeferred<ConfirmDecision>()
        pendingDecision = deferred
        pendingStep = describe(call)
        val decision = deferred.await()
        pendingStep = null
        pendingDecision = null
        decision
    }

    fun refreshAuthState() {
        needsAuth = !container.settings.isConfigured
    }

    suspend fun connectAnthropicOAuth(code: String): Result<Unit> =
        AnthropicAuth.exchangeCode(container.http, container.settings, code)
            .also { refreshAuthState() }

    fun send(text: String, silent: Boolean = false) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || busy) return
        val gateway = container.gateway(confirmer) ?: run {
            needsAuth = true
            return
        }

        if (!silent) items = items + ChatItem.User(trimmed)
        busy = true
        iterationCount = 0
        val history = conversation + ChatMessage.user(trimmed)

        turnJob = viewModelScope.launch {
            gateway.runTurn(history).collect { event ->
                when (event) {
                    is AgentEvent.TextDelta -> appendAssistantText(event.text)
                    is AgentEvent.ToolStarted -> {
                        finishStreamingBubble()
                        items = items + ChatItem.ToolUse(event.call.name, running = true, isError = false)
                    }
                    is AgentEvent.ToolFinished -> {
                        items = items.replaceLast<ChatItem.ToolUse> {
                            it.copy(running = false, isError = event.result.isError)
                        }
                    }
                    is AgentEvent.IterationUpdate -> iterationCount = event.current
                    is AgentEvent.TurnComplete -> {
                        conversation = event.messages
                        finishStreamingBubble()
                        busy = false
                        iterationCount = 0
                        container.sessionStore.save(items)
                        sessions = container.sessionStore.loadAll()
                    }
                    is AgentEvent.TurnLimitReached -> {
                        conversation = event.messages
                        finishStreamingBubble()
                        items = items + ChatItem.LimitReached(event.max)
                        busy = false
                        iterationCount = 0
                        container.sessionStore.save(items)
                        sessions = container.sessionStore.loadAll()
                    }
                    is AgentEvent.TurnFailed -> {
                        finishStreamingBubble()
                        items = items + ChatItem.Error(event.message)
                        busy = false
                        iterationCount = 0
                    }
                }
            }
        }
    }

    /** Re-runs from the last limit-reached point without adding a visible user bubble. */
    fun continueTurn() {
        if (busy) return
        val lastLimitIdx = items.indexOfLast { it is ChatItem.LimitReached }
        if (lastLimitIdx >= 0) items = items.take(lastLimitIdx)
        send("Continue from where you left off.", silent = true)
    }

    // ── Step-through controls (the "game controller") ─────────────────────────

    fun stepForward() { resolveStep(ConfirmDecision.Proceed) }
    fun stepBack() { resolveStep(ConfirmDecision.Back) }
    fun stepChat(note: String) {
        if (note.isNotBlank()) resolveStep(ConfirmDecision.Chat(note.trim()))
    }
    fun stepStop() { resolveStep(ConfirmDecision.Cancel) }

    private fun resolveStep(decision: ConfirmDecision) {
        pendingDecision?.complete(decision)
    }

    private fun describe(call: ToolCall): String {
        fun arg(key: String) = call.input[key]?.let {
            runCatching { it.jsonPrimitive.content }.getOrNull()
        }?.takeIf { it.isNotBlank() }
        return when (call.name) {
            "ui_action" -> when (arg("action")) {
                "tap" -> "Tap “${arg("target") ?: "?"}”"
                "type" -> "Type “${arg("text") ?: ""}”" + (arg("target")?.let { " into “$it”" } ?: "")
                "scroll" -> "Scroll ${arg("direction") ?: "down"}"
                "back" -> "Press Back"
                "home" -> "Go Home"
                "recents" -> "Open Recents"
                else -> "Do ${arg("action") ?: "action"}"
            }
            else -> call.name + (arg("app")?.let { " “$it”" } ?: "")
        }
    }

    fun loadSession(session: SavedSession) {
        cancelTurn()
        items = session.messages.map { msg ->
            if (msg.role == "user") ChatItem.User(msg.text)
            else ChatItem.Assistant(msg.text, streaming = false)
        }
        conversation = session.messages.map { msg ->
            if (msg.role == "user") ChatMessage.user(msg.text)
            else ChatMessage(Role.ASSISTANT, listOf(ContentBlock.Text(msg.text)))
        }
    }

    fun deleteSession(session: SavedSession) {
        container.sessionStore.delete(session.id)
        sessions = container.sessionStore.loadAll()
    }

    fun cancelTurn() {
        resolveStep(ConfirmDecision.Cancel)
        turnJob?.cancel()
        turnJob = null
        pendingStep = null
        pendingDecision = null
        finishStreamingBubble()
        busy = false
        iterationCount = 0
    }

    /** Re-sends the last user message, stripping the failed response from history. */
    fun retryLastTurn() {
        if (busy) return
        val lastUserIdx = items.indexOfLast { it is ChatItem.User }
        if (lastUserIdx < 0) return
        val lastUser = items[lastUserIdx] as ChatItem.User
        items = items.take(lastUserIdx)
        val lastUserConvIdx = conversation.indexOfLast { it.role == Role.USER }
        if (lastUserConvIdx >= 0) conversation = conversation.take(lastUserConvIdx)
        send(lastUser.text)
    }

    fun clearChat() {
        cancelTurn()
        conversation = emptyList()
        items = emptyList()
    }

    private fun appendAssistantText(delta: String) {
        val last = items.lastOrNull()
        items = if (last is ChatItem.Assistant && last.streaming) {
            items.dropLast(1) + last.copy(text = last.text + delta)
        } else {
            items + ChatItem.Assistant(delta, streaming = true)
        }
    }

    private fun finishStreamingBubble() {
        items = items.replaceLast<ChatItem.Assistant> { it.copy(streaming = false) }
    }

    private inline fun <reified T : ChatItem> List<ChatItem>.replaceLast(
        transform: (T) -> T,
    ): List<ChatItem> {
        val index = indexOfLast { it is T }
        if (index < 0) return this
        return toMutableList().also { it[index] = transform(it[index] as T) }
    }
}
