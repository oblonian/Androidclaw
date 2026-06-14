package com.androidclaw.app

import android.content.Context
import com.androidclaw.overlay.SessionEntry
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

/**
 * Persists up to 25 conversation sessions in the app's private files directory.
 *
 * Two writers share one instance — the in-app chat and the floating overlay —
 * so every read-modify-write is [Synchronized] and writes are atomic
 * (temp file + rename) to avoid lost updates or a torn JSON file.
 */
class SessionStore(context: Context) {

    private val file = File(context.filesDir, "claw_sessions.json")
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized
    fun loadAll(): List<SavedSession> = runCatching {
        json.decodeFromString<List<SavedSession>>(file.readText())
    }.getOrDefault(emptyList())

    /**
     * Persists [entries] as a single session, updating the row with [existingId]
     * in place when supplied (so a multi-turn conversation stays one entry).
     * Returns the session id to reuse on the next turn.
     */
    @Synchronized
    fun save(entries: List<SessionEntry>, existingId: String? = null): String? {
        val messages = entries.mapNotNull { entry ->
            when (entry) {
                is SessionEntry.User -> SavedMessage("user", entry.text)
                is SessionEntry.Assistant -> entry.text.takeIf { it.isNotBlank() }
                    ?.let { SavedMessage("assistant", it) }
                else -> null
            }
        }
        if (messages.isEmpty()) return existingId
        val rawTitle = messages.firstOrNull { it.role == "user" }?.text ?: return existingId
        val title = if (rawTitle.length > 55) "${rawTitle.take(52).trimEnd()}…" else rawTitle
        val session = SavedSession(id = existingId ?: UUID.randomUUID().toString(), title = title, messages = messages)
        upsert(session)
        return session.id
    }

    /** Update an existing session in-place, or prepend it as new if not found. */
    @Synchronized
    fun upsert(session: SavedSession) {
        val existing = loadAll()
        val updated = if (existing.any { it.id == session.id }) {
            existing.map { if (it.id == session.id) session else it }
        } else {
            (listOf(session) + existing).take(25)
        }
        writeAtomically(updated)
    }

    @Synchronized
    fun delete(id: String) {
        writeAtomically(loadAll().filter { it.id != id })
    }

    private fun writeAtomically(sessions: List<SavedSession>) {
        runCatching {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.encodeToString(sessions))
            if (!tmp.renameTo(file)) {
                // renameTo can fail across some filesystems — fall back to a direct write.
                file.writeText(json.encodeToString(sessions))
                tmp.delete()
            }
        }
    }
}
