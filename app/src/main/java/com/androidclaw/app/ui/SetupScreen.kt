package com.androidclaw.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.androidclaw.app.LlmBackend
import com.androidclaw.app.SettingsStore
import kotlinx.coroutines.launch

@Composable
fun SetupScreen(
    settings: SettingsStore,
    isOverlayGranted: () -> Boolean,
    isAccessibilityGranted: () -> Boolean,
    onRequestOverlay: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onSignInAnthropic: () -> Unit,
    onSignInOpenRouter: () -> Unit,
    onConnectAnthropicCode: suspend (String) -> Result<Unit>,
    onContinue: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Re-evaluate permission states every time the app resumes (user may return from Settings).
    val lifecycleOwner = LocalLifecycleOwner.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    val notifOk by remember(tick) {
        derivedStateOf {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }
    }
    val overlayOk by remember(tick) { derivedStateOf { isOverlayGranted() } }
    val accessOk by remember(tick) { derivedStateOf { isAccessibilityGranted() } }
    val authOk by remember(tick) { derivedStateOf { settings.isConfigured } }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { tick++ }

    // Auth form state
    var backend by remember { mutableStateOf(settings.backend) }
    var apiKey by remember { mutableStateOf(settings.anthropicKey.orEmpty()) }
    var showApiKey by remember { mutableStateOf(false) }
    var useOAuth by remember { mutableStateOf(settings.anthropicUseOAuth) }
    var oauthCode by remember { mutableStateOf("") }
    var oauthStatus by remember { mutableStateOf("") }
    var oauthBusy by remember { mutableStateOf(false) }

    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── Header ────────────────────────────────────────────────────────────
            Column(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("🦞", style = MaterialTheme.typography.displayMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "AndroidClaw",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Your AI agent on Android",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            // ── Step 1: Sign in ───────────────────────────────────────────────────
            SetupCard(
                step = 1,
                icon = "🔑",
                title = "Sign in",
                subtitle = "Connect to an AI model to get started",
                done = authOk,
            ) {
                if (!authOk) {
                    // Provider picker
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            Modifier.clickable { backend = LlmBackend.ANTHROPIC },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = backend == LlmBackend.ANTHROPIC, onClick = null)
                            Text("Anthropic", style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(Modifier.width(12.dp))
                        Row(
                            Modifier.clickable { backend = LlmBackend.OPENROUTER },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = backend == LlmBackend.OPENROUTER, onClick = null)
                            Text("OpenRouter", style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    if (backend == LlmBackend.ANTHROPIC) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Row(
                                Modifier.clickable { useOAuth = false },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = !useOAuth, onClick = null)
                                Text("API key", style = MaterialTheme.typography.bodyMedium)
                            }
                            Spacer(Modifier.width(8.dp))
                            Row(
                                Modifier.clickable { useOAuth = true },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = useOAuth, onClick = null)
                                Text("Claude account", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        if (!useOAuth) {
                            OutlinedTextField(
                                value = apiKey,
                                onValueChange = { apiKey = it },
                                label = { Text("Anthropic API key") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                visualTransformation = if (showApiKey) VisualTransformation.None
                                                       else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { showApiKey = !showApiKey }) {
                                        Icon(
                                            if (showApiKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                            contentDescription = if (showApiKey) "Hide key" else "Show key",
                                        )
                                    }
                                },
                            )
                            Text(
                                "Get your key at console.anthropic.com",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            )
                            Button(
                                onClick = {
                                    settings.backend = LlmBackend.ANTHROPIC
                                    settings.anthropicUseOAuth = false
                                    settings.anthropicKey = apiKey
                                    tick++
                                },
                                enabled = apiKey.isNotBlank(),
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Save API key") }
                        } else {
                            Button(
                                onClick = {
                                    settings.backend = LlmBackend.ANTHROPIC
                                    settings.anthropicUseOAuth = true
                                    onSignInAnthropic()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Open Anthropic sign-in") }
                            Text(
                                "After signing in, paste the code shown in your browser below.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                            OutlinedTextField(
                                value = oauthCode,
                                onValueChange = { oauthCode = it },
                                label = { Text("Paste code") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            Button(
                                onClick = {
                                    scope.launch {
                                        oauthBusy = true
                                        val result = onConnectAnthropicCode(oauthCode)
                                        oauthStatus = result.fold(
                                            onSuccess = { "✓ Connected!" },
                                            onFailure = { "Error: ${it.message}" },
                                        )
                                        if (result.isSuccess) {
                                            oauthCode = ""
                                            settings.backend = LlmBackend.ANTHROPIC
                                            settings.anthropicUseOAuth = true
                                            tick++
                                        }
                                        oauthBusy = false
                                    }
                                },
                                enabled = oauthCode.isNotBlank() && !oauthBusy,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(if (oauthBusy) "Connecting…" else "Connect") }
                            if (oauthStatus.isNotEmpty()) {
                                Text(
                                    oauthStatus,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (oauthStatus.startsWith("✓"))
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    } else {
                        Button(
                            onClick = {
                                settings.backend = LlmBackend.OPENROUTER
                                onSignInOpenRouter()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Sign in with OpenRouter") }
                    }
                }
            }

            // ── Step 2: Notifications ─────────────────────────────────────────────
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                SetupCard(
                    step = 2,
                    icon = "🔔",
                    title = "Notifications",
                    subtitle = "Shows a status bar notification while the floating overlay is active",
                    done = notifOk,
                ) {
                    if (!notifOk) {
                        OutlinedButton(
                            onClick = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Allow notifications") }
                    }
                }
            }

            // ── Step 3: Float over other apps ─────────────────────────────────────
            SetupCard(
                step = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) 3 else 2,
                icon = "🪟",
                title = "Float over other apps",
                subtitle = "The 🦞 tab stays visible on top of YouTube, WhatsApp, and anything else",
                done = overlayOk,
            ) {
                if (!overlayOk) {
                    OutlinedButton(
                        onClick = onRequestOverlay,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Grant permission") }
                }
            }

            // ── Step 4: Accessibility ─────────────────────────────────────────────
            val accessStep = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) 4 else 3
            SetupCard(
                step = accessStep,
                icon = "⚙️",
                title = "Screen control",
                subtitle = "Lets Claw tap, type and scroll inside other apps on your behalf",
                done = accessOk,
            ) {
                if (!accessOk) {
                    OutlinedButton(
                        onClick = onRequestAccessibility,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Enable in Accessibility settings") }
                    Text(
                        "Find \"AndroidClaw\" in the list and toggle it on.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    // Android 13+ blocks accessibility for sideloaded APKs with a
                    // "Restricted setting" error. A one-time ADB command clears it.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        Spacer(Modifier.height(8.dp))
                        RestrictedSettingsHint()
                    }
                }
            }

            // ── Start button ──────────────────────────────────────────────────────
            Button(
                onClick = onContinue,
                enabled = authOk,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text(
                    if (authOk) "Start using Claw →" else "Sign in first to continue",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                )
            }

            if (!authOk) {
                Text(
                    "Overlay and screen control can be granted later from Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }
}

@Composable
private fun RestrictedSettingsHint() {
    val clipboard = LocalClipboardManager.current
    val cmd = "adb shell cmd appops set com.androidclaw.app ACCESS_RESTRICTED_SETTINGS allow"
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Seeing \"Restricted setting\"?",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                "Run this once on your PC with USB debugging on:",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    cmd,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { clipboard.setText(AnnotatedString(cmd)) }) {
                    Text("Copy")
                }
            }
            Text(
                "Or use install.sh / install.bat from the GitHub release — they handle this automatically.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun SetupCard(
    step: Int,
    icon: String,
    title: String,
    subtitle: String,
    done: Boolean,
    content: @Composable () -> Unit,
) {
    val stepSymbols = listOf("①", "②", "③", "④", "⑤")
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (done)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            else
                MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (done) 0.dp else 2.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = (stepSymbols.getOrNull(step - 1) ?: "$step.") + " $icon",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.size(36.dp).padding(end = 4.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
                Text(
                    if (done) "✓" else "→",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                    fontWeight = FontWeight.Bold,
                )
            }
            if (done) {
                Text(
                    "Done",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
            } else {
                HorizontalDivider()
                content()
            }
        }
    }
}
