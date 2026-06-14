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
import android.content.res.Configuration
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

    private var transcriptView: TextView? = null
    private var scrollView: ScrollView? = null
    private var statusDotView: TextView? = null
    private val transcript = SpannableStringBuilder()
    private var collapsedX: Int = -1
    private var collapsedY: Int = -1

    private var cardWidth: Int = -1
    private var transcriptHeight: Int = -1

    private var pendingConfirmText: String? = null
    private var pendingConfirm: CompletableDeferred<OverlayDecision>? = null

    private var limitReachedMax: Int? = null
    private var showRetryButton = false
    private var lastUserText: String? = null
    private var pendingSharedText: String? = null

    // Resolved per-show from the current theme; used by transcript append helpers.
    private var mutedLineColor = Color.parseColor("#4A4A5A")
    private var userLabelColor = Color.parseColor("#3949AB")
    private var clawLabelColor = Color.parseColor("#7C3E1A")

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        collapsedX = OverlayBridge.initialPuckX
        collapsedY = OverlayBridge.initialPuckY
        cardWidth = OverlayBridge.initialCardWidth
        transcriptHeight = OverlayBridge.initialTranscriptHeight
        startInForeground()
        OverlayBridge.confirmHandler = ::handleConfirm

        // The shared session is the single source of truth: render its transcript
        // verbatim and follow its live events for streaming chrome. A task started
        // in the app streams straight into this overlay because both observe it.
        OverlayBridge.session?.let { session ->
            session.transcript.onEach { renderTranscript(it) }.launchIn(scope)
            session.events.onEach { render(it) }.launchIn(scope)
            session.state.onEach { st ->
                isAgentActive = st.busy
                if (expanded) updateActiveStatus()
            }.launchIn(scope)
        }
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
        scope.cancel()
        rootView?.let { runCatching { windowManager.removeView(it) } }
        rootView = null
        super.onDestroy()
    }

    // ── Theme ─────────────────────────────────────────────────────────────────

    private class Palette(
        val panelBg: Int, val panelStroke: Int, val transcriptBg: Int,
        val textColor: Int, val mutedText: Int,
        val inputBarBg: Int, val inputFieldBg: Int, val inputStroke: Int, val hint: Int, val inputText: Int,
        val headerEnd: Int, val divider: Int,
        val userLabel: Int, val clawLabel: Int, val collapseBtn: Int,
    )

    private fun lightPalette() = Palette(
        panelBg = Color.parseColor("#F4F5FB"), panelStroke = Color.parseColor("#D6D8E5"),
        transcriptBg = Color.parseColor("#FFFFFF"),
        textColor = Color.parseColor("#2A2A35"), mutedText = Color.parseColor("#4A4A5A"),
        inputBarBg = Color.parseColor("#ECEDF5"), inputFieldBg = Color.parseColor("#FFFFFF"),
        inputStroke = Color.parseColor("#D0D2E0"), hint = Color.parseColor("#9A9AAE"), inputText = Color.parseColor("#1E1E28"),
        headerEnd = Color.parseColor("#7366BD"), divider = Color.parseColor("#E0E2EC"),
        userLabel = Color.parseColor("#3949AB"), clawLabel = Color.parseColor("#7C3E1A"),
        collapseBtn = Color.parseColor("#FFCCBC"),
    )

    private fun darkPalette() = Palette(
        panelBg = Color.parseColor("#23242B"), panelStroke = Color.parseColor("#3A3B45"),
        transcriptBg = Color.parseColor("#1A1B20"),
        textColor = Color.parseColor("#E6E6EC"), mutedText = Color.parseColor("#B8B8C4"),
        inputBarBg = Color.parseColor("#2A2B33"), inputFieldBg = Color.parseColor("#1F2026"),
        inputStroke = Color.parseColor("#3A3B45"), hint = Color.parseColor("#80808E"), inputText = Color.parseColor("#ECECF2"),
        headerEnd = Color.parseColor("#5560B0"), divider = Color.parseColor("#33343D"),
        userLabel = Color.parseColor("#9FA8DA"), clawLabel = Color.parseColor("#E0A87C"),
        collapseBtn = Color.parseColor("#FFFFFF"),
    )

    private fun resolvePalette(): Palette {
        val dark = when (OverlayBridge.theme.mode) {
            OverlayThemeMode.DARK -> true
            OverlayThemeMode.LIGHT -> false
            OverlayThemeMode.AUTO ->
                (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        val p = if (dark) darkPalette() else lightPalette()
        mutedLineColor = p.mutedText
        userLabelColor = p.userLabel
        clawLabelColor = p.clawLabel
        return p
    }

    private fun darken(color: Int, f: Float): Int = Color.rgb(
        (Color.red(color) * f).roundToInt().coerceIn(0, 255),
        (Color.green(color) * f).roundToInt().coerceIn(0, 255),
        (Color.blue(color) * f).roundToInt().coerceIn(0, 255),
    )

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
        val theme = OverlayBridge.theme
        params.width = WindowManager.LayoutParams.WRAP_CONTENT
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        params.alpha = if (isAgentActive) (theme.puckAlpha + 0.3f).coerceAtMost(1f) else theme.puckAlpha

        if (collapsedX >= 0) params.x = collapsedX
        if (collapsedY >= 0) params.y = collapsedY

        val ringColor = if (isAgentActive) theme.accent else darken(theme.accent, 0.85f)
        val fillColor = (0x33000000.toInt()) or (theme.accent and 0x00FFFFFF)

        val sz = dp(56)
        val puck = TextView(this).apply {
            text = theme.puckGlyph
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
        val theme = OverlayBridge.theme
        val p = resolvePalette()
        val screenW = resources.displayMetrics.widthPixels
        params.width = if (cardWidth > 0) cardWidth.coerceIn(dp(260), screenW) else dp(320)
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        params.alpha = theme.panelAlpha

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(p.panelBg)
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), p.panelStroke)
            }
            elevation = dp(20).toFloat()
            clipToOutline = true
        }
        card.setOnTouchListener { _, _ -> restoreNotFocusable(); false }

        card.addView(buildHeader(p, theme.accent))
        card.addView(buildTranscriptArea(p))
        buildContinueBar()?.let { card.addView(it) }
        buildRetryBar()?.let { card.addView(it) }
        card.addView(divider(p))
        val pending = pendingConfirmText
        if (pending != null) {
            card.addView(buildConfirmPanel(pending))
            card.addView(divider(p))
        }
        card.addView(buildInputRow(pending, p, theme.accent))
        card.addView(buildResizeHandle(p))

        setRoot(card)
        // Re-render with the freshly resolved palette and the latest history.
        OverlayBridge.session?.let { renderTranscript(it.transcript.value) }

        card.alpha = 0f; card.scaleX = 0.96f; card.scaleY = 0.96f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(140).start()
    }

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
        attachDragAndTap(titleText, snapToEdge = false) { }
        titleBar.addView(titleText)
        titleBar.addView(TextView(this).apply {
            text = "▿"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            val sz = dp(36)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            setOnClickListener { showExpanded() }
        })
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

    private fun buildHeader(p: Palette, accent: Int): LinearLayout {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(6), dp(10))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(accent, p.headerEnd),
            ).apply {
                cornerRadii = floatArrayOf(dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), dp(20).toFloat(), 0f, 0f, 0f, 0f)
            }
        }
        val title = TextView(this).apply {
            text = "${OverlayBridge.theme.puckGlyph} Claw"
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        attachDragAndTap(title, snapToEdge = false) { }

        val dot = TextView(this).apply {
            text = if (isAgentActive) "● thinking…" else "● ready"
            textSize = 10f
            setTextColor(if (isAgentActive) Color.parseColor("#FFEB3B") else Color.parseColor("#C8E6C9"))
            setPadding(0, 0, dp(4), 0)
        }
        statusDotView = dot

        fun headerBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            val sz = dp(34)
            layoutParams = LinearLayout.LayoutParams(sz, sz)
            setOnClickListener { onClick() }
        }

        header.addView(title)
        header.addView(dot)
        if (isAgentActive) {
            header.addView(headerBtn("◼") {
                OverlayBridge.session?.stop()
                isAgentActive = false; isGhosted = false
                updateNotification("Tap to open.")
                autoHideAfterTask()
            })
        }
        header.addView(headerBtn("⧉") {
            val clip = ClipData.newPlainText("Claw conversation", transcript.toString())
            getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        })
        header.addView(headerBtn("✕") { stopSelf() })
        header.addView(headerBtn("▾") { showCollapsed() })
        return header
    }

    private fun buildTranscriptArea(p: Palette): ScrollView {
        val screenH = resources.displayMetrics.heightPixels
        val h = if (transcriptHeight > 0) transcriptHeight.coerceIn(dp(100), dp(420))
        else (screenH * 0.28f).roundToInt().coerceIn(dp(120), dp(260))
        val tv = TextView(this).apply {
            text = if (transcript.isEmpty()) "Ask Claw something…" else transcript
            textSize = 12.5f
            setTextColor(p.textColor)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        transcriptView = tv
        return ScrollView(this).apply {
            setBackgroundColor(p.transcriptBg)
            addView(tv)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h)
            setOnTouchListener { _, _ -> restoreNotFocusable(); false }
        }.also { scrollView = it }
    }

    /** Bottom-right grip: drag to resize card width + transcript height; persisted. */
    private fun buildResizeHandle(p: Palette): TextView {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f; var downY = 0f
        var startW = 0; var startH = 0
        val screenW = resources.displayMetrics.widthPixels
        return TextView(this).apply {
            text = "⇲"
            textSize = 14f
            setTextColor(p.mutedText)
            gravity = Gravity.CENTER or Gravity.END
            setPadding(0, dp(2), dp(10), dp(4))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX; downY = event.rawY
                        startW = params.width
                        startH = scrollView?.height ?: ((resources.displayMetrics.heightPixels * 0.28f).roundToInt())
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dw = (event.rawX - downX).roundToInt()
                        val dh = (event.rawY - downY).roundToInt()
                        if (abs(dw) > slop || abs(dh) > slop) {
                            cardWidth = (startW + dw).coerceIn(dp(260), (screenW - dp(8)).coerceAtLeast(dp(260)))
                            transcriptHeight = (startH + dh).coerceIn(dp(100), dp(420))
                            params.width = cardWidth
                            scrollView?.let {
                                it.layoutParams = (it.layoutParams as LinearLayout.LayoutParams).apply { height = transcriptHeight }
                                it.requestLayout()
                            }
                            rootView?.let { v -> runCatching { windowManager.updateViewLayout(v, params) } }
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        OverlayBridge.onCardSizeChanged?.invoke(cardWidth, transcriptHeight)
                        true
                    }
                    else -> false
                }
            }
        }
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
                text = "↺ Reached $max-action limit"
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
                    val session = OverlayBridge.session ?: return@setOnClickListener
                    limitReachedMax = null
                    isAgentActive = true
                    updateNotification("Continuing…")
                    session.continueFromLimit()
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

    private fun buildInputRow(pending: String?, p: Palette, accent: Int): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            background = GradientDrawable().apply { setColor(p.inputBarBg) }
        }
        val input = EditText(this).apply {
            hint = when {
                OverlayBridge.session == null -> "Open Claw app and sign in"
                pending != null -> "Tell Claw what to do instead…"
                else -> "Ask Claw something…"
            }
            setHintTextColor(p.hint)
            textSize = 13f
            setTextColor(p.inputText)
            isEnabled = OverlayBridge.session != null
            maxLines = 3
            background = GradientDrawable().apply {
                setColor(p.inputFieldBg)
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), p.inputStroke)
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
                intArrayOf(accent, darken(accent, 0.78f)),
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

    private fun divider(p: Palette) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        setBackgroundColor(p.divider)
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
        val session = OverlayBridge.session ?: run {
            appendLine("⚠ Open the Claw app and sign in first.")
            return
        }
        pendingConfirm?.complete(OverlayDecision.Stop)
        pendingConfirm = null
        pendingConfirmText = null
        lastUserText = text
        limitReachedMax = null
        showRetryButton = false
        isAgentActive = true
        updateNotification("Working on: ${text.take(40)}…")
        if (!expanded) showExpanded() else updateActiveStatus()
        // The session owns the turn; its transcript/events stream back into us.
        session.send(text)
    }

    // Chrome only — the conversation text is rendered from session.transcript.
    private fun render(reply: OverlayReply) {
        when (reply) {
            is OverlayReply.TextDelta -> { /* text comes from the transcript */ }
            is OverlayReply.ToolStatus -> {
                if (reply.running) {
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
                        params.alpha = OverlayBridge.theme.panelAlpha
                        rootView?.let { runCatching { windowManager.updateViewLayout(it, params) } }
                    }
                    if (isAgentActive) {
                        statusDotView?.apply {
                            text = "● thinking…"
                            setTextColor(Color.parseColor("#FFEB3B"))
                        }
                    }
                }
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
                    params.alpha = OverlayBridge.theme.panelAlpha
                    rootView?.let { runCatching { windowManager.updateViewLayout(it, params) } }
                }
                autoHideAfterTask()
            }
            is OverlayReply.LimitReached -> {
                isAgentActive = false
                isGhosted = false
                limitReachedMax = reply.max
                updateNotification("Reached action limit — tap to continue.")
                showExpanded()
            }
            is OverlayReply.Failed -> {
                isAgentActive = false
                isGhosted = false
                showRetryButton = lastUserText != null
                updateNotification("Something went wrong — tap to open.")
                showExpanded()
            }
        }
    }

    private fun autoHideAfterTask() {
        statusDotView?.apply {
            text = "● done"
            setTextColor(Color.parseColor("#A5D6A7"))
        }
        updateNotification("Tap to open the panel.")
        rootView?.postDelayed({
            if (!isAgentActive && pendingConfirmText == null) showCollapsed()
        }, 1400)
    }

    private fun updateActiveStatus() {
        statusDotView?.apply {
            text = if (isAgentActive) "● thinking…" else "● ready"
            setTextColor(if (isAgentActive) Color.parseColor("#FFEB3B") else Color.parseColor("#C8E6C9"))
        }
        if (!expanded) showCollapsed()
    }

    // ── Transcript ────────────────────────────────────────────────────────────

    /**
     * Rebuilds the visible transcript from the shared session's entries. This is
     * the only writer of conversation text, so the overlay always shows exactly
     * what the app shows — including a task that began in the app and popped here.
     */
    private fun renderTranscript(entries: List<SessionEntry>) {
        transcript.clear()
        for (entry in entries) {
            when (entry) {
                is SessionEntry.User -> labeledLine("You: ", userLabelColor, entry.text)
                is SessionEntry.Assistant ->
                    if (entry.text.isNotBlank()) labeledLine("Claw: ", clawLabelColor, entry.text)
                is SessionEntry.Tool -> mutedLine(
                    when {
                        entry.isError -> "⚠ ${entry.name} failed"
                        entry.running -> "⚙ ${entry.name}…"
                        else -> "✓ ${entry.name}"
                    },
                )
                is SessionEntry.Error -> mutedLine("⚠ ${entry.text}")
                is SessionEntry.LimitReached ->
                    mutedLine("↺ Reached ${entry.max}-action limit — tap Continue below")
            }
        }
        trimTranscript()
        flushTranscript()
    }

    private fun labeledLine(prefix: String, labelColor: Int, body: String) {
        if (transcript.isNotEmpty() && transcript.last() != '\n') transcript.append('\n')
        val start = transcript.length
        transcript.append(prefix)
        transcript.setSpan(ForegroundColorSpan(labelColor), start, start + prefix.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        transcript.append(body).append('\n')
    }

    private fun mutedLine(line: String) {
        if (transcript.isNotEmpty() && transcript.last() != '\n') transcript.append('\n')
        val start = transcript.length
        transcript.append(line).append('\n')
        transcript.setSpan(ForegroundColorSpan(mutedLineColor), start, start + line.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    /** One-off system note (e.g. not-signed-in) that isn't part of the session. */
    private fun appendLine(line: String, color: Int = mutedLineColor) {
        if (transcript.isNotEmpty() && transcript.last() != '\n') transcript.append('\n')
        val start = transcript.length
        transcript.append(line).append('\n')
        transcript.setSpan(ForegroundColorSpan(color), start, start + line.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
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

        /** Start (if needed) and pop the expanded card — used to auto-show a running task. */
        fun show(context: Context) {
            val i = Intent(context, OverlayService::class.java).setAction(ACTION_SHOW)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, OverlayService::class.java).setAction(ACTION_STOP))
        }
    }
}
