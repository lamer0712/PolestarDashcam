package com.polestar.dashcamexporter

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.os.storage.StorageManager
import android.media.MediaMetadataRetriever
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.Semaphore
import java.util.concurrent.ConcurrentHashMap

data class CategoryPage(val entries: List<DvrMedia> = emptyList(), val next: Int = 0,
                        val hasMore: Boolean = true, val error: String? = null)
data class ExportState(
    val base: String = DvrApi.DEFAULT_BASE,
    val useListMode: Boolean = true,
    val connected: Boolean = false,
    val dvrStatus: String = "Connect to the vehicle DVR",
    val directories: List<MediaDirectory> = emptyList(),
    val pages: Map<MediaKind, CategoryPage> = emptyMap(),
    val thumbnails: Map<String, String> = emptyMap(),
    val thumbnailFailures: Set<String> = emptySet(),
    val saved: List<SavedMedia> = emptyList(),
    val busy: Boolean = false,
    val progressText: String = "",
    val fraction: Float? = null,
    val message: String = "",
    val errorMessage: String? = null,
    val recoveryBase: String? = null,
    val exportTree: Uri? = null,
    val usbConnected: Boolean = false,
    val phoneServerUrl: String? = null
)

class ExporterApplication : Application() {
    lateinit var controller: ExportController
    override fun onCreate() { super.onCreate(); controller = ExportController(this) }
}

class ExportController(private val app: Application) {
    private val preferences = app.getSharedPreferences("exporter", 0)
    private val store = DownloadStore(File(app.filesDir, "exports"))
    private val thumbnailStore = ThumbnailStore(File(app.cacheDir, "dvr-thumbnails"))
    private val initialListMode = if (!preferences.contains("listModeDefaultV3")) {
        preferences.edit().putBoolean("listMode", true).putBoolean("listModeDefaultV3", true).commit()
        true
    } else preferences.getBoolean("listMode", true)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mutable = MutableStateFlow(ExportState(
        base = preferences.getString("base", DvrApi.DEFAULT_BASE)!!,
        useListMode = initialListMode,
        recoveryBase = preferences.getString("recoveryBase", null),
        exportTree = preferences.getString("exportTree", null)?.let(Uri::parse),
        errorMessage = preferences.getString("lastError", null),
        busy = true, progressText = "Checking saved files"
    ))
    val state = mutable.asStateFlow()
    val appContext: Application get() = app
    private var stop = StopToken()
    private var pendingTransfer: (() -> String)? = null
    /** A tile may be composed more than once; only attempt each DVR thumbnail once per refresh. */
    private val thumbnailAttempts = ConcurrentHashMap.newKeySet<String>()
    private var playbackBase: String? = null
    @Volatile private var playbackHeartbeatRunning = false
    private var playbackHeartbeat: Thread? = null
    /** Limit thumbnail traffic so a long list does not open one DVR request per tile. */
    private val thumbnailSlots = Semaphore(20, true)
    private val phoneDvrLock = Any()
    private val phoneServer = PhoneFileServer(
        files = { state.value.saved },
        open = ::openSavedInput,
        dvrFiles = {
            loadDvrForPhone()
        },
        openDvr = { item, _ ->
            val connection = DvrApi.connection(item.url)
            // The DVR relay currently opens one sequential stream and skips locally.
            // This avoids relying on vehicle-specific Range behavior.
            if (connection.responseCode != 200) DvrApi.requireOk(connection)
            connection.inputStream
        },
        savedThumbnail = ::savedThumbnailForPhone,
        dvrThumbnail = { item ->
            runCatching {
                thumbnailStore.fetch(DvrApi(state.value.base), item, StopToken())?.readBytes()
            }.getOrNull()
        }
    )

    init {
        scope.launch {
            val saved = withContext(Dispatchers.IO) { store.cleanPartialFiles(); visibleSaved() }
            mutable.update { it.copy(saved = saved, busy = false, progressText = "") }
        }
    }

    fun autoConnect() {
        scope.launch {
            while (state.value.busy) delay(50)
            if (!state.value.connected && state.value.recoveryBase == null) refresh()
        }
    }

    /** Keep the DVR in file-list mode for the lifetime of the Gallery+ session. */
    fun startAppHeartbeat() = enterDvrBrowsingMode()

    fun message(value: String) { mutable.update { it.copy(message = value) } }

    fun startPhoneServer() {
        if (state.value.saved.isEmpty()) {
            message("There are no saved files to send.")
            return
        }
        try {
            val url = phoneServer.start()
            mutable.update { it.copy(phoneServerUrl = url, message = "Scan the QR code with your phone.") }
        } catch (e: Exception) {
            reportError("Unable to start phone file server: ${e.message ?: "No Wi-Fi connection."}")
        }
    }

    fun stopPhoneServer() {
        phoneServer.stop()
        mutable.update { it.copy(phoneServerUrl = null) }
    }

    /** Loads DVR pages for the phone web UI independently of the in-car Compose list. */
    private fun loadDvrForPhone(): List<DvrMedia> = synchronized(phoneDvrLock) {
        val api = DvrApi(state.value.base)
        val status = api.status()
        if (!status.usable) throw IOException("DVR storage is unavailable.")
        if (status.recording != "in-file-list") api.setMode("enter-file-list")
        api.directories().flatMap { directory ->
            val all = mutableListOf<DvrMedia>()
            var start = 0
            var guard = 0
            do {
                val batch = api.files(directory, start)
                all += batch
                start += batch.size
                guard++
            } while (batch.isNotEmpty() && (directory.count <= 0 || all.size < directory.count) && guard < 100)
            all
        }
    }

    private fun savedThumbnailForPhone(item: SavedMedia): ByteArray? = runCatching {
        if (item.mime.startsWith("image/")) {
            openSavedInput(item).use { input -> input.readBytes().take(5 * 1024 * 1024).toByteArray() }
        } else {
            val retriever = MediaMetadataRetriever()
            try {
                if (item.file != null) retriever.setDataSource(item.file.absolutePath)
                else item.uri?.let { uri -> app.contentResolver.openFileDescriptor(uri, "r")?.use { retriever.setDataSource(it.fileDescriptor) } }
                val bitmap = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: return@runCatching null
                ByteArrayOutputStream().use { output ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, output)
                    bitmap.recycle()
                    output.toByteArray()
                }
            } finally { retriever.release() }
        }
    }.getOrNull()

    fun refreshUsbState() {
        val connected = app.getSystemService(StorageManager::class.java).storageVolumes.any {
            it.isRemovable && it.state == Environment.MEDIA_MOUNTED && it.directory != null
        }
        mutable.update { if (it.usbConnected == connected) it else it.copy(usbConnected = connected) }
    }

    fun shouldPromptInitialFolder(): Boolean =
        state.value.exportTree == null && !preferences.getBoolean("initialFolderPromptedV1", false)

    fun markInitialFolderPrompted() {
        preferences.edit().putBoolean("initialFolderPromptedV1", true).apply()
    }

    fun setExportFolder(tree: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { app.contentResolver.takePersistableUriPermission(tree, flags) }
        preferences.edit().putString("exportTree", tree.toString()).apply()
        scope.launch {
            val saved = withContext(Dispatchers.IO) { visibleSaved(tree) }
            mutable.update { it.copy(exportTree = tree, saved = saved, message = "Save folder selected. Downloads will be copied there automatically.") }
        }
    }
    fun configure(base: String, mode: Boolean): Boolean {
        if (state.value.busy || state.value.recoveryBase != null) return false
        return try {
            val validated = DvrJson.baseUrl(base)
            preferences.edit().putString("base", validated).putBoolean("listMode", mode).apply()
            mutable.update { it.copy(base = validated, useListMode = mode, connected = false,
                pages = emptyMap(), directories = emptyList(), dvrStatus = "Connect to the vehicle DVR", message = "Settings saved.") }
            true
        } catch (e: Exception) { message(e.message.orEmpty()); false }
    }

    private fun begin(label: String, recovery: Boolean = false): Boolean {
        if (state.value.busy) return false
        if (!recovery && state.value.recoveryBase != null) {
            message("Confirm recovery of the previous DVR session first."); return false
        }
        stop = StopToken()
        preferences.edit().remove("lastError").apply()
        mutable.update { it.copy(busy = true, progressText = label, fraction = null, message = "", errorMessage = null) }
        return true
    }

    private suspend fun finishWork(action: () -> String) {
        try {
            message(withContext(Dispatchers.IO) { action() })
        } catch (e: Exception) {
            val text = if (e is UserCancelledException) "Operation cancelled. Completed files are kept."
                else e.message ?: "Operation failed."
            if (e is UserCancelledException) message(text) else reportError(text)
        } finally {
            withContext(NonCancellable) {
                val saved = runCatching { withContext(Dispatchers.IO) { visibleSaved() } }
                    .getOrElse { store.saved() }
                mutable.update { it.copy(busy = false, progressText = "", fraction = null, saved = saved) }
            }
        }
    }

    fun clearError() {
        preferences.edit().remove("lastError").apply()
        mutable.update { it.copy(errorMessage = null) }
    }

    fun reportError(text: String) {
        preferences.edit().putString("lastError", text).apply()
        mutable.update { it.copy(message = text, errorMessage = text) }
    }

    fun enterPlaybackMode(onReady: () -> Unit) {
        val config = state.value
        if (playbackBase == config.base && playbackHeartbeatRunning) {
            onReady()
            return
        }
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val api = DvrApi(config.base)
                    val status = api.status()
                    if (!status.usable) throw IOException("DVR storage is unavailable.")
                    if (status.recording != "in-file-list") api.setMode("enter-file-list")
                }
                playbackBase = config.base
                startPlaybackHeartbeat(DvrApi(config.base))
                onReady()
            } catch (e: Exception) {
                reportError("Failed to enter playback mode: ${e.message ?: "No DVR response"}")
            }
        }
    }

    /** Keeps DVR in file-list mode from the moment a DVR album is opened, matching OEM Gallery. */
    fun enterDvrBrowsingMode() {
        val config = state.value
        if (playbackBase == config.base && playbackHeartbeatRunning) return
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val api = DvrApi(config.base)
                    val status = api.status()
                    if (!status.usable) throw IOException("DVR storage is unavailable.")
                    if (status.recording != "in-file-list") api.setMode("enter-file-list")
                }
                playbackBase = config.base
                startPlaybackHeartbeat(DvrApi(config.base))
            } catch (e: Exception) {
                reportError("Failed to enter DVR album mode: ${e.message ?: "No DVR response"}")
            }
        }
    }

    fun exitPlaybackMode() {
        val base = playbackBase ?: return
        playbackBase = null
        stopPlaybackHeartbeat()
        scope.launch(Dispatchers.IO) {
            runCatching { DvrApi(base).setMode("normal") }
        }
    }

    /** Stop the app-level heartbeat when the activity is no longer in use. */
    fun stopAppHeartbeat() {
        if (state.value.phoneServerUrl != null) stopPhoneServer()
        if (!state.value.busy) exitPlaybackMode()
    }

    private fun startPlaybackHeartbeat(api: DvrApi) {
        stopPlaybackHeartbeat()
        playbackHeartbeatRunning = true
        playbackHeartbeat = Thread({
            while (playbackHeartbeatRunning) {
                try {
                    Thread.sleep(5_000L)
                    if (!playbackHeartbeatRunning) break
                    if (api.statusOrNull()?.recording != "in-file-list") {
                        api.setMode("enter-file-list")
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (_: Exception) {
                    // Retry on the next tick while the detail screen remains open.
                }
            }
        }, "dvr-playback-heartbeat").also { it.isDaemon = true; it.start() }
    }

    private fun stopPlaybackHeartbeat() {
        playbackHeartbeatRunning = false
        playbackHeartbeat?.interrupt()
        runCatching { playbackHeartbeat?.join(1_000L) }
        playbackHeartbeat = null
    }

    fun cancel() {
        if (!state.value.busy) return
        stop.cancel()
        mutable.update { it.copy(progressText = "Cancelling… recording state will be restored after the current response ends.") }
    }

    private fun rememberRecovery(base: String?) {
        // Synchronous persistence happens before sending POST, so process death retains recovery information.
        if (!preferences.edit().apply {
                if (base == null) remove("recoveryBase") else putString("recoveryBase", base)
            }.commit()) throw IOException("Unable to save DVR session recovery information.")
        mutable.update { it.copy(recoveryBase = base) }
    }

    private fun <T> session(api: DvrApi, enabled: Boolean, action: () -> T): T {
        if (!enabled) return action()
        val status = api.status()
        if (!status.usable) throw IOException("DVR usable=false: check the storage.")
        // Do not take ownership of a list session entered by the OEM gallery.
        if (status.recording == "in-file-list") return action()
        // OEM Gallery treats every non-file-list state (including "off") as
        // eligible for a file-list transition and lets the DVR decide whether
        // the transition is allowed.
        rememberRecovery(api.base)
        var failure: Throwable? = null
        val heartbeatRunning = AtomicBoolean(true)
        val heartbeat = Thread({
            while (heartbeatRunning.get()) {
                try {
                    Thread.sleep(5_000L)
                    if (!heartbeatRunning.get()) break
                    val current = api.statusOrNull()
                    if (current?.recording != "in-file-list") {
                        // OEM Gallery renews file-list mode every five seconds.
                        api.setMode("enter-file-list")
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (_: Exception) {
                    // The foreground transfer remains authoritative; the next tick retries.
                }
            }
        }, "dvr-file-list-heartbeat")
        heartbeat.isDaemon = true
        try {
            api.setMode("enter-file-list")
            heartbeat.start()
            stop.check()
            return action()
        } catch (e: Throwable) { failure = e; throw e }
        finally {
            heartbeatRunning.set(false)
            heartbeat.interrupt()
            runCatching { heartbeat.join(1_000L) }
            try {
                api.setMode("normal")
                rememberRecovery(null)
            } catch (restore: Exception) {
                val detail = "Recording recovery was not confirmed. Tap Retry recording recovery. ${restore.message}"
                if (failure != null) failure.addSuppressed(restore)
                mutable.update { it.copy(dvrStatus = detail) }
                if (failure == null) throw IOException(detail, restore)
            }
        }
    }

    private fun requiresFileListMode(error: Throwable): Boolean =
        error.message?.let { message ->
            message.contains("file-list mode", ignoreCase = true) ||
                message.contains("file list mode", ignoreCase = true) ||
                message.contains("in-file-list", ignoreCase = true) ||
                // The DVR returns an nginx 403 HTML page for media URLs when the
                // file-list session has expired, instead of its JSON mode error.
                message.contains("HTTP 403", ignoreCase = true)
        } == true

    /** The vehicle may require list mode even when the user left the safe GET-only default enabled. */
    private fun <T> sessionWithAutomaticMode(api: DvrApi, configured: Boolean, action: () -> T): T {
        return try {
            session(api, configured, action)
        } catch (error: IOException) {
            if (configured || !requiresFileListMode(error)) throw error
            mutable.update { it.copy(message = "The vehicle requires list mode. Switching temporarily.") }
            session(api, enabled = true, action)
        }
    }

    fun recoverRecording() {
        val base = state.value.recoveryBase ?: return
        if (!begin("Confirming recording recovery", recovery = true)) return
        scope.launch { finishWork {
            val api = DvrApi(base)
            if (api.status().recording != "normal") api.setMode("normal")
            rememberRecovery(null)
            mutable.update { it.copy(dvrStatus = "Confirmed recording state: normal") }
            "Confirmed DVR recording state: normal."
        } }
    }

    fun refresh() {
        if (!begin("Checking DVR connection")) return
        val config = state.value
        thumbnailAttempts.clear()
        mutable.update { it.copy(connected = false, pages = emptyMap(), directories = emptyList()) }
        scope.launch { finishWork {
            val api = DvrApi(config.base)
            val status = api.statusOrNull()
            if (status?.usable == false) throw IOException("DVR usable=false: check the vehicle DVR storage.")
            mutable.update { it.copy(dvrStatus = if (status == null)
                "Status response received · checking list API" else "DVR response received · ${status.recording}") }
            fun loadLists() {
                stop.check()
                val directories = api.directories()
                mutable.update { it.copy(directories = directories, connected = true) }
                for (kind in MediaKind.entries) {
                    stop.check()
                    val directory = directories.firstOrNull { it.kind == kind }
                    if (directory == null) {
                        mutable.update { it.copy(pages = it.pages + (kind to CategoryPage(hasMore = false,
                            error = "DVR response is missing the ${kind.api} folder."))) }
                    } else fetchPage(api, directory, reset = true)
                }
            }
            sessionWithAutomaticMode(api, config.useListMode) { loadLists() }
            val count = state.value.pages.values.sumOf { it.entries.size }
            val errors = state.value.pages.values.count { it.error != null }
            "${count} files loaded${if (errors > 0) " · $errors category errors: retry in that tab." else " complete"}"
        } }
    }

    private fun fetchPage(api: DvrApi, directory: MediaDirectory, reset: Boolean) {
        val previous = if (reset) CategoryPage() else state.value.pages[directory.kind] ?: CategoryPage()
        mutable.update { it.copy(progressText = "Loading ${directory.kind.label} list from ${previous.next}") }
        try {
            val batch = api.files(directory, previous.next)
            stop.check()
            val combined = (previous.entries + batch).distinctBy { it.key }
            if (batch.isNotEmpty() && previous.entries.isNotEmpty() && combined.size == previous.entries.size)
                throw IOException("The DVR returned the same page repeatedly. Refresh the list.")
            val loaded = combined.size
            val hasMore = batch.isNotEmpty() && (directory.count <= 0 || loaded < directory.count)
            val page = CategoryPage(combined, previous.next + batch.size, hasMore)
            mutable.update { it.copy(pages = it.pages + (directory.kind to page)) }
        } catch (e: UserCancelledException) { throw e }
        catch (e: Exception) {
            if (requiresFileListMode(e)) throw e
            mutable.update { it.copy(pages = it.pages + (directory.kind to previous.copy(error = e.message))) }
        }
    }

    fun ensureThumbnail(item: DvrMedia) {
        if (state.value.thumbnails.containsKey(item.key) || item.key in state.value.thumbnailFailures ||
            !thumbnailAttempts.add(item.key)) return
        scope.launch(Dispatchers.IO) {
            thumbnailSlots.acquire()
            try {
                val file = thumbnailStore.fetch(DvrApi(state.value.base), item, StopToken())
                if (file != null) mutable.update { it.copy(
                    thumbnails = it.thumbnails + (item.key to file.absolutePath),
                    thumbnailFailures = it.thumbnailFailures - item.key
                ) } else mutable.update { it.copy(thumbnailFailures = it.thumbnailFailures + item.key) }
            } catch (_: Exception) {
                // Thumbnails are optional; leave the placeholder for failed items.
                mutable.update { it.copy(thumbnailFailures = it.thumbnailFailures + item.key) }
            } finally {
                thumbnailSlots.release()
            }
        }
    }

    private fun fetchThumbnails(api: DvrApi, items: List<DvrMedia>) {
        for ((index, item) in items.withIndex()) {
            stop.check()
            val existing = thumbnailStore.existing(item)
            if (existing != null) {
                mutable.update { it.copy(thumbnails = it.thumbnails + (item.key to existing.absolutePath)) }
                continue
            }
            mutable.update { it.copy(progressText = "Receiving ${item.kind.label} thumbnail ${index + 1}/${items.size}") }
            try {
                val file = thumbnailStore.fetch(api, item, stop)
                if (file != null) {
                    mutable.update { it.copy(thumbnails = it.thumbnails + (item.key to file.absolutePath)) }
                }
            } catch (e: UserCancelledException) {
                throw e
            } catch (e: UserCancelledException) {
                throw e
            } catch (_: Exception) {
                // Thumbnail generation is best-effort. Any DVR thumbnail failure
                // (including internal error code 11) is ignored for this item so
                // all file-list entries remain visible and no error dialog appears.
                continue
            }
        }
    }

    fun more(kind: MediaKind) {
        val directory = state.value.directories.firstOrNull { it.kind == kind } ?: return
        if (!begin("Loading next page")) return
        val config = state.value
        scope.launch { finishWork {
            val api = DvrApi(config.base)
            sessionWithAutomaticMode(api, config.useListMode) { fetchPage(api, directory, reset = false) }
            state.value.pages[kind]?.error ?: "${kind.label} List refreshed."
        } }
    }

    private fun transfer(label: String, action: () -> String) {
        if (!begin(label)) return
        pendingTransfer = action
        try { ContextCompat.startForegroundService(app, Intent(app, TransferService::class.java)) }
        catch (e: Exception) {
            pendingTransfer = null
            mutable.update { it.copy(busy = false, progressText = "", message = "Unable to start transfer service: ${e.message}") }
        }
    }

    suspend fun runPendingTransfer() {
        val action = pendingTransfer ?: return
        pendingTransfer = null
        finishWork(action)
    }

    fun download(items: List<DvrMedia>) {
        if (items.isEmpty()) return
        val config = state.value
        transfer("Preparing selected file download") {
            val api = DvrApi(config.base)
            sessionWithAutomaticMode(api, config.useListMode) {
                batch(items, "Download") { item, index ->
                    // Present one continuous per-file bar across the DVR download
                    // and optional user-folder copy.
                    fun phase(start: Float, weight: Float, label: String) =
                        { done: Long, total: Long ->
                            val ratio = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                            progress(index, items.size, label, ((start + weight * ratio) * 1000).toLong(), 1000)
                        }
                    val saved = store.download(item, stop, phase(0f, 0.9f, item.name))
                    state.value.exportTree?.let { tree ->
                        copyOneToFolder(saved, tree, index, items.size, phase(0.9f, 0.1f, item.name))
                    }
                    progress(index, items.size, item.name, 1000, 1000)
                    mutable.update { it.copy(saved = visibleSaved()) }
                }
            }
        }
    }


    fun deleteSaved(items: List<SavedMedia>) {
        if (items.isEmpty()) return
        transfer("Deleting selected files") {
            var deleted = 0
            items.forEach { item ->
                stop.check()
                val ok = item.uri?.let { runCatching { DocumentsContract.deleteDocument(app.contentResolver, it) }.getOrDefault(false) }
                    ?: item.file?.let { it.delete() }
                    ?: false
                if (!ok) throw IOException("Unable to delete file: ${item.name}")
                deleted++
            }
            mutable.update { it.copy(saved = visibleSaved()) }
            "${deleted} file(s) deleted."
        }
    }

    fun copyToUsb(items: List<SavedMedia>) {
        if (items.isEmpty() || !state.value.usbConnected) return
        transfer("Copying selected files to USB") {
            val volume = app.getSystemService(StorageManager::class.java).storageVolumes.firstOrNull {
                it.isRemovable && it.state == Environment.MEDIA_MOUNTED
            } ?: throw IOException("Connect a USB drive and try again.")
            val root = volume.directory ?: throw IOException("The connected USB drive has no accessible root.")
            val destination = File(root, "polestar_dashcam")
            if (!destination.exists() && !destination.mkdirs()) throw IOException("Unable to create polestar_dashcam on USB.")
            items.forEachIndexed { index, item ->
                stop.check()
                val target = File(destination, item.name)
                openSavedInput(item).use { input -> target.outputStream().use { output ->
                    StreamCopy.copy(input, output, item.size, stop) { done, total ->
                        progress(index + 1, items.size, item.name, done, total)
                    }
                } }
            }
            "Copied ${items.size} file(s) to USB."
        }
    }

    fun copyToFolder(items: List<SavedMedia>, tree: Uri) {
        if (items.isEmpty()) return
        transfer("Preparing folder copy") {
            val resolver = app.contentResolver
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val names = mutableSetOf<String>()
            val cursor = resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?: throw IOException("Unable to read the selected folder.")
            cursor.use { while (it.moveToNext()) names += it.getString(0) }
            batch(items, "Copy folder") { item, index ->
                stop.check()
                var name = item.name
                var suffix = 1
                while (name in names) {
                    val extension = item.name.substringAfterLast('.', "")
                    name = item.name.substringBeforeLast('.', item.name) + " (${suffix++})" +
                        if (extension.isEmpty()) "" else ".$extension"
                }
                val document = DocumentsContract.createDocument(resolver, parent, item.mime, name)
                    ?: throw IOException("Unable to create file. Check USB write access.")
                names += name
                try {
                    val output = resolver.openOutputStream(document, "w") ?: throw IOException("Unable to open file.")
                    output.use { sink -> openSavedInput(item).use { input ->
                        StreamCopy.copy(input, sink, item.size, stop) { done, total ->
                            progress(index, items.size, item.name, done, total)
                        }
                        sink.flush()
                    } }
                    resolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use {
                        if (it.moveToFirst() && !it.isNull(0) && it.getLong(0) != item.size)
                            throw IOException("Copied file size differs from the source.")
                    }
                } catch (e: Exception) {
                    val deleted = runCatching { DocumentsContract.deleteDocument(resolver, document) }.getOrDefault(false)
                    if (!deleted) throw IOException("${e.message} · An incomplete file may remain in the selected folder: $name", e)
                    throw e
                }
            }
        }
    }

    private fun visibleSaved(tree: Uri? = state.value.exportTree): List<SavedMedia> =
        tree?.let { folderSaved(it) } ?: store.saved()

    private fun folderSaved(tree: Uri): List<SavedMedia> {
        val resolver = app.contentResolver
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val result = mutableListOf<SavedMedia>()
        resolver.query(children, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (cursor.moveToNext()) {
                val mime = cursor.getString(mimeCol).orEmpty()
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                val name = cursor.getString(nameCol).orEmpty()
                if (name.isBlank() || name.endsWith(".part")) continue
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(idCol))
                val size = if (cursor.isNull(sizeCol)) 0L else cursor.getLong(sizeCol)
                val modified = if (cursor.isNull(modifiedCol)) 0L else cursor.getLong(modifiedCol)
                result += SavedMedia(
                    file = null,
                    kind = kindFor(name, mime),
                    uri = uri,
                    displayName = name,
                    byteSize = size,
                    modifiedAt = modified,
                    mimeType = mime.takeIf { it.isNotBlank() }
                )
            }
        }
        return result.sortedByDescending { it.modifiedAt }
    }

    private fun kindFor(name: String, mime: String): MediaKind = when {
        mime.startsWith("image/") -> MediaKind.PHOTO
        name.substringAfterLast('.', "").lowercase() in setOf("jpg", "jpeg", "png", "heic") -> MediaKind.PHOTO
        else -> MediaKind.NORMAL
    }

    private fun openSavedInput(item: SavedMedia): InputStream = item.uri?.let { uri ->
        app.contentResolver.openInputStream(uri) ?: throw IOException("Unable to open file: ${item.name}")
    } ?: item.file?.inputStream() ?: throw IOException("File not found: ${item.name}")

    /** Copies one completed item to the remembered SAF folder during download. */
    private fun copyOneToFolder(item: SavedMedia, tree: Uri, index: Int, count: Int,
                                progressCallback: ((Long, Long) -> Unit)? = null) {
        val resolver = app.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val names = mutableSetOf<String>()
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) names += c.getString(0)
        }
        var name = item.name
        var suffix = 1
        while (name in names) {
            val extension = item.name.substringAfterLast('.', "")
            name = item.name.substringBeforeLast('.', item.name) + " (${suffix++})" +
                if (extension.isEmpty()) "" else ".${extension}"
        }
        val document = DocumentsContract.createDocument(resolver, parent, item.mime, name)
            ?: throw IOException("Unable to create a file in the selected save folder.")
        try {
            val output = resolver.openOutputStream(document, "w") ?: throw IOException("Unable to open the selected save folder.")
            output.use { sink -> openSavedInput(item).use { input ->
                StreamCopy.copy(input, sink, item.size, stop) { done, total ->
                    (progressCallback ?: { d, t -> progress(index, count, item.name, d, t) })(done, total)
                }
                sink.flush()
            } }
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, document) }
            throw e
        }
    }

    private fun progress(index: Int, count: Int, name: String, done: Long, total: Long) {
        mutable.update { it.copy(progressText = "$index/$count · $name",
            fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null) }
    }

    private fun <T> batch(items: List<T>, label: String, action: (T, Int) -> Unit): String {
        var success = 0
        val errors = mutableListOf<String>()
        items.forEachIndexed { index, item ->
            stop.check()
            try { action(item, index + 1); success++ }
            catch (e: UserCancelledException) { throw e }
            catch (e: Exception) {
                if (e is IOException && requiresFileListMode(e)) throw e
                val name = when (item) { is DvrMedia -> item.name; is SavedMedia -> item.name; else -> "${index + 1}" }
                errors += "$name: ${e.message}"
            }
        }
        if (errors.isNotEmpty()) {
            throw IOException("$label $success/${items.size} completed\n" + errors.take(5).joinToString("\n"))
        }
        return "$label $success/${items.size} completed"
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
