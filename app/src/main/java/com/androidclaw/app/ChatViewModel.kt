package com.androidclaw.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.androidclaw.gateway.AgentEvent
import com.androidclaw.llm.ChatMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** One visual entry in the chat list. */
sealed interface ChatItem {
    data class User(val text: String) : ChatItem
    data class Assistant(val text: String, val streaming: Boolean) : ChatItem
    data class ToolUse(val name: String, val running: Boolean, val isError: Boolean) : ChatItem
    data class Error(val text: String) : ChatItem
}

class ChatViewModel(private val container: AppContainer) : ViewModel() {

    var items by mutableStateOf<List<ChatItem>>(emptyList())
        private set
    var busy by mutableStateOf(false)
        private set
    var needsAuth by mutableStateOf(!container.settings.isConfigured)
        private set

    /** Wire-format history owned here; the gateway is stateless (SPEC §4). */
    private var conversation: List<ChatMessage> = emptyList()
    private var turnJob: Job? = null

    fun refreshAuthState() {
        needsAuth = !container.settings.isConfigured
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || busy) return
        val gateway = container.gateway() ?: run {
            needsAuth = true
            return
        }

        items = items + ChatItem.User(trimmed)
        busy = true
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
                    is AgentEvent.TurnComplete -> {
                        conversation = event.messages
                        finishStreamingBubble()
                        busy = false
                    }
                    is AgentEvent.TurnFailed -> {
                        finishStreamingBubble()
                        items = items + ChatItem.Error(event.message)
                        busy = false
                    }
                }
            }
        }
    }

    fun cancelTurn() {
        turnJob?.cancel()
        turnJob = null
        finishStreamingBubble()
        busy = false
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
