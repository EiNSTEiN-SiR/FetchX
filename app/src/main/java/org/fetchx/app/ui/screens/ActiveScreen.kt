package org.fetchx.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.fetchx.app.data.DownloadEntity
import org.fetchx.app.data.Engine
import org.fetchx.app.data.Status
import org.fetchx.app.ui.AppViewModel
import org.fetchx.app.ui.Format

fun telemetry(d: DownloadEntity): String {
    val engine = if (d.engine == Engine.ARIA2) "aria2c" else "yt-dlp"
    val size = when {
        d.totalBytes > 0 -> "${Format.bytes(d.doneBytes)} / ${Format.bytes(d.totalBytes)}"
        d.doneBytes > 0 -> Format.bytes(d.doneBytes)
        else -> "-- / --"
    }
    return when (d.status) {
        Status.RUNNING -> {
            val threads = if (d.engine == Engine.ARIA2) d.connections else d.threads
            "${Format.speed(d.speedBps)} • $size • $threads Threads • ETA ${Format.eta(d.etaSec)}"
        }
        Status.QUEUED -> "Queued • $engine"
        Status.PAUSED -> "Paused • $size • $engine"
        Status.FAILED -> "Failed • ${d.error ?: "unknown error"}"
        else -> size
    }
}

@Composable
fun ActiveScreen(vm: AppViewModel) {
    val items by vm.active.collectAsState()
    var refreshTarget by remember { mutableStateOf<DownloadEntity?>(null) }

    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No active downloads",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            items(items, key = { it.id }) { d ->
                ActiveRow(
                    d = d,
                    onPause = { vm.pause(d.id) },
                    onResume = { vm.resume(d.id) },
                    onCancel = { vm.cancel(d.id) },
                    onRefresh = { refreshTarget = d },
                )
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }

    refreshTarget?.let { target ->
        RefreshLinkDialog(
            current = target.url,
            onDismiss = { refreshTarget = null },
            onConfirm = {
                vm.refreshUrl(target.id, it)
                refreshTarget = null
            },
        )
    }
}

@Composable
private fun ActiveRow(
    d: DownloadEntity,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(d.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (d.status == Status.RUNNING || d.status == Status.PAUSED) {
                val frac = if (d.totalBytes > 0) (d.doneBytes.toFloat() / d.totalBytes).coerceIn(0f, 1f) else null
                Box(Modifier.padding(vertical = 5.dp)) {
                    if (frac != null) {
                        LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth().height(2.dp), gapSize = 0.dp, drawStopIndicator = {})
                    } else if (d.status == Status.RUNNING) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
                    } else {
                        LinearProgressIndicator(progress = { 0f }, modifier = Modifier.fillMaxWidth().height(2.dp), gapSize = 0.dp, drawStopIndicator = {})
                    }
                }
            } else {
                Box(Modifier.height(4.dp))
            }
            Text(
                telemetry(d),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (d.status == Status.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            if (d.status == Status.PAUSED || d.status == Status.FAILED) {
                IconButton(onClick = onRefresh, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Link, "Update link") }
            }
            when (d.status) {
                Status.RUNNING, Status.QUEUED ->
                    IconButton(onClick = onPause, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Pause, "Pause") }
                Status.FAILED ->
                    IconButton(onClick = onResume, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Refresh, "Retry") }
                else ->
                    IconButton(onClick = onResume, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.PlayArrow, "Resume") }
            }
            IconButton(onClick = onCancel, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Close, "Cancel") }
        }
    }
}

@Composable
private fun RefreshLinkDialog(current: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Update link") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Paste a fresh URL for this download. Bytes already downloaded are kept.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("New URL") },
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    current,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text("Update and resume") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
