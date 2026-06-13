package com.androidclaw.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.androidclaw.control.ClawAccessibilityService
import com.androidclaw.overlay.OverlayService
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.androidclaw.app.ui.ChatScreen
import com.androidclaw.app.ui.SetupScreen
import com.androidclaw.app.ui.theme.ClawTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val container by lazy { (application as ClawApp).container }
    private val vm: ChatViewModel by viewModels { ChatViewModelFactory(container) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleOAuthCallback(intent)
        setContent {
            ClawTheme {
                var showSetup by remember { mutableStateOf(!container.settings.isConfigured) }
                if (showSetup) {
                    SetupScreen(
                        settings = container.settings,
                        isOverlayGranted = { OverlayService.canDraw(this) },
                        isAccessibilityGranted = { ClawAccessibilityService.isEnabled(this) },
                        onRequestOverlay = { enableOverlay() },
                        onRequestAccessibility = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        onSignInAnthropic = { AnthropicAuth.launchSignIn(this, container.settings) },
                        onSignInOpenRouter = { OpenRouterAuth.launchSignIn(this, container.settings) },
                        onConnectAnthropicCode = { code ->
                            vm.connectAnthropicOAuth(code).also { vm.refreshAuthState() }
                        },
                        onContinue = {
                            vm.refreshAuthState()
                            showSetup = false
                        },
                    )
                } else {
                    ChatScreen(
                        vm = vm,
                        settings = container.settings,
                        onSignInOpenRouter = { OpenRouterAuth.launchSignIn(this, container.settings) },
                        onSignInAnthropic = { AnthropicAuth.launchSignIn(this, container.settings) },
                        onToggleOverlay = { enableOverlay() },
                        onOpenAccessibility = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        isAccessibilityEnabled = { ClawAccessibilityService.isEnabled(this) },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOAuthCallback(intent)
    }

    /**
     * Launches the floating overlay (SPEC §5.4). Requests the "draw over other
     * apps" grant first if it's missing — the user returns and taps again.
     */
    private fun enableOverlay() {
        if (!OverlayService.canDraw(this)) {
            Toast.makeText(this, "Grant \"Display over other apps\", then tap Float again", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
            return
        }
        OverlayService.start(this)
        Toast.makeText(this, "Claw is floating — check the screen edge", Toast.LENGTH_SHORT).show()
    }

    private fun handleOAuthCallback(intent: Intent?) {
        val uri = intent?.data ?: return
        if (!OpenRouterAuth.isCallback(uri)) return
        lifecycleScope.launch {
            val result = OpenRouterAuth.handleCallback(container.http, container.settings, uri)
            val message = result.fold(
                onSuccess = { "Signed in to OpenRouter" },
                onFailure = { "Sign-in failed: ${it.message}" },
            )
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            vm.refreshAuthState()
        }
    }
}

class ChatViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        ChatViewModel(container) as T
}
