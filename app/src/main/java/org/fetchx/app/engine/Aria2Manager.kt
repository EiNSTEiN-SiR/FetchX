package org.fetchx.app.engine

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class Aria2Exception(message: String) : Exception(message)

sealed interface Aria2Poll {
    data class Progress(
        val total: Long,
        val done: Long,
        val speed: Long,
        val connections: Int,
        val gid: String,
    ) : Aria2Poll

    data class Finished(val files: List<File>, val total: Long) : Aria2Poll
    data class Failed(val message: String) : Aria2Poll
    data object Paused : Aria2Poll
    data object Missing : Aria2Poll
}

data class Aria2Job(
    val url: String,
    val dir: File,
    val outName: String?,
    val threads: Int,
    val userAgent: String?,
    val referer: String?,
    val headers: List<String>,
    val cookieFile: File?,
)

/**
 * Runs one aria2c daemon and talks to it over JSON-RPC (HTTP, localhost, random port, random secret).
 */
class Aria2Manager(private val context: Context) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val startLock = Mutex()
    private val ids = AtomicInteger()
    private var process: Process? = null
    private var port = 0
    private val secret = UUID.randomUUID().toString().replace("-", "")
    private val json = "application/json".toMediaType()

    private fun alive(): Boolean = process?.let { p ->
        try {
            p.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        }
    } ?: false

    private val tuning = listOf(
        "--max-concurrent-downloads=16",
        "--async-dns=false",
        "--console-log-level=warn",
        "--summary-interval=0",
        "--file-allocation=none",
        "--auto-file-renaming=false",
        "--allow-overwrite=true",
        "--continue=true",
        "--always-resume=true",
        "--max-tries=6",
        "--retry-wait=3",
        "--timeout=30",
        "--connect-timeout=15",
        "--seed-time=0",
        "--bt-enable-lpd=true",
        "--enable-dht=true",
        "--enable-dht6=false",
        "--bt-save-metadata=false",
        "--follow-torrent=true",
        "--check-integrity=false",
        "--disk-cache=16M",
    )

    /** Starts aria2c with [extra] options. Returns null on success, otherwise the captured error output. */
    private suspend fun tryStart(bin: File, extra: List<String>): String? {
        port = ServerSocket(0).use { it.localPort }
        val args = mutableListOf(
            bin.absolutePath,
            "--enable-rpc=true",
            "--rpc-listen-port=$port",
            "--rpc-listen-all=false",
            "--rpc-secret=$secret",
        )
        args += extra
        NativePaths.caBundle(context)?.let { args += "--ca-certificate=${it.absolutePath}" }

        val pb = ProcessBuilder(args).redirectErrorStream(true)
        pb.environment().apply {
            put("LD_LIBRARY_PATH", NativePaths.libraryDirs(context))
            put("HOME", context.filesDir.absolutePath)
            put("TMPDIR", context.cacheDir.absolutePath)
        }
        pb.directory(context.filesDir)
        val p = try {
            pb.start()
        } catch (e: Exception) {
            return "could not launch ${bin.absolutePath}: ${e.message}"
        }
        process = p
        val output = StringBuffer()
        Thread {
            runCatching {
                p.inputStream.bufferedReader().forEachLine {
                    Log.d("aria2c", it)
                    if (output.length < 1500) output.append(it).append(" | ")
                }
            }
        }.apply { isDaemon = true }.start()

        repeat(40) {
            if (!alive()) {
                delay(300)
                return "exit ${runCatching { p.exitValue() }.getOrNull()}: ${output.toString().ifBlank { "no output" }}"
            }
            try {
                rpc("aria2.getVersion")
                return null
            } catch (_: Exception) {
                delay(150)
            }
        }
        runCatching { p.destroy() }
        process = null
        return "RPC did not respond: ${output.toString().ifBlank { "no output" }}"
    }

    suspend fun ensureStarted() = startLock.withLock {
        if (alive()) return@withLock
        withContext(Dispatchers.IO) {
            val bin = NativePaths.aria2Binary(context)
                ?: throw Aria2Exception("aria2c binary not found in the app package")
            bin.setExecutable(true, false)

            val first = tryStart(bin, tuning) ?: return@withContext
            shutdown()
            // Retry with RPC options only, in case this aria2 build rejects one of the tuning options.
            val second = tryStart(bin, emptyList()) ?: return@withContext
            shutdown()
            throw Aria2Exception("aria2c failed to start. [full] $first [minimal] $second [bin] ${bin.absolutePath}")
        }
    }

    fun shutdown() {
        runCatching { process?.destroy() }
        process = null
    }

    private suspend fun rpc(method: String, vararg params: Any?): Any? = withContext(Dispatchers.IO) {
        val arr = JSONArray().put("token:$secret")
        params.forEach { arr.put(it) }
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", ids.incrementAndGet().toString())
            .put("method", method)
            .put("params", arr)
            .toString()
        val req = Request.Builder()
            .url("http://127.0.0.1:$port/jsonrpc")
            .post(body.toRequestBody(json))
            .build()
        val call = try {
            http.newCall(req).execute()
        } catch (e: java.io.IOException) {
            throw Aria2Exception("aria2c is not reachable: ${e.message}")
        }
        call.use { resp ->
            val text = resp.body?.string().orEmpty()
            val obj = runCatching { JSONObject(text) }.getOrNull()
                ?: throw Aria2Exception("Bad RPC response (${resp.code})")
            obj.optJSONObject("error")?.let { throw Aria2Exception(it.optString("message", "RPC error")) }
            obj.opt("result")
        }
    }

    private fun optionsFor(job: Aria2Job, isBt: Boolean): JSONObject {
        val o = JSONObject()
        o.put("dir", job.dir.absolutePath)
        if (!isBt) {
            job.outName?.let { o.put("out", it) }
            val threads = job.threads.coerceIn(1, 32)
            o.put("split", threads.toString())
            // aria2 refuses more than 16 connections to a single server.
            o.put("max-connection-per-server", threads.coerceAtMost(16).toString())
            o.put("min-split-size", "1M")
            o.put("lowest-speed-limit", "0")
        }
        job.userAgent?.takeIf { it.isNotBlank() }?.let { o.put("user-agent", it) }
        job.referer?.takeIf { it.isNotBlank() }?.let { o.put("referer", it) }
        if (job.headers.isNotEmpty()) o.put("header", JSONArray(job.headers))
        job.cookieFile?.takeIf { it.exists() }?.let { o.put("load-cookies", it.absolutePath) }
        return o
    }

    /** Starts the job and returns its gid. */
    suspend fun add(job: Aria2Job): String {
        ensureStarted()
        job.dir.mkdirs()
        val url = job.url
        return when {
            url.startsWith("magnet:", ignoreCase = true) ->
                rpc("aria2.addUri", JSONArray().put(url), optionsFor(job, true)) as String

            LinkDetector.kind(url) == LinkKind.TORRENT_FILE -> {
                val bytes = withContext(Dispatchers.IO) {
                    val rb = Request.Builder().url(url)
                    job.userAgent?.takeIf { it.isNotBlank() }?.let { rb.header("User-Agent", it) }
                    job.referer?.takeIf { it.isNotBlank() }?.let { rb.header("Referer", it) }
                    OkHttpClient().newCall(rb.build()).execute().use { r ->
                        if (!r.isSuccessful) throw Aria2Exception("Torrent file request failed: HTTP ${r.code}")
                        r.body?.bytes() ?: throw Aria2Exception("Empty torrent file")
                    }
                }
                val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                rpc("aria2.addTorrent", b64, JSONArray(), optionsFor(job, true)) as String
            }

            else -> rpc("aria2.addUri", JSONArray().put(url), optionsFor(job, false)) as String
        }
    }

    suspend fun pause(gid: String): Boolean = runCatching { rpc("aria2.forcePause", gid); true }.getOrDefault(false)

    suspend fun unpause(gid: String): Boolean = runCatching { rpc("aria2.unpause", gid); true }.getOrDefault(false)

    /** Replaces the source URI of a live download without discarding downloaded bytes. */
    suspend fun changeUri(gid: String, oldUri: String, newUri: String): Boolean = runCatching {
        rpc("aria2.changeUri", gid, 1, JSONArray().put(oldUri), JSONArray().put(newUri))
        true
    }.getOrDefault(false)

    suspend fun remove(gid: String) {
        if (!alive()) return
        runCatching { rpc("aria2.forceRemove", gid) }
        runCatching { rpc("aria2.removeDownloadResult", gid) }
    }

    suspend fun poll(gid: String): Aria2Poll {
        if (!alive()) return Aria2Poll.Missing
        val keys = JSONArray(
            listOf("status", "totalLength", "completedLength", "downloadSpeed", "connections", "errorMessage", "followedBy", "files"),
        )
        val st = try {
            rpc("aria2.tellStatus", gid, keys) as? JSONObject
        } catch (e: Exception) {
            return Aria2Poll.Missing
        } ?: return Aria2Poll.Missing

        val total = st.optString("totalLength", "0").toLongOrNull() ?: 0
        val done = st.optString("completedLength", "0").toLongOrNull() ?: 0
        val speed = st.optString("downloadSpeed", "0").toLongOrNull() ?: 0
        val conns = st.optString("connections", "0").toIntOrNull() ?: 0
        val followed = st.optJSONArray("followedBy")

        return when (st.optString("status")) {
            "complete" -> {
                if (followed != null && followed.length() > 0) {
                    // Magnet metadata finished; the real transfer continues under a new gid.
                    Aria2Poll.Progress(0, 0, 0, 0, followed.getString(0))
                } else {
                    val files = st.optJSONArray("files")
                    val out = mutableListOf<File>()
                    if (files != null) {
                        for (i in 0 until files.length()) {
                            val f = files.getJSONObject(i)
                            if (f.optString("selected", "true") == "false") continue
                            val path = f.optString("path")
                            if (path.isNotBlank() && !path.startsWith("[METADATA]")) out += File(path)
                        }
                    }
                    Aria2Poll.Finished(out, total)
                }
            }

            "error" -> Aria2Poll.Failed(st.optString("errorMessage", "aria2 error"))
            "removed" -> Aria2Poll.Missing
            "paused" -> Aria2Poll.Paused
            else -> Aria2Poll.Progress(total, done, speed, conns, gid)
        }
    }
}
