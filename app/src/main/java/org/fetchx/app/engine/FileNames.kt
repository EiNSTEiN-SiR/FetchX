package org.fetchx.app.engine

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Cleans up output file names, mainly for HLS/DASH streams whose manifest is called "hls.m3u8", "index.m3u8" and so on. */
object FileNames {
    private val genericName = Regex(
        "^(hls|index|master|playlist|chunklist|manifest|stream|video|media|download|file|output|untitled)([-_. ]?[vaf]?\\d+)*$",
        RegexOption.IGNORE_CASE,
    )
    private val knownExt = Regex("\\.(m3u8|mpd|mp4|mkv|webm|m4v|mov|flv|avi|3gp|ts|m4a|mp3|opus|ogg|aac|flac|wav)$", RegexOption.IGNORE_CASE)
    private val trailingTag = Regex("^(.*?)\\s*\\[([^\\]]*)\\]\\s*$")

    val mediaExt = setOf("mp4", "mkv", "webm", "m4v", "mov", "flv", "avi", "3gp", "ts", "m4a", "mp3", "opus", "ogg", "aac", "flac", "wav")
    val audioExt = setOf("m4a", "mp3", "opus", "ogg", "aac", "flac", "wav")

    fun withoutKnownExt(name: String): String = name.replace(knownExt, "")

    /** True for empty names and for placeholder names such as "hls", "index", "master", "playlist" or "video". */
    fun isGeneric(name: String?): Boolean {
        val n = withoutKnownExt(name?.trim().orEmpty()).trim()
        return n.isEmpty() || genericName.matches(n)
    }

    /** Makes arbitrary text (a page title, for example) safe to use as a file name. */
    fun sanitize(text: String): String {
        var s = text
        s = s.replace(":", " -")
        s = s.replace(Regex("[\\\\/|]"), "-")
        s = s.replace(Regex("[*?\"<>]"), "")
        s = s.replace(Regex("[\\u0000-\\u001f]"), " ")
        s = s.replace(Regex("\\s+"), " ").trim().trim('.', ' ', '-')
        return s.take(150)
    }

    /** Removes trailing "[tag]" parts that repeat the title or are generic themselves ("hls [hls]" becomes "hls"). */
    fun stripRedundantTags(base: String): String {
        var cur = base.trim()
        while (true) {
            val m = trailingTag.find(cur) ?: break
            val rest = m.groupValues[1].trim()
            val tag = m.groupValues[2].trim()
            if (rest.isNotEmpty() && (tag.equals(rest, ignoreCase = true) || isGeneric(tag))) cur = rest else break
        }
        return cur
    }

    fun timestampName(audio: Boolean): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return (if (audio) "FetchX_Audio_" else "FetchX_Video_") + ts
    }

    /**
     * Picks the final base name (no extension) for a finished download:
     * 1. the name yt-dlp produced, if it is meaningful;
     * 2. the title stored with the task, if it is meaningful;
     * 3. the page's og:title or <title>;
     * 4. FetchX_Video_YYYYMMDD_HHMMSS.
     */
    suspend fun resolveBase(
        producedBase: String,
        taskTitle: String?,
        audio: Boolean,
        pageTitle: suspend () -> String?,
    ): String {
        val produced = stripRedundantTags(producedBase)
        if (!isGeneric(produced)) return produced

        val fromTask = taskTitle?.let { stripRedundantTags(withoutKnownExt(it)) }
        if (fromTask != null && !isGeneric(fromTask)) return sanitize(fromTask)

        val fromPage = pageTitle()?.let { sanitize(it) }
        if (!fromPage.isNullOrBlank() && !isGeneric(fromPage)) return fromPage

        return timestampName(audio)
    }
}
