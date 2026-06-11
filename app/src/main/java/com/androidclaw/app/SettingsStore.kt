package com.androidclaw.app

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

enum class LlmBackend { ANTHROPIC, OPENROUTER }

/**
 * Keystore-backed settings per SPEC §11 — keys never touch disk in
 * plaintext.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        prefs = EncryptedSharedPreferences.create(
            context,
            "androidclaw_settings",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var backend: LlmBackend
        get() = runCatching { LlmBackend.valueOf(prefs.getString(KEY_BACKEND, null) ?: "") }
            .getOrDefault(LlmBackend.ANTHROPIC)
        set(value) = prefs.edit { putString(KEY_BACKEND, value.name) }

    var anthropicKey: String?
        get() = prefs.getString(KEY_ANTHROPIC_KEY, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit { putString(KEY_ANTHROPIC_KEY, value?.trim()) }

    var anthropicModel: String
        get() = prefs.getString(KEY_ANTHROPIC_MODEL, DEFAULT_ANTHROPIC_MODEL) ?: DEFAULT_ANTHROPIC_MODEL
        set(value) = prefs.edit {
            putString(KEY_ANTHROPIC_MODEL, value.trim().ifBlank { DEFAULT_ANTHROPIC_MODEL })
        }

    var openRouterKey: String?
        get() = prefs.getString(KEY_OPENROUTER_KEY, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit { putString(KEY_OPENROUTER_KEY, value?.trim()) }

    var openRouterModel: String
        get() = prefs.getString(KEY_OPENROUTER_MODEL, DEFAULT_OPENROUTER_MODEL) ?: DEFAULT_OPENROUTER_MODEL
        set(value) = prefs.edit {
            putString(KEY_OPENROUTER_MODEL, value.trim().ifBlank { DEFAULT_OPENROUTER_MODEL })
        }

    /** PKCE verifier stashed across the OAuth browser round-trip. */
    var pendingVerifier: String?
        get() = prefs.getString(KEY_PENDING_VERIFIER, null)
        set(value) = prefs.edit { putString(KEY_PENDING_VERIFIER, value) }

    val isConfigured: Boolean
        get() = when (backend) {
            LlmBackend.ANTHROPIC -> anthropicKey != null
            LlmBackend.OPENROUTER -> openRouterKey != null
        }

    companion object {
        private const val KEY_BACKEND = "backend"
        private const val KEY_ANTHROPIC_KEY = "anthropic_api_key"
        private const val KEY_ANTHROPIC_MODEL = "model"
        private const val KEY_OPENROUTER_KEY = "openrouter_key"
        private const val KEY_OPENROUTER_MODEL = "openrouter_model"
        private const val KEY_PENDING_VERIFIER = "pending_verifier"
        const val DEFAULT_ANTHROPIC_MODEL = "claude-sonnet-4-6"
        const val DEFAULT_OPENROUTER_MODEL = "anthropic/claude-sonnet-4.6"
    }
}
