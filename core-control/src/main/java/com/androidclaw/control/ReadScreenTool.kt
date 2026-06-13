package com.androidclaw.control

import com.androidclaw.llm.ToolSchema
import com.androidclaw.tools.PermissionTier
import com.androidclaw.tools.Tool
import com.androidclaw.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Returns a compact text view of the foreground app's UI (SPEC §5.3).
 * Read-only, so AUTO tier.
 */
class ReadScreenTool : Tool {

    override val tier = PermissionTier.AUTO

    // Reading the screen is free perception — it must not consume the action
    // budget, or a tap-then-read loop would burn through it twice as fast.
    override val countsTowardActionLimit = false

    override val schema = ToolSchema(
        name = "read_screen",
        description = "Read the current screen of the foreground app as a compact list of UI " +
            "elements (text, icons, and which are tappable/editable/scrollable). Use this to " +
            "see what is on screen before deciding what to tap or type.",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {})
        },
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.Main) {
        val service = ClawAccessibilityService.instance
            ?: return@withContext ToolResult.error(
                "Accessibility service is off. Ask the user to enable AndroidClaw in " +
                    "Settings → Accessibility so I can read and control the screen.",
            )
        ToolResult(service.dumpScreen())
    }
}
