package com.androidclaw.overlay

import android.animation.ValueAnimator
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
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Hosts the floating "Claw" overlay inside a low-priority foreground service.
 * Two states: draggable edge tab (collapsed) / dark chat card (expanded).
 *
 * Auto-expands when a turn starts from the overlay or when a confirmation is
 * needed (so it stays visible on top of any other app, e.g. YouTube).
 */
class OverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager

    private var rootView: FrameLayout? = null
    private val params by lazy { defaultParams() }

    private var expanded = false
    private var isAgentActive = false
    private var turnJob: Job? = null

    private var transcriptView: TextView? = null
    private var scrollView: ScrollView? = null
    private var statusDotView: TextView? = null
    private val transcript = StringBuilder()
    private var assistantLineStart = -1

    private var pendingConfirmText: String? = null
    private var pendingConfirm: CompletableDeferred<OverlayDecision>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startInForeground()
        OverlayBridge.confirmHandler = ::handleConfirm
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_SHOW -> {
                if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
                showExpanded()
                return START_STICKY
            }
        }
        if (rootView == null) {
            if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
            showCollapsed()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        OverlayBridge.confirmHandler = null
        pendingConfirm?.complete(OverlayDecision.Stop)
        turnJob?.cancel()
        scope.cancel()
        rootView?.let { runCatching { windowManager.removeView(it) } }
        rootView = null
        super.onDestroy()
    }

    // ── Confirmation ──────────────────────────────────────────────────────────

    /** Called off-main by the gateway confirmer; auto-expands the card. */
    private suspend fun handleConfirm(description: String): OverlayDecision {
        val deferred = CompletableDeferred<OverlayDecision>()
        withContext(Dispatchers.Main.immediate) {
            // Cancel any prior pending confirm (two concurrent callers would otherwise
            // overwrite pendingConfirm, leaving the first deferred abandoned forever).
            pendingConfirm?.complete(OverlayDecision.Stop)
            pendingConfirmText = description
            pendingConfirm = deferred
            showExpanded()
        }
        val decision = deferred.await()
        withContext(Dispatchers.Main.immediate) {
            // Guard: service may have been destroyed while we were suspended.
            if (rootView == null) return@withContext
            pendingConfirmText = null
            pendingConfirm = null
            showExpanded()
        }
        return decision
    }

    private fun resolveConfirm(decision: OverlayDecision) {
        // The user may have tapped the input (making the window focusable) and then
        // pressed a button instead of typing. Restore FLAG_NOT_FOCUSABLE before the
        // agent continues, or rootInActiveWindow would return OUR tree, not the
        // target app's — making every subsequent read_screen blind.
        restoreNotFocusable()
        pendingConfirm?.complete(decision)
    }

    /** Re-asserts FLAG_NOT_FOCUSABLE (and hides the IME) if the input had cleared it. */
    private fun restoreNotFocusable() {
        if (params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0) {
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            rootView?.let {
                getSystemService(InputMethodManager::class.java)
                    .hideSoftInputFromWindow(it.windowToken, 0)
                windowManager.updateViewLayout(it, params)
            }
        }
    }

    // ── Foreground notification ───────────────────────────────────────────────

    private fun startInForeground() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Claw overlay", NotificationManager.IMPORTANCE_MIN)
                    .apply { setShowBadge(false) },
            )
        }
        val stopPi = PendingIntent.getService(
            this, 0, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val showPi = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_SHOW),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Claw is floating")
            .setContentText("Tap to open the panel. Tap 'Stop' to close.")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(showPi) // tap the notification body → re-open the panel
            .addAction(android.R.drawable.ic_menu_view, "Open", showPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    // ── Window ────────────────────────────────────────────────────────────────

    private fun defaultParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        // TOP|START so x AND y are free — the puck can be dragged anywhere.
        gravity = Gravity.TOP or Gravity.START
        x = dp(280)
        y = dp(140)
    }

    // ── Collapsed: floating joystick puck ─────────────────────────────────────

    /**
     * A small, translucent circular puck — the "gaming-console" controller.
     * Drag it anywhere; tap to open the panel. Deliberately low-opacity so it
     * barely covers what's underneath.
     */
    private fun showCollapsed() {
        expanded = false
        statusDotView = null
        params.width = WindowManager.LayoutParams.WRAP_CONTENT
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        params.alpha = if (isAgentActive) 0.85f else 0.55f // see-through when idle

        val ringColor = if (isAgentActive) Color.parseColor("#FF7043") else Color.parseColor("#5C6BC0")
        val fillColor = if (isAgentActive) Color.parseColor("#33FF7043") else Color.parseColor("#33FFFFFF")

        val sz = dp(56)
        val puck = TextView(this).apply {
            text = "🦞"
            textSize = 22f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(fillColor)
                setStroke(dp(2), ringColor)
            }
            elevation = dp(8).toFloat()
        }

        if (isAgentActive) {
            fun doPulse() {
                puck.animate().scaleX(1.14f).scaleY(1.14f).setDuration(620)
                    .withEndAction {
                        puck.animate().scaleX(1f).scaleY(1f).setDuration(620)
                            .withEndAction { if (isAgentActive && !expanded) doPulse() }
                            .start()
                    }.start()
            }
            doPulse()
        }

        attachDragAndTap(puck, snapToEdge = true) { showExpanded() }
        setRoot(puck)
    }

    // ── Expanded: dark chat card ──────────────────────────────────────────────

    private fun showExpanded() {
        expanded = true
        params.width = dp(320)
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        // CRITICAL: keep FLAG_NOT_FOCUSABLE even when expanded. Android's
        // AccessibilityService.getRootInActiveWindow() returns the FOCUSED window.
        // If our overlay steals focus, read_screen returns our view tree instead of
        // YouTube's (or whatever the target app is), breaking all screen reads.
        // The EditText uses a click listener to temporarily clear this flag when needed.
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        // Slightly see-through so it doesn't fully block the app behind it, but
        // opaque enough that the text stays readable.
        params.alpha = 0.96f

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F4F5FB")) // light panel
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.parseColor("#D6D8E5"))
            }
            elevation = dp(20).toFloat()
            clipToOutline = true
        }
        card.addView(buildHeader())
        card.addView(buildTranscriptArea())
        card.addView(divider())
        val pending = pendingConfirmText
        if (pending != null) {
            card.addView(buildConfirmPanel(pending))
            card.addView(divider())
        }
        card.addView(buildInputRow(pending))

        setRoot(card)

        // Subtle entrance: fade + scale up so the panel "pops" rather than snapping in.
        card.alpha = 0f
        card.scaleX = 0.96f
        card.scaleY = 0.96f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(140).start()
    }

    private fun buildHeader(): LinearLayout {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(11), dp(10), dp(11))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#C62828"), Color.parseColor("#E64A19")),
            ).apply {
                cornerRadii = floatArrayOf(
                    dp(20).toFloat(), dp(20).toFloat(),
                    dp(20).toFloat(), dp(20).toFloat(),
                    0f, 0f, 0f, 0f,
                )
            }
        }
        val title = TextView(this).apply {
            text = "🦞 Claw"
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val dot = TextView(this).apply {
            text = if (isAgentActive) "● thinking…" else "● ready"
            textSize = 10f
            setTextColor(
                if (isAgentActive) Color.parseColor("#FFEB3B") else Color.parseColor("#A5D6A7"),
            )
            setPadding(0, 0, dp(6), 0)
        }
        statusDotView = dot
        val collapseBtn = TextView(this).apply {
            text = "▾"
            textSize = 22f
            setTextColor(Color.parseColor("#FFCCBC"))
            setPadding(dp(6), 0, dp(4), 0)
            setOnClickListener { showCollapsed() }
        }
        attachDragAndTap(title, snapToEdge = false) { /* drag-only handle */ }
        header.addView(title)
        header.addView(dot)
        header.addView(collapseBtn)
        return header
    }

    private fun buildTranscriptArea(): ScrollView {
        val tv = TextView(this).apply {
            text = if (transcript.isEmpty()) "Ask Claw something…" else transcript.toString()
            textSize = 12.5f
            setTextColor(Color.parseColor("#2A2A35"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        transcriptView = tv
        return ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            addView(tv)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(200),
            )
        }.also { scrollView = it }
    }

    private fun buildConfirmPanel(pending: String): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FFF6E0")) // soft amber on light theme
                setStroke(dp(2), Color.parseColor("#FFA000"))
            }
        }
        panel.addView(TextView(this).apply {
            text = "🔔 Claw wants to:"
            textSize = 10.5f
            setTextColor(Color.parseColor("#B26A00"))
            typeface = Typeface.DEFAULT_BOLD
        })
        panel.addView(TextView(this).apply {
            text = pending
            textSize = 13.5f
            setTextColor(Color.parseColor("#5D3A00"))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(3), 0, dp(8))
        })
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(4))
        }
        btnRow.addView(
            confirmBtn("▶ Do it", Color.parseColor("#1B5E20"), Color.parseColor("#388E3C")) {
                resolveConfirm(OverlayDecision.Proceed)
            },
        )
        btnRow.addView(spacer(dp(6)))
        btnRow.addView(
            confirmBtn("◀ Skip", Color.parseColor("#263238"), Color.parseColor("#455A64")) {
                resolveConfirm(OverlayDecision.Back)
            },
        )
        btnRow.addView(spacer(dp(6)))
        btnRow.addView(
            confirmBtn("⛔ Stop", Color.parseColor("#7F0000"), Color.parseColor("#C62828")) {
                resolveConfirm(OverlayDecision.Stop)
            },
        )
        panel.addView(btnRow)
        return panel
    }

    private fun confirmBtn(label: String, dark: Int, light: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 12.5f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(9), dp(6), dp(9))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(light, dark),
            ).apply { cornerRadius = dp(10).toFloat() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
        }

    private fun buildInputRow(pending: String?): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            background = GradientDrawable().apply { setColor(Color.parseColor("#ECEDF5")) }
        }
        val input = EditText(this).apply {
            hint = when {
                OverlayBridge.agent == null -> "Open Claw app and sign in"
                pending != null -> "Tell Claw what to do instead…"
                else -> "Ask Claw something…"
            }
            setHintTextColor(Color.parseColor("#9A9AAE"))
            textSize = 13f
            setTextColor(Color.parseColor("#1E1E28"))
            isEnabled = OverlayBridge.agent != null
            maxLines = 3
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FFFFFF"))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.parseColor("#D0D2E0"))
            }
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .also { it.setMargins(0, 0, dp(8), 0) }
            imeOptions = EditorInfo.IME_ACTION_SEND
        }
        // Tapping the input temporarily clears FLAG_NOT_FOCUSABLE so the keyboard appears.
        // FLAG_NOT_FOCUSABLE is normally kept set so the AccessibilityService's
        // rootInActiveWindow stays on the target app (YouTube etc.), not on our overlay.
        input.setOnClickListener {
            if (params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0) {
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                rootView?.let { windowManager.updateViewLayout(it, params) }
            }
            // updateViewLayout applies the focusability change asynchronously; an
            // immediate requestFocus() lands before the window can take focus and the
            // IME never shows. Post the focus + explicit IME request to the next frame.
            input.post {
                input.requestFocus()
                getSystemService(InputMethodManager::class.java)
                    .showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        val sendBtn = TextView(this).apply {
            text = "↑"
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#FF6D3A"), Color.parseColor("#C44B00")),
            ).apply { cornerRadius = dp(22).toFloat() }
            val sz = dp(40)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
        }
        val doSend: () -> Unit = {
            val text = input.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) {
                input.setText("")
                // Restore FLAG_NOT_FOCUSABLE so accessibility reads the target app again.
                restoreNotFocusable()
                if (pendingConfirmText != null) resolveConfirm(OverlayDecision.Chat(text))
                else send(text)
            }
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { doSend(); true } else false
        }
        sendBtn.setOnClickListener { doSend() }
        row.addView(input)
        row.addView(sendBtn)
        return row
    }

    private fun divider() = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        setBackgroundColor(Color.parseColor("#E0E2EC"))
    }

    private fun spacer(w: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(w, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    // ── Turn handling ─────────────────────────────────────────────────────────

    private fun send(text: String) {
        val agent = OverlayBridge.agent ?: run {
            appendLine("⚠ Open the Claw app and sign in first.")
            return
        }
        // Clean up any confirm waiting from a previous (now-cancelled) turn so its
        // suspended coroutine can unblock and exit cleanly.
        turnJob?.cancel()
        pendingConfirm?.complete(OverlayDecision.Stop)
        pendingConfirm = null
        pendingConfirmText = null
        appendLine("You: $text")
        assistantLineStart = -1
        isAgentActive = true
        if (!expanded) showExpanded() else updateActiveStatus()
        turnJob = agent.runTurn(text)
            .onEach { render(it) }
            // Gateway only converts LlmException to TurnFailed; anything else thrown
            // by a tool would otherwise escape launchIn and crash the whole process.
            .catch { e -> render(OverlayReply.Failed(e.message ?: "Something went wrong")) }
            .launchIn(scope)
    }

    private fun render(reply: OverlayReply) {
        when (reply) {
            is OverlayReply.TextDelta -> appendAssistant(reply.text)
            is OverlayReply.ToolStatus -> appendLine(
                when {
                    reply.isError -> "⚠ ${reply.name} failed"
                    reply.running -> "⚙ ${reply.name}…"
                    else -> "✓ ${reply.name}"
                },
            )
            OverlayReply.Done -> {
                isAgentActive = false
                // Task finished — auto-hide back to the small translucent puck so it
                // stops covering the screen. The transcript is kept; tapping the puck
                // brings the full answer back.
                autoHideAfterTask()
            }
            is OverlayReply.Failed -> {
                appendLine("⚠ ${reply.message}")
                isAgentActive = false
                // Keep the panel open on failure so the user sees what went wrong.
                updateActiveStatus()
            }
        }
    }

    /** Collapse to the puck shortly after a successful turn, unless a confirm is waiting. */
    private fun autoHideAfterTask() {
        statusDotView?.apply {
            text = "● done"
            setTextColor(Color.parseColor("#2E7D32"))
        }
        rootView?.postDelayed({
            // Don't hide if a new turn started or a confirmation is now pending.
            if (!isAgentActive && pendingConfirmText == null) showCollapsed()
        }, 1400)
    }

    private fun updateActiveStatus() {
        statusDotView?.apply {
            text = if (isAgentActive) "● thinking…" else "● ready"
            setTextColor(
                if (isAgentActive) Color.parseColor("#FFEB3B") else Color.parseColor("#A5D6A7"),
            )
        }
        if (!expanded) showCollapsed()
    }

    // ── Transcript ────────────────────────────────────────────────────────────

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

    // ── View helpers ──────────────────────────────────────────────────────────

    private fun setRoot(content: View) {
        val existing = rootView
        val container = existing ?: FrameLayout(this).also { rootView = it }
        container.removeAllViews()
        container.addView(content)
        if (existing == null) windowManager.addView(container, params)
        else windowManager.updateViewLayout(container, params)
    }

    private fun attachDragAndTap(handle: View, snapToEdge: Boolean, onTap: () -> Unit) {
        // Use the system touch slop: a hand-held tap easily wobbles past a few px,
        // and a too-tight threshold makes taps register as drags (puck "ignores" taps).
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragged = false
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = params.x; startY = params.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downX).roundToInt()
                    val dy = (event.rawY - downY).roundToInt()
                    if (abs(dx) > slop || abs(dy) > slop) dragged = true
                    // Move freely in BOTH axes — drop it anywhere on screen.
                    if (dragged) {
                        params.x = (startX + dx).coerceAtLeast(0)
                        params.y = (startY + dy).coerceAtLeast(0)
                        rootView?.let { windowManager.updateViewLayout(it, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) onTap()
                    else if (snapToEdge) snapToNearestEdge(handle)
                    true
                }
                MotionEvent.ACTION_CANCEL -> { dragged = false; true }
                else -> false
            }
        }
    }

    /** Glide the puck to whichever vertical screen edge is closer, like a chat-bubble. */
    private fun snapToNearestEdge(view: View) {
        val screenW = resources.displayMetrics.widthPixels
        val viewW = view.width.takeIf { it > 0 } ?: dp(56)
        val targetX = if (params.x + viewW / 2 < screenW / 2) 0 else screenW - viewW
        ValueAnimator.ofInt(params.x, targetX).apply {
            duration = 180
            addUpdateListener { anim ->
                params.x = anim.animatedValue as Int
                rootView?.let { runCatching { windowManager.updateViewLayout(it, params) } }
            }
            start()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val CHANNEL_ID = "androidclaw_overlay"
        private const val NOTIF_ID = 0xC1A7
        const val ACTION_STOP = "com.androidclaw.overlay.STOP"
        const val ACTION_SHOW = "com.androidclaw.overlay.SHOW"

        fun canDraw(context: Context): Boolean = Settings.canDrawOverlays(context)

        fun start(context: Context) {
            val i = Intent(context, OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, OverlayService::class.java).setAction(ACTION_STOP))
        }
    }
}
