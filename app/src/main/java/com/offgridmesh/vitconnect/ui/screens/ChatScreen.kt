package com.offgridmesh.vitconnect.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.offgridmesh.vitconnect.mesh.MeshMessage
import com.offgridmesh.vitconnect.mesh.MeshStatus
import com.offgridmesh.vitconnect.mesh.PeerInfo
import com.offgridmesh.vitconnect.ui.MeshViewModel
import com.offgridmesh.vitconnect.ui.theme.MeshPurple
import com.offgridmesh.vitconnect.ui.theme.MeshPurpleLight
import com.offgridmesh.vitconnect.ui.theme.MeshSurfaceVariant
import com.offgridmesh.vitconnect.ui.theme.MeshTeal
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun ChatScreen(viewModel: MeshViewModel, onOpenDirectory: () -> Unit) {
    val peers by viewModel.peers.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val status by viewModel.status.collectAsState()

    var input by remember { mutableStateOf("") }
    var showEditName by remember { mutableStateOf(false) }
    var nameDraft by remember { mutableStateOf(viewModel.displayName) }

    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        MeshHeader(
            status = status,
            peerCount = peers.size,
            displayName = viewModel.displayName,
            onEditName = {
                nameDraft = viewModel.displayName
                showEditName = true
            },
            onOpenDirectory = onOpenDirectory,
        )
        PeerStrip(peers = peers.values.toList())

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(messages, key = { it.id }) { msg ->
                MessageBubble(msg = msg, isMine = msg.senderId == viewModel.deviceId)
            }
        }

        MessageInputBar(
            value = input,
            onValueChange = { input = it },
            onSend = {
                viewModel.sendText(input)
                input = ""
            },
        )
    }

    if (showEditName) {
        AlertDialog(
            onDismissRequest = { showEditName = false },
            title = { Text("Your display name") },
            text = {
                OutlinedTextField(
                    value = nameDraft,
                    onValueChange = { nameDraft = it },
                    singleLine = true,
                    label = { Text("Shown to nearby peers") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.displayName = nameDraft
                    showEditName = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showEditName = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun MeshHeader(
    status: MeshStatus,
    peerCount: Int,
    displayName: String,
    onEditName: () -> Unit,
    onOpenDirectory: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp, 14.dp, 16.dp, 10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "VIT Connect",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                StatusDot(status)
                Spacer(Modifier.width(10.dp))
                IconButton(onClick = onOpenDirectory, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.People,
                        contentDescription = "Direct messages",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = statusLabel(status, peerCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "· you: $displayName",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onEditName, modifier = Modifier.size(20.dp)) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = "Edit name",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusDot(status: MeshStatus) {
    val color = when (status) {
        MeshStatus.RUNNING -> MeshTeal
        MeshStatus.STARTING -> MaterialTheme.colorScheme.tertiary
        MeshStatus.ERROR -> MaterialTheme.colorScheme.error
        MeshStatus.IDLE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .size(9.dp)
            .clip(CircleShape)
            .background(color),
    )
}

private fun statusLabel(status: MeshStatus, peerCount: Int): String = when (status) {
    MeshStatus.RUNNING -> "$peerCount peer${if (peerCount == 1) "" else "s"} connected"
    MeshStatus.STARTING -> "Starting mesh…"
    MeshStatus.ERROR -> "Mesh error — check permissions"
    MeshStatus.IDLE -> "Mesh idle"
}

@Composable
private fun PeerStrip(peers: List<PeerInfo>) {
    if (peers.isEmpty()) {
        Text(
            "Searching for nearby devices…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        return
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(peers, key = { it.endpointId }) { peer ->
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MeshSurfaceVariant,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MeshTeal),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(peer.displayName, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
fun MessageBubble(msg: MeshMessage, isMine: Boolean) {
    val bubbleColor = if (isMine) MeshPurple else MeshSurfaceVariant
    val align = if (isMine) Alignment.End else Alignment.Start
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = align,
    ) {
        if (!isMine) {
            Text(
                msg.senderName,
                style = MaterialTheme.typography.labelSmall,
                color = MeshPurpleLight,
                modifier = Modifier.padding(start = 10.dp, bottom = 2.dp),
            )
        }
        Surface(
            color = bubbleColor,
            shape = RoundedCornerShape(
                topStart = 14.dp,
                topEnd = 14.dp,
                bottomStart = if (isMine) 14.dp else 4.dp,
                bottomEnd = if (isMine) 4.dp else 14.dp,
            ),
        ) {
            Column(modifier = Modifier.padding(12.dp, 8.dp)) {
                Text(msg.body, color = Color.White)
                Text(
                    timeFmt.format(msg.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
fun MessageInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    placeholder: String = "Message the mesh…",
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(placeholder) },
                singleLine = true,
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = onSend,
                enabled = value.isNotBlank(),
            ) {
                Icon(
                    Icons.Filled.Send,
                    contentDescription = "Send",
                    tint = if (value.isNotBlank()) MeshPurpleLight else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
