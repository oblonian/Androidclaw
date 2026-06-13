package com.androidclaw.tools

import com.androidclaw.llm.ToolCall
import com.androidclaw.llm.ToolSchema
import kotlinx.coroutines.CancellationException

class ToolRegistry {
    private val tools = LinkedHashMap<String, Tool>()

    fun register(tool: Tool) {
        tools[tool.schema.name] = tool
    }

    fun schemas(): List<ToolSchema> = tools.values
        .filter { it.tier != PermissionTier.BLOCKED }
        .map { it.schema }

    /** Permission tier of a registered tool, or null if unknown. */
    fun tierOf(name: String): PermissionTier? = tools[name]?.tier

    /** Whether a call to [name] counts against the per-turn action budget. */
    fun countsTowardLimit(name: String): Boolean = tools[name]?.countsTowardActionLimit ?: true

    suspend fun execute(call: ToolCall): ToolResult {
        val tool = tools[call.name]
            ?: return ToolResult.error("Unknown tool: ${call.name}")
        if (tool.tier == PermissionTier.BLOCKED) {
            return ToolResult.error("Tool '${call.name}' is disabled")
        }
        return try {
            tool.execute(call.input)
        } catch (e: CancellationException) {
            // Never swallow cancellation — it must propagate so a stopped turn
            // actually aborts the in-flight tool instead of continuing the loop.
            throw e
        } catch (e: Exception) {
            ToolResult.error("Tool '${call.name}' failed: ${e.message}")
        }
    }
}
