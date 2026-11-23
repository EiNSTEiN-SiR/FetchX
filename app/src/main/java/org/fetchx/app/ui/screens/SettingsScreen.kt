package org.fetchx.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.fetchx.app.BuildConfig
import org.fetchx.app.data.AppSettings
import org.fetchx.app.data.ThemeMode
import org.fetchx.app.ui.AppViewModel

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun Divider() = HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, s: AppSettings) {
    val context = LocalContext.current
    val cookies by vm.cookiesLoaded.collectAsState()
    val ytVersion by vm.ytVersion.collectAsState()
    val updating by vm.updating.collectAsState()
    val updateStatus by vm.updateStatus.collectAsState()

    val pickDir = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            vm.setDest(uri.toString())
        }
    }
    val pickWallpaper = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) vm.setWallpaper(uri)
    }
    val pickCookies = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) vm.importCookies(uri)
    }

    var ua by remember { mutableStateOf(s.userAgent) }
    var referer by remember { mutableStateOf(s.referer) }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        SectionHeader("Appearance")
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { i, mode ->
                    SegmentedButton(
                        selected = s.theme == mode,
                        onClick = { vm.setTheme(mode) },
                        shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                    ) { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) }
                }
            }
        }
        SettingRow(
            "Dynamic color",
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "Use wallpaper colors" else "Requires Android 12 or newer",
            trailing = {
                Switch(
                    checked = s.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                    onCheckedChange = vm::setDynamic,
                    enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                )
            },
        )
        SettingRow("Pure black", "AMOLED background in the dark theme", trailing = {
            Switch(checked = s.amoled, onCheckedChange = vm::setAmoled)
        })
        val hasWallpaper = s.wallpaperVersion > 0L
        SettingRow(
            "Custom wallpaper",
            if (hasWallpaper) "Image set" else "Pick an image from your gallery",
            onClick = {
                pickWallpaper.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            trailing = { if (hasWallpaper) TextButton(onClick = vm::clearWallpaper) { Text("Remove") } },
        )
        if (hasWallpaper) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text("Wallpaper opacity: ${s.wallpaperAlpha}%", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Slider(
                    value = s.wallpaperAlpha.toFloat(),
                    onValueChange = { vm.setWallpaperAlpha(it.toInt()) },
                    valueRange = 0f..100f,
                )
            }
        }
        Divider()

        SectionHeader("Downloads")
        SettingRow("Default folder", vm.folderLabel(s.destTreeUri.ifBlank { null }), onClick = { pickDir.launch(null) }, trailing = {
            if (s.destTreeUri.isNotBlank()) TextButton(onClick = { vm.setDest("") }) { Text("Reset") }
        })
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text("Default threads: ${s.defaultThreads}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            Slider(
                value = s.defaultThreads.toFloat(),
                onValueChange = { vm.setDefaultThreads(it.toInt()) },
                valueRange = 1f..32f, steps = 30,
            )
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text("Simultaneous downloads: ${s.maxConcurrent}", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            Slider(
                value = s.maxConcurrent.toFloat(),
                onValueChange = { vm.setMaxConcurrent(it.toInt()) },
                valueRange = 1f..8f, steps = 6,
            )
        }
        SettingRow("Watch clipboard", "Offer to add copied links", trailing = {
            Switch(checked = s.clipboardWatch, onCheckedChange = vm::setClipboardWatch)
        })
        Divider()

        SectionHeader("Network")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = ua, onValueChange = { ua = it; vm.setUserAgent(it) }, singleLine = true,
                label = { Text("Default User-Agent") }, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = referer, onValueChange = { referer = it; vm.setReferer(it) }, singleLine = true,
                label = { Text("Default Referer") }, modifier = Modifier.fillMaxWidth(),
            )
        }
        SettingRow(
            "cookies.txt",
            if (cookies) "Imported, sent with new downloads" else "Netscape format, for sites that need a login",
            onClick = { pickCookies.launch(arrayOf("text/*", "application/octet-stream")) },
            trailing = { if (cookies) TextButton(onClick = vm::clearCookies) { Text("Remove") } },
        )
        Divider()

        SectionHeader("Engines")
        SettingRow(
            "yt-dlp",
            (ytVersion?.let { "Installed: $it" } ?: "Version unknown") + (updateStatus?.let { "\n$it" } ?: ""),
            trailing = {
                TextButton(onClick = vm::updateYtDlp, enabled = !updating) {
                    Text(if (updating) "Updating…" else "Update")
                }
            },
        )
        SettingRow("Nightly yt-dlp builds", "Newer site fixes, updated more often", trailing = {
            Switch(checked = s.ytNightly, onCheckedChange = vm::setYtNightly)
        })
        SettingRow("aria2c", "Bundled, controlled over JSON-RPC")
        SettingRow("FFmpeg", "Bundled, used by yt-dlp to merge streams")
        Divider()

        SectionHeader("About")
        SettingRow("FetchX by Emtrix", "Version ${BuildConfig.VERSION_NAME} • GPL-3.0")
        Divider()
    }
}
