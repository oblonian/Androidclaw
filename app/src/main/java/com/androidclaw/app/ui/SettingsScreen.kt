package com.androidclaw.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import kotlin.math.roundToInt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.androidclaw.app.LlmBackend
import com.androidclaw.app.SettingsStore
import com.androidclaw.app.applyOverlaySettings
import kotlinx.coroutines.launch

private val ACCENT_PRESETS = listOf(
    0xFFD2512A, 0xFF5560B0, 0xFF2E7D32, 0xFF7E57C2,
    0xFF1E88E5, 0xFFD81B60, 0xFF00897B, 0xFFF59E0B,
).map { it.toInt() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: SettingsStore,
    onSignInOpenRouter: () -> Unit,
    onSignInAnthropic: () -> Unit,
    onConnectAnthropicCode: suspend (String) -> Result<Unit>,
    onOpenAccessibility: () -> Unit,
    isAccessibilityEnabled: () -> Boolean,
    onSignOut: () -> Unit,
    onClose: () -> Unit,
) {
    var backend by remember { mutableStateOf(settings.backend) }
    var anthropicKey by remember { mutableStateOf(settings.anthropicKey.orEmpty()) }
    var anthropicModel by remember { mutableStateOf(settings.anthropicModel) }
    var openRouterModel by remember { mutableStateOf(settings.openRouterModel) }
    var stepThrough by remember { mutableStateOf(settings.stepThrough) }
    var maxIterations by remember { mutableStateOf(settings.maxIterations) }
    var overlayTheme by remember { mutableStateOf(settings.overlayThemeMode) }
    var overlayAccent by remember { mutableStateOf(settings.overlayAccent) }
    var panelOpacity by remember { mutableStateOf(settings.overlayPanelOpacity) }
    var puckOpacity by remember { mutableStateOf(settings.overlayPuckOpacity) }
    var puckGlyph by remember { mutableStateOf(settings.overlayPuckGlyph) }
    val openRouterConnected = settings.openRouterKey != null

    var anthropicUseOAuth by remember { mutableStateOf(settings.anthropicUseOAuth) }
    var oauthCode by remember { mutableStateOf("") }
    var oauthConnecting by remember { mutableStateOf(false) }
    var oauthMessage by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun save() {
        settings.backend = backend
        settings.anthropicKey = anthropicKey
        settings.anthropicModel = anthropicModel
        settings.openRouterModel = openRouterModel
        settings.anthropicUseOAuth = anthropicUseOAuth
        settings.stepThrough = stepThrough
        settings.maxIterations = maxIterations
        settings.overlayThemeMode = overlayTheme
        settings.overlayAccent = overlayAccent
        settings.overlayPanelOpacity = panelOpacity
        settings.overlayPuckOpacity = puckOpacity
        settings.overlayPuckGlyph = puckGlyph
        applyOverlaySettings(settings)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = { save(); onClose() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── Model provider ────────────────────────────────────────────────────
            SettingsSection(title = "AI provider") {
                BackendOption(
                    label = "Anthropic",
                    selected = backend == LlmBackend.ANTHROPIC,
                    onSelect = { backend = LlmBackend.ANTHROPIC },
                )
                BackendOption(
                    label = "OpenRouter",
                    selected = backend == LlmBackend.OPENROUTER,
                    onSelect = { backend = LlmBackend.OPENROUTER },
                )

                if (backend == LlmBackend.ANTHROPIC) {
                    Row(
                        Modifier.padding(start = 4.dp, top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = !anthropicUseOAuth, onClick = { anthropicUseOAuth = false })
                        Text("API key", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.width(16.dp))
                        RadioButton(selected = anthropicUseOAuth, onClick = { anthropicUseOAuth = true })
                        Text("Claude account", style = MaterialTheme.typography.bodyMedium)
                    }

                    if (!anthropicUseOAuth) {
                        OutlinedTextField(
                            value = anthropicKey,
                            onValueChange = { anthropicKey = it },
                            label = { Text("Anthropic API key") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        val connected = settings.anthropicOAuthToken != null
                        Text(
                            if (connected) "✓ Connected via Claude account" else "Not connected",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (connected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                        OutlinedButton(
                            onClick = { onSignInAnthropic() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Open Anthropic sign-in") }
                        Text(
                            "After signing in, copy the code shown in the browser and paste it below.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        OutlinedTextField(
                            value = oauthCode,
                            onValueChange = { oauthCode = it },
                            label = { Text("Paste auth code") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    oauthConnecting = true
                                    val result = onConnectAnthropicCode(oauthCode)
                                    oauthMessage = result.fold(
                                        onSuccess = { "✓ Connected!" },
                                        onFailure = { "Failed: ${it.message}" },
                                    )
                                    oauthConnecting = false
                                    if (result.isSuccess) oauthCode = ""
                                }
                            },
                            enabled = oauthCode.isNotBlank() && !oauthConnecting,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(if (oauthConnecting) "Connecting…" else "Connect") }
                        if (oauthMessage.isNotEmpty()) {
                            Text(
                                oauthMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (oauthMessage.startsWith("✓"))
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.error,
                            )
                        }
                    }

                    OutlinedTextField(
                        value = anthropicModel,
                        onValueChange = { anthropicModel = it },
                        label = { Text("Model") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(
                        if (openRouterConnected) "✓ Connected to OpenRouter" else "Not connected",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(
                        onClick = { save(); onSignInOpenRouter() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (openRouterConnected) "Sign in again" else "Sign in with OpenRouter")
                    }
                    OutlinedTextField(
                        value = openRouterModel,
                        onValueChange = { openRouterModel = it },
                        label = { Text("Model") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // ── Screen control ────────────────────────────────────────────────────
            SettingsSection(title = "Screen control") {
                val screenControlOn = isAccessibilityEnabled()
                Text(
                    if (screenControlOn) "✓ Enabled — Claw can tap inside other apps"
                    else "Off — Claw can't tap inside other apps yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (screenControlOn) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                )
                OutlinedButton(
                    onClick = onOpenAccessibility,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (screenControlOn) "Open Accessibility settings" else "Enable screen control")
                }
            }

            // ── Behaviour ─────────────────────────────────────────────────────────
            SettingsSection(title = "Behaviour") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Step-through mode", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Approve each action in another app one at a time",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    Switch(checked = stepThrough, onCheckedChange = { stepThrough = it })
                }
                Column {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Max actions per turn", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Taps, typing and app launches per turn. Reading the screen is free.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        Text(
                            "$maxIterations",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Slider(
                        value = maxIterations.toFloat(),
                        onValueChange = { maxIterations = it.roundToInt() },
                        valueRange = 4f..20f,
                        steps = 15,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (maxIterations > 12) {
                        Text(
                            "Higher limits use more API credits per task",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            // ── Overlay appearance ────────────────────────────────────────────────
            SettingsSection(title = "Overlay appearance") {
                OverlayPreview(accent = overlayAccent, glyph = puckGlyph, panelAlpha = panelOpacity)

                Text("Theme", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf("LIGHT" to "Light", "DARK" to "Dark", "AUTO" to "Auto").forEach { (value, label) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = overlayTheme == value, onClick = { overlayTheme = value })
                            Text(label, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                Text("Accent", style = MaterialTheme.typography.bodyMedium)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    ACCENT_PRESETS.forEach { argb ->
                        val selected = overlayAccent == argb
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(argb))
                                .border(
                                    width = if (selected) 3.dp else 1.dp,
                                    color = if (selected) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.outlineVariant,
                                    shape = CircleShape,
                                )
                                .clickable { overlayAccent = argb },
                        )
                    }
                }

                Text("Panel opacity  ${(panelOpacity * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = panelOpacity,
                    onValueChange = { panelOpacity = it },
                    valueRange = 0.6f..1f,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("Puck opacity  ${(puckOpacity * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = puckOpacity,
                    onValueChange = { puckOpacity = it },
                    valueRange = 0.3f..1f,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = puckGlyph,
                    onValueChange = { puckGlyph = it.take(2) },
                    label = { Text("Puck icon (emoji)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Text(
                "🔒 Keys and tokens are stored encrypted on this device only.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = { save(); onClose() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save & close") }

            if (settings.isConfigured) {
                OutlinedButton(
                    onClick = { settings.signOut(); onSignOut() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Sign out") }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            ),
        ) {
            Column(
                Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) { content() }
        }
    }
}

@Composable
private fun OverlayPreview(accent: Int, glyph: String, panelAlpha: Float) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Puck preview
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color(accent).copy(alpha = 0.2f))
                .border(2.dp, Color(accent), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text(glyph, style = MaterialTheme.typography.titleMedium) }

        // Card header preview
        Box(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(accent).copy(alpha = panelAlpha))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text("$glyph Claw", color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun BackendOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
