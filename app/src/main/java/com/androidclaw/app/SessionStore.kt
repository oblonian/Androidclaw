package com.androidclaw.app

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class SavedMessage(val role: String, val text: String)

@Serializable
data class SavedSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val timestamp: Long = System.currentTimeMillis(),
    val messages: List<SavedMessage>,
)

/** Persists up to 25 conversation sessions in the app's private files directory. */
class SessionStore(context: Context) {

    private val file = File(context.filesDir, "claw_sessions.json")
    private val json = Json { ignoreUnknownKeys = true }

    fun loadAll(): List<SavedSession> = runCatching {
        json.decodeFromString<List<SavedSession>>(file.readText())
    }.getOrDefault(emptyList())

    fun save(items: List<ChatItem>) {
        val messages = items.mapNotNull { item ->
            when (item) {
                is ChatItem.User -> SavedMessage("user", item.text)
                is ChatItem.Assistant -> item.text.takeIf { it.isNotBlank() }
                    ?.let { SavedMessage("assistant", it) }
                else -> null
            }
        }
        if (messages.isEmpty()) return
        val rawTitle = messages.firstOrNull { it.role == "user" }?.text ?: return
        val title = if (rawTitle.length > 55) "${rawTitle.take(52).trimEnd()}…" else rawTitle
        val session = SavedSession(title = title, messages = messages)
        val updated = (listOf(session) + loadAll()).take(25)
        runCatching { file.writeText(json.encodeToString(updated)) }
    }

    /** Update an existing session in-place, or prepend it as new if not found. */
    fun upsert(session: SavedSession) {
        val existing = loadAll()
        val updated = if (existing.any { it.id == session.id }) {
            existing.map { if (it.id == session.id) session else it }
        } else {
            (listOf(session) + existing).take(25)
        }
        runCatching { file.writeText(json.encodeToString(updated)) }
    }

    fun delete(id: String) {
        val updated = loadAll().filter { it.id != id }
        runCatching { file.writeText(json.encodeToString(updated)) }
    }
}
