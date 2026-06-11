package com.androidclaw.llm.openai

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
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
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
 * Streaming client for OpenAI-compatible chat-completions APIs with tool
 * calling. Covers OpenRouter, OpenAI itself, and most self-hosted gateways.
 */
class OpenAiCompatProvider(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val model: String,
    private val baseUrl: String = "https://openrouter.ai/api/v1",
    private val extraHeaders: Map<String, String> = emptyMap(),
) : LlmProvider {

    override val supportsTools = true

    private class PendingCall {
        var id: String = ""
        var name: String = ""
        val args = StringBuilder()
    }

    override fun stream(request: LlmRequest): Flow<LlmEvent> = callbackFlow {
        val httpRequest = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .apply { extraHeaders.forEach { (k, v) -> header(k, v) } }
            .post(encodeRequest(request).toString().toRequestBody("application/json".toMediaType()))
            .build()

        val listener = object : EventSourceListener() {
            val calls = sortedMapOf<Int, PendingCall>()
            var stopReason = StopReason.OTHER
            var finished = false

            fun finish() {
                if (finished) return
                finished = true
                calls.values.forEach { pending ->
                    val input = runCatching {
                        ClawJson.parseToJsonElement(pending.args.toString().ifBlank { "{}" }).jsonObject
                    }.getOrElse { JsonObject(emptyMap()) }
                    trySendBlocking(LlmEvent.ToolCallReady(ToolCall(pending.id, pending.name, input)))
                }
                trySendBlocking(LlmEvent.Completed(stopReason))
                close()
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data.trim() == "[DONE]") {
                    finish()
                    return
                }
                val json = runCatching { ClawJson.parseToJsonElement(data).jsonObject }
                    .getOrElse { return }
                val choice = json["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return

                choice["delta"]?.jsonObject?.let { delta ->
                    delta["content"]?.jsonPrimitive?.takeIf { it.isString }?.content?.let {
                        if (it.isNotEmpty()) trySendBlocking(LlmEvent.TextDelta(it))
                    }
                    delta["tool_calls"]?.jsonArray?.forEach { element ->
                        val call = element.jsonObject
                        val index = call["index"]?.jsonPrimitive?.int ?: 0
                        val pending = calls.getOrPut(index) { PendingCall() }
                        call["id"]?.jsonPrimitive?.content?.let { pending.id = it }
                        call["function"]?.jsonObject?.let { fn ->
                            fn["name"]?.jsonPrimitive?.content?.let { pending.name = it }
                            fn["arguments"]?.jsonPrimitive?.content?.let { pending.args.append(it) }
                        }
                    }
                }

                when (choice["finish_reason"]?.jsonPrimitive?.takeIf { it.isString }?.content) {
                    "tool_calls" -> stopReason = StopReason.TOOL_USE
                    "stop" -> stopReason = StopReason.END_TURN
                    "length" -> stopReason = StopReason.MAX_TOKENS
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
                // Some gateways close the stream without a [DONE] sentinel.
                finish()
            }
        }

        val source = EventSources.createFactory(client).newEventSource(httpRequest, listener)
        awaitClose { source.cancel() }
    }

    private fun encodeRequest(request: LlmRequest): JsonObject = buildJsonObject {
        put("model", model)
        put("max_tokens", request.maxTokens)
        put("stream", true)
        put("messages", buildJsonArray {
            add(buildJsonObject {
                put("role", "system")
                put("content", request.system)
            })
            request.messages.forEach { message -> encodeMessage(message).forEach { add(it) } }
        })
        if (request.tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                request.tools.forEach { tool ->
                    add(buildJsonObject {
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", tool.inputSchema)
                        })
                    })
                }
            })
        }
    }

    /** One internal message can expand to several wire messages (tool results). */
    private fun encodeMessage(message: ChatMessage): List<JsonObject> {
        if (message.role == Role.USER) {
            val toolResults = message.content.filterIsInstance<ContentBlock.ToolResult>()
            if (toolResults.isNotEmpty()) {
                return toolResults.map { result ->
                    buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", result.toolUseId)
                        put("content", if (result.isError) "ERROR: ${result.content}" else result.content)
                    }
                }
            }
            return listOf(buildJsonObject {
                put("role", "user")
                put("content", message.content.filterIsInstance<ContentBlock.Text>()
                    .joinToString("\n") { it.text })
            })
        }

        val text = message.content.filterIsInstance<ContentBlock.Text>()
            .joinToString("\n") { it.text }
        val toolUses = message.content.filterIsInstance<ContentBlock.ToolUse>()
        return listOf(buildJsonObject {
            put("role", "assistant")
            if (text.isNotEmpty()) put("content", text)
            if (toolUses.isNotEmpty()) {
                put("tool_calls", buildJsonArray {
                    toolUses.forEach { use ->
                        add(buildJsonObject {
                            put("id", use.id)
                            put("type", "function")
                            put("function", buildJsonObject {
                                put("name", use.name)
                                put("arguments", use.input.toString())
                            })
                        })
                    }
                })
            }
        })
    }
}
