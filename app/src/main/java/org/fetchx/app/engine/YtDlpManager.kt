package org.fetchx.app.engine

import android.content.Context
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale

data class FormatInfo(
    val id: String,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val height: Int,
    val fps: Int,
    val vcodec: String,
    val acodec: String,
    val ext: String,
    val tbr: Int,
    val sizeBytes: Long,
) {
    val label: String
        get() {
            val size = if (sizeBytes > 0) " • ~" + org.fetchx.app.ui.Format.bytes(sizeBytes) else ""
            val rate = if (tbr > 0) " • ${tbr}k" else ""
            return if (hasVideo) {
                val res = if (height > 0) "${height}p" + (if (fps > 30) fps.toString() else "") else "video"
                val audio = if (hasAudio) " • +audio" else ""
                "$res • ${YtDlpManager.codecName(vcodec)} • $ext$rate$audio$size  [$id]"
            } else {
                "${YtDlpManager.codecName(acodec)} • $ext$rate$size  [$id]"
            }
        }
}

data class ProbeResult(
    val title: String,
    val uploader: String?,
    val durationSec: Long,
    /** Available heights, highest first. */
    val heights: List<Int>,
    val videoFormats: List<FormatInfo>,
    val audioFormats: List<FormatInfo>,
)

data class YtProgress(
    val percent: Float,
    val totalBytes: Long,
    val doneBytes: Long,
    val speedBps: Long,
    val etaSec: Long,
)

class YtDlpManager(private val context: Context) {

    companion object {
        fun codecName(c: String): String = when {
            c.startsWith("av01") || c.startsWith("av1") -> "AV1"
            c.startsWith("vp09") || c.startsWith("vp9") -> "VP9"
            c.startsWith("vp8") -> "VP8"
            c.startsWith("avc") || c.startsWith("h264") -> "H.264"
            c.startsWith("hev") || c.startsWith("hvc") || c.startsWith("h265") -> "H.265"
            c.startsWith("mp4a") || c == "aac" -> "AAC"
            c.startsWith("opus") -> "Opus"
            c.startsWith("vorbis") -> "Vorbis"
            c.startsWith("ec-3") || c.startsWith("ac-3") -> "Dolby"
            c.startsWith("mp3") -> "MP3"
            else -> c.substringBefore('.').take(8)
        }
    }

    @Volatile private var ready = false

    @Synchronized
    fun init() {
        if (ready) return
        YoutubeDL.getInstance().init(context)
        FFmpeg.getInstance().init(context)
        Aria2c.getInstance().init(context)
        NativePaths.invalidate()
        ready = true
    }

    fun version(): String? = runCatching { YoutubeDL.getInstance().version(context) }.getOrNull()

    /** Looks up the nightly channel object without a compile-time dependency on its exact class layout. */
    private fun nightlyChannel(): Any? {
        for (n in listOf(
            "com.yausername.youtubedl_android.YoutubeDL\$UpdateChannel\$NIGHTLY",
            "com.yausername.youtubedl_android.UpdateChannel\$NIGHTLY",
        )) {
            runCatching { return Class.forName(n).getField("INSTANCE").get(null) }
        }
        for (n in listOf(
            "com.yausername.youtubedl_android.YoutubeDL\$UpdateChannel",
            "com.yausername.youtubedl_android.UpdateChannel",
        )) {
            runCatching { return Class.forName(n).getField("_NIGHTLY").get(null) }
        }
        return null
    }

    private fun updateNightly(ytdl: YoutubeDL): Boolean {
        val channel = nightlyChannel() ?: return false
        val m = ytdl.javaClass.methods.firstOrNull { it.name == "updateYoutubeDL" && it.parameterTypes.size == 2 } ?: return false
        m.invoke(ytdl, context, channel)
        return true
    }

    /** Updates the bundled yt-dlp from its GitHub releases (stable or nightly). Returns a human-readable status. */
    suspend fun update(nightly: Boolean): String = withContext(Dispatchers.IO) {
        init()
        val before = version()
        val ytdl = YoutubeDL.getInstance()
        var note = ""
        if (nightly) {
            val ok = runCatching { updateNightly(ytdl) }.getOrDefault(false)
            if (!ok) {
                note = " (nightly channel unavailable, used stable)"
                ytdl.updateYoutubeDL(context)
            }
        } else {
            ytdl.updateYoutubeDL(context)
        }
        val after = version()
        if (before != null && before == after) "Already up to date ($after)$note"
        else "Updated yt-dlp: ${before ?: "?"} → ${after ?: "?"}$note"
    }

    private fun applyNetworkOptions(
        req: YoutubeDLRequest,
        userAgent: String?,
        referer: String?,
        headers: String?,
        cookieFile: File?,
    ) {
        userAgent?.takeIf { it.isNotBlank() }?.let { req.addOption("--user-agent", it) }
        referer?.takeIf { it.isNotBlank() }?.let { req.addOption("--referer", it) }
        headers?.lines()?.map { it.trim() }?.filter { it.contains(':') }?.forEach { req.addOption("--add-header", it) }
        cookieFile?.takeIf { it.exists() && it.length() > 0 }?.let { req.addOption("--cookies", it.absolutePath) }
    }

    suspend fun probe(
        url: String,
        userAgent: String?,
        referer: String?,
        headers: String?,
        cookieFile: File?,
    ): ProbeResult = withContext(Dispatchers.IO) {
        init()
        val req = YoutubeDLRequest(url)
        req.addOption("-J")
        req.addOption("--no-playlist")
        req.addOption("--no-warnings")
        req.addOption("--socket-timeout", "20")
        applyNetworkOptions(req, userAgent, referer, headers, cookieFile)
        val out = YoutubeDL.getInstance().execute(req).out
        val start = out.indexOf('{')
        if (start < 0) error("yt-dlp returned no metadata")
        parse(JSONObject(out.substring(start)))
    }

    private fun JSONObject.str(key: String): String =
        optString(key, "").let { if (it == "null") "" else it }

    private fun parse(root: JSONObject): ProbeResult {
        // A playlist-like result: use the first entry.
        val info = root.optJSONArray("entries")?.optJSONObject(0) ?: root
        val title = info.optString("title").ifBlank { root.optString("title", "video") }
        val formats = info.optJSONArray("formats")

        val video = mutableListOf<FormatInfo>()
        val audio = mutableListOf<FormatInfo>()
        val heights = sortedSetOf<Int>()

        if (formats != null) {
            for (i in 0 until formats.length()) {
                val f = formats.optJSONObject(i) ?: continue
                val vc = f.str("vcodec").let { if (it.isBlank() || it == "none") "" else it }
                val ac = f.str("acodec").let { if (it.isBlank() || it == "none") "" else it }
                val ext = f.str("ext")
                val note = f.str("format_note")
                if (ext == "mhtml" || note.contains("storyboard", ignoreCase = true)) continue
                if (vc.isEmpty() && ac.isEmpty()) continue
                val id = f.str("format_id")
                if (id.isEmpty()) continue
                val size = f.optLong("filesize", 0).takeIf { it > 0 } ?: f.optLong("filesize_approx", 0)
                val height = f.optInt("height", 0)
                val tbr = f.optDouble("tbr", 0.0).let { if (it.isNaN()) 0 else it.toInt() }
                val fi = FormatInfo(
                    id = id,
                    hasVideo = vc.isNotEmpty(),
                    hasAudio = ac.isNotEmpty(),
                    height = height,
                    fps = f.optInt("fps", 0),
                    vcodec = vc,
                    acodec = ac,
                    ext = ext,
                    tbr = tbr,
                    sizeBytes = size,
                )
                if (fi.hasVideo) {
                    video += fi
                    if (height > 0) heights += height
                } else {
                    audio += fi
                }
            }
        }

        return ProbeResult(
            title = title,
            uploader = info.optString("uploader").takeIf { it.isNotBlank() },
            durationSec = info.optLong("duration", 0),
            heights = heights.descendingSet().toList(),
            videoFormats = video
                .sortedWith(compareByDescending<FormatInfo> { it.height }.thenByDescending { it.tbr })
                .take(60),
            audioFormats = audio.sortedByDescending { it.tbr }.take(30),
        )
    }

    /**
     * Runs a download into [workDir]. Partial files are kept (`--continue`), so calling this again after a pause,
     * failure or URL refresh resumes where it stopped. Returns the finished files.
     */
    suspend fun download(
        processId: String,
        url: String,
        workDir: File,
        options: YtOptions,
        threads: Int,
        userAgent: String?,
        referer: String?,
        headers: String?,
        cookieFile: File?,
        onProgress: (YtProgress) -> Unit,
    ): List<File> = withContext(Dispatchers.IO) {
        init()
        workDir.mkdirs()
        val req = YoutubeDLRequest(url)
        req.addOption("--no-playlist")
        req.addOption("--no-mtime")
        req.addOption("--continue")
        req.addOption("--newline")
        req.addOption("--no-warnings")
        req.addOption("--retries", "10")
        req.addOption("--fragment-retries", "10")
        req.addOption("--socket-timeout", "30")
        req.addOption("--concurrent-fragments", threads.coerceIn(1, 32).toString())
        req.addOption("--trim-filenames", "150")
        req.addOption("--windows-filenames")
        req.addOption("-P", workDir.absolutePath)
        req.addOption("-o", "%(title)s [%(id)s].%(ext)s")
        req.addOption("-f", options.selector)
        if (options.type == MediaType.AUDIO_ONLY) {
            req.addOption("-x")
            if (options.audioFormat == "original") {
                req.addOption("--audio-format", "best")
            } else {
                req.addOption("--audio-format", options.audioFormat)
                req.addOption("--audio-quality", if (options.audioQuality > 0) "${options.audioQuality}K" else "0")
            }
        } else {
            options.container?.let { req.addOption("--merge-output-format", it) }
        }
        if (options.embedMetadata) {
            req.addOption("--embed-metadata")
            req.addOption("--embed-chapters")
        }
        if (options.embedThumbnail) {
            req.addOption("--embed-thumbnail")
            req.addOption("--convert-thumbnails", "jpg")
        }
        if (options.subtitles) {
            req.addOption("--embed-subs")
            req.addOption("--sub-langs", "en.*,.*-orig")
        }
        if (options.sponsorBlock) {
            req.addOption("--sponsorblock-remove", "sponsor,selfpromo,interaction")
        }
        applyNetworkOptions(req, userAgent, referer, headers, cookieFile)

        YoutubeDL.getInstance().execute(req, processId = processId, callback = { progress, eta, line ->
            parseLine(progress, eta, line)?.let(onProgress)
        })

        val partial = setOf("part", "ytdl", "temp", "aria2", "tmp")
        workDir.listFiles().orEmpty()
            .filter { it.isFile && it.extension.lowercase() !in partial && it.length() > 0 }
            .also { check(it.isNotEmpty()) { "yt-dlp finished but produced no output file" } }
    }

    fun cancel(processId: String): Boolean = runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }.getOrDefault(false)

    private val sizeRegex = Regex("""of\s+~?\s*([\d.]+)\s*([KMGT]?i?B)""")
    private val speedRegex = Regex("""at\s+([\d.]+)\s*([KMGT]?i?B)/s""")

    private fun toBytes(value: String, unit: String): Long {
        val v = value.toDoubleOrNull() ?: return 0
        val mult = when (unit.uppercase(Locale.ROOT).removeSuffix("B").removeSuffix("I")) {
            "K" -> 1024.0
            "M" -> 1024.0 * 1024
            "G" -> 1024.0 * 1024 * 1024
            "T" -> 1024.0 * 1024 * 1024 * 1024
            else -> 1.0
        }
        return (v * mult).toLong()
    }

    private fun parseLine(progress: Float, eta: Long, line: String): YtProgress? {
        if (progress < 0f) return null
        val total = sizeRegex.find(line)?.let { toBytes(it.groupValues[1], it.groupValues[2]) } ?: 0L
        val speed = speedRegex.find(line)?.let { toBytes(it.groupValues[1], it.groupValues[2]) } ?: 0L
        val done = if (total > 0) (total * (progress / 100f)).toLong() else 0L
        return YtProgress(progress, total, done, speed, eta)
    }
}
