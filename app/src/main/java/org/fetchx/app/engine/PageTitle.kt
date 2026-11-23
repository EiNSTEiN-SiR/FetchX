package org.fetchx.app.engine

import android.text.Html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Reads a web page's og:title (preferred) or <title>, used to name downloads that have no useful name of their own. */
object PageTitle {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    private val ogA = Regex("""<meta[^>]+property\s*=\s*["']og:title["'][^>]*content\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
    private val ogB = Regex("""<meta[^>]+content\s*=\s*["']([^"']*)["'][^>]*property\s*=\s*["']og:title["']""", RegexOption.IGNORE_CASE)
    private val titleTag = Regex("""<title[^>]*>([\s\S]*?)</title>""", RegexOption.IGNORE_CASE)

    suspend fun fetch(url: String?, userAgent: String?, cookie: String?): String? = withContext(Dispatchers.IO) {
        if (url.isNullOrBlank() || !url.startsWith("http", ignoreCase = true)) return@withContext null
        runCatching {
            val rb = Request.Builder().url(url).header("Accept", "text/html,application/xhtml+xml")
            userAgent?.takeIf { it.isNotBlank() }?.let { rb.header("User-Agent", it) }
            cookie?.takeIf { it.isNotBlank() }?.let { rb.header("Cookie", it) }
            client.newCall(rb.build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val source = resp.body?.source() ?: return@use null
                source.request(512L * 1024)
                val html = source.buffer.readUtf8(minOf(source.buffer.size, 512L * 1024))
                parse(html)
            }
        }.getOrNull()
    }

    fun parse(html: String): String? {
        val raw = ogA.find(html)?.groupValues?.get(1)
            ?: ogB.find(html)?.groupValues?.get(1)
            ?: titleTag.find(html)?.groupValues?.get(1)
            ?: return null
        val text = Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString().replace(Regex("\\s+"), " ").trim()
        return text.takeIf { it.isNotBlank() }
    }
}
