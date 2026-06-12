package com.androidclaw.control

import com.androidclaw.llm.ToolSchema
import com.androidclaw.tools.PermissionTier
import com.androidclaw.tools.Tool
import com.androidclaw.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Drives the foreground app: tap, type, scroll, or navigate (back/home/recents),
 * via the AccessibilityService (SPEC §5.1).
 *
 * CONFIRM tier: the Gateway routes each call through the user's step-through
 * control before it runs, so actions in other apps are approved (or redirected)
 * one at a time (SPEC §6/§12).
 */
class UiActionTool : Tool {

    // Acting inside other apps is gated: the user steps each action through
    // (forward / back / chat / stop). The app supplies the confirmer.
    override val tier = PermissionTier.CONFIRM

    override val schema = ToolSchema(
        name = "ui_action",
        description = "Act on the foreground app. Actions: " +
            "'tap' (taps the element whose text/description matches `target`), " +
            "'type' (enters `text` into a field, optionally the one matching `target`), " +
            "'scroll' (scrolls `direction` up/down), " +
            "'back' / 'home' / 'recents' (system navigation). " +
            "Call read_screen first to see what is available.",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "One of: tap, type, scroll, back, home, recents")
                    put("enum", buildJsonArray {
                        listOf("tap", "type", "scroll", "back", "home", "recents").forEach { add(it) }
                    })
                })
                put("target", strProp("Text or description of the element to tap, or the field to type into"))
                put("text", strProp("The text to enter (for the 'type' action)"))
                put("direction", strProp("Scroll direction: 'up' or 'down' (for the 'scroll' action)"))
            })
            put("required", buildJsonArray { add("action") })
        },
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.Main) {
        val service = ClawAccessibilityService.instance
            ?: return@withContext ToolResult.error(
                "Accessibility service is off. Ask the user to enable AndroidClaw in " +
                    "Settings → Accessibility so I can control the screen.",
            )
        val action = args.str("action")?.lowercase()
            ?: return@withContext ToolResult.error("Missing 'action'")
        val target = args.str("target")
        val text = args.str("text")
        val direction = args.str("direction")

        val result = when (action) {
            "tap" -> target?.let { service.click(it) }
                ?: "tap needs a 'target' (the element text/description)"
            "type" -> text?.let { t ->
                // Retry up to ~2 s — the target field may appear after a tap/animation
                service.typeTextOrNull(target, t)
                    ?: run { delay(700); service.typeTextOrNull(target, t) }
                    ?: run { delay(1300); service.typeText(target, t) }
            } ?: "type needs 'text' to enter"
            "scroll" -> service.scroll(forward = !direction.equals("up", ignoreCase = true))
            "back", "home", "recents" -> service.globalAction(action)
            else -> "Unknown action '$action'"
        }
        ToolResult(result)
    }

    private fun strProp(description: String): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", description)
    }

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
}
