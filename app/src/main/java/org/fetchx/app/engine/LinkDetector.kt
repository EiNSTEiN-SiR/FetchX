package org.fetchx.app.engine

import android.net.Uri
import org.fetchx.app.data.Engine

enum class LinkKind { MAGNET, TORRENT_FILE, DIRECT_FILE, HLS_OR_DASH, VIDEO_PAGE, INVALID }

object LinkDetector {
    private val fileExtensions = setOf(
        "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso", "img", "apk", "xapk", "aab", "exe", "msi", "dmg", "deb", "rpm",
        "pdf", "epub", "mobi", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv",
        "mp3", "flac", "wav", "ogg", "opus", "m4a", "aac",
        "mp4", "mkv", "webm", "avi", "mov", "flv", "wmv", "m4v", "ts", "3gp",
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg",
        "bin", "jar", "pkg", "ttf", "otf",
    )

    /** Hosts whose pages are always handled by yt-dlp, even when the path looks like a file. */
    private val extractorHosts = listOf(
        "youtube.com", "youtu.be", "vimeo.com", "dailymotion.com", "twitter.com", "x.com", "facebook.com", "fb.watch",
        "instagram.com", "tiktok.com", "reddit.com", "twitch.tv", "soundcloud.com", "bilibili.com", "bandcamp.com",
    )

    fun kind(raw: String): LinkKind {
        val s = raw.trim()
        if (s.startsWith("magnet:", ignoreCase = true)) return LinkKind.MAGNET
        val uri = runCatching { Uri.parse(s) }.getOrNull() ?: return LinkKind.INVALID
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https" && scheme != "ftp" && scheme != "sftp") return LinkKind.INVALID
        val host = uri.host?.lowercase() ?: return LinkKind.INVALID
        val path = uri.path?.lowercase().orEmpty()
        val ext = path.substringAfterLast('.', "").substringBefore('/')

        if (ext == "torrent") return LinkKind.TORRENT_FILE
        if (ext == "m3u8" || ext == "mpd") return LinkKind.HLS_OR_DASH
        if (scheme == "ftp" || scheme == "sftp") return LinkKind.DIRECT_FILE
        if (extractorHosts.any { host == it || host.endsWith(".$it") }) return LinkKind.VIDEO_PAGE
        if (ext in fileExtensions) return LinkKind.DIRECT_FILE
        return LinkKind.VIDEO_PAGE
    }

    fun engineFor(kind: LinkKind): Engine = when (kind) {
        LinkKind.MAGNET, LinkKind.TORRENT_FILE, LinkKind.DIRECT_FILE -> Engine.ARIA2
        LinkKind.HLS_OR_DASH, LinkKind.VIDEO_PAGE -> Engine.YTDLP
        LinkKind.INVALID -> Engine.ARIA2
    }

    fun fileNameFromUrl(url: String): String {
        if (url.startsWith("magnet:", ignoreCase = true)) {
            val dn = Uri.parse(url).getQueryParameter("dn")
            return dn?.takeIf { it.isNotBlank() }?.sanitize() ?: "magnet-download"
        }
        val last = runCatching { Uri.parse(url).lastPathSegment }.getOrNull().orEmpty()
        return last.takeIf { it.isNotBlank() }?.sanitize() ?: "download-${System.currentTimeMillis()}"
    }

    private fun String.sanitize(): String = replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").take(180)

    /** Pulls the first URL or magnet link out of arbitrary shared text. */
    fun extractFirstLink(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val m = Regex("""(magnet:\?[^\s]+|(?:https?|ftp|sftp)://[^\s]+)""", RegexOption.IGNORE_CASE).find(text)
        return m?.value?.trimEnd('.', ',', ')', ']', '>', '"', '\'')
    }
}
