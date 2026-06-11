package com.androidclaw.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.material3.RadioButton
import com.androidclaw.app.ChatItem
import com.androidclaw.app.ChatViewModel
import com.androidclaw.app.LlmBackend
import com.androidclaw.app.SettingsStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    settings: SettingsStore,
    onSignInOpenRouter: () -> Unit,
) {
    var showSettings by remember { mutableStateOf(vm.needsAuth) }
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

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (vm.needsAuth) "Sign in or set an API key first" else "Ask AndroidClaw…") },
                    enabled = !vm.needsAuth,
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

    if (showSettings) {
        SettingsDialog(
            settings = settings,
            onSignInOpenRouter = {
                showSettings = false
                onSignInOpenRouter()
            },
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
        is ChatItem.Assistant -> Bubble(
            text = item.text + if (item.streaming) " ▌" else "",
            alignEnd = false,
            container = MaterialTheme.colorScheme.surfaceVariant,
        )
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

@Composable
private fun Bubble(text: String, alignEnd: Boolean, container: androidx.compose.ui.graphics.Color) {
    Box(Modifier.fillMaxWidth(), contentAlignment = if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart) {
        Text(
            text = text,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(container, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun SettingsDialog(
    settings: SettingsStore,
    onSignInOpenRouter: () -> Unit,
    onDismiss: () -> Unit,
) {
    var backend by remember { mutableStateOf(settings.backend) }
    var anthropicKey by remember { mutableStateOf(settings.anthropicKey.orEmpty()) }
    var anthropicModel by remember { mutableStateOf(settings.anthropicModel) }
    var openRouterModel by remember { mutableStateOf(settings.openRouterModel) }
    val openRouterConnected = settings.openRouterKey != null

    fun save() {
        settings.backend = backend
        settings.anthropicKey = anthropicKey
        settings.anthropicModel = anthropicModel
        settings.openRouterModel = openRouterModel
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BackendOption(
                    label = "Anthropic API key",
                    selected = backend == LlmBackend.ANTHROPIC,
                    onSelect = { backend = LlmBackend.ANTHROPIC },
                )
                BackendOption(
                    label = "OpenRouter (sign in)",
                    selected = backend == LlmBackend.OPENROUTER,
                    onSelect = { backend = LlmBackend.OPENROUTER },
                )

                if (backend == LlmBackend.ANTHROPIC) {
                    OutlinedTextField(
                        value = anthropicKey,
                        onValueChange = { anthropicKey = it },
                        label = { Text("Anthropic API key") },
                        singleLine = true,
                    )
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
