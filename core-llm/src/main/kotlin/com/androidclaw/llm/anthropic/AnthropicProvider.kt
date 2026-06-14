package com.androidclaw.llm.anthropic

import com.androidclaw.common.ClawJson
import com.androidclaw.llm.ChatMessage
import com.androidclaw.llm.ContentBlock
import com.androidclaw.llm.LlmEvent
import com.androidclaw.llm.LlmException
import com.androidclaw.llm.LlmProvider
import com.androidclaw.llm.LlmRequest
import com.androidclaw.llm.Role
import com.androidclaw.llm.StopReason
import com.androidclaw.llm.ToolCall
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

/**
 * Streaming client for the Anthropic Messages API with tool use.
 * https://docs.anthropic.com/en/api/messages-streaming
 *
 * Supports two auth modes:
 *  - API key:    x-api-key header (apiKey non-empty)
 *  - OAuth:      Authorization: Bearer header (bearerToken non-empty)
 */
class AnthropicProvider(
    private val client: OkHttpClient,
    private val apiKey: String = "",
    private val bearerToken: String = "",
    private val model: String,
    private val baseUrl: String = "https://api.anthropic.com",
) : LlmProvider {

    override val supportsTools = true

    override fun stream(request: LlmRequest): Flow<LlmEvent> = callbackFlow {
        val body = encodeRequest(request).toString()
            .toRequestBody("application/json".toMediaType())
        val httpRequest = Request.Builder()
            .url("$baseUrl/v1/messages")
            .apply {
                // anthropic-version is required on every request, regardless of auth mode.
                header("anthropic-version", "2023-06-01")
                if (bearerToken.isNotEmpty()) {
                    header("Authorization", "Bearer $bearerToken")
                    header("anthropic-beta", "oauth-2025-04-20,prompt-caching-2024-07-31")
                } else {
                    header("x-api-key", apiKey)
                    header("anthropic-beta", "prompt-caching-2024-07-31")
                }
            }
            .post(body)
            .build()

        val listener = object : EventSourceListener() {
            // Accumulators for in-flight content blocks, keyed by block index.
            val toolNames = HashMap<Int, Pair<String, String>>() // index -> (id, name)
            val toolJson = HashMap<Int, StringBuilder>()
            var stopReason: StopReason = StopReason.OTHER

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                val json = runCatching { ClawJson.parseToJsonElement(data).jsonObject }
                    .getOrElse { return }
                when (type) {
                    "content_block_start" -> {
                        val block = json["content_block"]?.jsonObject ?: return
                        if (block["type"]?.jsonPrimitive?.content == "tool_use") {
                            val index = json["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: return
                            toolNames[index] = Pair(
                                block["id"]?.jsonPrimitive?.content.orEmpty(),
                                block["name"]?.jsonPrimitive?.content.orEmpty(),
                            )
                            toolJson[index] = StringBuilder()
                        }
                    }
                    "content_block_delta" -> {
                        val index = json["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: return
                        val delta = json["delta"]?.jsonObject ?: return
                        when (delta["type"]?.jsonPrimitive?.content) {
                            "text_delta" -> delta["text"]?.jsonPrimitive?.content?.let {
                                trySendBlocking(LlmEvent.TextDelta(it))
                            }
                            "input_json_delta" -> delta["partial_json"]?.jsonPrimitive?.content?.let {
                                toolJson[index]?.append(it)
                            }
                        }
                    }
                    "content_block_stop" -> {
                        val index = json["index"]?.jsonPrimitive?.content?.toIntOrNull() ?: return
                        val (toolId, name) = toolNames.remove(index) ?: return
                        val raw = toolJson.remove(index)?.toString().orEmpty()
                        val input = runCatching {
                            ClawJson.parseToJsonElement(raw.ifBlank { "{}" }).jsonObject
                        }.getOrElse { JsonObject(emptyMap()) }
                        trySendBlocking(LlmEvent.ToolCallReady(ToolCall(toolId, name, input)))
                    }
                    "message_delta" -> {
                        val reason = json["delta"]?.jsonObject
                            ?.get("stop_reason")?.jsonPrimitive?.content
                        stopReason = when (reason) {
                            "end_turn", "stop_sequence" -> StopReason.END_TURN
                            "tool_use" -> StopReason.TOOL_USE
                            "max_tokens" -> StopReason.MAX_TOKENS
                            else -> stopReason
                        }
                    }
                    "message_stop" -> {
                        trySendBlocking(LlmEvent.Completed(stopReason))
                        close()
                    }
                    "error" -> {
                        val message = json["error"]?.jsonObject
                            ?.get("message")?.jsonPrimitive?.content ?: "Unknown API error"
                        close(LlmException(message))
                    }
                }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val detail = response?.let { resp ->
                    val errorBody = runCatching { resp.body?.string() }.getOrNull()
                    "HTTP ${resp.code}: ${errorBody?.take(500) ?: resp.message}"
                } ?: t?.message ?: "Connection failed"
                close(LlmException(detail, t))
            }

            override fun onClosed(eventSource: EventSource) {
                close()
            }
        }

        val source = EventSources.createFactory(client).newEventSource(httpRequest, listener)
        awaitClose { source.cancel() }
    }

    private fun encodeRequest(request: LlmRequest): JsonObject = buildJsonObject {
        put("model", model)
        put("max_tokens", request.maxTokens)
        put("stream", true)
        // System prompt is always sent as an array so cache_control can be attached.
        // The last block is marked ephemeral — the API caches everything up to and
        // including the marked block, so the system prompt + tools are cached together.
        put("system", buildJsonArray {
            if (bearerToken.isNotEmpty()) {
                // OAuth tokens are scoped to the Claude Code client; the API requires the
                // system prompt to begin with its identity block. Ours follows as a second block.
                add(buildJsonObject {
                    put("type", "text")
                    put("text", "You are Claude Code, Anthropic's official CLI for Claude.")
                })
            }
            add(buildJsonObject {
                put("type", "text")
                put("text", request.system)
                put("cache_control", buildJsonObject { put("type", "ephemeral") })
            })
        })
        put("messages", buildJsonArray {
            request.messages.forEach { add(encodeMessage(it)) }
        })
        if (request.tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                request.tools.forEachIndexed { index, tool ->
                    add(buildJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("input_schema", tool.inputSchema)
                        // Mark the last tool so the system+tools block is cached together.
                        if (index == request.tools.lastIndex) {
                            put("cache_control", buildJsonObject { put("type", "ephemeral") })
                        }
                    })
                }
            })
        }
    }

    private fun encodeMessage(message: ChatMessage): JsonObject = buildJsonObject {
        put("role", if (message.role == Role.USER) "user" else "assistant")
        put("content", buildJsonArray {
            message.content.forEach { block ->
                add(when (block) {
                    is ContentBlock.Text -> buildJsonObject {
                        put("type", "text")
                        put("text", block.text)
                    }
                    is ContentBlock.ToolUse -> buildJsonObject {
                        put("type", "tool_use")
                        put("id", block.id)
                        put("name", block.name)
                        put("input", block.input)
                    }
                    is ContentBlock.ToolResult -> buildJsonObject {
                        put("type", "tool_result")
                        put("tool_use_id", block.toolUseId)
                        put("content", block.content)
                        if (block.isError) put("is_error", true)
                    }
                })
            }
        })
    }
}
