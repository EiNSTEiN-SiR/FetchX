package org.fetchx.app.ui

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fetchx.app.App
import org.fetchx.app.data.AppSettings
import org.fetchx.app.data.DownloadEntity
import org.fetchx.app.data.Engine
import org.fetchx.app.data.ThemeMode
import org.fetchx.app.data.Wallpaper
import org.fetchx.app.engine.AudioFormatPref
import org.fetchx.app.engine.CodecPref
import org.fetchx.app.engine.ContainerPref
import org.fetchx.app.engine.FileNames
import org.fetchx.app.engine.FormatSelector
import org.fetchx.app.engine.LinkDetector
import org.fetchx.app.engine.MediaType
import org.fetchx.app.engine.YtOptions
import org.fetchx.app.engine.LinkKind
import org.fetchx.app.engine.ProbeResult

data class AddState(
    val input: String = "",
    val link: String = "",
    val kind: LinkKind = LinkKind.INVALID,
    val probing: Boolean = false,
    val probe: ProbeResult? = null,
    val probeError: String? = null,
    val selected: Int = 0,
    val threads: Int = 16,
    val destTreeUri: String? = null,
    val forceDirect: Boolean = false,
    val userAgent: String = "",
    val referer: String = "",
    val headers: String = "",
    val type: MediaType = MediaType.VIDEO_AUDIO,
    val custom: Boolean = false,
    val videoId: String? = null,
    val audioId: String? = null,
    val codec: CodecPref = CodecPref.AUTO,
    val container: ContainerPref = ContainerPref.AUTO,
    val audioFmt: AudioFormatPref = AudioFormatPref.ORIGINAL,
    val audioQuality: Int = 0,
    val embedMetadata: Boolean = false,
    val embedThumbnail: Boolean = false,
    val subtitles: Boolean = false,
    val sponsorBlock: Boolean = false,
) {
    val engine: Engine get() = if (forceDirect) Engine.ARIA2 else LinkDetector.engineFor(kind)
    val valid: Boolean get() = kind != LinkKind.INVALID && link.isNotBlank()
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val controller = (app as App).controller
    private val settingsRepo = controller.settings

    val settings: StateFlow<AppSettings?> = settingsRepo.flow.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val active: StateFlow<List<DownloadEntity>> =
        controller.dao.observeActive().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val completed: StateFlow<List<DownloadEntity>> =
        controller.dao.observeCompleted().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _add = MutableStateFlow<AddState?>(null)
    val add: StateFlow<AddState?> = _add.asStateFlow()

    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    private val _clipboardOffer = MutableStateFlow<String?>(null)
    val clipboardOffer: StateFlow<String?> = _clipboardOffer.asStateFlow()

    private val _ytVersion = MutableStateFlow<String?>(null)
    val ytVersion: StateFlow<String?> = _ytVersion.asStateFlow()
    private val _updateStatus = MutableStateFlow<String?>(null)
    val updateStatus: StateFlow<String?> = _updateStatus.asStateFlow()
    private val _updating = MutableStateFlow(false)
    val updating: StateFlow<Boolean> = _updating.asStateFlow()
    private val _cookiesLoaded = MutableStateFlow(controller.cookieFile.exists() && controller.cookieFile.length() > 0)
    val cookiesLoaded: StateFlow<Boolean> = _cookiesLoaded.asStateFlow()

    private var probeJob: Job? = null
    private var lastClip: String? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { controller.ytdlp.init() }
            _ytVersion.value = controller.ytdlp.version()
        }
    }

    // ------------------------------------------------------------ add sheet

    fun showAdd(prefill: String? = null) {
        val s = settings.value ?: AppSettings()
        _add.value = AddState(threads = s.defaultThreads, destTreeUri = s.destTreeUri.ifBlank { null })
        if (!prefill.isNullOrBlank()) setInput(prefill)
    }

    fun dismissAdd() {
        probeJob?.cancel()
        _add.value = null
    }

    fun setInput(raw: String) {
        val link = LinkDetector.extractFirstLink(raw) ?: raw.trim()
        val kind = if (link.isBlank()) LinkKind.INVALID else LinkDetector.kind(link)
        probeJob?.cancel()
        _add.value = _add.value?.copy(
            input = raw, link = link, kind = kind, probe = null, probeError = null, probing = false,
            selected = 0, forceDirect = false,
        )
        if (kind == LinkKind.VIDEO_PAGE || kind == LinkKind.HLS_OR_DASH) {
            probeJob = viewModelScope.launch {
                delay(700)
                probe()
            }
        }
    }

    fun probe() {
        val st = _add.value ?: return
        if (st.engine != Engine.YTDLP) return
        probeJob?.cancel()
        _add.value = st.copy(probing = true, probeError = null, probe = null)
        probeJob = viewModelScope.launch {
            val cur = _add.value ?: return@launch
            val s = settings.value ?: AppSettings()
            val cookies = controller.cookieFile.takeIf { it.exists() && it.length() > 0 }
            val result = runCatching {
                controller.ytdlp.probe(
                    url = cur.link,
                    userAgent = cur.userAgent.ifBlank { s.userAgent },
                    referer = cur.referer.ifBlank { s.referer },
                    headers = cur.headers,
                    cookieFile = cookies,
                )
            }
            val now = _add.value ?: return@launch
            if (now.link != cur.link) return@launch
            _add.value = result.fold(
                onSuccess = { now.copy(probing = false, probe = it, selected = 0) },
                onFailure = {
                    val msg = it.message.orEmpty().lines().lastOrNull { l -> l.isNotBlank() }.orEmpty().take(200)
                    now.copy(probing = false, probeError = msg.ifBlank { "Could not extract formats" })
                },
            )
        }
    }

    fun selectQuality(i: Int) { _add.value = _add.value?.copy(selected = i) }
    fun setThreads(n: Int) { _add.value = _add.value?.copy(threads = n.coerceIn(1, 32)) }
    fun setAddDest(uri: String?) { _add.value = _add.value?.copy(destTreeUri = uri) }
    fun setForceDirect(v: Boolean) { _add.value = _add.value?.copy(forceDirect = v, probeError = null) }
    fun setAddUserAgent(v: String) { _add.value = _add.value?.copy(userAgent = v) }
    fun setAddReferer(v: String) { _add.value = _add.value?.copy(referer = v) }
    fun setAddHeaders(v: String) { _add.value = _add.value?.copy(headers = v) }

    fun updateAdd(f: (AddState) -> AddState) {
        _add.value = _add.value?.let(f)
    }

    /** Turns the add-sheet choices into the options stored with the task. Null means nothing is selected. */
    private fun buildOptions(st: AddState): YtOptions? {
        val extras = YtOptions(
            embedMetadata = st.embedMetadata,
            embedThumbnail = st.embedThumbnail,
            subtitles = st.subtitles,
            sponsorBlock = st.sponsorBlock,
        )
        val p = st.probe ?: return extras
        if (st.custom) {
            val v = st.videoId
            val a = st.audioId
            if (v == null && a == null) return null
            val type = when {
                v != null && a == null -> MediaType.VIDEO_ONLY
                v == null -> MediaType.AUDIO_ONLY
                else -> MediaType.VIDEO_AUDIO
            }
            return extras.copy(
                selector = listOfNotNull(v, a).joinToString("+"),
                type = type,
                container = if (type == MediaType.AUDIO_ONLY) null else st.container.value,
                audioFormat = st.audioFmt.value,
                audioQuality = st.audioQuality,
            )
        }
        val heights = listOf(0) + p.heights
        val h = heights.getOrElse(st.selected) { 0 }
        return extras.copy(
            selector = FormatSelector.build(st.type, h, st.codec),
            type = st.type,
            container = if (st.type == MediaType.AUDIO_ONLY) null else st.container.value,
            audioFormat = st.audioFmt.value,
            audioQuality = st.audioQuality,
        )
    }

    fun submit() {
        val st = _add.value ?: return
        if (!st.valid || st.probing) return
        val opts = if (st.engine == Engine.YTDLP) buildOptions(st) else null
        if (st.engine == Engine.YTDLP && opts == null) {
            messages.tryEmit("Pick at least one video or audio stream")
            return
        }
        viewModelScope.launch {
            controller.enqueue(
                url = st.link,
                engine = st.engine,
                title = st.probe?.title,
                formatSelector = null,
                audioOnly = false,
                threads = st.threads,
                destTreeUri = st.destTreeUri,
                userAgent = st.userAgent,
                referer = st.referer,
                headers = st.headers,
                ytOptions = opts,
            )
            messages.tryEmit("Added to queue")
        }
        dismissAdd()
    }

    // ------------------------------------------------------------ clipboard

    fun checkClipboard(text: String?) {
        val s = settings.value ?: return
        if (!s.clipboardWatch || _add.value != null) return
        val link = LinkDetector.extractFirstLink(text) ?: return
        if (link == lastClip) return
        lastClip = link
        if (LinkDetector.kind(link) != LinkKind.INVALID) _clipboardOffer.value = link
    }

    fun clearClipboardOffer() { _clipboardOffer.value = null }

    /** Queues a media URL found by the in-app browser, reusing the browser's cookies, User-Agent and referer. */
    fun downloadSniffed(url: String, kind: String, label: String, pageTitle: String, userAgent: String?, referer: String?, cookie: String?) {
        val headers = if (cookie.isNullOrBlank()) "" else "Cookie: $cookie"
        val stream = kind == "HLS" || kind == "DASH"
        val base = FileNames.sanitize(pageTitle).let { if (FileNames.isGeneric(it)) "" else it }
        val tag = if (label != kind) " [" + label.removePrefix("$kind ") + "]" else ""
        val fileName = if (stream || base.isBlank()) null else "$base$tag.${kind.lowercase()}"
        viewModelScope.launch {
            val threads = settings.value?.defaultThreads ?: 16
            controller.enqueue(
                url = url,
                engine = if (stream) Engine.YTDLP else Engine.ARIA2,
                title = base.ifBlank { null },
                formatSelector = null,
                audioOnly = false,
                threads = threads,
                destTreeUri = null,
                userAgent = userAgent,
                referer = referer,
                headers = headers,
                fileName = fileName,
            )
        }
    }

    // ------------------------------------------------------------ task actions

    fun pause(id: Long) = controller.pause(id)
    fun resume(id: Long) = controller.resume(id)
    fun cancel(id: Long) = controller.cancel(id)
    fun pauseAll() = controller.pauseAll()
    fun resumeAll() = controller.resumeAll()

    fun refreshUrl(id: Long, newUrl: String) {
        val link = LinkDetector.extractFirstLink(newUrl) ?: newUrl.trim()
        if (LinkDetector.kind(link) == LinkKind.INVALID) {
            messages.tryEmit("That is not a valid link")
            return
        }
        controller.refreshUrl(id, link) { r ->
            if (r.isSuccess) {
                messages.tryEmit("Link updated, resuming")
                controller.resume(id)
            } else {
                messages.tryEmit("Could not update link: ${r.exceptionOrNull()?.message}")
            }
        }
    }

    fun removeCompleted(item: DownloadEntity, deleteFile: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (deleteFile && item.outputUri != null) {
                val ctx = getApplication<Application>()
                val uri = Uri.parse(item.outputUri)
                val ok = runCatching { ctx.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)
                if (!ok) runCatching { DocumentFile.fromSingleUri(ctx, uri)?.delete() }
            }
            controller.dao.delete(item.id)
        }
    }

    fun clearFinished() {
        viewModelScope.launch(Dispatchers.IO) { controller.dao.clearFinished() }
    }

    // ------------------------------------------------------------ settings

    fun setTheme(v: ThemeMode) = viewModelScope.launch { settingsRepo.setTheme(v) }
    fun setAmoled(v: Boolean) = viewModelScope.launch { settingsRepo.setAmoled(v) }
    fun setDynamic(v: Boolean) = viewModelScope.launch { settingsRepo.setDynamic(v) }
    fun setDefaultThreads(v: Int) = viewModelScope.launch { settingsRepo.setThreads(v) }
    fun setMaxConcurrent(v: Int) = viewModelScope.launch {
        settingsRepo.setConcurrent(v)
        controller.schedule()
    }
    fun setUserAgent(v: String) = viewModelScope.launch { settingsRepo.setUserAgent(v) }
    fun setReferer(v: String) = viewModelScope.launch { settingsRepo.setReferer(v) }
    fun setDest(v: String) = viewModelScope.launch { settingsRepo.setDest(v) }
    fun setClipboardWatch(v: Boolean) = viewModelScope.launch { settingsRepo.setClipboard(v) }
    fun setYtNightly(v: Boolean) = viewModelScope.launch { settingsRepo.setYtNightly(v) }

    fun setWallpaper(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            if (Wallpaper.import(getApplication(), uri)) {
                settingsRepo.setWallpaperVersion(System.currentTimeMillis())
            } else {
                messages.tryEmit("Could not load that image")
            }
        }
    }

    fun clearWallpaper() {
        viewModelScope.launch(Dispatchers.IO) {
            Wallpaper.remove(getApplication())
            settingsRepo.setWallpaperVersion(0L)
        }
    }

    fun setWallpaperAlpha(v: Int) = viewModelScope.launch { settingsRepo.setWallpaperAlpha(v) }

    fun folderLabel(treeUri: String?): String {
        if (treeUri.isNullOrBlank()) return "Downloads/FetchX"
        return runCatching {
            DocumentFile.fromTreeUri(getApplication(), Uri.parse(treeUri))?.name
        }.getOrNull() ?: treeUri
    }

    fun importCookies(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val text = runCatching {
                ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (text.isNullOrBlank() || !text.lines().any { it.split('\t').size >= 7 }) {
                messages.tryEmit("Not a Netscape-format cookies.txt file")
                return@launch
            }
            controller.cookieFile.writeText(text)
            _cookiesLoaded.value = true
            messages.tryEmit("Cookies imported")
        }
    }

    fun clearCookies() {
        controller.cookieFile.delete()
        _cookiesLoaded.value = false
        messages.tryEmit("Cookies removed")
    }

    // ------------------------------------------------------------ engine updater

    fun updateYtDlp() {
        if (_updating.value) return
        _updating.value = true
        _updateStatus.value = "Checking GitHub releases…"
        viewModelScope.launch {
            val msg = runCatching { controller.ytdlp.update(settings.value?.ytNightly == true) }
                .getOrElse { "Update failed: ${it.message}" }
            _updateStatus.value = msg
            _ytVersion.value = withContext(Dispatchers.IO) { controller.ytdlp.version() }
            _updating.value = false
        }
    }
}
