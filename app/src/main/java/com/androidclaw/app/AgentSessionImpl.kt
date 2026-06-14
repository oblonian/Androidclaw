package com.androidclaw.app

import android.content.Context
import com.androidclaw.gateway.AgentEvent
import com.androidclaw.gateway.ConfirmDecision
import com.androidclaw.gateway.Confirmer
import com.androidclaw.llm.ChatMessage
import com.androidclaw.llm.ContentBlock
import com.androidclaw.llm.Role
import com.androidclaw.llm.ToolCall
import com.androidclaw.overlay.AgentSession
import com.androidclaw.overlay.OverlayBridge
import com.androidclaw.overlay.OverlayDecision
import com.androidclaw.overlay.OverlayReply
import com.androidclaw.overlay.OverlayService
import com.androidclaw.overlay.SessionEntry
import com.androidclaw.overlay.SessionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonPrimitive

/**
 * The single agent conversation behind both the in-app chat and the floating
 * overlay (SPEC §3/§12). It owns the one wire-format history, the one in-flight
 * turn, and the streams every view renders from. When a device action starts it
 * auto-pops the overlay onto this same session, so a task begun in the app keeps
 * running over whatever app the user switches to — one unit, not two.
 */
class AgentSessionImpl(
    private val container: AppContainer,
    private val appContext: Context,
) : AgentSession {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _transcript = MutableStateFlow<List<SessionEntry>>(emptyList())
    override val transcript: StateFlow<List<SessionEntry>> = _transcript.asStateFlow()

    private val _state = MutableStateFlow(SessionState(maxIterations = container.settings.maxIterations))
    override val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _pendingStep = MutableStateFlow<String?>(null)
    override val pendingStep: StateFlow<String?> = _pendingStep.asStateFlow()

    // Ephemeral chrome deltas; drop on overflow rather than ever stalling the turn.
    private val _events = MutableSharedFlow<OverlayReply>(
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<OverlayReply> = _events.asSharedFlow()

    /** Wire-format history; the gateway is stateless and is handed this each turn. */
    private var conversation: List<ChatMessage> = emptyList()
    private var currentSessionId: String? = null
    private var turnJob: Job? = null
    private var pendingDecision: CompletableDeferred<ConfirmDecision>? = null
    private var autoPoppedThisTurn = false
    /** Channel for mid-turn user redirects; replaced each turn, null between turns. */
    private var currentInterjectionChannel: Channel<String>? = null

    /** Gates each CONFIRM-tier action through whichever surface the user can see. */
    private val confirmer = Confirmer { call ->
        if (!container.settings.stepThrough) return@Confirmer ConfirmDecision.Proceed
        // Prefer the overlay (visible on top of any app); fall back to the in-app bar.
        val overlayConfirm = OverlayBridge.confirmHandler
        if (overlayConfirm != null) {
            return@Confirmer when (val od = overlayConfirm(describe(call))) {
                OverlayDecision.Proceed -> ConfirmDecision.Proceed
                OverlayDecision.Back -> ConfirmDecision.Back
                OverlayDecision.Stop -> ConfirmDecision.Cancel
                is OverlayDecision.Chat -> ConfirmDecision.Chat(od.note)
            }
        }
        val deferred = CompletableDeferred<ConfirmDecision>()
        pendingDecision = deferred
        _pendingStep.value = describe(call)
        val decision = deferred.await()
        _pendingStep.value = null
        pendingDecision = null
        decision
    }

    // ── Public API ────────────────────────────────────────────────────────────

    override fun send(text: String) = send(text, silent = false)

    fun send(text: String, silent: Boolean) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (_state.value.busy) {
            // Interject into the running turn — the gateway picks it up at the
            // start of the next iteration so context is never lost.
            if (!silent) appendEntry(SessionEntry.User(trimmed))
            currentInterjectionChannel?.trySend(trimmed)
            return
        }
        val gateway = container.gateway(confirmer) ?: run {
            appendEntry(SessionEntry.Error("Open the Claw app and sign in first."))
            _events.tryEmit(OverlayReply.Failed("Claw isn't set up yet."))
            return
        }
        if (!silent) appendEntry(SessionEntry.User(trimmed))
        autoPoppedThisTurn = false
        _state.value = SessionState(busy = true, iteration = 0, maxIterations = container.settings.maxIterations)
        val channel = Channel<String>(Channel.UNLIMITED)
        currentInterjectionChannel = channel
        val history = conversation + ChatMessage.user(trimmed)
        turnJob = scope.launch {
            try {
                gateway.runTurn(history, channel).collect { handle(it) }
            } finally {
                currentInterjectionChannel = null
            }
        }
    }

    override fun continueFromLimit() {
        if (_state.value.busy) return
        // Drop the trailing limit marker so the continued turn reads cleanly.
        _transcript.value = _transcript.value.dropLastWhile { it is SessionEntry.LimitReached }
        send("Continue from where you left off.", silent = true)
    }

    override fun stop() {
        pendingDecision?.complete(ConfirmDecision.Cancel)
        turnJob?.cancel()
        turnJob = null
        currentInterjectionChannel = null
        _pendingStep.value = null
        pendingDecision = null
        finishStreaming()
        _state.value = _state.value.copy(busy = false, iteration = 0)
    }

    override fun stepForward() = resolveStep(ConfirmDecision.Proceed)
    override fun stepBack() = resolveStep(ConfirmDecision.Back)
    override fun stepChat(note: String) {
        if (note.isNotBlank()) resolveStep(ConfirmDecision.Chat(note.trim()))
    }

    /**
     * App-only: the in-app step bar's Stop. Cancels gracefully through the
     * confirm gate so the gateway answers outstanding tool calls and ends the
     * turn with valid history (vs. [stop], which hard-cancels the coroutine).
     */
    fun stepStop() {
        if (pendingDecision != null) resolveStep(ConfirmDecision.Cancel) else stop()
    }

    /** App-only: stop and wipe the conversation for a fresh chat. */
    fun clear() {
        stop()
        conversation = emptyList()
        _transcript.value = emptyList()
        currentSessionId = null
    }

    /** App-only: re-ask the last user message, dropping the failed/partial response. */
    fun retry() {
        if (_state.value.busy) return
        val lastUserIdx = _transcript.value.indexOfLast { it is SessionEntry.User }
        if (lastUserIdx < 0) return
        val lastUser = _transcript.value[lastUserIdx] as SessionEntry.User
        _transcript.value = _transcript.value.take(lastUserIdx)
        val lastUserConvIdx = conversation.indexOfLast { it.role == Role.USER }
        if (lastUserConvIdx >= 0) conversation = conversation.take(lastUserConvIdx)
        send(lastUser.text)
    }

    /** App-only: load a saved conversation into the live session. */
    fun load(saved: SavedSession) {
        stop()
        currentSessionId = saved.id
        _transcript.value = saved.messages.map { m ->
            if (m.role == "user") SessionEntry.User(m.text)
            else SessionEntry.Assistant(m.text, streaming = false)
        }
        conversation = saved.messages.map { m ->
            if (m.role == "user") ChatMessage.user(m.text)
            else ChatMessage(Role.ASSISTANT, listOf(ContentBlock.Text(m.text)))
        }
    }

    // ── Turn event handling ─────────────────────────────────────────────────────

    private suspend fun handle(event: AgentEvent) {
        when (event) {
            is AgentEvent.TextDelta -> {
                appendAssistantText(event.text)
                _events.tryEmit(OverlayReply.TextDelta(event.text))
            }
            is AgentEvent.ToolStarted -> {
                finishStreaming()
                appendEntry(SessionEntry.Tool(event.call.name, running = true, isError = false))
                maybeAutoPop(event.call)
                _events.tryEmit(OverlayReply.ToolStatus(event.call.name, running = true, isError = false))
            }
            is AgentEvent.ToolFinished -> {
                updateLastTool(running = false, isError = event.result.isError)
                _events.tryEmit(OverlayReply.ToolStatus(event.call.name, running = false, isError = event.result.isError))
            }
            is AgentEvent.IterationUpdate -> {
                _state.value = _state.value.copy(iteration = event.current, maxIterations = event.max)
                _events.tryEmit(OverlayReply.IterationUpdate(event.current, event.max))
            }
            is AgentEvent.TurnComplete -> {
                conversation = event.messages
                finishStreaming()
                // Persist before flipping busy=false: observers reload the saved
                // list on that transition and must see this turn already written.
                persist()
                _state.value = _state.value.copy(busy = false, iteration = 0)
                _events.tryEmit(OverlayReply.Done)
            }
            is AgentEvent.TurnLimitReached -> {
                conversation = event.messages
                finishStreaming()
                appendEntry(SessionEntry.LimitReached(event.max))
                persist()
                _state.value = _state.value.copy(busy = false, iteration = 0)
                _events.tryEmit(OverlayReply.LimitReached(event.max))
            }
            is AgentEvent.TurnFailed -> {
                finishStreaming()
                appendEntry(SessionEntry.Error(event.message))
                _state.value = _state.value.copy(busy = false, iteration = 0)
                _events.tryEmit(OverlayReply.Failed(event.message))
            }
        }
    }

    private fun maybeAutoPop(call: ToolCall) {
        if (autoPoppedThisTurn) return
        if (call.name != "ui_action" && call.name != "open_app") return
        autoPoppedThisTurn = true
        if (!OverlayService.canDraw(appContext)) return
        OverlayService.show(appContext)
    }

    private fun resolveStep(decision: ConfirmDecision) {
        pendingDecision?.complete(decision)
    }

    // ── Transcript mutation ─────────────────────────────────────────────────────

    private fun appendEntry(entry: SessionEntry) {
        _transcript.value = _transcript.value + entry
    }

    private fun appendAssistantText(delta: String) {
        val list = _transcript.value
        val last = list.lastOrNull()
        _transcript.value = if (last is SessionEntry.Assistant && last.streaming) {
            list.dropLast(1) + last.copy(text = last.text + delta)
        } else {
            list + SessionEntry.Assistant(delta, streaming = true)
        }
    }

    private fun finishStreaming() {
        val list = _transcript.value
        val idx = list.indexOfLast { it is SessionEntry.Assistant }
        if (idx < 0) return
        val a = list[idx] as SessionEntry.Assistant
        if (!a.streaming) return
        _transcript.value = list.toMutableList().also { it[idx] = a.copy(streaming = false) }
    }

    private fun updateLastTool(running: Boolean, isError: Boolean) {
        val list = _transcript.value
        val idx = list.indexOfLast { it is SessionEntry.Tool }
        if (idx < 0) return
        val t = list[idx] as SessionEntry.Tool
        _transcript.value = list.toMutableList().also { it[idx] = t.copy(running = running, isError = isError) }
    }

    private fun persist() {
        currentSessionId = container.sessionStore.save(_transcript.value, currentSessionId)
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
}
