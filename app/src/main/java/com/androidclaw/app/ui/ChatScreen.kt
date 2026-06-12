package com.androidclaw.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.material3.RadioButton
import com.androidclaw.app.ChatItem
import com.androidclaw.app.ChatViewModel
import com.androidclaw.app.LlmBackend
import com.androidclaw.app.SettingsStore
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    settings: SettingsStore,
    onSignInOpenRouter: () -> Unit,
    onSignInAnthropic: () -> Unit,
    onToggleOverlay: () -> Unit,
    onOpenAccessibility: () -> Unit,
    isAccessibilityEnabled: () -> Boolean,
) {
    var showSettings by remember { mutableStateOf(vm.needsAuth) }
    var showPlaybook by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(vm.items.size) {
        if (vm.items.isNotEmpty()) listState.animateScrollToItem(vm.items.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AndroidClaw") },
                actions = {
                    IconButton(onClick = { showPlaybook = true }) {
                        Icon(Icons.Default.List, contentDescription = "Playbook")
                    }
                    IconButton(onClick = onToggleOverlay) {
                        Icon(Icons.Default.Face, contentDescription = "Float Claw overlay")
                    }
                    IconButton(onClick = { vm.clearChat() }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear chat")
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        modifier = Modifier.imePadding(),
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(vm.items) { item -> ChatBubble(item) }
            }

            vm.pendingStep?.let { step ->
                StepBar(
                    action = step,
                    onForward = { vm.stepForward() },
                    onBack = { vm.stepBack() },
                    onChat = { vm.stepChat(it) },
                    onStop = { vm.stepStop() },
                )
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (vm.needsAuth) "Sign in or set an API key first" else "Ask AndroidClaw…") },
                    enabled = !vm.needsAuth && vm.pendingStep == null,
                    maxLines = 4,
                )
                if (vm.busy) {
                    TextButton(onClick = { vm.cancelTurn() }) { Text("Stop") }
                } else {
                    IconButton(
                        onClick = {
                            vm.send(input)
                            input = ""
                        },
                        enabled = input.isNotBlank() && !vm.needsAuth,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }

    if (showPlaybook) {
        PlaybookScreen(
            sessions = vm.sessions,
            onSelectSession = { session ->
                vm.loadSession(session)
                showPlaybook = false
            },
            onDeleteSession = { vm.deleteSession(it) },
            onUseQuickAction = { prompt ->
                input = prompt
                showPlaybook = false
            },
            onBack = { showPlaybook = false },
        )
        return
    }

    if (showSettings) {
        SettingsDialog(
            settings = settings,
            onSignInOpenRouter = {
                showSettings = false
                onSignInOpenRouter()
            },
            onSignInAnthropic = onSignInAnthropic,
            onConnectAnthropicCode = { code -> vm.connectAnthropicOAuth(code) },
            onOpenAccessibility = onOpenAccessibility,
            isAccessibilityEnabled = isAccessibilityEnabled,
            onDismiss = {
                showSettings = false
                vm.refreshAuthState()
            },
        )
    }
}

@Composable
private fun ChatBubble(item: ChatItem) {
    when (item) {
        is ChatItem.User -> Bubble(
            text = item.text,
            alignEnd = true,
            container = MaterialTheme.colorScheme.primaryContainer,
        )
        is ChatItem.Assistant -> {
            val clipboard = LocalClipboardManager.current
            Bubble(
                text = item.text + if (item.streaming) " ▌" else "",
                alignEnd = false,
                container = MaterialTheme.colorScheme.surfaceVariant,
                onLongClick = if (!item.streaming) ({ clipboard.setText(AnnotatedString(item.text)) }) else null,
            )
        }
        is ChatItem.ToolUse -> Text(
            text = when {
                item.running -> "⚙ ${item.name}…"
                item.isError -> "⚠ ${item.name} failed"
                else -> "✓ ${item.name}"
            },
            style = MaterialTheme.typography.labelMedium,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(start = 8.dp),
        )
        is ChatItem.Error -> Bubble(
            text = item.text,
            alignEnd = false,
            container = MaterialTheme.colorScheme.errorContainer,
        )
    }
}

/** The step-through "game controller": forward / back / chat / stop. */
@Composable
private fun StepBar(
    action: String,
    onForward: () -> Unit,
    onBack: () -> Unit,
    onChat: (String) -> Unit,
    onStop: () -> Unit,
) {
    var chatMode by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }

    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Claw wants to: $action",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            if (chatMode) {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Tell Claw what to do instead…") },
                    maxLines = 3,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { chatMode = false }) { Text("Cancel") }
                    Button(
                        onClick = { onChat(note); note = ""; chatMode = false },
                        enabled = note.isNotBlank(),
                    ) { Text("Send") }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = onForward) { Text("▶ Do it") }
                    OutlinedButton(onClick = onBack) { Text("◀ Back") }
                    TextButton(onClick = { chatMode = true }) { Text("Chat") }
                    TextButton(onClick = onStop) { Text("Stop") }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    text: String,
    alignEnd: Boolean,
    container: androidx.compose.ui.graphics.Color,
    onLongClick: (() -> Unit)? = null,
) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart) {
        Text(
            text = text,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(container, RoundedCornerShape(16.dp))
                .then(
                    if (onLongClick != null)
                        Modifier.combinedClickable(onClick = {}, onLongClick = onLongClick)
                    else Modifier
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun SettingsDialog(
    settings: SettingsStore,
    onSignInOpenRouter: () -> Unit,
    onSignInAnthropic: () -> Unit,
    onConnectAnthropicCode: suspend (String) -> Result<Unit>,
    onOpenAccessibility: () -> Unit,
    isAccessibilityEnabled: () -> Boolean,
    onDismiss: () -> Unit,
) {
    var backend by remember { mutableStateOf(settings.backend) }
    var anthropicKey by remember { mutableStateOf(settings.anthropicKey.orEmpty()) }
    var anthropicModel by remember { mutableStateOf(settings.anthropicModel) }
    var openRouterModel by remember { mutableStateOf(settings.openRouterModel) }
    var stepThrough by remember { mutableStateOf(settings.stepThrough) }
    val openRouterConnected = settings.openRouterKey != null

    // Anthropic OAuth sub-section
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
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BackendOption(
                    label = "Anthropic",
                    selected = backend == LlmBackend.ANTHROPIC,
                    onSelect = { backend = LlmBackend.ANTHROPIC },
                )
                BackendOption(
                    label = "OpenRouter (sign in)",
                    selected = backend == LlmBackend.OPENROUTER,
                    onSelect = { backend = LlmBackend.OPENROUTER },
                )

                if (backend == LlmBackend.ANTHROPIC) {
                    // Auth method sub-selector
                    Row(
                        Modifier.padding(start = 8.dp),
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
                        )
                    } else {
                        // OAuth section
                        val connected = settings.anthropicOAuthToken != null
                        Text(
                            if (connected) "✓ Connected via Claude account" else "Not connected",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (connected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface,
                        )
                        Button(onClick = { onSignInAnthropic() }) {
                            Text("Open Anthropic sign-in")
                        }
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
                        ) {
                            Text(if (oauthConnecting) "Connecting…" else "Connect")
                        }
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
                    )
                } else {
                    Text(
                        if (openRouterConnected) "✓ Connected to OpenRouter" else "Not connected",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = {
                        save()
                        onSignInOpenRouter()
                    }) {
                        Text(if (openRouterConnected) "Sign in again" else "Sign in with OpenRouter")
                    }
                    OutlinedTextField(
                        value = openRouterModel,
                        onValueChange = { openRouterModel = it },
                        label = { Text("Model") },
                        singleLine = true,
                    )
                }
                HorizontalDivider()
                val screenControlOn = isAccessibilityEnabled()
                Text(
                    if (screenControlOn) "✓ Screen control enabled"
                    else "Screen control off — Claw can't tap inside other apps yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (screenControlOn) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                )
                TextButton(onClick = onOpenAccessibility) {
                    Text(if (screenControlOn) "Open Accessibility settings" else "Enable screen control")
                }

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

                Text(
                    "Stored encrypted on this device only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                save()
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun BackendOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
