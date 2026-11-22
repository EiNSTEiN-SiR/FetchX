package org.fetchx.app.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.fetchx.app.ui.AppViewModel

data class SniffedMedia(val url: String, val kind: String, val pageUrl: String?)

object Sniffer {
    private val video = setOf("mp4", "webm", "mkv", "m4v", "mov", "flv", "3gp", "avi")
    private val audio = setOf("mp3", "m4a", "aac", "ogg", "opus", "flac", "wav")

    /** Returns a short label ("HLS", "MP4"...) if the request looks like a media file or playlist. */
    fun classify(url: String): String? {
        if (!url.startsWith("http", ignoreCase = true)) return null
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        val ext = path.substringAfterLast('.', "")
        return when {
            ext == "m3u8" -> "HLS"
            ext == "mpd" -> "DASH"
            ext in video || ext in audio -> ext.uppercase()
            else -> null
        }
    }

    fun isStream(kind: String) = kind == "HLS" || kind == "DASH"
}

private fun normalize(text: String): String {
    val t = text.trim()
    if (t.isEmpty()) return "https://duckduckgo.com"
    if (t.startsWith("http://") || t.startsWith("https://")) return t
    if (!t.contains(' ') && t.contains('.')) return "https://$t"
    return "https://duckduckgo.com/?q=" + Uri.encode(t)
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(vm: AppViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val main = remember { Handler(Looper.getMainLooper()) }
    var input by remember { mutableStateOf("") }
    var pageUrl by remember { mutableStateOf("") }
    var pageTitle by remember { mutableStateOf("") }
    var progress by remember { mutableIntStateOf(0) }
    var canBack by remember { mutableStateOf(false) }
    var canForward by remember { mutableStateOf(false) }
    var showList by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val found = remember { mutableStateListOf<SniffedMedia>() }

    BackHandler {
        val w = webView
        if (w != null && w.canGoBack()) w.goBack() else onClose()
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close browser") }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("Search or enter address") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, keyboardType = KeyboardType.Uri),
                    keyboardActions = KeyboardActions(onGo = { webView?.loadUrl(normalize(input)) }),
                    textStyle = MaterialTheme.typography.bodyMedium,
                )
                IconButton(onClick = { webView?.reload() }) { Icon(Icons.Default.Refresh, "Reload") }
            }
            if (progress in 1..99) {
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp), gapSize = 0.dp, drawStopIndicator = {})
            } else {
                HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }

            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                val u = request.url.toString()
                                val kind = Sniffer.classify(u)
                                if (kind != null) {
                                    val page = view.url
                                    main.post {
                                        if (found.none { it.url == u }) found.add(SniffedMedia(u, kind, page))
                                    }
                                }
                                return null
                            }

                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                found.clear()
                                pageUrl = url
                                input = url
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                pageUrl = url
                                input = url
                                canBack = view.canGoBack()
                                canForward = view.canGoForward()
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                progress = newProgress
                            }

                            override fun onReceivedTitle(view: WebView, title: String?) {
                                pageTitle = title.orEmpty()
                            }
                        }
                        webView = this
                        loadUrl("https://duckduckgo.com")
                    }
                },
                onRelease = { it.destroy() },
            )

            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row {
                    IconButton(onClick = { webView?.goBack() }, enabled = canBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    IconButton(onClick = { webView?.goForward() }, enabled = canForward) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Forward") }
                }
                Row {
                    TextButton(onClick = { if (pageUrl.startsWith("http")) vm.showAdd(pageUrl) }, enabled = pageUrl.startsWith("http")) {
                        Text("Use page link")
                    }
                    TextButton(onClick = { showList = true }, enabled = found.isNotEmpty()) {
                        Text("Videos (${found.size})", fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }

    if (showList) {
        ModalBottomSheet(
            onDismissRequest = { showList = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.background,
            tonalElevation = 0.dp,
            shape = MaterialTheme.shapes.small,
        ) {
            Text(
                "Detected on this page",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (found.isEmpty()) {
                Text(
                    "Nothing yet. Press play on the video, then check again.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            }
            LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding()) {
                items(found.toList(), key = { it.url }) { m ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                val ua = webView?.settings?.userAgentString
                                val cookie = CookieManager.getInstance().getCookie(m.url)
                                    ?: CookieManager.getInstance().getCookie(pageUrl)
                                vm.downloadSniffed(m.url, m.kind, pageTitle, ua, m.pageUrl ?: pageUrl, cookie)
                                Toast.makeText(context, "Added to queue", Toast.LENGTH_SHORT).show()
                                showList = false
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text(m.kind, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        Text(
                            m.url,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}
