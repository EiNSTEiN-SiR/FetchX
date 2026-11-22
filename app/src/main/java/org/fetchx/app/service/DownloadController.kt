package org.fetchx.app.service

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.fetchx.app.data.AppDb
import org.fetchx.app.data.DownloadEntity
import org.fetchx.app.data.Engine
import org.fetchx.app.data.SettingsRepo
import org.fetchx.app.data.Status
import org.fetchx.app.engine.Aria2Job
import org.fetchx.app.engine.Aria2Manager
import org.fetchx.app.engine.Aria2Poll
import org.fetchx.app.engine.Exporter
import org.fetchx.app.engine.LinkDetector
import org.fetchx.app.engine.YtDlpManager
import org.fetchx.app.engine.YtProgress
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns every running transfer. The foreground service and the WorkManager worker only keep the process alive;
 * all state transitions happen here and are persisted in Room.
 */
class DownloadController(private val context: Context) {
    val db = AppDb.get(context)
    val dao = db.downloads()
    val settings = SettingsRepo(context)
    val aria = Aria2Manager(context)
    val ytdlp = YtDlpManager(context)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = ConcurrentHashMap<Long, Job>()
    private val stopping = ConcurrentHashMap<Long, Status>()
    private val scheduleLock = Mutex()

    val cookieFile: File get() = File(context.filesDir, "cookies.txt")

    fun workDir(id: Long): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "work/$id")

    fun hasActiveJobs(): Boolean = jobs.isNotEmpty()

    // ---------------------------------------------------------------- enqueue

    suspend fun enqueue(
        url: String,
        engine: Engine,
        title: String?,
        formatSelector: String?,
        audioOnly: Boolean,
        threads: Int,
        destTreeUri: String?,
        userAgent: String?,
        referer: String?,
        headers: String?,
    ): Long {
        val s = settings.flow.first()
        val isMagnet = url.startsWith("magnet:", ignoreCase = true)
        val name = LinkDetector.fileNameFromUrl(url)
        val entity = DownloadEntity(
            url = url,
            pageUrl = if (engine == Engine.YTDLP) url else null,
            title = title?.takeIf { it.isNotBlank() } ?: name,
            engine = engine,
            formatSelector = formatSelector,
            audioOnly = audioOnly,
            threads = threads.coerceIn(1, 32),
            destTreeUri = destTreeUri ?: s.destTreeUri.ifBlank { null },
            userAgent = userAgent?.takeIf { it.isNotBlank() } ?: s.userAgent.ifBlank { null },
            referer = referer?.takeIf { it.isNotBlank() } ?: s.referer.ifBlank { null },
            headers = headers?.takeIf { it.isNotBlank() },
            useCookies = cookieFile.exists() && cookieFile.length() > 0,
            outName = if (engine == Engine.ARIA2 && !isMagnet && LinkDetector.kind(url).name != "TORRENT_FILE") name else null,
        )
        val id = dao.insert(entity)
        schedule()
        return id
    }

    // ---------------------------------------------------------------- control

    fun resume(id: Long) {
        scope.launch {
            val t = dao.get(id) ?: return@launch
            if (t.status == Status.RUNNING || t.status == Status.COMPLETED) return@launch
            dao.setStatus(id, Status.QUEUED)
            schedule()
        }
    }

    fun pause(id: Long) {
        scope.launch { stop(id, Status.PAUSED) }
    }

    /** Stops the transfer and removes the task together with its partial data. */
    fun cancel(id: Long) {
        scope.launch {
            stop(id, Status.CANCELED)
            workDir(id).deleteRecursively()
            dao.delete(id)
        }
    }

    fun delete(id: Long) {
        scope.launch {
            val t = dao.get(id)
            if (t != null && t.status != Status.COMPLETED) stop(id, Status.CANCELED)
            workDir(id).deleteRecursively()
            dao.delete(id)
        }
    }

    fun pauseAll() {
        scope.launch {
            dao.byStatus(Status.RUNNING).forEach { stop(it.id, Status.PAUSED) }
            dao.byStatus(Status.QUEUED).forEach { dao.setStatus(it.id, Status.PAUSED) }
        }
    }

    fun resumeAll() {
        scope.launch {
            dao.byStatus(Status.PAUSED).forEach { dao.setStatus(it.id, Status.QUEUED) }
            dao.byStatus(Status.FAILED).forEach { dao.setStatus(it.id, Status.QUEUED) }
            schedule()
        }
    }

    private suspend fun stop(id: Long, reason: Status) {
        val t = dao.get(id) ?: return
        stopping[id] = reason
        val job = jobs[id]
        if (t.engine == Engine.YTDLP) ytdlp.cancel(processId(id))
        if (t.engine == Engine.ARIA2 && t.gid != null) {
            if (reason == Status.PAUSED) aria.pause(t.gid) else aria.remove(t.gid)
        }
        job?.cancel()
        job?.join()
        stopping.remove(id)
        if (reason == Status.PAUSED) dao.setStatus(id, Status.PAUSED)
    }

    /**
     * Replaces the URL of a paused or failed task. Downloaded bytes are kept: aria2 resumes the same partial file
     * (via changeUri on a live gid, or its .aria2 control file), and yt-dlp resumes its .part files.
     */
    fun refreshUrl(id: Long, newUrl: String, onDone: (Result<Unit>) -> Unit = {}) {
        scope.launch {
            val result = runCatching {
                val t = dao.get(id) ?: error("Task not found")
                require(t.status != Status.COMPLETED) { "Task is already completed" }
                if (t.status == Status.RUNNING) stop(id, Status.PAUSED)
                var gid = t.gid
                if (t.engine == Engine.ARIA2 && gid != null) {
                    if (!aria.changeUri(gid, t.url, newUrl)) gid = null
                }
                dao.update(
                    t.copy(
                        url = newUrl,
                        pageUrl = if (t.engine == Engine.YTDLP) newUrl else t.pageUrl,
                        status = Status.PAUSED,
                        error = null,
                        gid = gid,
                        speedBps = 0,
                        etaSec = -1,
                        connections = 0,
                    ),
                )
            }
            withContext(Dispatchers.Main) { onDone(result) }
        }
    }

    // ---------------------------------------------------------------- scheduling

    /** Called at start-up and from the worker: tasks that were running when the process died go back to the queue. */
    suspend fun recoverInterrupted() {
        dao.byStatus(Status.RUNNING).filter { !jobs.containsKey(it.id) }.forEach {
            dao.setStatus(it.id, Status.QUEUED)
        }
        schedule()
    }

    suspend fun schedule() = scheduleLock.withLock {
        val max = settings.flow.first().maxConcurrent
        val free = max - jobs.size
        if (free <= 0) return@withLock
        val queued = dao.byStatus(Status.QUEUED).filter { !jobs.containsKey(it.id) }.take(free)
        for (t in queued) launchTask(t)
    }

    private fun launchTask(t: DownloadEntity) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                dao.setStatus(t.id, Status.RUNNING)
                DownloadService.start(context)
                when (t.engine) {
                    Engine.ARIA2 -> runAria(t.id)
                    Engine.YTDLP -> runYt(t.id)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (stopping[t.id] == null) {
                    dao.setStatus(t.id, Status.FAILED, e.message ?: e.javaClass.simpleName)
                }
            } finally {
                jobs.remove(t.id)
                scope.launch { schedule() }
            }
        }
        jobs[t.id] = job
        job.start()
    }

    // ---------------------------------------------------------------- aria2

    private suspend fun runAria(id: Long) {
        var t = dao.get(id) ?: return
        val job = Aria2Job(
            url = t.url,
            dir = workDir(id),
            outName = t.outName,
            threads = t.threads,
            userAgent = t.userAgent,
            referer = t.referer,
            headers = t.headers?.lines()?.map { it.trim() }?.filter { it.contains(':') }.orEmpty(),
            cookieFile = if (t.useCookies) cookieFile else null,
        )
        withContext(Dispatchers.IO) { ytdlp.init() }
        aria.ensureStarted()

        var gid = t.gid
        if (gid == null || !aria.unpause(gid)) {
            gid = aria.add(job)
            dao.setGid(id, gid)
        }

        var missing = 0
        while (true) {
            delay(1000)
            when (val p = aria.poll(gid!!)) {
                is Aria2Poll.Progress -> {
                    missing = 0
                    if (p.gid != gid) {
                        gid = p.gid
                        dao.setGid(id, gid)
                    }
                    val eta = if (p.speed > 0 && p.total > p.done) (p.total - p.done) / p.speed else -1
                    dao.setProgress(id, p.total, p.done, p.speed, eta, p.connections)
                }

                is Aria2Poll.Finished -> {
                    t = dao.get(id) ?: return
                    finish(t, p.files, p.total)
                    return
                }

                is Aria2Poll.Failed -> {
                    dao.setStatus(id, Status.FAILED, p.message)
                    return
                }

                Aria2Poll.Paused -> {
                    // Paused behind our back; resume it.
                    aria.unpause(gid)
                }

                Aria2Poll.Missing -> {
                    // The daemon restarted or lost the gid: add again, aria2 resumes from the .aria2 control file.
                    if (++missing >= 2) {
                        gid = aria.add(job)
                        dao.setGid(id, gid)
                        missing = 0
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- yt-dlp

    private fun processId(id: Long) = "dl-$id"

    private suspend fun runYt(id: Long) {
        val t = dao.get(id) ?: return
        val latest = AtomicReference<YtProgress?>(null)
        val ticker = scope.launch {
            while (true) {
                delay(1000)
                latest.get()?.let { p ->
                    dao.setProgress(id, p.totalBytes, p.doneBytes, p.speedBps, p.etaSec, t.threads)
                }
            }
        }
        try {
            val files = ytdlp.download(
                processId = processId(id),
                url = t.url,
                workDir = workDir(id),
                selector = t.formatSelector,
                audioOnly = t.audioOnly,
                threads = t.threads,
                userAgent = t.userAgent,
                referer = t.referer,
                headers = t.headers,
                cookieFile = if (t.useCookies) cookieFile else null,
                onProgress = { latest.set(it) },
            )
            ticker.cancel()
            finish(dao.get(id) ?: return, files, files.sumOf { it.length() })
        } finally {
            ticker.cancel()
        }
    }

    // ---------------------------------------------------------------- completion

    private suspend fun finish(t: DownloadEntity, files: List<File>, total: Long) = withContext(Dispatchers.IO) {
        val existing = files.filter { it.exists() && it.isFile }
        if (existing.isEmpty()) {
            dao.setStatus(t.id, Status.FAILED, "Download finished but no output file was found")
            return@withContext
        }
        try {
            var firstUri: String? = null
            for (f in existing) {
                val uri = Exporter.export(context, f, t.destTreeUri)
                if (firstUri == null) firstUri = uri
            }
            val finalTitle = existing.singleOrNull()?.name ?: t.title
            dao.update((dao.get(t.id) ?: t).copy(title = finalTitle, totalBytes = total, doneBytes = total))
            dao.markCompleted(t.id, firstUri, System.currentTimeMillis())
            t.gid?.let { aria.remove(it) }
            workDir(t.id).deleteRecursively()
        } catch (e: Exception) {
            dao.setStatus(t.id, Status.FAILED, "Could not save file: ${e.message}")
        }
    }

    fun shutdown() {
        aria.shutdown()
    }
}
