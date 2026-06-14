package com.androidclaw.overlay

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One rendered line of the shared conversation. Both the in-app chat list and
 * the floating overlay transcript render from the same [SessionEntry] stream,
 * so what you see in one is exactly what you see in the other.
 */
sealed interface SessionEntry {
    data class User(val text: String) : SessionEntry
    data class Assistant(val text: String, val streaming: Boolean) : SessionEntry
    data class Tool(val name: String, val running: Boolean, val isError: Boolean) : SessionEntry
    data class Error(val text: String) : SessionEntry
    /** The per-turn action cap was hit; the user can continue without losing context. */
    data class LimitReached(val max: Int) : SessionEntry
}

/** Coarse run state of the session, mirrored into both UIs. */
data class SessionState(
    val busy: Boolean = false,
    val iteration: Int = 0,
    val maxIterations: Int = 0,
)

/**
 * The single agent conversation, shared by the in-app chat and the floating
 * overlay. A task started in the app keeps running in the overlay (and vice
 * versa) because there is exactly one history, one in-flight turn, and one
 * event stream behind both views (SPEC §3/§12).
 *
 * [transcript] is the source of truth for *what* is on screen; [events] carries
 * ephemeral deltas a streaming UI needs for chrome (ghosting, status dots,
 * auto-hide). [pendingStep] is non-null while a device action awaits the user's
 * step-through verdict.
 */
interface AgentSession {
    val transcript: StateFlow<List<SessionEntry>>
    val state: StateFlow<SessionState>
    val pendingStep: StateFlow<String?>
    val events: SharedFlow<OverlayReply>

    fun send(text: String)
    fun continueFromLimit()
    fun stop()

    // Step-through verdicts (the "game controller").
    fun stepForward()
    fun stepBack()
    fun stepChat(note: String)
}
