package com.offgridmesh.vitconnect.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.offgridmesh.vitconnect.mesh.gateway.GatewayLimits
import com.offgridmesh.vitconnect.mesh.gateway.QueryItem
import com.offgridmesh.vitconnect.mesh.gateway.QueryKind
import com.offgridmesh.vitconnect.mesh.gateway.QueryStatus
import com.offgridmesh.vitconnect.ui.MeshViewModel
import com.offgridmesh.vitconnect.ui.theme.MeshTeal

/**
 * Search the web or ask an AI, with or without internet on this phone. When offline, the question
 * travels through the mesh to the nearest gateway (a nearby phone that has internet and has opted
 * in) and the answer comes back the same way.
 */
@Composable
fun SearchScreen(viewModel: MeshViewModel, onBack: () -> Unit) {
    val queries by viewModel.queries.collectAsState()
    val gateways by viewModel.gateways.collectAsState()
    val online by viewModel.hasInternet.collectAsState()
    val serving by viewModel.servingAsGateway.collectAsState()

    var kind by remember { mutableStateOf(QueryKind.SEARCH) }
    var input by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }

    val answerLocally = online && viewModel.canAnswerLocally(kind)
    val nearest = gateways.firstOrNull { kind in it.kinds }

    Column(modifier = Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Text(
                        "Search & Ask AI",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Gateway settings")
                    }
                }
                Text(
                    text = when {
                        answerLocally -> "Online — answered directly from this phone"
                        nearest != null ->
                            "Offline — routing through ${nearest.name}, " +
                                "${nearest.hops} hop${if (nearest.hops == 1) "" else "s"} away"
                        else -> "No internet gateway in range — your question will wait for one"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (answerLocally || nearest != null) MeshTeal else MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(start = 16.dp, end = 12.dp),
                )
                if (serving) {
                    Text(
                        "You're sharing your internet with the mesh",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 2.dp),
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (queries.isEmpty()) {
                item {
                    Text(
                        "Ask anything. With no Wi-Fi or data, VIT Connect finds the nearest phone " +
                            "in the mesh that has internet, asks it for you, and brings the answer back.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
            items(queries, key = { it.id }) { QueryCard(it) }
        }

        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QueryKind.entries.forEach { k ->
                    FilterChip(
                        selected = kind == k,
                        onClick = { kind = k },
                        label = { Text(k.label) },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { if (it.length <= GatewayLimits.MAX_QUERY_CHARS) input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (kind == QueryKind.SEARCH) "Search the web…" else "Ask the AI…") },
                    maxLines = 4,
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        if (viewModel.submitQuery(kind, input)) input = ""
                    },
                    enabled = input.isNotBlank(),
                ) {
                    Icon(Icons.Filled.Search, contentDescription = "Submit")
                }
            }
        }
    }

    if (showSettings) {
        GatewaySettingsDialog(viewModel = viewModel, onDismiss = { showSettings = false })
    }
}

@Composable
private fun QueryCard(item: QueryItem) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                item.kind.label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(item.text, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                statusText(item),
                style = MaterialTheme.typography.labelMedium,
                color = when (item.status) {
                    QueryStatus.DONE -> MeshTeal
                    QueryStatus.FAILED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.tertiary
                },
            )
            item.answer?.let { answer ->
                Spacer(Modifier.height(8.dp))
                SelectionContainer {
                    Text(answer, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private fun statusText(item: QueryItem): String {
    val hopText = item.hops?.let { " ($it hop${if (it == 1) "" else "s"})" }.orEmpty()
    return when (item.status) {
        QueryStatus.WAITING_FOR_GATEWAY -> "Waiting for a gateway…"
        QueryStatus.SENT -> "Sent to ${item.gatewayName}$hopText — waiting for the answer…"
        QueryStatus.RUNNING_LOCALLY -> "Running on this phone…"
        QueryStatus.DONE -> if (item.gatewayName != null) "Answered via ${item.gatewayName}$hopText" else "Answered"
        QueryStatus.FAILED -> "Couldn't get an answer"
    }
}

@Composable
private fun GatewaySettingsDialog(viewModel: MeshViewModel, onDismiss: () -> Unit) {
    val shareEnabled by viewModel.shareEnabled.collectAsState()
    var braveKey by remember { mutableStateOf(viewModel.braveKey) }
    var anthropicKey by remember { mutableStateOf(viewModel.anthropicKey) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Internet gateway") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Add your own API keys to search or ask AI from this phone when it's online. " +
                        "Turn on sharing and phones nearby with no internet can use your connection " +
                        "for searches and AI questions — only those two things, never anything else " +
                        "on your phone. Keys stay on this device.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Share my internet with the mesh", modifier = Modifier.weight(1f))
                    Switch(checked = shareEnabled, onCheckedChange = { viewModel.setShareEnabled(it) })
                }
                OutlinedTextField(
                    value = braveKey,
                    onValueChange = { braveKey = it },
                    label = { Text("Brave Search API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = anthropicKey,
                    onValueChange = { anthropicKey = it },
                    label = { Text("Claude API key (for Ask AI)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (shareEnabled && braveKey.isBlank() && anthropicKey.isBlank()) {
                    Text(
                        "Add at least one key — without one there's nothing to share.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                Spacer(Modifier.size(2.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                viewModel.saveGatewayKeys(braveKey, anthropicKey)
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
