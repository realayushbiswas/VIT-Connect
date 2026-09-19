package com.offgridmesh.vitconnect.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.offgridmesh.vitconnect.ui.MeshViewModel
import com.offgridmesh.vitconnect.ui.theme.MeshTeal

/** Lists every device known mesh-wide (direct peers, key-gossip, and saved QR contacts). */
@Composable
fun DirectoryScreen(
    viewModel: MeshViewModel,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    onOpenMyQr: () -> Unit,
) {
    val knownDevices by viewModel.knownDevices.collectAsState()
    val peers by viewModel.peers.collectAsState()
    val verifiedContactIds by viewModel.verifiedContactIds.collectAsState()
    val onlineDeviceIds = remember(peers) { peers.values.map { it.deviceId }.toSet() }
    val others = remember(knownDevices) {
        knownDevices.filterKeys { it != viewModel.deviceId }.toList().sortedBy { it.second }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SimpleTopBar(
            title = "Direct Messages",
            onBack = onBack,
            trailing = {
                IconButton(onClick = onOpenMyQr) {
                    Icon(Icons.Filled.QrCode, contentDescription = "My QR code / scan a contact")
                }
            },
        )
        if (others.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No other devices discovered yet.\nThey'll show up here as soon as the mesh finds them.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
                items(others, key = { it.first }) { (deviceId, name) ->
                    val online = deviceId in onlineDeviceIds
                    val verified = deviceId in verifiedContactIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenChat(deviceId) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(name, style = MaterialTheme.typography.bodyLarge)
                                if (verified) {
                                    Spacer(Modifier.width(4.dp))
                                    Icon(
                                        Icons.Filled.Verified,
                                        contentDescription = "Verified via QR",
                                        tint = MeshTeal,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                            Text(
                                when {
                                    online && verified -> "connected directly · verified contact"
                                    online -> "connected directly"
                                    verified -> "verified contact · reachable via mesh"
                                    else -> "reachable via mesh"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (online) MeshTeal else MaterialTheme.colorScheme.onSurfaceVariant),
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DirectChatScreen(viewModel: MeshViewModel, peerDeviceId: String, onBack: () -> Unit) {
    val knownDevices by viewModel.knownDevices.collectAsState()
    val conversations by viewModel.directConversations.collectAsState()
    val messages = conversations[peerDeviceId].orEmpty()
    val peerName = knownDevices[peerDeviceId] ?: peerDeviceId

    var input by remember { mutableStateOf("") }
    var sendFailed by remember { mutableStateOf(false) }
    var showSafetyNumber by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SimpleTopBar(
            title = peerName,
            subtitle = "🔒 End-to-end encrypted",
            onBack = onBack,
            trailing = {
                IconButton(onClick = { showSafetyNumber = true }) {
                    Icon(Icons.Filled.Lock, contentDescription = "Verify encryption", tint = MeshTeal)
                }
            },
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(messages, key = { it.id }) { msg ->
                MessageBubble(msg = msg, isMine = msg.senderId == viewModel.deviceId)
            }
        }
        if (sendFailed) {
            Text(
                "Still learning ${peerName}'s encryption key — try again in a moment.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        MessageInputBar(
            value = input,
            onValueChange = {
                input = it
                sendFailed = false
            },
            onSend = {
                val sent = viewModel.sendDirectMessage(peerDeviceId, input)
                if (sent) input = "" else sendFailed = true
            },
            placeholder = "Encrypted message to $peerName…",
        )
    }

    if (showSafetyNumber) {
        val safetyNumber = viewModel.safetyNumberFor(peerDeviceId)
        AlertDialog(
            onDismissRequest = { showSafetyNumber = false },
            icon = {
                Icon(
                    if (safetyNumber != null) Icons.Filled.Lock else Icons.Filled.LockOpen,
                    contentDescription = null,
                )
            },
            title = { Text("Verify encryption") },
            text = {
                if (safetyNumber != null) {
                    Column {
                        Text(
                            "This code is unique to your conversation with $peerName. " +
                                "Compare it with them in person or over another channel — if it " +
                                "matches on both phones, no one is intercepting your messages.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Spacer(Modifier.width(4.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        ) {
                            Text(
                                safetyNumber,
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                } else {
                    Text("Still exchanging keys with $peerName — check back once you're both connected to the mesh.")
                }
            },
            confirmButton = {
                TextButton(onClick = { showSafetyNumber = false }) { Text("Done") }
            },
        )
    }
}

@Composable
fun SimpleTopBar(
    title: String,
    subtitle: String? = null,
    onBack: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp, 8.dp, 12.dp, 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            trailing?.invoke()
        }
    }
}
