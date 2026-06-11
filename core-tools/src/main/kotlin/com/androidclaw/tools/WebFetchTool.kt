package com.androidclaw.tools

import com.androidclaw.llm.ToolSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fetches a URL and returns readable text. HTML is crudely stripped to keep
 * token usage low; output is capped at [maxChars].
 */
class WebFetchTool(
    private val client: OkHttpClient,
    private val maxChars: Int = 8_000,
) : Tool {

    override val schema = ToolSchema(
        name = "web_fetch",
        description = "Fetch a web page by URL and return its readable text content.",
        inputSchema = objectSchema(
            mapOf("url" to "The http(s) URL to fetch"),
        ),
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val url = args["url"]?.jsonPrimitive?.content
            ?: return@withContext ToolResult.error("Missing 'url' argument")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return@withContext ToolResult.error("Only http(s) URLs are supported")
        }
        val request = Request.Builder().url(url).header("Accept", "text/html, text/plain, */*").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return@withContext ToolResult.error("HTTP ${response.code} for $url")
            }
            // Read at most 512 KB off the wire regardless of Content-Length.
            val raw = response.body?.source()?.let { source ->
                source.request(MAX_BODY_BYTES)
                source.buffer.readUtf8(minOf(source.buffer.size, MAX_BODY_BYTES))
            }.orEmpty()
            val text = if (raw.contains('<')) stripHtml(raw) else raw
            ToolResult(text.take(maxChars).ifBlank { "(empty page)" })
        }
    }

    private fun stripHtml(html: String): String = html
        .replace(Regex("(?is)<(script|style|noscript)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</li>|</h[1-6]>"), "\n")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&")
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\n\\s*\\n+"), "\n\n")
        .trim()

    private companion object {
        const val MAX_BODY_BYTES = 512L * 1024
    }
}
