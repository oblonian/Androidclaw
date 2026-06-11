package com.androidclaw.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.androidclaw.common.ClawJson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * OpenRouter's OAuth PKCE flow for third-party apps:
 * https://openrouter.ai/docs/use-cases/oauth-pkce
 *
 * The browser round-trip returns a one-time code to androidclaw://oauth,
 * which we exchange (with the stashed PKCE verifier) for a scoped API key.
 */
object OpenRouterAuth {

    const val SCHEME = "androidclaw"
    const val HOST = "oauth"
    private const val CALLBACK_URL = "$SCHEME://$HOST"

    fun launchSignIn(context: Context, settings: SettingsStore) {
        val verifier = randomVerifier()
        settings.pendingVerifier = verifier
        val uri = Uri.parse("https://openrouter.ai/auth").buildUpon()
            .appendQueryParameter("callback_url", CALLBACK_URL)
            .appendQueryParameter("code_challenge", challengeFor(verifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }

    fun isCallback(uri: Uri?): Boolean =
        uri != null && uri.scheme == SCHEME && uri.host == HOST

    /** Exchanges the callback code for an API key and stores it. */
    suspend fun handleCallback(
        http: OkHttpClient,
        settings: SettingsStore,
        uri: Uri,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val code = uri.getQueryParameter("code")
            ?: return@withContext Result.failure(Exception("Callback has no code"))
        val verifier = settings.pendingVerifier
            ?: return@withContext Result.failure(Exception("No pending sign-in"))

        val body = buildJsonObject {
            put("code", code)
            put("code_verifier", verifier)
            put("code_challenge_method", "S256")
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/auth/keys")
            .post(body)
            .build()

        runCatching {
            http.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code}" }
                val json = ClawJson.parseToJsonElement(response.body!!.string()).jsonObject
                json["key"]?.jsonPrimitive?.content ?: error("No key in response")
            }
        }.map { key ->
            settings.openRouterKey = key
            settings.backend = LlmBackend.OPENROUTER
            settings.pendingVerifier = null
        }
    }

    private fun randomVerifier(): String {
        val bytes = ByteArray(64)
        SecureRandom().nextBytes(bytes)
        return base64Url(bytes)
    }

    private fun challengeFor(verifier: String): String =
        base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    private fun base64Url(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
