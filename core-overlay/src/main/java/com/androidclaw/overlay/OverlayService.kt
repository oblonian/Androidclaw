package com.androidclaw.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Hosts the floating "Claw" overlay (SPEC §5.4) inside a low-priority
 * foreground service. Two states: a draggable edge tab (collapsed) and a
 * compact chat card (expanded). Wakes only on user interaction — no polling,
 * no wakelock, no resident model.
 *
 * Uses plain Android Views (chat-head style) rather than Compose-in-a-window
 * to keep the skeleton robust; the actuator (AccessibilityService) lands next.
 */
class OverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager

    private var rootView: FrameLayout? = null
    private val params by lazy { defaultParams() }

    private var expanded = false
    private var turnJob: Job? = null

    // Expanded-card widgets, retained so streaming deltas can update them.
    private var transcriptView: TextView? = null
    private var scrollView: ScrollView? = null
    private val transcript = StringBuilder()
    private var assistantLineStart = -1 // index in `transcript` of the in-flight reply

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startInForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
        }
        if (rootView == null) {
            if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
            showCollapsed()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        turnJob?.cancel()
        scope.cancel()
        rootView?.let { runCatching { windowManager.removeView(it) } }
        rootView = null
        super.onDestroy()
    }

    // ── Foreground plumbing ───────────────────────────────────────────────────

    private fun startInForeground() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "AndroidClaw overlay", NotificationManager.IMPORTANCE_MIN,
            ).apply { setShowBadge(false) }
            mgr.createNotificationChannel(channel)
        }
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Claw is floating")
            .setContentText("Tap the edge tab to summon. Swipe notification action to stop.")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    // ── Window params ─────────────────────────────────────────────────────────

    private fun defaultParams(): WindowManager.LayoutParams {
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 0
            y = dp(120)
        }
    }

    // ── Collapsed state: edge tab ─────────────────────────────────────────────

    private fun showCollapsed() {
        expanded = false
        params.width = WindowManager.LayoutParams.WRAP_CONTENT
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

        val tab = TextView(this).apply {
            text = "🦞"
            textSize = 22f
            setPadding(dp(10), dp(14), dp(10), dp(14))
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#D2691E"))
                cornerRadii = floatArrayOf(
                    dp(20).toFloat(), dp(20).toFloat(), 0f, 0f,
                    0f, 0f, dp(20).toFloat(), dp(20).toFloat(),
                )
            }
        }
        attachDragAndTap(tab) { toggle() }
        setRoot(tab)
    }

    // ── Expanded state: chat card ─────────────────────────────────────────────

    private fun showExpanded() {
        expanded = true
        params.width = dp(300)
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        // Allow the EditText to take focus for typing.
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FAFAFA"))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.parseColor("#22000000"))
            }
        }

        // Header: title + collapse button (also a drag handle).
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "🦞 Claw"
            textSize = 15f
            setTextColor(Color.parseColor("#D2691E"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val collapseBtn = TextView(this).apply {
            text = "—"
            textSize = 20f
            setTextColor(Color.parseColor("#888888"))
            setPadding(dp(12), 0, dp(8), 0)
            setOnClickListener { toggle() }
        }
        attachDragAndTap(title) { /* drag only; tap does nothing on title */ }
        header.addView(title)
        header.addView(collapseBtn)
        card.addView(header)

        // Transcript.
        val tv = TextView(this).apply {
            text = transcript.ifEmpty { StringBuilder("Ask Claw to do something…") }
            textSize = 13f
            setTextColor(Color.parseColor("#222222"))
            setPadding(0, dp(8), 0, dp(8))
        }
        transcriptView = tv
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(180),
            )
            addView(tv)
        }
        scrollView = scroll
        card.addView(scroll)

        // Input row.
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val input = EditText(this).apply {
            hint = if (OverlayBridge.agent == null) "Set up Claw in the app first" else "Message…"
            textSize = 13f
            isEnabled = OverlayBridge.agent != null
            maxLines = 3
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            imeOptions = EditorInfo.IME_ACTION_SEND
        }
        val sendBtn = Button(this).apply { text = "Send" }
        val doSend = {
            val text = input.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) {
                input.setText("")
                send(text)
            }
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { doSend(); true } else false
        }
        sendBtn.setOnClickListener { doSend() }
        inputRow.addView(input)
        inputRow.addView(sendBtn)
        card.addView(inputRow)

        setRoot(card)
    }

    // ── Turn handling ─────────────────────────────────────────────────────────

    private fun send(text: String) {
        val agent = OverlayBridge.agent ?: run {
            appendLine("⚠ Claw isn't set up yet — open the app and sign in.")
            return
        }
        appendLine("You: $text")
        beginAssistantLine()
        turnJob?.cancel()
        turnJob = agent.runTurn(text)
            .onEach { reply -> render(reply) }
            .launchIn(scope)
    }

    private fun render(reply: OverlayReply) {
        when (reply) {
            is OverlayReply.TextDelta -> appendAssistant(reply.text)
            is OverlayReply.ToolStatus -> {
                val mark = when {
                    reply.isError -> "⚠ ${reply.name} failed"
                    reply.running -> "⚙ ${reply.name}…"
                    else -> "✓ ${reply.name}"
                }
                appendLine(mark)
                beginAssistantLine()
            }
            OverlayReply.Done -> beginAssistantLine()
            is OverlayReply.Failed -> appendLine("⚠ ${reply.message}")
        }
    }

    // ── Transcript helpers ────────────────────────────────────────────────────

    private fun beginAssistantLine() {
        if (transcript.isNotEmpty() && transcript.last() != '\n') transcript.append('\n')
        transcript.append("Claw: ")
        assistantLineStart = transcript.length
        flushTranscript()
    }

    private fun appendAssistant(delta: String) {
        if (assistantLineStart < 0) beginAssistantLine()
        transcript.append(delta)
        flushTranscript()
    }

    private fun appendLine(line: String) {
        if (transcript.isNotEmpty() && transcript.last() != '\n') transcript.append('\n')
        transcript.append(line).append('\n')
        assistantLineStart = -1
        flushTranscript()
    }

    private fun flushTranscript() {
        transcriptView?.text = transcript
        scrollView?.post { scrollView?.fullScroll(View.FOCUS_DOWN) }
    }

    // ── View swap + drag ──────────────────────────────────────────────────────

    private fun setRoot(content: View) {
        val existing = rootView
        val container = existing ?: FrameLayout(this).also { rootView = it }
        container.removeAllViews()
        container.addView(content)
        if (existing == null) {
            windowManager.addView(container, params)
        } else {
            windowManager.updateViewLayout(container, params)
        }
    }

    private fun toggle() {
        if (expanded) showCollapsed() else showExpanded()
    }

    /** Vertical drag to reposition; a near-stationary press counts as a tap. */
    private fun attachDragAndTap(handle: View, onTap: () -> Unit) {
        var downY = 0f
        var startParamY = 0
        var dragged = false
        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downY = event.rawY
                    startParamY = params.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = (event.rawY - downY).roundToInt()
                    if (abs(dy) > dp(6)) dragged = true
                    params.y = (startParamY + dy).coerceAtLeast(0)
                    rootView?.let { windowManager.updateViewLayout(it, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) onTap()
                    true
                }
                else -> false
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val CHANNEL_ID = "androidclaw_overlay"
        private const val NOTIF_ID = 0xC1A7
        const val ACTION_STOP = "com.androidclaw.overlay.STOP"

        /** True if the user has granted "draw over other apps". */
        fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context)

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, OverlayService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
