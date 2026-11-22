package org.fetchx.app

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.fetchx.app.data.Status
import org.fetchx.app.service.DownloadController
import org.fetchx.app.service.QueueWorker

class App : Application() {
    val controller: DownloadController by lazy { DownloadController(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            runCatching { controller.ytdlp.init() }
            val pending = controller.dao.byStatus(Status.RUNNING) + controller.dao.byStatus(Status.QUEUED)
            if (pending.isNotEmpty()) QueueWorker.enqueue(this@App)
        }
    }
}
