package com.polestar.dashcamexporter

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
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
import java.util.concurrent.atomic.AtomicBoolean

data class CategoryPage(val entries: List<DvrMedia> = emptyList(), val next: Int = 0,
                        val hasMore: Boolean = true, val error: String? = null)
data class ExportState(
    val base: String = DvrApi.DEFAULT_BASE,
    val useListMode: Boolean = true,
    val connected: Boolean = false,
    val dvrStatus: String = "차량 DVR에 연결하세요",
    val directories: List<MediaDirectory> = emptyList(),
    val pages: Map<MediaKind, CategoryPage> = emptyMap(),
    val thumbnails: Map<String, String> = emptyMap(),
    val saved: List<SavedMedia> = emptyList(),
    val busy: Boolean = false,
    val progressText: String = "",
    val fraction: Float? = null,
    val message: String = "",
    val errorMessage: String? = null,
    val recoveryBase: String? = null,
    val exportTree: Uri? = null
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
        busy = true, progressText = "저장 파일 확인 중"
    ))
    val state = mutable.asStateFlow()
    val appContext: Application get() = app
    private var stop = StopToken()
    private var pendingTransfer: (() -> String)? = null
    private var playbackBase: String? = null
    @Volatile private var playbackHeartbeatRunning = false
    private var playbackHeartbeat: Thread? = null

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

    fun message(value: String) { mutable.update { it.copy(message = value) } }

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
            mutable.update { it.copy(exportTree = tree, saved = saved, message = "저장 폴더를 선택했습니다. 이후 내보내기 파일이 이 폴더에 자동으로 복사됩니다.") }
        }
    }
    fun configure(base: String, mode: Boolean): Boolean {
        if (state.value.busy || state.value.recoveryBase != null) return false
        return try {
            val validated = DvrJson.baseUrl(base)
            preferences.edit().putString("base", validated).putBoolean("listMode", mode).apply()
            mutable.update { it.copy(base = validated, useListMode = mode, connected = false,
                pages = emptyMap(), directories = emptyList(), dvrStatus = "차량 DVR에 연결하세요", message = "설정을 저장했습니다.") }
            true
        } catch (e: Exception) { message(e.message.orEmpty()); false }
    }

    private fun begin(label: String, recovery: Boolean = false): Boolean {
        if (state.value.busy) return false
        if (!recovery && state.value.recoveryBase != null) {
            message("이전 DVR 세션의 녹화 복귀를 먼저 확인하세요."); return false
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
            val text = if (e is UserCancelledException) "작업을 취소했습니다. 이미 완료된 파일은 보관됩니다."
                else e.message ?: "작업에 실패했습니다."
            if (e is UserCancelledException) message(text) else reportError(text)
        } finally {
            withContext(NonCancellable) {
                val saved = withContext(Dispatchers.IO) { visibleSaved() }
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
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val api = DvrApi(config.base)
                    val status = api.status()
                    if (!status.usable) throw IOException("DVR 저장장치를 사용할 수 없습니다.")
                    if (status.recording != "in-file-list") api.setMode("enter-file-list")
                }
                playbackBase = config.base
                startPlaybackHeartbeat(DvrApi(config.base))
                onReady()
            } catch (e: Exception) {
                reportError("재생 모드 전환 실패: ${e.message ?: "DVR 응답 없음"}")
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
        mutable.update { it.copy(progressText = "취소 중… 현재 응답 종료 후 녹화 상태를 정리합니다.") }
    }

    private fun rememberRecovery(base: String?) {
        // Synchronous persistence happens before sending POST, so process death retains recovery information.
        if (!preferences.edit().apply {
                if (base == null) remove("recoveryBase") else putString("recoveryBase", base)
            }.commit()) throw IOException("DVR 세션 복구 정보를 저장하지 못했습니다.")
        mutable.update { it.copy(recoveryBase = base) }
    }

    private fun <T> session(api: DvrApi, enabled: Boolean, action: () -> T): T {
        if (!enabled) return action()
        val status = api.status()
        if (!status.usable) throw IOException("DVR usable=false: 저장장치 상태를 확인하세요.")
        // Do not take ownership of a list session entered by the OEM gallery.
        if (status.recording == "in-file-list") return action()
        if (status.recording != "normal") throw IOException("DVR 상태 ${status.recording}: 목록 모드로 변경할 수 없습니다.")
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
                val detail = "녹화 복귀를 확인하지 못했습니다. ‘녹화 복귀 재시도’를 누르세요. ${restore.message}"
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
            mutable.update { it.copy(message = "차량이 목록 모드를 요구해 잠시 전환합니다.") }
            session(api, enabled = true, action)
        }
    }

    fun recoverRecording() {
        val base = state.value.recoveryBase ?: return
        if (!begin("녹화 복귀 확인 중", recovery = true)) return
        scope.launch { finishWork {
            val api = DvrApi(base)
            if (api.status().recording != "normal") api.setMode("normal")
            rememberRecovery(null)
            mutable.update { it.copy(dvrStatus = "녹화 상태 normal 확인") }
            "DVR 녹화 상태 normal을 확인했습니다."
        } }
    }

    fun refresh() {
        if (!begin("DVR 연결 확인 중")) return
        val config = state.value
        mutable.update { it.copy(connected = false, pages = emptyMap(), directories = emptyList()) }
        scope.launch { finishWork {
            val api = DvrApi(config.base)
            val status = api.statusOrNull()
            if (status?.usable == false) throw IOException("DVR usable=false: 차량의 DVR 저장장치를 확인하세요.")
            mutable.update { it.copy(dvrStatus = if (status == null)
                "status 응답 확인 · 목록 API 확인 중" else "DVR 응답 확인 · ${status.recording}") }
            fun loadLists() {
                stop.check()
                val directories = api.directories()
                mutable.update { it.copy(directories = directories, connected = true) }
                for (kind in MediaKind.entries) {
                    stop.check()
                    val directory = directories.firstOrNull { it.kind == kind }
                    if (directory == null) {
                        mutable.update { it.copy(pages = it.pages + (kind to CategoryPage(hasMore = false,
                            error = "DVR 응답에 ${kind.api} 폴더가 없습니다."))) }
                    } else fetchPage(api, directory, reset = true)
                }
            }
            sessionWithAutomaticMode(api, config.useListMode) { loadLists() }
            val count = state.value.pages.values.sumOf { it.entries.size }
            val errors = state.value.pages.values.count { it.error != null }
            "${count}개 조회${if (errors > 0) " · 분류 $errors 개 오류: 해당 탭에서 재시도하세요." else " 완료"}"
        } }
    }

    private fun fetchPage(api: DvrApi, directory: MediaDirectory, reset: Boolean) {
        val previous = if (reset) CategoryPage() else state.value.pages[directory.kind] ?: CategoryPage()
        mutable.update { it.copy(progressText = "${directory.kind.label} 목록 ${previous.next}번부터 조회 중") }
        try {
            val batch = api.files(directory, previous.next)
            stop.check()
            val combined = (previous.entries + batch).distinctBy { it.key }
            if (batch.isNotEmpty() && previous.entries.isNotEmpty() && combined.size == previous.entries.size)
                throw IOException("DVR이 같은 페이지를 반복 반환했습니다. 새로고침하세요.")
            val page = CategoryPage(combined, previous.next + batch.size, batch.isNotEmpty())
            mutable.update { it.copy(pages = it.pages + (directory.kind to page)) }
            fetchThumbnails(api, batch)
        } catch (e: UserCancelledException) { throw e }
        catch (e: Exception) {
            if (requiresFileListMode(e)) throw e
            mutable.update { it.copy(pages = it.pages + (directory.kind to previous.copy(error = e.message))) }
        }
    }

    private fun fetchThumbnails(api: DvrApi, items: List<DvrMedia>) {
        var consecutiveFailures = 0
        for ((index, item) in items.withIndex()) {
            stop.check()
            val existing = thumbnailStore.existing(item)
            if (existing != null) {
                mutable.update { it.copy(thumbnails = it.thumbnails + (item.key to existing.absolutePath)) }
                consecutiveFailures = 0
                continue
            }
            mutable.update { it.copy(progressText = "${item.kind.label} 썸네일 ${index + 1}/${items.size} 받는 중") }
            try {
                val file = thumbnailStore.fetch(api, item, stop)
                mutable.update { it.copy(thumbnails = it.thumbnails + (item.key to file.absolutePath)) }
                consecutiveFailures = 0
            } catch (e: UserCancelledException) {
                throw e
            } catch (e: IOException) {
                if (requiresFileListMode(e)) throw e
                // Thumbnail generation is best-effort. Vehicle DVRs return
                // internal error code 11 for files that have no generated frame;
                // leave that tile without a thumbnail and continue the list.
                if (e.message?.contains("code 11", ignoreCase = true) == true ||
                    e.message?.contains("internal error", ignoreCase = true) == true) {
                    consecutiveFailures = 0
                    continue
                }
                consecutiveFailures++
                if (consecutiveFailures >= 3) return
            }
        }
    }

    fun more(kind: MediaKind) {
        val directory = state.value.directories.firstOrNull { it.kind == kind } ?: return
        if (!begin("다음 목록 조회 중")) return
        val config = state.value
        scope.launch { finishWork {
            val api = DvrApi(config.base)
            sessionWithAutomaticMode(api, config.useListMode) { fetchPage(api, directory, reset = false) }
            state.value.pages[kind]?.error ?: "${kind.label} 목록을 갱신했습니다."
        } }
    }

    private fun transfer(label: String, action: () -> String) {
        if (!begin(label)) return
        pendingTransfer = action
        try { ContextCompat.startForegroundService(app, Intent(app, TransferService::class.java)) }
        catch (e: Exception) {
            pendingTransfer = null
            mutable.update { it.copy(busy = false, progressText = "", message = "전송 서비스를 시작하지 못했습니다: ${e.message}") }
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
        transfer("선택 파일 다운로드 준비 중") {
            val api = DvrApi(config.base)
            sessionWithAutomaticMode(api, config.useListMode) {
                batch(items, "다운로드") { item, index ->
                    // Present one continuous per-file bar across the three physical
                    // writes instead of restarting it for each destination.
                    fun phase(start: Float, weight: Float, label: String) =
                        { done: Long, total: Long ->
                            val ratio = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                            progress(index, items.size, label, ((start + weight * ratio) * 1000).toLong(), 1000)
                        }
                    val saved = store.download(item, stop, phase(0f, 0.8f, item.name))
                    PublicMediaStore.publish(app, saved, stop, phase(0.8f, 0.1f, item.name))
                    state.value.exportTree?.let { tree ->
                        copyOneToFolder(saved, tree, index, items.size, phase(0.9f, 0.1f, item.name))
                    }
                    progress(index, items.size, item.name, 1000, 1000)
                    mutable.update { it.copy(saved = visibleSaved()) }
                }
            }
        }
    }

    fun copyToFolder(items: List<SavedMedia>, tree: Uri) {
        if (items.isEmpty()) return
        transfer("선택 폴더에 복사 준비 중") {
            val resolver = app.contentResolver
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val names = mutableSetOf<String>()
            val cursor = resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?: throw IOException("선택 폴더를 읽을 수 없습니다.")
            cursor.use { while (it.moveToNext()) names += it.getString(0) }
            batch(items, "폴더 복사") { item, index ->
                stop.check()
                var name = item.name
                var suffix = 1
                while (name in names) {
                    val extension = item.name.substringAfterLast('.', "")
                    name = item.name.substringBeforeLast('.', item.name) + " (${suffix++})" +
                        if (extension.isEmpty()) "" else ".$extension"
                }
                val document = DocumentsContract.createDocument(resolver, parent, item.mime, name)
                    ?: throw IOException("파일을 만들 수 없습니다. USB 쓰기 권한을 확인하세요.")
                names += name
                try {
                    val output = resolver.openOutputStream(document, "w") ?: throw IOException("파일을 열 수 없습니다.")
                    output.use { sink -> openSavedInput(item).use { input ->
                        StreamCopy.copy(input, sink, item.size, stop) { done, total ->
                            progress(index, items.size, item.name, done, total)
                        }
                        sink.flush()
                    } }
                    resolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use {
                        if (it.moveToFirst() && !it.isNull(0) && it.getLong(0) != item.size)
                            throw IOException("복사된 파일 크기가 원본과 다릅니다.")
                    }
                } catch (e: Exception) {
                    val deleted = runCatching { DocumentsContract.deleteDocument(resolver, document) }.getOrDefault(false)
                    if (!deleted) throw IOException("${e.message} · 선택 폴더에 미완성 파일이 남았을 수 있습니다: $name", e)
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
        app.contentResolver.openInputStream(uri) ?: throw IOException("파일을 열 수 없습니다: ${item.name}")
    } ?: item.file?.inputStream() ?: throw IOException("파일을 찾을 수 없습니다: ${item.name}")

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
            ?: throw IOException("선택한 저장 폴더에 파일을 만들 수 없습니다.")
        try {
            val output = resolver.openOutputStream(document, "w") ?: throw IOException("선택한 저장 폴더를 열 수 없습니다.")
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
        mutable.update { it.copy(progressText = "$index/$count · $name · ${formatBytes(done)}" +
            if (total > 0) " / ${formatBytes(total)}" else "",
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
            throw IOException("$label $success/${items.size}개 완료\n" + errors.take(5).joinToString("\n"))
        }
        return "$label $success/${items.size}개 완료"
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
