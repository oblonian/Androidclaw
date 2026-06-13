package com.androidclaw.overlay

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
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
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
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

class OverlayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var windowManager: WindowManager

    private var rootView: FrameLayout? = null
    private val params by lazy { defaultParams() }

    private var expanded = false
    private var isAgentActive = false
    private var isGhosted = false
    private var turnJob: Job? = null

    private var transcriptView: TextView? = null
    private var scrollView: ScrollView? = null
    private var statusDotView: TextView? = null
    private val transcript = SpannableStringBuilder()
    private var assistantLineStart = -1
    private var collapsedX: Int = -1
    private var collapsedY: Int = -1

    private var pendingConfirmText: String? = null
    private var pendingConfirm: CompletableDeferred<OverlayDecision>? = null

    private var limitReachedMax: Int? = null
    private var showRetryButton = false
    private var lastUserText: String? = null
    private var pendingSharedText: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        collapsedX = OverlayBridge.initialPuckX
        collapsedY = OverlayBridge.initialPuckY
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
            ACTION_SHARED_TEXT -> {
                val text = intent.getStringExtra(EXTRA_TEXT) ?: return START_NOT_STICKY
                if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
                pendingSharedText = text
                if (rootView == null) showCollapsed()
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

    private suspend fun handleConfirm(description: String): OverlayDecision {
        val deferred = CompletableDeferred<OverlayDecision>()
        withContext(Dispatchers.Main.immediate) {
            pendingConfirm?.complete(OverlayDecision.Stop)
            pendingConfirmText = description
            pendingConfirm = deferred
            isGhosted = false
            showCompactConfirm(description)
        }
        val decision = deferred.await()
        withContext(Dispatchers.Main.immediate) {
            if (rootView == null) return@withContext
            pendingConfirmText = null
            pendingConfirm = null
            if (isAgentActive) {
                // Re-ghost: agent is continuing after the user's verdict
                isGhosted = true
                params.alpha = 0.12f
                rootView?.let { runCatching { windowManager.updateViewLayout(it, params) } }
            } else {
                showExpanded()
            }
        }
        return decision
    }

    private fun resolveConfirm(decision: OverlayDecision) {
        restoreNotFocusable()
        pendingConfirm?.complete(decision)
    }

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

    // ── Notification ──────────────────────────────────────────────────────────

    private fun buildNotification(text: String): Notification {
        val stopPi = PendingIntent.getService(
            this, 0, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val showPi = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_SHOW),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Claw is floating")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(showPi)
            .addAction(android.R.drawable.ic_menu_view, "Open", showPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPi)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun startInForeground() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Claw overlay", NotificationManager.IMPORTANCE_MIN)
                    .apply { setShowBadge(false) },
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, buildNotification("Tap to open."), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, buildNotification("Tap to open."))
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
        gravity = Gravity.TOP or Gravity.START
        x = dp(280)
        y = dp(140)
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
    }

    // ── Collapsed puck ────────────────────────────────────────────────────────

    private fun showCollapsed() {
        expanded = false
        statusDotView = null
        params.width = WindowManager.LayoutParams.WRAP_CONTENT
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        params.alpha = if (isAgentActive) 0.85f else 0.55f

        if (collapsedX >= 0) params.x = collapsedX
        if (collapsedY >= 0) params.y = collapsedY

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

    // ── Expanded card ─────────────────────────────────────────────────────────

    private fun showExpanded() {
        expanded = true
        isGhosted = false
        params.width = dp(320)
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        params.alpha = 0.96f

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F4F5FB"))
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), Color.parseColor("#D6D8E5"))
            }
            elevation = dp(20).toFloat()
            clipToOutline = true
        }
        // Dismiss keyboard if user touches transcript/card outside input
        card.setOnTouchListener { _, _ -> restoreNotFocusable(); false }

        card.addView(buildHeader())
        card.addView(buildTranscriptArea())
        buildContinueBar()?.let { card.addView(it) }
        buildRetryBar()?.let { card.addView(it) }
        card.addView(divider())
        val pending = pendingConfirmText
        if (pending != null) {
            card.addView(buildConfirmPanel(pending))
            card.addView(divider())
        }
        card.addView(buildInputRow(pending))

        setRoot(card)

        card.alpha = 0f; card.scaleX = 0.96f; card.scaleY = 0.96f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(140).start()
    }

    // ── Compact confirm (small card anchored near puck) ───────────────────────

    private fun showCompactConfirm(pending: String) {
        expanded = false
        val screenW = resources.displayMetrics.widthPixels
        params.width = dp(300)
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        params.alpha = 0.97f
        if (collapsedX >= 0) params.x = collapsedX.coerceIn(0, (screenW - dp(300)).coerceAtLeast(0))
        if (collapsedY >= 0) params.y = collapsedY

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FFF8E1"))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(2), Color.parseColor("#FFA000"))
            }
            elevation = dp(20).toFloat()
            clipToOutline = true
        }

        val titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(8), dp(8))
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#E65100"), Color.parseColor("#FFA000")),
            ).apply {
                cornerRadii = floatArrayOf(dp(16).toFloat(), dp(16).toFloat(), dp(16).toFloat(), dp(16).toFloat(), 0f, 0f, 0f, 0f)
            }
        }
        val titleText = TextView(this).apply {
            text = "🔔 Claw wants to:"
            textSize = 12f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        attachDragAndTap(titleText, snapToEdge = false) { /* drag handle */ }
        titleBar.addView(titleText)

        // Expand to full card button
        val expandBtn = TextView(this).apply {
            text = "▿"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            val sz = dp(36)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            setOnClickListener { showExpanded() }
        }
        titleBar.addView(expandBtn)
        card.addView(titleBar)

        card.addView(TextView(this).apply {
            text = pending
            textSize = 14f
            setTextColor(Color.parseColor("#5D3A00"))
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(10), dp(12), dp(6))
        })

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(4), dp(10), dp(12))
        }
        btnRow.addView(confirmBtn("▶ Do it", Color.parseColor("#1B5E20"), Color.parseColor("#388E3C")) { resolveConfirm(OverlayDecision.Proceed) })
        btnRow.addView(spacer(dp(6)))
        btnRow.addView(confirmBtn("◀ Skip", Color.parseColor("#263238"), Color.parseColor("#455A64")) { resolveConfirm(OverlayDecision.Back) })
        btnRow.addView(spacer(dp(6)))
        btnRow.addView(confirmBtn("⛔ Stop", Color.parseColor("#7F0000"), Color.parseColor("#C62828")) { resolveConfirm(OverlayDecision.Stop) })
        card.addView(btnRow)

        setRoot(card)
        card.alpha = 0f; card.scaleX = 0.92f; card.scaleY = 0.92f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(120).start()
    }

    private fun buildHeader(): LinearLayout {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(6), dp(10))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#D2512A"), Color.parseColor("#7366BD")),
            ).apply {
                cornerRadii = floatArrayOf(dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), 0f, 0f, 0f, 0f)
            }
        }
        val title = TextView(this).apply {
            text = "🦞 Claw"
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        attachDragAndTap(title, snapToEdge = false) { /* drag handle */ }

        val dot = TextView(this).apply {
            text = if (isAgentActive) "● thinking…" else "● ready"
            textSize = 10f
            setTextColor(if (isAgentActive) Color.parseColor("#FFEB3B") else Color.parseColor("#A5D6A7"))
            setPadding(0, 0, dp(4), 0)
        }
        statusDotView = dot

        fun headerBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(Color.parseColor("#FFCCBC"))
            gravity = Gravity.CENTER
            val sz = dp(36)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            setOnClickListener { onClick() }
        }

        header.addView(title)
        header.addView(dot)

        // Cancel (stop running turn) — visible only while agent is active
        if (isAgentActive) {
            header.addView(headerBtn("◼") {
                turnJob?.cancel(); isAgentActive = false; isGhosted = false
                appendLine("⛔ Stopped.")
                updateNotification("Tap to open.")
                autoHideAfterTask()
            })
        }

        // Copy transcript
        val copyBtn = headerBtn("⧉") {
            val clip = ClipData.newPlainText("Claw conversation", transcript.toString())
            getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        }
        header.addView(copyBtn)

        // Close service
        header.addView(headerBtn("✕") { stopSelf() })

        // Collapse to puck
        header.addView(headerBtn("▾") { showCollapsed() })

        return header
    }

    private fun buildTranscriptArea(): ScrollView {
        val screenH = resources.displayMetrics.heightPixels
        val transcriptH = (screenH * 0.28f).roundToInt().coerceIn(dp(120), dp(260))
        val tv = TextView(this).apply {
            text = if (transcript.isEmpty()) "Ask Claw something…" else transcript
            textSize = 12.5f
            setTextColor(Color.parseColor("#2A2A35"))
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        transcriptView = tv
        return ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            addView(tv)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, transcriptH)
            setOnTouchListener { _, _ -> restoreNotFocusable(); false }
        }.also { scrollView = it }
    }

    private fun buildContinueBar(): LinearLayout? {
        val max = limitReachedMax ?: return null
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(8), dp(8))
            setBackgroundColor(Color.parseColor("#EDE7F6"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

            addView(TextView(this@OverlayService).apply {
                text = "↺ Reached $max-step limit"
                textSize = 12f
                setTextColor(Color.parseColor("#4527A0"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@OverlayService).apply {
                text = "Continue ›"
                textSize = 12f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#5E35B1"))
                    cornerRadius = dp(8).toFloat()
                }
                setOnClickListener {
                    val agent = OverlayBridge.agent ?: return@setOnClickListener
                    limitReachedMax = null
                    appendLine("↺ Continuing…", Color.parseColor("#5E35B1"))
                    isAgentActive = true
                    updateNotification("Continuing…")
                    turnJob?.cancel()
                    turnJob = agent.continueFromLimit()
                        .onEach { render(it) }
                        .catch { e -> render(OverlayReply.Failed(e.message ?: "Error")) }
                        .launchIn(scope)
                    showExpanded()
                }
            })
        }
    }

    private fun buildRetryBar(): LinearLayout? {
        if (!showRetryButton) return null
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(8), dp(8))
            setBackgroundColor(Color.parseColor("#FFEBEE"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

            addView(TextView(this@OverlayService).apply {
                text = "Something went wrong"
                textSize = 12f
                setTextColor(Color.parseColor("#B71C1C"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(TextView(this@OverlayService).apply {
                text = "↺ Retry"
                textSize = 12f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(6), dp(10), dp(6))
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#C62828"))
                    cornerRadius = dp(8).toFloat()
                }
                setOnClickListener {
                    val text = lastUserText ?: return@setOnClickListener
                    showRetryButton = false
                    send(text)
                }
            })
        }
    }

    private fun buildConfirmPanel(pending: String): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(6))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FFF6E0"))
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
        btnRow.addView(confirmBtn("▶ Do it", Color.parseColor("#1B5E20"), Color.parseColor("#388E3C")) { resolveConfirm(OverlayDecision.Proceed) })
        btnRow.addView(spacer(dp(6)))
        btnRow.addView(confirmBtn("◀ Skip", Color.parseColor("#263238"), Color.parseColor("#455A64")) { resolveConfirm(OverlayDecision.Back) })
        btnRow.addView(spacer(dp(6)))
        btnRow.addView(confirmBtn("⛔ Stop", Color.parseColor("#7F0000"), Color.parseColor("#C62828")) { resolveConfirm(OverlayDecision.Stop) })
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
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
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
            pendingSharedText?.let { setText(it); pendingSharedText = null; setSelection(it.length) }
        }
        input.setOnClickListener {
            if (params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0) {
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                rootView?.let { windowManager.updateViewLayout(it, params) }
            }
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
                restoreNotFocusable()
                if (pendingConfirmText != null) resolveConfirm(OverlayDecision.Chat(text))
                else send(text)
            }
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { doSend(); true } else false
        }
        sendBtn.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> v.animate().alpha(0.55f).setDuration(60).start()
                MotionEvent.ACTION_UP -> { v.animate().alpha(1f).setDuration(80).start(); doSend() }
                MotionEvent.ACTION_CANCEL -> v.animate().alpha(1f).setDuration(80).start()
            }
            true
        }
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

    private fun trimTranscript() {
        if (transcript.length > 5000) {
            val keepFrom = transcript.indexOf('\n', 1500).takeIf { it >= 0 } ?: 1500
            transcript.delete(0, keepFrom + 1)
        }
    }

    // ── Turn handling ─────────────────────────────────────────────────────────

    private fun send(text: String) {
        val agent = OverlayBridge.agent ?: run {
            appendLine("⚠ Open the Claw app and sign in first.")
            return
        }
        turnJob?.cancel()
        pendingConfirm?.complete(OverlayDecision.Stop)
        pendingConfirm = null
        pendingConfirmText = null
        lastUserText = text
        limitReachedMax = null
        showRetryButton = false
        appendUserLine(text)
        assistantLineStart = -1
        isAgentActive = true
        updateNotification("Working on: ${text.take(40)}…")
        if (!expanded) showExpanded() else updateActiveStatus()
        turnJob = agent.runTurn(text)
            .onEach { render(it) }
            .catch { e -> render(OverlayReply.Failed(e.message ?: "Something went wrong")) }
            .launchIn(scope)
    }

    private fun render(reply: OverlayReply) {
        when (reply) {
            is OverlayReply.TextDelta -> appendAssistant(reply.text)
            is OverlayReply.ToolStatus -> {
                if (reply.running) {
                    // Ghost: nearly transparent so the agent can see/tap what's behind
                    if (pendingConfirmText == null) {
                        isGhosted = true
                        params.alpha = 0.12f
                        rootView?.let { runCatching { windowManager.updateViewLayout(it, params) } }
                    }
                    statusDotView?.apply {
                        text = "⚙ ${reply.name}…"
                        setTextColor(Color.parseColor("#FFEB3B"))
                    }
                } else {
                    if (isGhosted) {
                        isGhosted = false
                        params.alpha = 0.96f
                        rootView?.let { runCatching { windowManager.updateViewLayout(it, params) } }
                    }
                    if (isAgentActive) {
                        statusDotView?.apply {
                            text = "● thinking…"
                            setTextColor(Color.parseColor("#FFEB3B"))
                        }
                    }
                }
                appendLine(when {
                    reply.isError -> "⚠ ${reply.name} failed"
                    reply.running -> "⚙ ${reply.name}…"
                    else -> "✓ ${reply.name}"
                })
            }
            is OverlayReply.IterationUpdate -> {
                statusDotView?.apply {
                    text = "⚙ ${reply.current}/${reply.max}"
                    setTextColor(Color.parseColor("#FFEB3B"))
                }
            }
            OverlayReply.Done -> {
                isAgentActive = false
                if (isGhosted) {
                    isGhosted = false
                    params.alpha = 0.96f
                    rootView?.let { runCatching { windowManager.updateViewLayout(it, params) } }
                }
                autoHideAfterTask()
            }
            is OverlayReply.LimitReached -> {
                isAgentActive = false
                isGhosted = false
                limitReachedMax = reply.max
                appendLine("↺ Reached ${reply.max}-step limit — tap Continue below")
                updateNotification("Reached step limit — tap to continue.")
                showExpanded()
            }
            is OverlayReply.Failed -> {
                isAgentActive = false
                isGhosted = false
                showRetryButton = lastUserText != null
                appendLine("⚠ ${reply.message}")
                updateNotification("Something went wrong — tap to open.")
                showExpanded()
            }
        }
    }

    private fun autoHideAfterTask() {
        statusDotView?.apply {
            text = "● done"
            setTextColor(Color.parseColor("#2E7D32"))
        }
        updateNotification("Tap to open the panel.")
        rootView?.postDelayed({
            if (!isAgentActive && pendingConfirmText == null) showCollapsed()
        }, 1400)
    }

    private fun updateActiveStatus() {
        statusDotView?.apply {
            text = if (isAgentActive) "● thinking…" else "● ready"
            setTextColor(if (isAgentActive) Color.parseColor("#FFEB3B") else Color.parseColor("#A5D6A7"))
        }
        if (!expanded) showCollapsed()
    }

    // ── Transcript ────────────────────────────────────────────────────────────

    private fun beginAssistantLine() {
        if (transcript.isNotEmpty() && transcript.last() != '\n') transcript.append('\n')
        val prefix = "Claw: "
        val start = transcript.length
        transcript.append(prefix)
        transcript.setSpan(ForegroundColorSpan(Color.parseColor("#7C3E1A")), start, start + prefix.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        assistantLineStart = transcript.length
        flushTranscript()
    }

    private fun appendAssistant(delta: String) {
        if (assistantLineStart < 0) beginAssistantLine()
        transcript.append(delta)
        flushTranscript()
    }

    private fun appendLine(line: String, color: Int = Color.parseColor("#4A4A5A")) {
        if (transcript.isNotEmpty() && transcript.last() != '\n') transcript.append('\n')
        val start = transcript.length
        transcript.append(line).append('\n')
        transcript.setSpan(ForegroundColorSpan(color), start, start + line.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        assistantLineStart = -1
        trimTranscript()
        flushTranscript()
    }

    private fun appendUserLine(text: String) {
        if (transcript.isNotEmpty() && transcript.last() != '\n') transcript.append('\n')
        val prefix = "You: "
        val start = transcript.length
        transcript.append(prefix)
        transcript.setSpan(ForegroundColorSpan(Color.parseColor("#3949AB")), start, start + prefix.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        transcript.append(text).append('\n')
        trimTranscript()
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
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f
        var startX = 0; var startY = 0
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
                    if (dragged) {
                        val screenW = resources.displayMetrics.widthPixels
                        val screenH = resources.displayMetrics.heightPixels
                        val viewW = rootView?.width ?: 0
                        val viewH = rootView?.height ?: 0
                        params.x = (startX + dx).coerceIn(0, (screenW - viewW).coerceAtLeast(0))
                        params.y = (startY + dy).coerceIn(0, (screenH - viewH).coerceAtLeast(0))
                        rootView?.let { windowManager.updateViewLayout(it, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) {
                        if (snapToEdge) {
                            collapsedX = params.x; collapsedY = params.y
                            OverlayBridge.onPuckPositionChanged?.invoke(params.x, params.y)
                            handle.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                        }
                        onTap()
                    } else if (snapToEdge) {
                        snapToNearestEdge(handle)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> { dragged = false; true }
                else -> false
            }
        }
    }

    private fun snapToNearestEdge(view: View) {
        val screenW = resources.displayMetrics.widthPixels
        val viewW = view.width.takeIf { it > 0 } ?: dp(56)
        val targetX = if (params.x + viewW / 2 < screenW / 2) 0 else screenW - viewW
        ValueAnimator.ofInt(params.x, targetX).apply {
            duration = 180
            addUpdateListener { anim ->
                params.x = anim.animatedValue as Int
                collapsedX = params.x
                OverlayBridge.onPuckPositionChanged?.invoke(collapsedX, collapsedY)
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
        const val ACTION_SHARED_TEXT = "com.androidclaw.overlay.SHARED_TEXT"
        const val EXTRA_TEXT = "text"

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
