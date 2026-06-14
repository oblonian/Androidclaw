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
        // EncryptedSharedPreferences.create() can throw transiently — most often a
        // brief keystore/keyset race right after an app update. Retry a few times
        // before doing anything destructive, so a recoverable hiccup never costs the
        // user their stored credentials.
        var opened: SharedPreferences? = null
        repeat(3) {
            if (opened == null) {
                runCatching { createPrefs(context) }
                    .onSuccess { opened = it }
                    .onFailure { Thread.sleep(50) }
            }
        }
        prefs = opened ?: run {
            // Persistently unreadable (e.g. the keystore key was invalidated by a
            // device restore). Dropping the unreadable prefs is the only way to
            // un-brick the app; the user re-enters credentials once. This is a last
            // resort — it does NOT run for the transient failures handled above.
            runCatching {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
                context.deleteSharedPreferences(PREFS_NAME)
            }
            createPrefs(context)
        }
    }

    private fun createPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
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

    /** PKCE verifier stashed across the OpenRouter OAuth browser round-trip. */
    var pendingVerifier: String?
        get() = prefs.getString(KEY_PENDING_VERIFIER, null)
        set(value) = prefs.edit { putString(KEY_PENDING_VERIFIER, value) }

    // ── Anthropic OAuth fields ────────────────────────────────────────────────

    /** Whether to use OAuth Bearer token instead of a manual API key. */
    var anthropicUseOAuth: Boolean
        get() = prefs.getString(KEY_ANTHROPIC_USE_OAUTH, "false").toBoolean()
        set(value) = prefs.edit { putString(KEY_ANTHROPIC_USE_OAUTH, value.toString()) }

    var anthropicOAuthToken: String?
        get() = prefs.getString(KEY_ANTHROPIC_OAUTH_TOKEN, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit { putString(KEY_ANTHROPIC_OAUTH_TOKEN, value?.trim()) }

    var anthropicOAuthRefreshToken: String?
        get() = prefs.getString(KEY_ANTHROPIC_OAUTH_REFRESH, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit { putString(KEY_ANTHROPIC_OAUTH_REFRESH, value?.trim()) }

    /** Epoch-millis expiry of the access token; 0 if not set. */
    var anthropicOAuthExpiry: Long
        get() = prefs.getString(KEY_ANTHROPIC_OAUTH_EXPIRY, "0")?.toLongOrNull() ?: 0L
        set(value) = prefs.edit { putString(KEY_ANTHROPIC_OAUTH_EXPIRY, value.toString()) }

    /** PKCE verifier stashed across the Anthropic OAuth browser round-trip. */
    var anthropicPkceVerifier: String?
        get() = prefs.getString(KEY_ANTHROPIC_PKCE_VERIFIER, null)
        set(value) = prefs.edit { putString(KEY_ANTHROPIC_PKCE_VERIFIER, value) }

    /** OAuth state stashed across the Anthropic OAuth browser round-trip. */
    var anthropicPkceState: String?
        get() = prefs.getString(KEY_ANTHROPIC_PKCE_STATE, null)
        set(value) = prefs.edit { putString(KEY_ANTHROPIC_PKCE_STATE, value) }

    /** Step-through mode: confirm each device action one at a time. On by default. */
    var stepThrough: Boolean
        get() = prefs.getString(KEY_STEP_THROUGH, "true").toBoolean()
        set(value) = prefs.edit { putString(KEY_STEP_THROUGH, value.toString()) }

    /** Max tool iterations per turn before the agent stops and offers "Continue". */
    var maxIterations: Int
        get() = prefs.getString(KEY_MAX_ITERATIONS, "8")?.toIntOrNull()?.coerceIn(4, 20) ?: 8
        set(value) = prefs.edit { putString(KEY_MAX_ITERATIONS, value.coerceIn(4, 20).toString()) }

    /** Overlay puck position — persisted so it survives service restarts. */
    var overlayPuckX: Int
        get() = prefs.getString(KEY_OVERLAY_PUCK_X, "-1")?.toIntOrNull() ?: -1
        set(value) = prefs.edit { putString(KEY_OVERLAY_PUCK_X, value.toString()) }
    var overlayPuckY: Int
        get() = prefs.getString(KEY_OVERLAY_PUCK_Y, "-1")?.toIntOrNull() ?: -1
        set(value) = prefs.edit { putString(KEY_OVERLAY_PUCK_Y, value.toString()) }

    // ── Overlay appearance (device prefs — not cleared on sign-out) ────────────

    /** "LIGHT" | "DARK" | "AUTO". */
    var overlayThemeMode: String
        get() = prefs.getString(KEY_OVERLAY_THEME, "LIGHT") ?: "LIGHT"
        set(value) = prefs.edit { putString(KEY_OVERLAY_THEME, value) }

    var overlayAccent: Int
        get() = prefs.getString(KEY_OVERLAY_ACCENT, null)?.toIntOrNull() ?: DEFAULT_OVERLAY_ACCENT
        set(value) = prefs.edit { putString(KEY_OVERLAY_ACCENT, value.toString()) }

    var overlayPanelOpacity: Float
        get() = prefs.getString(KEY_OVERLAY_PANEL_OPACITY, null)?.toFloatOrNull()?.coerceIn(0.6f, 1f) ?: 0.96f
        set(value) = prefs.edit { putString(KEY_OVERLAY_PANEL_OPACITY, value.coerceIn(0.6f, 1f).toString()) }

    var overlayPuckOpacity: Float
        get() = prefs.getString(KEY_OVERLAY_PUCK_OPACITY, null)?.toFloatOrNull()?.coerceIn(0.3f, 1f) ?: 0.55f
        set(value) = prefs.edit { putString(KEY_OVERLAY_PUCK_OPACITY, value.coerceIn(0.3f, 1f).toString()) }

    var overlayPuckGlyph: String
        get() = prefs.getString(KEY_OVERLAY_PUCK_GLYPH, "🦞")?.takeIf { it.isNotBlank() } ?: "🦞"
        set(value) = prefs.edit { putString(KEY_OVERLAY_PUCK_GLYPH, value.take(2).ifBlank { "🦞" }) }

    /** Expanded-card size in px (-1 = default). */
    var overlayCardWidth: Int
        get() = prefs.getString(KEY_OVERLAY_CARD_W, "-1")?.toIntOrNull() ?: -1
        set(value) = prefs.edit { putString(KEY_OVERLAY_CARD_W, value.toString()) }
    var overlayTranscriptHeight: Int
        get() = prefs.getString(KEY_OVERLAY_TRANSCRIPT_H, "-1")?.toIntOrNull() ?: -1
        set(value) = prefs.edit { putString(KEY_OVERLAY_TRANSCRIPT_H, value.toString()) }

    /** Clears all stored credentials. The backend preference is kept so the sign-in screen
     *  pre-selects the last-used provider. */
    fun signOut() {
        prefs.edit {
            remove(KEY_ANTHROPIC_KEY)
            remove(KEY_ANTHROPIC_OAUTH_TOKEN)
            remove(KEY_ANTHROPIC_OAUTH_REFRESH)
            remove(KEY_ANTHROPIC_OAUTH_EXPIRY)
            remove(KEY_ANTHROPIC_USE_OAUTH)
            remove(KEY_OPENROUTER_KEY)
        }
    }

    val isConfigured: Boolean
        get() = when (backend) {
            LlmBackend.ANTHROPIC -> anthropicKey != null || anthropicOAuthToken != null
            LlmBackend.OPENROUTER -> openRouterKey != null
        }

    companion object {
        private const val PREFS_NAME = "androidclaw_settings"
        private const val KEY_BACKEND = "backend"
        private const val KEY_ANTHROPIC_KEY = "anthropic_api_key"
        private const val KEY_ANTHROPIC_MODEL = "model"
        private const val KEY_OPENROUTER_KEY = "openrouter_key"
        private const val KEY_OPENROUTER_MODEL = "openrouter_model"
        private const val KEY_PENDING_VERIFIER = "pending_verifier"
        private const val KEY_ANTHROPIC_USE_OAUTH = "anthropic_use_oauth"
        private const val KEY_ANTHROPIC_OAUTH_TOKEN = "anthropic_oauth_token"
        private const val KEY_ANTHROPIC_OAUTH_REFRESH = "anthropic_oauth_refresh"
        private const val KEY_ANTHROPIC_OAUTH_EXPIRY = "anthropic_oauth_expiry"
        private const val KEY_ANTHROPIC_PKCE_VERIFIER = "anthropic_pkce_verifier"
        private const val KEY_ANTHROPIC_PKCE_STATE = "anthropic_pkce_state"
        private const val KEY_STEP_THROUGH = "step_through"
        private const val KEY_MAX_ITERATIONS = "max_iterations"
        private const val KEY_OVERLAY_PUCK_X = "overlay_puck_x"
        private const val KEY_OVERLAY_PUCK_Y = "overlay_puck_y"
        private const val KEY_OVERLAY_THEME = "overlay_theme"
        private const val KEY_OVERLAY_ACCENT = "overlay_accent"
        private const val KEY_OVERLAY_PANEL_OPACITY = "overlay_panel_opacity"
        private const val KEY_OVERLAY_PUCK_OPACITY = "overlay_puck_opacity"
        private const val KEY_OVERLAY_PUCK_GLYPH = "overlay_puck_glyph"
        private const val KEY_OVERLAY_CARD_W = "overlay_card_w"
        private const val KEY_OVERLAY_TRANSCRIPT_H = "overlay_transcript_h"
        const val DEFAULT_OVERLAY_ACCENT: Int = 0xFFD2512A.toInt()
        const val DEFAULT_ANTHROPIC_MODEL = "claude-sonnet-4-6"
        const val DEFAULT_OPENROUTER_MODEL = "anthropic/claude-sonnet-4.6"
    }
}
