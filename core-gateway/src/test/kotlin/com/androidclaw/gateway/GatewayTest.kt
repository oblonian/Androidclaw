package com.androidclaw.gateway

import com.androidclaw.llm.ChatMessage
import com.androidclaw.llm.ContentBlock
import com.androidclaw.llm.LlmEvent
import com.androidclaw.llm.LlmProvider
import com.androidclaw.llm.LlmRequest
import com.androidclaw.llm.StopReason
import com.androidclaw.llm.ToolCall
import com.androidclaw.llm.ToolSchema
import com.androidclaw.tools.PermissionTier
import com.androidclaw.tools.SCREEN_MARKER
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

    /** The messages of each request, captured in order, for prompt assertions. */
    val requests = mutableListOf<List<ChatMessage>>()

    override fun stream(request: LlmRequest): Flow<LlmEvent> {
        requests += request.messages
        return flowOf(*turns[calls++].toTypedArray())
    }
}

private class EchoTool : Tool {
    override val schema = ToolSchema("echo", "Echoes input", buildJsonObject { put("type", "object") })
    override suspend fun execute(args: JsonObject) = ToolResult("echoed")
}

/** A read-only tool that is exempt from the action budget (like read_screen). */
private class PeekTool : Tool {
    override val countsTowardActionLimit = false
    override val schema = ToolSchema("peek", "Reads state", buildJsonObject { put("type", "object") })
    override suspend fun execute(args: JsonObject) = ToolResult("peeked")
}

/** Mimics ui_action / read_screen: returns an outcome plus a tagged screen dump. */
private class ScreenTool : Tool {
    private var n = 0
    override val countsTowardActionLimit = false
    override val schema = ToolSchema("screen", "Acts and returns the screen", buildJsonObject { put("type", "object") })
    override suspend fun execute(args: JsonObject): ToolResult {
        n++
        return ToolResult("acted$n" + SCREEN_MARKER + "App: screen-body-$n")
    }
}

/** A CONFIRM-tier tool that records whether it actually ran. */
private class GuardedTool : Tool {
    var ran = false
        private set
    override val tier = PermissionTier.CONFIRM
    override val schema = ToolSchema("guarded", "Needs approval", buildJsonObject { put("type", "object") })
    override suspend fun execute(args: JsonObject): ToolResult {
        ran = true
        return ToolResult("did it")
    }
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

    private fun guardedProvider() = FakeProvider(listOf(
        listOf(
            LlmEvent.ToolCallReady(ToolCall("g1", "guarded", JsonObject(emptyMap()))),
            LlmEvent.Completed(StopReason.TOOL_USE),
        ),
        listOf(LlmEvent.TextDelta("ok"), LlmEvent.Completed(StopReason.END_TURN)),
    ))

    @Test
    fun `confirm Proceed runs the guarded tool`() = runTest {
        val tool = GuardedTool()
        val registry = ToolRegistry().apply { register(tool) }
        val gateway = Gateway(guardedProvider(), registry, "sys") { ConfirmDecision.Proceed }

        val events = gateway.runTurn(history).toList()

        assertTrue(tool.ran)
        assertEquals("did it", events.filterIsInstance<AgentEvent.ToolFinished>().single().result.content)
    }

    @Test
    fun `confirm Back skips the tool and feeds the model a note`() = runTest {
        val tool = GuardedTool()
        val registry = ToolRegistry().apply { register(tool) }
        val gateway = Gateway(guardedProvider(), registry, "sys") { ConfirmDecision.Back }

        val events = gateway.runTurn(history).toList()

        assertTrue(!tool.ran)
        // No device action ran, so no ToolFinished event was emitted.
        assertTrue(events.none { it is AgentEvent.ToolFinished })
        val complete = events.last() as AgentEvent.TurnComplete
        val result = complete.messages[2].content.single() as ContentBlock.ToolResult
        assertTrue(result.content.contains("stepped back"))
    }

    @Test
    fun `confirm Chat passes the user note as the tool result`() = runTest {
        val tool = GuardedTool()
        val registry = ToolRegistry().apply { register(tool) }
        val gateway = Gateway(guardedProvider(), registry, "sys") { ConfirmDecision.Chat("use the other button") }

        val events = gateway.runTurn(history).toList()

        assertTrue(!tool.ran)
        val complete = events.last() as AgentEvent.TurnComplete
        val result = complete.messages[2].content.single() as ContentBlock.ToolResult
        assertTrue(result.content.contains("use the other button"))
    }

    @Test
    fun `confirm Cancel ends the turn with every tool_use answered`() = runTest {
        val tool = GuardedTool()
        val registry = ToolRegistry().apply { register(tool) }
        val provider = guardedProvider()
        val gateway = Gateway(provider, registry, "sys") { ConfirmDecision.Cancel }

        val events = gateway.runTurn(history).toList()

        assertTrue(!tool.ran)
        val complete = events.last() as AgentEvent.TurnComplete
        // user, assistant(tool_use), user(tool_result=cancelled) — model not called again.
        assertEquals(3, complete.messages.size)
        assertEquals(1, provider.calls) // model was NOT re-invoked after cancel
        val result = complete.messages[2].content.single() as ContentBlock.ToolResult
        assertTrue(result.content.contains("Cancelled"))
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
        val limitEvent = events.last() as AgentEvent.TurnLimitReached
        assertEquals(3, limitEvent.max)
    }

    private fun toolResults(messages: List<ChatMessage>): List<ContentBlock.ToolResult> =
        messages.flatMap { it.content }.filterIsInstance<ContentBlock.ToolResult>()

    @Test
    fun `stale screens are pruned from the prompt but kept in history`() = runTest {
        val call = ToolCall("s1", "screen", JsonObject(emptyMap()))
        // Two screen-returning tool calls, then a final answer.
        val provider = FakeProvider(listOf(
            listOf(LlmEvent.ToolCallReady(call), LlmEvent.Completed(StopReason.TOOL_USE)),
            listOf(LlmEvent.ToolCallReady(call), LlmEvent.Completed(StopReason.TOOL_USE)),
            listOf(LlmEvent.TextDelta("done"), LlmEvent.Completed(StopReason.END_TURN)),
        ))
        val registry = ToolRegistry().apply { register(ScreenTool()) }
        val gateway = Gateway(provider, registry, "sys")

        val events = gateway.runTurn(history).toList()

        // On the final request, the first screen is collapsed, the second is intact.
        val lastRequest = provider.requests.last()
        val results = toolResults(lastRequest)
        assertEquals(2, results.size)
        assertTrue(results[0].content.contains("[earlier screen omitted]"))
        assertTrue(!results[0].content.contains("screen-body-1"))
        // The action outcome before the screen is preserved.
        assertTrue(results[0].content.startsWith("acted1"))
        // The most recent screen is kept verbatim.
        assertTrue(results[1].content.contains("screen-body-2"))

        // The persisted history (TurnComplete) keeps BOTH full screens.
        val complete = events.last() as AgentEvent.TurnComplete
        val kept = toolResults(complete.messages)
        assertTrue(kept[0].content.contains("screen-body-1"))
        assertTrue(kept[1].content.contains("screen-body-2"))
    }

    @Test
    fun `a single screen is left untouched`() = runTest {
        val call = ToolCall("s1", "screen", JsonObject(emptyMap()))
        val provider = FakeProvider(listOf(
            listOf(LlmEvent.ToolCallReady(call), LlmEvent.Completed(StopReason.TOOL_USE)),
            listOf(LlmEvent.TextDelta("done"), LlmEvent.Completed(StopReason.END_TURN)),
        ))
        val registry = ToolRegistry().apply { register(ScreenTool()) }
        val gateway = Gateway(provider, registry, "sys")

        gateway.runTurn(history).toList()

        val results = toolResults(provider.requests.last())
        assertEquals(1, results.size)
        assertTrue(results[0].content.contains("screen-body-1"))
        assertTrue(!results[0].content.contains("omitted"))
    }

    @Test
    fun `read-only tools do not consume the action budget`() = runTest {
        val peek = ToolCall("p1", "peek", JsonObject(emptyMap()))
        // Five perception calls then a final text answer — more than maxIterations=3,
        // yet the turn must complete normally because peek is exempt.
        val provider = FakeProvider(
            List(5) { listOf<LlmEvent>(LlmEvent.ToolCallReady(peek), LlmEvent.Completed(StopReason.TOOL_USE)) } +
                listOf(listOf(LlmEvent.TextDelta("answer"), LlmEvent.Completed(StopReason.END_TURN))),
        )
        val registry = ToolRegistry().apply { register(PeekTool()) }
        val gateway = Gateway(provider, registry, "sys", maxIterations = 3)

        val events = gateway.runTurn(history).toList()

        assertEquals(6, provider.calls)
        assertTrue(events.last() is AgentEvent.TurnComplete)
        assertTrue(events.none { it is AgentEvent.TurnLimitReached })
        // Exempt tools never emit a budget update.
        assertTrue(events.none { it is AgentEvent.IterationUpdate })
    }
}
