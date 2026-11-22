package org.fetchx.app.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.fetchx.app.data.DownloadEntity
import org.fetchx.app.data.Engine
import org.fetchx.app.engine.Exporter
import org.fetchx.app.ui.AppViewModel
import org.fetchx.app.ui.Format
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CompletedScreen(vm: AppViewModel) {
    val items by vm.completed.collectAsState()
    val context = LocalContext.current
    var deleting by remember { mutableStateOf<DownloadEntity?>(null) }
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No completed downloads",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            items(items, key = { it.id }) { d ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(d.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val engine = if (d.engine == Engine.ARIA2) "aria2c" else "yt-dlp"
                        Text(
                            "${Format.bytes(d.totalBytes)} • $engine • ${fmt.format(Date(d.completedAt.takeIf { it > 0 } ?: d.createdAt))}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.End) {
                        if (d.outputUri != null) {
                            IconButton(
                                onClick = {
                                    val intent = Intent(Intent.ACTION_VIEW)
                                        .setDataAndType(Uri.parse(d.outputUri), Exporter.mimeOf(d.title))
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                                    try {
                                        context.startActivity(intent)
                                    } catch (_: ActivityNotFoundException) {
                                        Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.size(40.dp),
                            ) { Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open") }
                        }
                        IconButton(onClick = { deleting = d }, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Default.Delete, "Delete")
                        }
                    }
                }
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }

    deleting?.let { d ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Remove download") },
            text = { Text(d.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(onClick = { vm.removeCompleted(d, deleteFile = true); deleting = null }) { Text("Delete file") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { deleting = null }) { Text("Cancel") }
                    TextButton(onClick = { vm.removeCompleted(d, deleteFile = false); deleting = null }) { Text("Remove from list") }
                }
            },
        )
    }
}
