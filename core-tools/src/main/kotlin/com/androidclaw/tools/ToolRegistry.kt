package com.androidclaw.tools

import com.androidclaw.llm.ToolCall
import com.androidclaw.llm.ToolSchema

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

    suspend fun execute(call: ToolCall): ToolResult {
        val tool = tools[call.name]
            ?: return ToolResult.error("Unknown tool: ${call.name}")
        if (tool.tier == PermissionTier.BLOCKED) {
            return ToolResult.error("Tool '${call.name}' is disabled")
        }
        return try {
            tool.execute(call.input)
        } catch (e: Exception) {
            ToolResult.error("Tool '${call.name}' failed: ${e.message}")
        }
    }
}
