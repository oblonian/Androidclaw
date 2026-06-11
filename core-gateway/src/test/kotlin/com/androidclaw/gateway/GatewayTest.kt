package com.androidclaw.gateway

import com.androidclaw.llm.ChatMessage
import com.androidclaw.llm.ContentBlock
import com.androidclaw.llm.LlmEvent
import com.androidclaw.llm.LlmProvider
import com.androidclaw.llm.LlmRequest
import com.androidclaw.llm.StopReason
import com.androidclaw.llm.ToolCall
import com.androidclaw.llm.ToolSchema
import com.androidclaw.tools.Tool
import com.androidclaw.tools.ToolRegistry
import com.androidclaw.tools.ToolResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Provider that replays a scripted sequence of responses, one per stream() call. */
private class FakeProvider(private val turns: List<List<LlmEvent>>) : LlmProvider {
    override val supportsTools = true
    var calls = 0
        private set

    override fun stream(request: LlmRequest): Flow<LlmEvent> =
        flowOf(*turns[calls++].toTypedArray())
}

private class EchoTool : Tool {
    override val schema = ToolSchema("echo", "Echoes input", buildJsonObject { put("type", "object") })
    override suspend fun execute(args: JsonObject) = ToolResult("echoed")
}

class GatewayTest {

    private val history = listOf(ChatMessage.user("hi"))

    @Test
    fun `plain text turn completes without tools`() = runTest {
        val provider = FakeProvider(listOf(listOf(
            LlmEvent.TextDelta("hello"),
            LlmEvent.Completed(StopReason.END_TURN),
        )))
        val gateway = Gateway(provider, ToolRegistry(), "sys")

        val events = gateway.runTurn(history).toList()

        assertEquals(listOf("hello"), events.filterIsInstance<AgentEvent.TextDelta>().map { it.text })
        val complete = events.last() as AgentEvent.TurnComplete
        assertEquals(2, complete.messages.size)
        assertEquals(1, provider.calls)
    }

    @Test
    fun `tool call loops back into the model`() = runTest {
        val call = ToolCall("t1", "echo", JsonObject(emptyMap()))
        val provider = FakeProvider(listOf(
            listOf(LlmEvent.ToolCallReady(call), LlmEvent.Completed(StopReason.TOOL_USE)),
            listOf(LlmEvent.TextDelta("done"), LlmEvent.Completed(StopReason.END_TURN)),
        ))
        val registry = ToolRegistry().apply { register(EchoTool()) }
        val gateway = Gateway(provider, registry, "sys")

        val events = gateway.runTurn(history).toList()

        assertEquals(2, provider.calls)
        val finished = events.filterIsInstance<AgentEvent.ToolFinished>().single()
        assertEquals("echoed", finished.result.content)
        val complete = events.last() as AgentEvent.TurnComplete
        // user, assistant(tool_use), user(tool_result), assistant(text)
        assertEquals(4, complete.messages.size)
        assertTrue(complete.messages[2].content.single() is ContentBlock.ToolResult)
    }

    @Test
    fun `unknown tool returns error result and loop continues`() = runTest {
        val call = ToolCall("t1", "nope", JsonObject(emptyMap()))
        val provider = FakeProvider(listOf(
            listOf(LlmEvent.ToolCallReady(call), LlmEvent.Completed(StopReason.TOOL_USE)),
            listOf(LlmEvent.TextDelta("recovered"), LlmEvent.Completed(StopReason.END_TURN)),
        ))
        val gateway = Gateway(provider, ToolRegistry(), "sys")

        val events = gateway.runTurn(history).toList()

        val finished = events.filterIsInstance<AgentEvent.ToolFinished>().single()
        assertTrue(finished.result.isError)
        assertTrue(events.last() is AgentEvent.TurnComplete)
    }

    @Test
    fun `loop is bounded by maxIterations`() = runTest {
        val call = ToolCall("t1", "echo", JsonObject(emptyMap()))
        val endless = List(3) {
            listOf<LlmEvent>(LlmEvent.ToolCallReady(call), LlmEvent.Completed(StopReason.TOOL_USE))
        }
        val provider = FakeProvider(endless)
        val registry = ToolRegistry().apply { register(EchoTool()) }
        val gateway = Gateway(provider, registry, "sys", maxIterations = 3)

        val events = gateway.runTurn(history).toList()

        assertEquals(3, provider.calls)
        assertTrue(events.last() is AgentEvent.TurnFailed)
    }
}
