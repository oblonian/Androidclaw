package com.androidclaw.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.androidclaw.app.ChatItem
import com.androidclaw.app.ChatViewModel
import com.androidclaw.app.SettingsStore
import kotlinx.coroutines.launch

private val EXAMPLE_PROMPTS = listOf(
    "Open YouTube and search for lo-fi beats",
    "Summarise my latest unread email",
    "What's on my screen right now?",
)

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
    onSignOut: () -> Unit,
) {
    var showSettings by remember { mutableStateOf(vm.needsAuth) }
    var showPlaybook by remember { mutableStateOf(false) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    // ── Sub-screens (full-screen, rendered instead of chat) ───────────────────
    if (showSettings) {
        SettingsScreen(
            settings = settings,
            onSignInOpenRouter = {
                showSettings = false
                onSignInOpenRouter()
            },
            onSignInAnthropic = onSignInAnthropic,
            onConnectAnthropicCode = { code -> vm.connectAnthropicOAuth(code) },
            onOpenAccessibility = onOpenAccessibility,
            isAccessibilityEnabled = isAccessibilityEnabled,
            onSignOut = { showSettings = false; onSignOut() },
            onClose = {
                showSettings = false
                vm.refreshAuthState()
            },
        )
        return
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

    LaunchedEffect(vm.items.size) {
        if (vm.items.isNotEmpty()) listState.animateScrollToItem(vm.items.size - 1)
    }

    val copyToClipboard: (String) -> Unit = { text ->
        clipboard.setText(AnnotatedString(text))
        scope.launch { snackbarHostState.showSnackbar("Copied to clipboard") }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🦞 ", style = MaterialTheme.typography.titleLarge)
                        Text("AndroidClaw")
                    }
                },
                actions = {
                    IconButton(onClick = { showPlaybook = true }) {
                        Icon(Icons.Default.History, contentDescription = "Playbook & history")
                    }
                    IconButton(onClick = onToggleOverlay) {
                        Icon(Icons.Default.PictureInPictureAlt, contentDescription = "Float Claw overlay")
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Clear chat") },
                            onClick = {
                                menuOpen = false
                                if (vm.items.isNotEmpty()) showClearConfirm = true
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            onClick = {
                                menuOpen = false
                                showSettings = true
                            },
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = Modifier.imePadding(),
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (vm.items.isEmpty() && !vm.busy) {
                EmptyState(
                    enabled = !vm.needsAuth,
                    onPick = { input = it },
                    modifier = Modifier.weight(1f),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(vm.items) { item ->
                        ChatBubble(item, copyToClipboard, onRetry = { vm.retryLastTurn() }, onContinue = { vm.continueTurn() })
                    }
                }
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
                    placeholder = {
                        Text(
                            when {
                                vm.needsAuth -> "Sign in or set an API key first"
                                vm.busy -> "Redirect agent…"
                                else -> "Ask AndroidClaw…"
                            },
                        )
                    },
                    enabled = !vm.needsAuth && vm.pendingStep == null,
                    maxLines = 4,
                    shape = RoundedCornerShape(16.dp),
                )
                Spacer(Modifier.size(8.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (vm.busy && vm.iterationCount > 0) {
                        Text(
                            "${vm.iterationCount} / ${settings.maxIterations}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (vm.pendingStep == null) {
                            IconButton(
                                onClick = {
                                    vm.send(input)
                                    input = ""
                                },
                                enabled = input.isNotBlank() && !vm.needsAuth,
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = if (vm.busy) "Redirect" else "Send")
                            }
                        }
                        if (vm.busy) {
                            TextButton(onClick = { vm.cancelTurn() }) { Text("Stop") }
                        }
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear this chat?") },
            text = { Text("The current conversation will be removed. Saved sessions in your Playbook are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearChat()
                    showClearConfirm = false
                }) { Text("Clear", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun EmptyState(
    enabled: Boolean,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("🦞", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(12.dp))
        Text(
            "How can Claw help?",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            if (enabled) "Ask anything, or try one of these:" else "Sign in from the menu to get started.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
        )
        if (enabled) {
            Spacer(Modifier.height(16.dp))
            EXAMPLE_PROMPTS.forEach { prompt ->
                AssistChip(
                    onClick = { onPick(prompt) },
                    label = { Text(prompt, maxLines = 2) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatBubble(item: ChatItem, onCopy: (String) -> Unit, onRetry: () -> Unit, onContinue: () -> Unit) {
    when (item) {
        is ChatItem.LimitReached -> Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "↺ Reached ${item.max}-step limit",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onContinue) { Text("Continue") }
            }
        }
        is ChatItem.User -> Bubble(
            text = item.text,
            alignEnd = true,
            container = MaterialTheme.colorScheme.primaryContainer,
            textColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        is ChatItem.Assistant -> {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                Column(
                    Modifier
                        .widthIn(max = 320.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 4.dp),
                        )
                        .then(
                            if (!item.streaming)
                                Modifier.combinedClickable(
                                    onClick = {},
                                    onLongClick = { onCopy(item.text) },
                                )
                            else Modifier,
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    if (item.text.isNotEmpty()) {
                        MarkdownText(
                            text = item.text,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // Show typing dots only while streaming with no text yet —
                    // once tokens arrive the streaming text itself signals activity.
                    if (item.streaming && item.text.isEmpty()) {
                        TypingIndicator()
                    }
                }
            }
        }
        is ChatItem.ToolUse -> ToolChip(item)
        is ChatItem.Error -> Column(Modifier.padding(end = 40.dp)) {
            Bubble(
                text = item.text,
                alignEnd = false,
                container = MaterialTheme.colorScheme.errorContainer,
                textColor = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(
                onClick = onRetry,
                modifier = Modifier.padding(start = 4.dp),
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.size(4.dp))
                Text("Retry", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun ToolChip(item: ChatItem.ToolUse) {
    val (label, color) = when {
        item.running -> "Running ${item.name}…" to MaterialTheme.colorScheme.secondary
        item.isError -> "${item.name} failed" to MaterialTheme.colorScheme.error
        else -> "Did ${item.name}" to MaterialTheme.colorScheme.outline
    }
    Row(
        Modifier.padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(7.dp)
                .background(color, CircleShape),
        )
        Spacer(Modifier.size(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontStyle = FontStyle.Italic,
            color = color,
        )
    }
}

@Composable
private fun TypingIndicator() {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = index * 160),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$index",
            )
            Box(
                Modifier
                    .padding(horizontal = 2.dp)
                    .size(6.dp)
                    .background(
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                        CircleShape,
                    ),
            )
        }
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
                fontWeight = FontWeight.Medium,
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
                    Button(onClick = onForward) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Do it")
                    }
                    OutlinedButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Back")
                    }
                    TextButton(onClick = { chatMode = true }) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Chat")
                    }
                    TextButton(onClick = onStop) {
                        Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(4.dp))
                        Text("Stop")
                    }
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
    textColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    onLongClick: (() -> Unit)? = null,
) {
    val shape = if (alignEnd)
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 4.dp, bottomStart = 16.dp)
    else
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 16.dp, bottomStart = 4.dp)
    Box(Modifier.fillMaxWidth(), contentAlignment = if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart) {
        Text(
            text = text,
            color = textColor,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(container, shape)
                .then(
                    if (onLongClick != null)
                        Modifier.combinedClickable(onClick = {}, onLongClick = onLongClick)
                    else Modifier,
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
