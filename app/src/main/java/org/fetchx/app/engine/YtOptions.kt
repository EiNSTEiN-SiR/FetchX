package org.fetchx.app.engine

import org.json.JSONObject

enum class MediaType(val label: String) {
    VIDEO_AUDIO("Video + audio"),
    VIDEO_ONLY("Video only"),
    AUDIO_ONLY("Audio only"),
}

enum class CodecPref(val label: String, val regex: String?) {
    AUTO("Auto", null),
    AV1("AV1", "^av0?1"),
    VP9("VP9", "^vp0?9"),
    H264("H.264", "^(avc|h264)"),
    H265("H.265", "^(hev|hvc|h265)"),
}

enum class ContainerPref(val label: String, val value: String?) {
    AUTO("Auto", null),
    MP4("MP4", "mp4"),
    MKV("MKV", "mkv"),
    WEBM("WebM", "webm"),
}

enum class AudioFormatPref(val label: String, val value: String) {
    ORIGINAL("Original", "original"),
    M4A("M4A", "m4a"),
    MP3("MP3", "mp3"),
    OPUS("Opus", "opus"),
}

/** Audio bitrates in kbps offered for converted audio; 0 means best quality. */
val AUDIO_QUALITIES = listOf(0, 320, 256, 192, 128, 96, 64)

data class YtOptions(
    val selector: String = "bv*+ba/b",
    val type: MediaType = MediaType.VIDEO_AUDIO,
    val container: String? = null,
    val audioFormat: String = "original",
    val audioQuality: Int = 0,
    val embedMetadata: Boolean = false,
    val embedThumbnail: Boolean = false,
    val subtitles: Boolean = false,
    val sponsorBlock: Boolean = false,
) {
    fun toJson(): String = JSONObject()
        .put("selector", selector)
        .put("type", type.name)
        .put("container", container ?: "")
        .put("audioFormat", audioFormat)
        .put("audioQuality", audioQuality)
        .put("embedMetadata", embedMetadata)
        .put("embedThumbnail", embedThumbnail)
        .put("subtitles", subtitles)
        .put("sponsorBlock", sponsorBlock)
        .toString()

    companion object {
        fun fromJson(s: String?): YtOptions {
            if (s.isNullOrBlank()) return YtOptions()
            return runCatching {
                val o = JSONObject(s)
                YtOptions(
                    selector = o.optString("selector", "bv*+ba/b").ifBlank { "bv*+ba/b" },
                    type = runCatching { MediaType.valueOf(o.optString("type")) }.getOrDefault(MediaType.VIDEO_AUDIO),
                    container = o.optString("container", "").ifBlank { null },
                    audioFormat = o.optString("audioFormat", "original"),
                    audioQuality = o.optInt("audioQuality", 0),
                    embedMetadata = o.optBoolean("embedMetadata", false),
                    embedThumbnail = o.optBoolean("embedThumbnail", false),
                    subtitles = o.optBoolean("subtitles", false),
                    sponsorBlock = o.optBoolean("sponsorBlock", false),
                )
            }.getOrDefault(YtOptions())
        }
    }
}

/** Builds yt-dlp `-f` expressions. Every chain ends in a plain fallback so a download never fails on a missing codec. */
object FormatSelector {
    fun build(type: MediaType, height: Int, codec: CodecPref): String {
        val h = if (height > 0) "[height<=$height]" else ""
        val c = codec.regex?.let { "[vcodec~='$it']" }.orEmpty()
        val parts = mutableListOf<String>()
        when (type) {
            MediaType.AUDIO_ONLY -> {
                parts += "ba"
                parts += "b"
            }

            MediaType.VIDEO_AUDIO -> {
                if (c.isNotEmpty()) parts += "bv*$c$h+ba"
                parts += "bv*$h+ba"
                parts += "b$h"
                parts += "b"
            }

            MediaType.VIDEO_ONLY -> {
                if (c.isNotEmpty()) parts += "bv$c$h"
                parts += "bv$h"
                parts += "b$h"
                parts += "b"
            }
        }
        return parts.distinct().joinToString("/")
    }
}
