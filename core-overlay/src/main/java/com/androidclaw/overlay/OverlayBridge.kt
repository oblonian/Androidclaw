package com.androidclaw.overlay

import kotlinx.coroutines.flow.Flow

/**
 * One streamed reply fragment from the agent to the overlay.
 * Deliberately tiny so core-overlay needn't depend on core-gateway's
 * richer event model — the app maps AgentEvent → OverlayReply.
 */
sealed interface OverlayReply {
    data class TextDelta(val text: String) : OverlayReply
    data class ToolStatus(val name: String, val running: Boolean, val isError: Boolean) : OverlayReply
    data object Done : OverlayReply
    data class Failed(val message: String) : OverlayReply
}

/**
 * The contract the overlay uses to run a turn. The app supplies an
 * implementation that delegates to the Gateway; the overlay module never
 * depends on `app`, avoiding a dependency cycle.
 */
fun interface OverlayAgent {
    /** Run one user turn; the overlay owns no history of its own beyond what it renders. */
    fun runTurn(userText: String): Flow<OverlayReply>
}

/** The user's verdict on a proposed device action, surfaced in the overlay. */
sealed interface OverlayDecision {
    data object Proceed : OverlayDecision
    data object Back : OverlayDecision
    data object Stop : OverlayDecision
    data class Chat(val note: String) : OverlayDecision
}

/**
 * Process-wide injection point. ClawApp sets [agent] on startup; the
 * foreground service reads it when the user sends a message. Null until
 * the app is configured (no API key / not signed in).
 */
object OverlayBridge {
    @Volatile
    var agent: OverlayAgent? = null

    /**
     * Set by the overlay service while it is showing. The agent's confirmer
     * calls this to ask the user to step a device action through; it suspends
     * until a button (or a typed note) resolves it. Null when the overlay is
     * not visible — callers should proceed automatically in that case.
     */
    @Volatile
    var confirmHandler: (suspend (String) -> OverlayDecision)? = null
}
