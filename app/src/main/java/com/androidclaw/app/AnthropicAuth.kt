package com.androidclaw.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import com.androidclaw.common.ClawJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Anthropic Claude OAuth PKCE flow.
 *
 * Because the redirect URI points to console.anthropic.com (Anthropic's own page),
 * the browser shows the user an auth code that they copy and paste back here.
 * This is the same "headless / paste code" path used by Claude Code in CI.
 *
 * Flow: launchSignIn → user pastes code → exchangeCode → Bearer token stored.
 * Token is used as  Authorization: Bearer  instead of  x-api-key.
 */
object AnthropicAuth {

    private const val CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
    private const val AUTH_URL = "https://claude.ai/oauth/authorize"
    private const val TOKEN_URL = "https://console.anthropic.com/v1/oauth/token"
    private const val REDIRECT_URI = "https://console.anthropic.com/oauth/code/callback"
    private const val SCOPE = "org:create_api_key user:profile user:inference"

    const val OAUTH_BETA = "oauth-2025-04-20"

    fun launchSignIn(context: Context, settings: SettingsStore) {
        val verifier = randomVerifier()
        settings.anthropicPkceVerifier = verifier
        val uri = Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", SCOPE)
            .appendQueryParameter("code_challenge", challengeFor(verifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    suspend fun exchangeCode(
        http: OkHttpClient,
        settings: SettingsStore,
        code: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val verifier = settings.anthropicPkceVerifier
            ?: return@withContext Result.failure(Exception("No PKCE verifier — tap sign-in first"))

        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code.trim())
            .add("client_id", CLIENT_ID)
            .add("redirect_uri", REDIRECT_URI)
            .add("code_verifier", verifier)
            .build()

        val request = Request.Builder()
            .url(TOKEN_URL)
            .post(body)
            .build()

        runCatching {
            http.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code}: ${response.body?.string()}" }
                val json = ClawJson.parseToJsonElement(response.body!!.string()).jsonObject
                settings.anthropicOAuthToken = json["access_token"]?.jsonPrimitive?.content
                    ?: error("No access_token in response")
                settings.anthropicOAuthRefreshToken =
                    json["refresh_token"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                val expiresIn = json["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L
                settings.anthropicOAuthExpiry = System.currentTimeMillis() + expiresIn * 1_000L
                settings.anthropicPkceVerifier = null
                settings.anthropicUseOAuth = true
            }
        }
    }

    suspend fun refreshIfNeeded(http: OkHttpClient, settings: SettingsStore) {
        val expiry = settings.anthropicOAuthExpiry
        // Refresh 60 s before expiry; 0 means never set, so skip
        if (expiry == 0L || System.currentTimeMillis() < expiry - 60_000L) return
        val refresh = settings.anthropicOAuthRefreshToken ?: return
        withContext(Dispatchers.IO) {
            val body = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("refresh_token", refresh)
                .add("client_id", CLIENT_ID)
                .build()
            val request = Request.Builder().url(TOKEN_URL).post(body).build()
            runCatching {
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@runCatching
                    val json = ClawJson.parseToJsonElement(response.body!!.string()).jsonObject
                    json["access_token"]?.jsonPrimitive?.content?.let {
                        settings.anthropicOAuthToken = it
                    }
                    json["refresh_token"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let {
                        settings.anthropicOAuthRefreshToken = it
                    }
                    val expiresIn = json["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L
                    settings.anthropicOAuthExpiry = System.currentTimeMillis() + expiresIn * 1_000L
                }
            }
        }
    }

    private fun randomVerifier(): String {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        return base64Url(bytes)
    }

    private fun challengeFor(verifier: String): String =
        base64Url(
            MessageDigest.getInstance("SHA-256")
                .digest(verifier.toByteArray(Charsets.US_ASCII))
        )

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
