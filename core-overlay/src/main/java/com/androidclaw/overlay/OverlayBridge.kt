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

/** Base colour scheme for the overlay panel. AUTO follows the system setting. */
enum class OverlayThemeMode { LIGHT, DARK, AUTO }

/**
 * User-tunable look for the floating overlay. The app resolves these from
 * settings and pushes them via [OverlayBridge.theme]; the service applies them
 * (accent on the header/send button/puck, opacity on the windows, the puck
 * glyph) and resolves the light/dark palette itself so AUTO works live.
 */
data class OverlayTheme(
    val mode: OverlayThemeMode = OverlayThemeMode.LIGHT,
    val accent: Int = DEFAULT_ACCENT,
    val panelAlpha: Float = 0.96f,
    val puckAlpha: Float = 0.55f,
    val puckGlyph: String = "🦞",
) {
    companion object {
        const val DEFAULT_ACCENT: Int = 0xFFD2512A.toInt() // coral
        val DEFAULT = OverlayTheme()
    }
}

object OverlayBridge {
    @Volatile var agent: OverlayAgent? = null

    @Volatile var confirmHandler: (suspend (String) -> OverlayDecision)? = null

    /** Current look; the app refreshes this from settings. */
    @Volatile var theme: OverlayTheme = OverlayTheme.DEFAULT

    /** Puck position, restored from SettingsStore across service restarts. */
    @Volatile var initialPuckX: Int = -1
    @Volatile var initialPuckY: Int = -1
    @Volatile var onPuckPositionChanged: ((x: Int, y: Int) -> Unit)? = null

    /** Expanded-card size (px), restored across restarts. -1 = use defaults. */
    @Volatile var initialCardWidth: Int = -1
    @Volatile var initialTranscriptHeight: Int = -1
    @Volatile var onCardSizeChanged: ((width: Int, transcriptHeight: Int) -> Unit)? = null
}
