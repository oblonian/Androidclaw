package com.androidclaw.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.androidclaw.app.SavedSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class QuickAction(val emoji: String, val label: String, val prompt: String)

private val QUICK_ACTIONS = listOf(
    QuickAction("🎬", "YouTube", "Open YouTube and search for "),
    QuickAction("💬", "WhatsApp", "Open WhatsApp and send a message to "),
    QuickAction("📧", "Gmail", "Open Gmail and show my latest unread emails"),
    QuickAction("🗺️", "Maps", "Open Google Maps and navigate to "),
    QuickAction("🔍", "Web", "Search the web for "),
    QuickAction("🎵", "Music", "Open Spotify and play "),
    QuickAction("📸", "Screen", "Describe what you can see on the current screen"),
    QuickAction("📱", "Open app", "Open "),
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlaybookScreen(
    sessions: List<SavedSession>,
    onSelectSession: (SavedSession) -> Unit,
    onDeleteSession: (SavedSession) -> Unit,
    onUseQuickAction: (String) -> Unit,
    onBack: () -> Unit,
) {
    var deleteTarget by remember { mutableStateOf<SavedSession?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Playbook") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            // ── Quick Actions ─────────────────────────────────────────────────────
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        "Quick actions",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "Tap to pre-fill the chat",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
            item {
                Row(
                    Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QUICK_ACTIONS.forEach { action ->
                        FilterChip(
                            selected = false,
                            onClick = { onUseQuickAction(action.prompt) },
                            label = {
                                Text("${action.emoji} ${action.label}")
                            },
                            shape = RoundedCornerShape(20.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ),
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            // ── Recent sessions ───────────────────────────────────────────────────
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Recent sessions",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "Long-press to delete · Tap to continue",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            if (sessions.isEmpty()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("🦞", style = MaterialTheme.typography.displaySmall)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "No sessions yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Text(
                            "Past conversations will appear here",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }

            items(sessions, key = { it.id }) { session ->
                val dismissState = rememberSwipeToDismissBoxState(
                    confirmValueChange = { value ->
                        if (value == SwipeToDismissBoxValue.EndToStart) deleteTarget = session
                        false // confirm via dialog, not auto-dismiss
                    },
                )
                SwipeToDismissBox(
                    state = dismissState,
                    enableDismissFromStartToEnd = false,
                    backgroundContent = {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .background(
                                    MaterialTheme.colorScheme.errorContainer,
                                    RoundedCornerShape(12.dp),
                                ),
                            contentAlignment = Alignment.CenterEnd,
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(end = 20.dp),
                            )
                        }
                    },
                ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .combinedClickable(
                            onClick = { onSelectSession(session) },
                            onLongClick = { deleteTarget = session },
                        ),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 1.dp,
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top,
                        ) {
                            Text(
                                session.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                                maxLines = 2,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                formatDate(session.timestamp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                            )
                        }
                        val preview = session.messages
                            .lastOrNull { it.role == "assistant" }?.text
                            ?.take(80)
                            ?.let { if (it.length == 80) "$it…" else it }
                        if (preview != null) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Claw: $preview",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline,
                                fontStyle = FontStyle.Italic,
                                maxLines = 2,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${session.messages.size} messages",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                } // end SwipeToDismissBox
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete session?") },
            text = { Text("\"${target.title}\" will be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteSession(target)
                    deleteTarget = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }
}

private fun formatDate(ts: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - ts
    return when {
        diff < 60_000 -> "just now"
        diff < 3_600_000 -> "${diff / 60_000}m ago"
        diff < 86_400_000 -> "${diff / 3_600_000}h ago"
        diff < 604_800_000 -> {
            val fmt = SimpleDateFormat("EEE", Locale.getDefault())
            fmt.format(Date(ts))
        }
        else -> {
            val fmt = SimpleDateFormat("MMM d", Locale.getDefault())
            fmt.format(Date(ts))
        }
    }
}
