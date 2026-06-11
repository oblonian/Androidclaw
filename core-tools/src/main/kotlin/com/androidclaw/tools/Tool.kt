package com.androidclaw.tools

import com.androidclaw.llm.ToolSchema
import kotlinx.serialization.json.JsonObject

/**
 * Permission tier per SPEC §6. P0 ships AUTO tools only; CONFIRM gating
 * (approval cards) lands in P1, BLOCKED tools stay disabled until enabled
 * in settings.
 */
enum class PermissionTier { AUTO, CONFIRM, BLOCKED }

data class ToolResult(val content: String, val isError: Boolean = false) {
    companion object {
        fun error(message: String) = ToolResult(message, isError = true)
    }
}

interface Tool {
    val schema: ToolSchema
    val tier: PermissionTier get() = PermissionTier.AUTO

    suspend fun execute(args: JsonObject): ToolResult
}
