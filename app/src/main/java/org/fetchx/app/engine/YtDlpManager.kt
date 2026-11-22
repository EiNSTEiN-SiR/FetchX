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

data class QualityOption(
    val label: String,
    val selector: String,
    val audioOnly: Boolean,
    val approxBytes: Long,
)

data class ProbeResult(
    val title: String,
    val uploader: String?,
    val durationSec: Long,
    val options: List<QualityOption>,
)

data class YtProgress(
    val percent: Float,
    val totalBytes: Long,
    val doneBytes: Long,
    val speedBps: Long,
    val etaSec: Long,
)

class YtDlpManager(private val context: Context) {

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

    /** Updates the bundled yt-dlp from its GitHub releases. Returns a human-readable status. */
    suspend fun update(): String = withContext(Dispatchers.IO) {
        init()
        val before = version()
        val status = YoutubeDL.getInstance().updateYoutubeDL(context)
        val after = version()
        when {
            status == null -> "Update check finished (installed: ${after ?: "unknown"})"
            before != null && before == after -> "Already up to date (${after})"
            else -> "Updated yt-dlp: ${before ?: "?"} → ${after ?: "?"}"
        }
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

    private fun parse(root: JSONObject): ProbeResult {
        // A playlist-like result: use the first entry.
        val info = root.optJSONArray("entries")?.optJSONObject(0) ?: root
        val title = info.optString("title").ifBlank { root.optString("title", "video") }
        val formats = info.optJSONArray("formats")

        val heights = sortedSetOf<Int>()
        val sizeByHeight = HashMap<Int, Long>()
        var bestAudioSize = 0L
        var hasAudio = false

        if (formats != null) {
            for (i in 0 until formats.length()) {
                val f = formats.getJSONObject(i)
                val vcodec = f.optString("vcodec", "none")
                val acodec = f.optString("acodec", "none")
                val size = f.optLong("filesize", 0).takeIf { it > 0 } ?: f.optLong("filesize_approx", 0)
                if (vcodec != "none" && f.optInt("height", 0) > 0) {
                    val h = f.optInt("height")
                    heights += h
                    if (size > (sizeByHeight[h] ?: 0L)) sizeByHeight[h] = size
                }
                if (acodec != "none") {
                    hasAudio = true
                    if (vcodec == "none" && size > bestAudioSize) bestAudioSize = size
                }
            }
        }

        val options = mutableListOf<QualityOption>()
        options += QualityOption("Best available", "bv*+ba/b", false, 0)
        for (h in heights.descendingSet()) {
            val size = (sizeByHeight[h] ?: 0L) + bestAudioSize
            options += QualityOption(
                label = "${h}p",
                selector = "bv*[height<=$h]+ba/b[height<=$h]/b",
                audioOnly = false,
                approxBytes = size,
            )
        }
        if (hasAudio) options += QualityOption("Audio only (m4a)", "ba[ext=m4a]/ba/b", true, bestAudioSize)
        return ProbeResult(
            title = title,
            uploader = info.optString("uploader").takeIf { it.isNotBlank() },
            durationSec = info.optLong("duration", 0),
            options = options,
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
        selector: String?,
        audioOnly: Boolean,
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
        req.addOption("-f", selector ?: "bv*+ba/b")
        if (audioOnly) {
            req.addOption("-x")
            req.addOption("--audio-format", "m4a")
        } else {
            req.addOption("--merge-output-format", "mp4/mkv")
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
