package com.androidclaw.control

import android.content.Context
import android.content.Intent
import com.androidclaw.llm.ToolSchema
import com.androidclaw.tools.SCREEN_MARKER
import com.androidclaw.tools.Tool
import com.androidclaw.tools.ToolResult
import com.androidclaw.tools.objectSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Launches an installed app by visible name or package id, resolved against
 * the device's launcher activities.
 */
class OpenAppTool(private val context: Context) : Tool {

    override val schema = ToolSchema(
        name = "open_app",
        description = "Open an installed app by its visible name (e.g. 'YouTube') " +
            "or package id (e.g. 'com.google.android.youtube'). " +
            "Returns the app's initial screen so you can act immediately without a read_screen call.",
        inputSchema = objectSchema(
            mapOf("app" to "App name or Android package id"),
        ),
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.Default) {
        val query = args["app"]?.jsonPrimitive?.content?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return@withContext ToolResult.error("Missing 'app' argument")

        val pm = context.packageManager

        // Exact package id first, then case-insensitive label match.
        val launchIntent = pm.getLaunchIntentForPackage(query) ?: run {
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val candidates = pm.queryIntentActivities(launcher, 0)
            val match = candidates.firstOrNull {
                it.loadLabel(pm).toString().equals(query, ignoreCase = true)
            } ?: candidates.firstOrNull {
                it.loadLabel(pm).toString().contains(query, ignoreCase = true)
            }
            match?.activityInfo?.let {
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .setClassName(it.packageName, it.name)
            }
        } ?: return@withContext ToolResult.error("No installed app matching '$query'")

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        // Wait for the launched app to render its first frame, then capture it.
        // The accessibility tree must be walked on the main thread.
        delay(1500)
        val screen = withContext(Dispatchers.Main) {
            ClawAccessibilityService.instance?.dumpScreen()
        }.orEmpty()
        val suffix = if (screen.isNotEmpty()) SCREEN_MARKER + screen else ""
        ToolResult("Opened ${launchIntent.component?.packageName ?: query}$suffix")
    }
}
