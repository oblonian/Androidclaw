package com.androidclaw.control

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The actuator (SPEC §5.1). Reads the foreground app's UI tree and drives
 * taps / typing / scrolling / global navigation on the user's behalf.
 *
 * A process-wide [instance] lets the `read_screen` and `ui_action` tools reach
 * the running service. The service holds no model and does no work until a tool
 * calls it — honouring the idle budget (SPEC §10).
 */
class ClawAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* event-driven; nothing to poll */ }

    override fun onInterrupt() { /* no-op */ }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    // ── Perception ────────────────────────────────────────────────────────────

    /** Compact text serialization of the foreground UI tree (no image tokens). */
    fun dumpScreen(): String {
        val root = rootInActiveWindow
            ?: return "No active window — the screen may be protected, empty, or mid-transition."
        val sb = StringBuilder("App: ${root.packageName ?: "?"}\n")
        var count = 0

        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || count >= MAX_NODES) return
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val interesting = text.isNotEmpty() || desc.isNotEmpty() ||
                node.isClickable || node.isEditable || node.isScrollable
            var childDepth = depth
            if (interesting) {
                count++
                val cls = node.className?.toString()?.substringAfterLast('.').orEmpty()
                val flags = buildList {
                    if (node.isClickable) add("tap")
                    if (node.isEditable) add("edit")
                    if (node.isScrollable) add("scroll")
                }.joinToString(",")
                val label = when {
                    text.isNotEmpty() -> "\"${text.take(80)}\""
                    desc.isNotEmpty() -> "desc=\"${desc.take(80)}\""
                    else -> ""
                }
                sb.append("  ".repeat(depth.coerceAtMost(10)))
                    .append("- ").append(cls)
                if (label.isNotEmpty()) sb.append(' ').append(label)
                if (flags.isNotEmpty()) sb.append(" [").append(flags).append(']')
                sb.append('\n')
                childDepth = depth + 1
            }
            for (i in 0 until node.childCount) walk(node.getChild(i), childDepth)
        }

        walk(root, 0)
        if (count >= MAX_NODES) sb.append("… (truncated at $MAX_NODES elements)\n")
        return sb.toString()
    }

    // ── Actuation ─────────────────────────────────────────────────────────────

    fun click(target: String): String {
        val root = rootInActiveWindow ?: return "No active window"
        val match = findNode(root) { n ->
            n.text?.toString()?.contains(target, true) == true ||
                n.contentDescription?.toString()?.contains(target, true) == true
        } ?: return "No element matching \"$target\" on screen"
        // Climb to the nearest clickable ancestor (labels often aren't clickable themselves).
        var node: AccessibilityNodeInfo? = match
        while (node != null && !node.isClickable) node = node.parent
        val hit = node ?: match
        return if (hit.performAction(AccessibilityNodeInfo.ACTION_CLICK)) "Tapped \"$target\""
        else "Found \"$target\" but the tap was not accepted"
    }

    fun typeText(target: String?, text: String): String {
        val root = rootInActiveWindow ?: return "No active window"
        val field = (
            if (!target.isNullOrBlank()) findNode(root) { n ->
                n.isEditable && (
                    n.text?.toString()?.contains(target, true) == true ||
                        n.contentDescription?.toString()?.contains(target, true) == true
                    )
            } else null
            ) ?: findNode(root) { it.isEditable }
            ?: return "No text field found on screen"
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return if (field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) "Typed the text"
        else "Found a field but typing was not accepted"
    }

    fun scroll(forward: Boolean): String {
        val root = rootInActiveWindow ?: return "No active window"
        val scrollable = findNode(root) { it.isScrollable } ?: return "Nothing scrollable on screen"
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return if (scrollable.performAction(action)) "Scrolled ${if (forward) "down" else "up"}"
        else "Reached the end / scroll not accepted"
    }

    fun globalAction(name: String): String {
        val action = when (name.lowercase()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            else -> return "Unknown navigation action: $name"
        }
        return if (performGlobalAction(action)) "Did $name" else "$name was not accepted"
    }

    private fun findNode(
        root: AccessibilityNodeInfo?,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): AccessibilityNodeInfo? {
        if (root == null) return null
        if (predicate(root)) return root
        for (i in 0 until root.childCount) {
            findNode(root.getChild(i), predicate)?.let { return it }
        }
        return null
    }

    companion object {
        private const val MAX_NODES = 160

        @Volatile
        var instance: ClawAccessibilityService? = null
            private set

        /** Whether the user has enabled AndroidClaw's accessibility service in Settings. */
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val component = ComponentName(context, ClawAccessibilityService::class.java).flattenToString()
            return enabled.split(':').any { it.equals(component, ignoreCase = true) }
        }
    }
}
