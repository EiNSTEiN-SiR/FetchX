package org.fetchx.app.ui.screens

import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.fetchx.app.data.Engine
import org.fetchx.app.engine.LinkKind
import org.fetchx.app.ui.AddState
import org.fetchx.app.ui.AppViewModel
import org.fetchx.app.ui.Format

private fun describe(st: AddState): String = when {
    st.link.isBlank() -> "Paste a direct link, magnet link or video page URL"
    st.kind == LinkKind.INVALID -> "Not a valid link"
    st.forceDirect -> "Direct file • aria2c"
    st.kind == LinkKind.MAGNET -> "Magnet link • aria2c (BitTorrent)"
    st.kind == LinkKind.TORRENT_FILE -> "Torrent file • aria2c (BitTorrent)"
    st.kind == LinkKind.DIRECT_FILE -> "Direct file • aria2c"
    st.kind == LinkKind.HLS_OR_DASH -> "HLS/DASH stream • yt-dlp + FFmpeg"
    else -> "Video page • yt-dlp + FFmpeg"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSheet(vm: AppViewModel, st: AddState) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val cookies by vm.cookiesLoaded.collectAsState()
    var advanced by remember { mutableStateOf(false) }

    val pickDir = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            vm.setAddDest(uri.toString())
        }
    }

    ModalBottomSheet(
        onDismissRequest = vm::dismissAdd,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        tonalElevation = 0.dp,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = st.input,
                onValueChange = vm::setInput,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Link") },
                minLines = 1,
                maxLines = 4,
                trailingIcon = {
                    TextButton(onClick = {
                        val cm = context.getSystemService(ClipboardManager::class.java)
                        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                        if (!text.isNullOrBlank()) vm.setInput(text)
                    }) { Text("Paste") }
                },
            )
            Text(
                describe(st),
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = if (st.kind == LinkKind.INVALID && st.link.isNotBlank()) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (st.engine == Engine.YTDLP && st.valid) {
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                when {
                    st.probing -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("Extracting formats…", style = MaterialTheme.typography.bodyMedium)
                    }

                    st.probeError != null -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(st.probeError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = vm::probe) { Text("Retry") }
                            OutlinedButton(onClick = { vm.setForceDirect(true) }) { Text("Download as direct file") }
                        }
                    }

                    st.probe != null -> {
                        Text(st.probe.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                        Column {
                            st.probe.options.forEachIndexed { i, opt ->
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { vm.selectQuality(i) },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected = st.selected == i, onClick = { vm.selectQuality(i) })
                                    Text(opt.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                    if (opt.approxBytes > 0) {
                                        Text(
                                            "~" + Format.bytes(opt.approxBytes),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (st.forceDirect) {
                TextButton(onClick = { vm.setForceDirect(false); vm.probe() }) { Text("Use yt-dlp instead") }
            }

            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

            Column {
                Text(
                    "Threads: ${st.threads}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                )
                Slider(
                    value = st.threads.toFloat(),
                    onValueChange = { vm.setThreads(it.toInt()) },
                    valueRange = 1f..32f,
                    steps = 30,
                )
                if (st.threads > 16 && st.engine == Engine.ARIA2) {
                    Text(
                        "aria2 opens at most 16 connections per server; splits above that only help with mirrors or torrents.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Save to", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(vm.folderLabel(st.destTreeUri), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                }
                TextButton(onClick = { pickDir.launch(null) }) { Text("Change") }
                if (st.destTreeUri != null) TextButton(onClick = { vm.setAddDest(null) }) { Text("Reset") }
            }

            TextButton(onClick = { advanced = !advanced }) {
                Text(if (advanced) "Hide headers" else "Headers and cookies")
            }
            if (advanced) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = st.userAgent, onValueChange = vm::setAddUserAgent, singleLine = true,
                        label = { Text("User-Agent") }, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = st.referer, onValueChange = vm::setAddReferer, singleLine = true,
                        label = { Text("Referer") }, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = st.headers, onValueChange = vm::setAddHeaders, minLines = 2, maxLines = 5,
                        label = { Text("Extra headers (Name: value, one per line)") }, modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        if (cookies) "cookies.txt is loaded and will be sent with this download"
                        else "No cookies.txt imported (Settings > Network)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = vm::dismissAdd) { Text("Cancel") }
                Button(onClick = vm::submit, enabled = st.valid && !st.probing) { Text("Download") }
            }
        }
    }
}
