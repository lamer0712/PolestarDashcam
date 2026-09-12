package com.polestar.dashcamexporter

import android.Manifest
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.clip
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.common.util.UnstableApi
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val controller get() = (application as ExporterApplication).controller
    private var pendingCopies = arrayListOf<String>()
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val folderPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val files = controller.state.value.saved.filter { it.key in pendingCopies }
        pendingCopies.clear()
        val tree = result.data?.data
        if (result.resultCode == RESULT_OK && tree != null) {
            val flags = result.data!!.flags
            if (flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION == 0) controller.message("선택 폴더에 쓰기 권한이 없습니다.")
            else if (files.isEmpty()) controller.message("복사할 파일을 다시 선택하세요.")
            else controller.copyToFolder(files, tree)
        } else controller.message("폴더 선택을 취소했습니다.")
    }
    private val destinationPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val tree = result.data?.data
        if (result.resultCode == RESULT_OK && tree != null) controller.setExportFolder(tree)
        else if (result.resultCode == RESULT_CANCELED) controller.message("저장 폴더 선택을 취소했습니다.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingCopies = savedInstanceState?.getStringArrayList("pendingCopies") ?: arrayListOf()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFFA3F0D5), onPrimary = Color(0xFF06382B),
                background = Color(0xFF10171E), surface = Color(0xFF19232D),
                surfaceVariant = Color(0xFF24323E), onSurface = Color(0xFFEAF1F7)
            )) {
                ExportScreen(controller, onDownload = {
                    requestNotifications()
                    controller.download(it)
                }, onOpen = { file ->
                    try { startActivity(Intent.createChooser(ShareFiles.viewIntent(this, file), "영상 재생")) }
                    catch (e: ActivityNotFoundException) { controller.message("이 파일을 재생할 수 있는 앱이 없습니다.") }
                    catch (e: Exception) { controller.message(e.message.orEmpty()) }
                }, onShare = { files ->
                    try { startActivity(Intent.createChooser(ShareFiles.intent(this, files), "공유 / 메일로 보내기")) }
                    catch (e: ActivityNotFoundException) { controller.message("파일 공유를 처리할 앱이 없습니다. 폴더 저장을 이용하세요.") }
                    catch (e: Exception) { controller.message(e.message.orEmpty()) }
                }, onFolder = { files ->
                    pendingCopies = ArrayList(files.map { it.key })
                    try {
                        folderPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        })
                    } catch (e: ActivityNotFoundException) {
                        pendingCopies.clear()
                        controller.message("이 차량에 시스템 폴더 선택기가 없습니다. 공유 기능을 이용하세요.")
                    } catch (e: Exception) { pendingCopies.clear(); controller.message(e.message.orEmpty()) }
                }, onChooseFolder = {
                    try {
                        destinationPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                        })
                    } catch (e: Exception) { controller.message("폴더 선택기를 열 수 없습니다: ${e.message}") }
                })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        controller.autoConnect()
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("pendingCopies", pendingCopies)
        super.onSaveInstanceState(outState)
    }
}

@Composable
private fun ExportScreen(controller: ExportController, onDownload: (List<DvrMedia>) -> Unit,
                         onOpen: (SavedMedia) -> Unit, onShare: (List<SavedMedia>) -> Unit,
                         onFolder: (List<SavedMedia>) -> Unit, onChooseFolder: () -> Unit) {
    val state by controller.state.collectAsState()
    LaunchedEffect(Unit) { controller.autoConnect() }
    LaunchedEffect(state.exportTree) {
        if (controller.shouldPromptInitialFolder()) {
            controller.markInitialFolderPrompted()
            onChooseFolder()
        }
    }
    LaunchedEffect(state.message) {
        if (state.message.isNotBlank()) Toast.makeText(controller.appContext, state.message, Toast.LENGTH_SHORT).show()
    }
    state.errorMessage?.let { error ->
        AlertDialog(
            onDismissRequest = controller::clearError,
            title = { Text("작업 오류") },
            text = { Text(error, color = Color.White) },
            confirmButton = { TextButton(onClick = controller::clearError) { Text("확인") } }
        )
    }
    LaunchedEffect(state.recoveryBase) {
        if (state.recoveryBase != null) Toast.makeText(controller.appContext, "DVR 녹화 복귀 확인이 필요합니다.", Toast.LENGTH_LONG).show()
    }
    var album by rememberSaveable { mutableStateOf<MediaKind?>(null) }
    var savedOpen by rememberSaveable { mutableStateOf(false) }
    var editMode by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var previewKey by rememberSaveable { mutableStateOf<String?>(null) }
    var fullScreenSaved by remember { mutableStateOf<SavedMedia?>(null) }
    var fullScreenPhoto by remember { mutableStateOf<SavedMedia?>(null) }
    val currentAlbum = album
    val inDetail = currentAlbum != null || savedOpen
    val local = savedOpen
    val page = currentAlbum?.let { state.pages[it] }
    val remote = page?.entries.orEmpty()
    val keys = if (local) state.saved.map { it.key } else remote.map { it.key }
    val selection = selected.filter { it in keys }.toSet()
    fun toggle(key: String) {
        val current = selected.filter { it in keys }.toSet()
        selected = ArrayList(if (key in current) current - key else current + key)
    }
    fun leaveDetail() {
        controller.exitPlaybackMode()
        album = null
        savedOpen = false
        editMode = false
        selected = emptyList()
        previewKey = null
    }
    fun openAlbum(kind: MediaKind) {
        album = kind
        savedOpen = false
        editMode = false
        selected = emptyList()
        previewKey = null
    }

    Scaffold(containerColor = Color(0xFF121212), bottomBar = {
        if (state.busy || inDetail) Surface(color = Color(0xFF171717), tonalElevation = 3.dp) {
            Row(
                Modifier.navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp).heightIn(min = 64.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (state.busy) {
                    Text(state.progressText.ifBlank { "작업 중" }, Modifier.widthIn(max = 560.dp), color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val fraction = state.fraction
                    if (fraction == null) LinearProgressIndicator(Modifier.weight(1f))
                    else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.weight(1f))
                    TextButton(onClick = controller::cancel) { Text("취소") }
                } else if (inDetail) {
                    Text("${selection.size}개 선택", Modifier.weight(1f), color = Color(0xFFEAF1F7), fontWeight = FontWeight.SemiBold)
                    if (local) {
                        OutlinedButton(onClick = { onFolder(state.saved.filter { it.key in selection }) }, enabled = selection.isNotEmpty(),
                            modifier = Modifier.heightIn(min = 52.dp)) { Text("USB 저장") }
                        Button(onClick = { onShare(state.saved.filter { it.key in selection }) }, enabled = selection.isNotEmpty(),
                            modifier = Modifier.heightIn(min = 52.dp)) { Text("공유") }
                    } else {
                        Button(onClick = {
                            onDownload(remote.filter { it.key in selection })
                            selected = emptyList()
                            editMode = false
                        }, enabled = selection.isNotEmpty() && state.recoveryBase == null,
                            modifier = Modifier.heightIn(min = 52.dp)) { Text("Export") }
                    }
                }
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).statusBarsPadding()) {
            GalleryHeader(
                title = if (savedOpen) "Saved" else currentAlbum?.galleryTitle ?: "Gallery+",
                inDetail = inDetail,
                editMode = editMode,
                busy = state.busy,
                onBack = ::leaveDetail,
                onEdit = { editMode = !editMode; selected = emptyList(); previewKey = null },
                onChooseFolder = onChooseFolder
            )
            Row(Modifier.fillMaxSize()) {
                GallerySidebar(savedSelected = savedOpen, onAlbums = ::leaveDetail, onSaved = {
                    album = null
                    savedOpen = true
                    editMode = false
                    selected = emptyList()
                    previewKey = null
                })
                if (inDetail) {
                    DetailGrid(
                        kind = currentAlbum,
                        local = local,
                        state = state,
                        page = page,
                        remote = remote,
                        selection = selection,
                        editMode = editMode,
                        previewKey = previewKey,
                        onToggle = ::toggle,
                        onLongPress = { key -> editMode = true; previewKey = null; if (key !in selection) selected = ArrayList(selected + key) },
                        onPreview = { key ->
                            if (previewKey == key) {
                                previewKey = null
                                controller.exitPlaybackMode()
                            } else {
                                controller.enterPlaybackMode { previewKey = key }
                            }
                        },
                        onOpen = { file ->
                            if (file.mime.startsWith("video/")) fullScreenSaved = file else fullScreenPhoto = file
                        },
                        onMore = { currentAlbum?.let(controller::more) },
                        onSelectAll = { selected = if (selection.size == keys.size) emptyList() else ArrayList(keys) }
                    )
                } else {
                    AlbumHome(state = state, onAlbum = ::openAlbum)
                }
            }
        }
    }
    fullScreenSaved?.let { file ->
        FullScreenVideo(file = file, onDismiss = { fullScreenSaved = null })
    }
    fullScreenPhoto?.let { file ->
        FullScreenPhoto(file = file, onDismiss = { fullScreenPhoto = null })
    }
}

@Composable
private fun GalleryHeader(title: String, inDetail: Boolean, editMode: Boolean,
                          busy: Boolean, onBack: () -> Unit, onEdit: () -> Unit,
                          onChooseFolder: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(130.dp).padding(horizontal = 31.dp), verticalAlignment = Alignment.CenterVertically) {
            if (inDetail) {
                Text("←", color = Color(0xFFFF7A00), fontSize = 48.sp, modifier = Modifier.clickable(onClick = onBack).padding(end = 24.dp))
            } else {
                Image(
                    painter = painterResource(R.drawable.ic_oem_gallery),
                    contentDescription = "Gallery+",
                    modifier = Modifier.size(47.dp)
                )
                Spacer(Modifier.width(31.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 48.sp, fontWeight = FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (inDetail) {
                if (editMode) {
                    IconButton(onClick = onEdit, modifier = Modifier.size(64.dp)) {
                        Text("×", color = Color.White, fontSize = 42.sp)
                    }
                } else {
                    TextButton(onClick = onEdit, modifier = Modifier.height(56.dp)) {
                        Text("선택", color = Color(0xFFA3F0D5), fontSize = 24.sp)
                    }
                }
            } else {
                IconButton(onClick = onChooseFolder, enabled = !busy, modifier = Modifier.size(56.dp)) {
                    Text("⚙", fontSize = 36.sp, color = Color(0xFFA3F0D5))
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF343434)))
    }
}

@Composable
private fun GallerySidebar(savedSelected: Boolean, onAlbums: () -> Unit, onSaved: () -> Unit) {
    Column(Modifier.width(244.dp).fillMaxHeight().background(Color(0xFF181818)).padding(top = 43.dp)) {
        SidebarItem(SidebarGlyph.Albums, "Albums", selected = !savedSelected, onClick = onAlbums)
        SidebarItem(SidebarGlyph.Saved, "Saved", selected = savedSelected, onClick = onSaved)
    }
}

@Composable
private fun SidebarItem(icon: SidebarGlyph, label: String, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) Color(0xFFFF7A00) else Color(0xFFB8B8B8)
    Column(
        Modifier.fillMaxWidth().height(145.dp).clickable(onClick = onClick).padding(start = 31.dp),
        verticalArrangement = Arrangement.Center
    ) {
        SidebarGlyphIcon(icon, color)
        Text(label, color = color, fontSize = 34.sp)
    }
}

private enum class SidebarGlyph { Albums, Saved }

@Composable
private fun SidebarGlyphIcon(glyph: SidebarGlyph, color: Color) {
    if (glyph == SidebarGlyph.Albums) {
        Icon(
            painter = painterResource(R.drawable.ic_oem_albums),
            contentDescription = "Albums",
            tint = color,
            modifier = Modifier.size(48.dp)
        )
    } else {
        Icon(
            painter = painterResource(R.drawable.ic_chrome_download),
            contentDescription = "Saved",
            tint = color,
            modifier = Modifier.size(48.dp)
        )
    }
}

@Composable
private fun AlbumHome(state: ExportState, onAlbum: (MediaKind) -> Unit) {
    Column(Modifier.fillMaxSize().padding(start = 31.dp, top = 31.dp, end = 16.dp)) {
        Text("Exterior", color = Color(0xFFB8B8B8), fontSize = 36.sp)
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(31.dp)) {
            MediaKind.entries.forEach { kind ->
                val albumPage = state.pages[kind]
                val cover = albumPage?.entries?.firstOrNull()?.let { state.thumbnails[it.key] }
                AlbumCard(kind, albumPage?.entries?.size ?: 0, cover, Modifier.width(334.dp), onClick = { onAlbum(kind) })
            }
        }
    }
}

@Composable
private fun AlbumCard(kind: MediaKind, count: Int, cover: String?, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick)) {
        Thumbnail(path = cover, width = 309.dp, height = 309.dp, radius = 0.dp)
        Spacer(Modifier.height(8.dp))
        Text(kind.galleryTitle, fontSize = 31.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("$count", fontSize = 31.sp, color = Color(0xFFB8B8B8))
    }
}

@Composable
private fun DetailGrid(kind: MediaKind?, local: Boolean, state: ExportState, page: CategoryPage?,
                       remote: List<DvrMedia>, selection: Set<String>, editMode: Boolean, previewKey: String?,
                       onToggle: (String) -> Unit, onLongPress: (String) -> Unit, onPreview: (String) -> Unit, onOpen: (SavedMedia) -> Unit, onMore: () -> Unit,
                       onSelectAll: () -> Unit) {
    val keys = if (local) state.saved.map { it.key } else remote.map { it.key }
    Column(Modifier.fillMaxSize().padding(horizontal = 27.dp, vertical = 21.dp)) {
        Row(
            Modifier.fillMaxWidth().height(48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (local) "${state.saved.size} files" else "${remote.size} files",
                color = Color(0xFFB8B8B8), fontSize = 18.sp, modifier = Modifier.weight(1f))
            if (editMode) TextButton(onClick = onSelectAll, enabled = keys.isNotEmpty() && !state.busy) {
                Text(if (selection.size == keys.size && keys.isNotEmpty()) "Deselect all" else "Select all")
            } else {
                Spacer(Modifier.width(112.dp))
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 370.dp),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            if (local) {
                gridItems(state.saved, key = { it.key }) { item ->
                    SavedTile(item, selected = item.key in selection, editMode = editMode,
                        onToggle = { onToggle(item.key) }, onLongPress = { onLongPress(item.key) }, onOpen = { onOpen(item) })
                }
            } else {
                remote.groupBy { dateGroupLabel(it.dateTime) }.forEach { (date, items) ->
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(date, color = Color.White, fontSize = 24.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 2.dp))
                    }
                    gridItems(items, key = { it.key }) { item ->
                        DvrTile(item, state.thumbnails[item.key], selected = item.key in selection,
                            playing = previewKey == item.key, editMode = editMode,
                            onToggle = { onToggle(item.key) }, onLongPress = { onLongPress(item.key) }, onPreview = { onPreview(item.key) })
                    }
                }
                if (page?.error != null) item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(page.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp))
                }
                if (page != null && (page.hasMore || page.error != null) && state.directories.any { it.kind == kind }) item(span = { GridItemSpan(maxLineSpan) }) {
                    OutlinedButton(onClick = onMore, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text(if (page.error != null) "Retry" else "Load more")
                    }
                }
            }
            if (keys.isEmpty() && !state.busy) item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fillMaxWidth().padding(vertical = 120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (local) "No saved files" else "No files", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(12.dp))
                    Text(if (local) "Export videos from an album first." else "The DVR did not return files for this album.",
                        color = Color(0xFFB8B8B8), fontSize = 18.sp)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DvrTile(item: DvrMedia, thumbnailPath: String?, selected: Boolean, playing: Boolean, editMode: Boolean,
                    onToggle: () -> Unit, onLongPress: () -> Unit, onPreview: () -> Unit) {
    val canPreview = item.kind != MediaKind.PHOTO
    Column(Modifier.combinedClickable(onClick = { if (editMode || !canPreview) onToggle() else onPreview() }, onLongClick = onLongPress)) {
        SelectableThumbnail(path = thumbnailPath, selected = selected, playing = playing, videoUrl = if (playing) item.url else null) {
            if (!editMode && canPreview && !playing) Surface(
                color = Color(0x99000000), shape = RoundedCornerShape(28.dp),
                modifier = Modifier.align(Alignment.Center).size(56.dp)
            ) { Box(contentAlignment = Alignment.Center) { Text("▶", color = Color.White, fontSize = 28.sp) } }
        }
        Text(item.displayRange, fontSize = 22.sp, color = if (selected) Color(0xFFFF7A00) else Color.White,
            lineHeight = 28.sp, modifier = Modifier.padding(top = 8.dp), fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(item.name, fontSize = 12.sp, color = if (selected) Color(0xFFFFB26A) else Color(0xFF9C9C9C),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(formatBytes(item.size), fontSize = 12.sp,
                color = if (selected) Color(0xFFFFB26A) else Color(0xFF9C9C9C),
                modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedTile(item: SavedMedia, selected: Boolean, editMode: Boolean, onToggle: () -> Unit, onLongPress: () -> Unit, onOpen: () -> Unit) {
    val context = LocalContext.current
    val thumbnail by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, item.key) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            savedThumbnail(context, item)?.asImageBitmap()
        }
    }
    Column(Modifier.combinedClickable(onClick = { if (editMode) onToggle() else onOpen() }, onLongClick = onLongPress)) {
        SelectableThumbnail(path = null, selected = selected, thumbnail = thumbnail)
        Text(item.name, fontSize = 22.sp, color = if (selected) Color(0xFFFF7A00) else Color.White,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp),
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        Text("${item.kind.galleryTitle} · ${formatBytes(item.size)}", fontSize = 12.sp,
            color = if (selected) Color(0xFFFFB26A) else Color(0xFF9C9C9C))
    }
}

@Composable
private fun SelectableThumbnail(path: String?, selected: Boolean, playing: Boolean = false, videoUrl: String? = null,
                                thumbnail: androidx.compose.ui.graphics.ImageBitmap? = null,
                                overlay: @Composable BoxScope.() -> Unit = {}) {
    Surface(
        color = Color.Transparent,
        shape = RoundedCornerShape(0.dp),
        border = if (selected) BorderStroke(5.dp, Color(0xFFFF7A00)) else null,
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
    ) {
        Box {
            if (videoUrl != null) InlineVideo(url = videoUrl)
            else if (thumbnail != null) Image(thumbnail, contentDescription = "썸네일", contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize())
            else Thumbnail(path = path, width = 248.dp, height = 140.dp, radius = 0.dp, fillFrame = true)
            if (playing) {
                Surface(color = Color(0xCC000000), modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)) {
                    Text("재생 중", color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
                }
            }
            if (selected) Box(Modifier.matchParentSize().background(Color(0x33000000)))
            overlay()
        }
    }
}

private fun savedThumbnail(context: android.content.Context, item: SavedMedia): android.graphics.Bitmap? {
    val isVideo = item.mime.startsWith("video/") || item.name.substringAfterLast('.', "").lowercase() in
        setOf("mp4", "m4v", "mov", "ts", "avi")
    if (!isVideo) {
        return try {
            item.file?.let { BitmapFactory.decodeFile(it.absolutePath) }
                ?: item.uri?.let { context.contentResolver.openFileDescriptor(it, "r")?.use { fd -> BitmapFactory.decodeFileDescriptor(fd.fileDescriptor) } }
        } catch (_: Exception) { null }
    }
    return try {
        MediaMetadataRetriever().use { retriever ->
            if (item.file != null) retriever.setDataSource(item.file.absolutePath)
            else if (item.uri != null) retriever.setDataSource(context, item.uri)
            retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }
    } catch (_: Exception) { null }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun InlineVideo(url: String) {
    val context = LocalContext.current
    var failureText by remember(url) { mutableStateOf<String?>(null) }
    var ready by remember(url) { mutableStateOf(false) }
    val player = remember(url) {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(60_000)
            .setReadTimeoutMs(60_000)
            // Match the OEM player's first request: a direct GET with no synthetic
            // Range or custom User-Agent. It adds Range only when seeking/resuming.
        val dataSourceFactory = PlaybackCache.factory(context, httpDataSourceFactory)
        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(buildUponParameters().setRendererDisabled(C.TRACK_TYPE_AUDIO, true))
        }
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setTrackSelector(trackSelector)
            .build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
            setMediaItem(MediaItem.fromUri(Uri.parse(url)))
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    ready = playbackState == Player.STATE_READY
                }

                override fun onPlayerError(error: PlaybackException) {
                    val response = generatePlaybackError(error)
                    failureText = "${error.errorCodeName}: $response"
                }
            })
            prepare()
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    this.player = player
                }
            },
            update = { view ->
                if (view.player !== player) view.player = player
                if (!player.isPlaying && failureText == null) player.play()
            },
            modifier = Modifier.matchParentSize()
        )
        if (!ready && failureText == null) {
            Box(Modifier.matchParentSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFFFF7A00), strokeWidth = 2.dp, modifier = Modifier.size(34.dp))
            }
        }
        failureText?.let { message ->
            Box(Modifier.matchParentSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
            Text("재생 실패\n$message", color = Color.White, fontSize = 11.sp, lineHeight = 14.sp)
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun generatePlaybackError(error: PlaybackException): String {
    var cause: Throwable? = error.cause
    while (cause != null) {
        if (cause is HttpDataSource.InvalidResponseCodeException) {
            val body = cause.headerFields?.values?.flatten()?.firstOrNull { it.isNotBlank() }.orEmpty()
            return "HTTP ${cause.responseCode}${if (body.isBlank()) "" else " · $body"}"
        }
        cause = cause.cause
    }
    return error.cause?.message ?: "DVR 응답을 읽지 못했습니다."
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun FullScreenVideo(file: SavedMedia, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val uri = remember(file.key) { ShareFiles.uri(context, file) }
    val player = remember(uri) {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(60_000)
            .setReadTimeoutMs(60_000)
            .setUserAgent("Gallery+")
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .build().apply {
                playWhenReady = true
                setMediaItem(MediaItem.fromUri(uri))
                prepare()
            }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { viewContext ->
                        PlayerView(viewContext).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            setShutterBackgroundColor(android.graphics.Color.BLACK)
                            this.player = player
                        }
                    },
                    update = { view -> if (view.player !== player) view.player = player },
                    modifier = Modifier.fillMaxSize()
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.TopEnd).padding(18.dp).size(64.dp)
                ) { Text("×", color = Color.White, fontSize = 52.sp) }
                Text(
                    file.name,
                    color = Color.White,
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart).padding(24.dp)
                )
            }
        }
    }
}

@Composable
private fun FullScreenPhoto(file: SavedMedia, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, file.key) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            savedThumbnail(context, file)?.asImageBitmap()
        }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
                bitmap?.let {
                    Image(it, contentDescription = file.name, contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(24.dp))
                } ?: CircularProgressIndicator(color = Color(0xFFFF7A00), modifier = Modifier.align(Alignment.Center))
                IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(18.dp).size(64.dp)) {
                    Text("×", color = Color.White, fontSize = 52.sp)
                }
                Text(file.name, color = Color.White, fontSize = 18.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart).padding(24.dp))
            }
        }
    }
}

@Composable
private fun Thumbnail(path: String?, width: androidx.compose.ui.unit.Dp = 72.dp,
                      height: androidx.compose.ui.unit.Dp = 72.dp,
                      radius: androidx.compose.ui.unit.Dp = 8.dp,
                      fillFrame: Boolean = false) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, path) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            path?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() }
        }
    }
    val frameModifier = if (fillFrame) Modifier.fillMaxSize() else Modifier.size(width = width, height = height)
    if (bitmap != null) Image(bitmap!!, contentDescription = "썸네일", contentScale = ContentScale.Crop,
        modifier = frameModifier.clip(RoundedCornerShape(radius)))
    else Surface(color = Color(0xFF252525), shape = RoundedCornerShape(radius),
        modifier = frameModifier) {
        Box(contentAlignment = Alignment.Center) { Text("▱", color = Color(0xFF777777), fontSize = 72.sp) }
    }
}

private fun formatTimestamp(value: Long): String? {
    if (value <= 0) return null
    val millis = if (value < 100_000_000_000L) value * 1000 else value
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(millis))
}

private fun dateGroupLabel(value: Long): String {
    if (value <= 0L) return "Unknown date"
    val millis = if (value < 100_000_000_000L) value * 1000 else value
    return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(millis))
}
