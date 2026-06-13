package com.androidclaw.overlay

import kotlinx.coroutines.flow.Flow

sealed interface OverlayReply {
    data class TextDelta(val text: String) : OverlayReply
    data class ToolStatus(val name: String, val running: Boolean, val isError: Boolean) : OverlayReply
    data class IterationUpdate(val current: Int, val max: Int) : OverlayReply
    data object Done : OverlayReply
    data class LimitReached(val max: Int) : OverlayReply
    data class Failed(val message: String) : OverlayReply
}

sealed interface OverlayDecision {
    data object Proceed : OverlayDecision
    data object Back : OverlayDecision
    data object Stop : OverlayDecision
    data class Chat(val note: String) : OverlayDecision
}

interface OverlayAgent {
    fun runTurn(userText: String): Flow<OverlayReply>
    fun continueFromLimit(): Flow<OverlayReply>
    fun reset()
}

object OverlayBridge {
    @Volatile var agent: OverlayAgent? = null

    @Volatile var confirmHandler: (suspend (String) -> OverlayDecision)? = null

    /** Set by ClawApp on startup; restored from SettingsStore. */
    @Volatile var initialPuckX: Int = -1
    @Volatile var initialPuckY: Int = -1

    /** Called when the puck is dragged/snapped; ClawApp persists to SettingsStore. */
    @Volatile var onPuckPositionChanged: ((x: Int, y: Int) -> Unit)? = null
}
