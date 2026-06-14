package com.androidclaw.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androidclaw.overlay.SessionEntry
import kotlinx.coroutines.launch

/** One visual entry in the chat list. */
sealed interface ChatItem {
    data class User(val text: String) : ChatItem
    data class Assistant(val text: String, val streaming: Boolean) : ChatItem
    data class ToolUse(val name: String, val running: Boolean, val isError: Boolean) : ChatItem
    data class Error(val text: String) : ChatItem
    /** The per-turn action cap was hit; the user can continue without losing context. */
    data class LimitReached(val max: Int) : ChatItem
}

/**
 * Thin presentation adapter over the app-wide [AgentSessionImpl]. The session
 * owns the conversation, the turn, and the step-through state; this view model
 * just mirrors its flows into Compose-observable state and forwards user intent.
 * The overlay observes the very same session, so the two stay in lockstep.
 */
class ChatViewModel(private val container: AppContainer) : ViewModel() {

    private val session = container.session

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
    var pendingStep by mutableStateOf<String?>(null)
        private set

    init {
        viewModelScope.launch {
            session.transcript.collect { entries -> items = entries.map(::toChatItem) }
        }
        viewModelScope.launch {
            session.state.collect { st ->
                busy = st.busy
                iterationCount = st.iteration
                // A finished turn has just been persisted — refresh the saved list.
                if (!st.busy) sessions = container.sessionStore.loadAll()
            }
        }
        viewModelScope.launch {
            session.pendingStep.collect { pendingStep = it }
        }
    }

    fun refreshAuthState() {
        needsAuth = !container.settings.isConfigured
    }

    suspend fun connectAnthropicOAuth(code: String): Result<Unit> =
        AnthropicAuth.exchangeCode(container.http, container.settings, code)
            .also { refreshAuthState() }

    fun send(text: String) {
        if (text.isBlank()) return
        if (!container.settings.isConfigured) {
            needsAuth = true
            return
        }
        session.send(text)
    }

    fun continueTurn() = session.continueFromLimit()
    fun cancelTurn() = session.stop()
    fun clearChat() = session.clear()
    fun retryLastTurn() = session.retry()

    fun stepForward() = session.stepForward()
    fun stepBack() = session.stepBack()
    fun stepChat(note: String) = session.stepChat(note)
    fun stepStop() = session.stepStop()

    fun loadSession(saved: SavedSession) = session.load(saved)

    fun deleteSession(saved: SavedSession) {
        container.sessionStore.delete(saved.id)
        sessions = container.sessionStore.loadAll()
    }

    private fun toChatItem(entry: SessionEntry): ChatItem = when (entry) {
        is SessionEntry.User -> ChatItem.User(entry.text)
        is SessionEntry.Assistant -> ChatItem.Assistant(entry.text, entry.streaming)
        is SessionEntry.Tool -> ChatItem.ToolUse(entry.name, entry.running, entry.isError)
        is SessionEntry.Error -> ChatItem.Error(entry.text)
        is SessionEntry.LimitReached -> ChatItem.LimitReached(entry.max)
    }
}
