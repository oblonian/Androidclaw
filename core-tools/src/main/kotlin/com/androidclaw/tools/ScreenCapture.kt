package com.androidclaw.tools

/**
 * Separates a tool's action outcome from a folded screen capture in a
 * [ToolResult.content]. Perception tools ([read_screen]) and acting tools that
 * return the post-action screen ([ui_action], [open_app]) place this exact
 * marker before the screen dump.
 *
 * The Gateway uses it to prune stale screens: only the most recent capture is
 * kept verbatim in the prompt; earlier ones are collapsed to a short note so
 * the prompt size stays flat as a turn grows. The marker is plain, readable
 * text so it costs the model nothing if it ever sees an unpruned copy.
 */
const val SCREEN_MARKER = "\n\n[screen]\n"
