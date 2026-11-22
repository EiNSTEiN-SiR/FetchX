package org.fetchx.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.fetchx.app.App
import org.fetchx.app.data.Status
import org.fetchx.app.ui.Format

/** Keeps the process in the foreground while transfers are active and mirrors their state in one notification. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var monitor: Job? = null
    private var idleTicks = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            Notifications.ID,
            Notifications.build(this, "FetchX", "Starting…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        if (monitor == null) monitor = scope.launch { watch() }
        return START_NOT_STICKY
    }

    private suspend fun watch() {
        val dao = (application as App).controller.dao
        dao.observeActive().collectLatest { list ->
            val running = list.filter { it.status == Status.RUNNING }
            val queued = list.count { it.status == Status.QUEUED }
            if (running.isEmpty() && queued == 0) {
                if (++idleTicks >= 3) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return@collectLatest
            }
            idleTicks = 0
            val speed = running.sumOf { it.speedBps }
            val total = running.sumOf { it.totalBytes }
            val done = running.sumOf { it.doneBytes }
            val pct = if (total > 0) ((done * 100) / total).toInt() else -1
            val title = "${running.size} active" + if (queued > 0) ", $queued queued" else ""
            val text = if (running.size == 1) {
                running[0].title
            } else {
                "${Format.speed(speed)} total"
            }
            getSystemService(android.app.NotificationManager::class.java)
                .notify(Notifications.ID, Notifications.build(this, title, "${Format.speed(speed)} • $text", pct))
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
            }
        }
    }
}
