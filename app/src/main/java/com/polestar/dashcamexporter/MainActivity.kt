package com.polestar.dashcamexporter

import android.Manifest
import android.graphics.BitmapFactory
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
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
                })
            }
        }
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
                         onShare: (List<SavedMedia>) -> Unit, onFolder: (List<SavedMedia>) -> Unit) {
    val state by controller.state.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var selected by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    val local = tab == 3
    val kind = MediaKind.entries.getOrNull(tab)
    val page = state.pages[kind]
    val remote = page?.entries.orEmpty()
    val keys = if (local) state.saved.map { it.key } else remote.map { it.key }
    val selection = selected.filter { it in keys }.toSet()
    fun toggle(key: String) { selected = ArrayList(if (key in selection) selection - key else selection + key) }

    Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = {
        Surface(tonalElevation = 3.dp) {
            Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${selection.size}개 선택", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    if (local) {
                        OutlinedButton(onClick = { onFolder(state.saved.filter { it.key in selection }) }, enabled = selection.isNotEmpty() && !state.busy,
                            modifier = Modifier.heightIn(min = 52.dp)) { Text("USB / 폴더 저장") }
                        Button(onClick = { onShare(state.saved.filter { it.key in selection }) }, enabled = selection.isNotEmpty() && !state.busy,
                            modifier = Modifier.heightIn(min = 52.dp)) { Text("공유 / 메일") }
                    } else {
                        Button(onClick = { onDownload(remote.filter { it.key in selection }) }, enabled = selection.isNotEmpty() && !state.busy && state.recoveryBase == null,
                            modifier = Modifier.heightIn(min = 52.dp)) { Text("선택 다운로드") }
                    }
                }
                Text(if (local) "메일 앱이 설치되어 있으면 공유 시트에서 선택하세요. USB는 폴더 선택기에 표시되어야 합니다."
                    else "다운로드한 파일은 ‘저장됨’에서 공유하거나 USB에 복사할 수 있습니다.", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).statusBarsPadding().padding(horizontal = 24.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("DASHCAM / EXPORT", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, letterSpacing = 2.sp)
                    Text("대시캠 파일 내보내기", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                }
                Button(onClick = { selected = emptyList(); controller.refresh() }, enabled = !state.busy && state.recoveryBase == null,
                    modifier = Modifier.heightIn(min = 48.dp)) { Text(if (state.connected) "새로고침" else "DVR 연결") }
            }
            Text("주차 중에 사용하세요 · ${state.dvrStatus}", fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (state.recoveryBase != null) {
                Surface(color = Color(0xFF553428), shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("DVR 녹화 복귀 확인 필요\n${state.recoveryBase}", Modifier.weight(1f))
                        Button(onClick = controller::recoverRecording, enabled = !state.busy) { Text("녹화 복귀 재시도") }
                    }
                }
            }
            if (state.busy) {
                Column(Modifier.padding(top = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(state.progressText, Modifier.weight(1f), fontSize = 13.sp, maxLines = 2)
                        TextButton(onClick = controller::cancel) { Text("취소") }
                    }
                    val fraction = state.fraction
                    if (fraction == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                }
            }
            if (state.message.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(state.message, Modifier.weight(1f).padding(vertical = 8.dp), fontSize = 13.sp, maxLines = 5)
                        TextButton(onClick = { controller.message("") }) { Text("닫기") }
                    }
                }
            }
            TabRow(selectedTabIndex = tab, containerColor = Color.Transparent, modifier = Modifier.padding(top = 8.dp)) {
                (MediaKind.entries.map { it.label } + "저장됨").forEachIndexed { index, title ->
                    Tab(selected = tab == index, onClick = { tab = index; selected = arrayListOf() }, text = {
                        val count = if (index == 3) state.saved.size else state.pages[MediaKind.entries[index]]?.entries?.size ?: 0
                        Text("$title  $count", maxLines = 1)
                    })
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (local) "기기에 보관된 파일 · ${formatBytes(state.saved.sumOf { it.size })}"
                    else "${kind?.api.orEmpty()} · 최신 파일부터 표시", fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { selected = if (selection.size == keys.size) arrayListOf() else ArrayList(keys) },
                    enabled = keys.isNotEmpty() && !state.busy) { Text(if (selection.isNotEmpty() && selection.size == keys.size) "선택 해제" else "전체 선택") }
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                if (local) {
                    items(state.saved, key = { it.key }) { item ->
                        MediaRow(item.name, detail = "${item.kind.label} · ${formatBytes(item.size)} · 저장 완료",
                            selected = item.key in selection, enabled = !state.busy, toggle = { toggle(item.key) })
                    }
                } else {
                    items(remote, key = { it.key }) { item ->
                        MediaRow(item.name, state.thumbnails[item.key], listOfNotNull(formatTimestamp(item.dateTime),
                            if (item.size > 0) formatBytes(item.size) else "크기 미상").joinToString(" · "),
                            item.key in selection, !state.busy, { toggle(item.key) })
                    }
                    if (page?.error != null) item {
                        Text(page.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(8.dp))
                    }
                    if (page != null && (page.hasMore || page.error != null) && state.directories.any { it.kind == kind }) item {
                        OutlinedButton(onClick = { kind?.let(controller::more) }, enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                            Text(if (page.error != null) "목록 재시도" else "다음 목록 불러오기")
                        }
                    }
                }
                if (keys.isEmpty() && !state.busy) item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (local) "아직 다운로드한 파일이 없습니다" else if (state.connected) "표시할 파일이 없습니다" else "차량의 대시캠 파일을 가져오세요",
                            fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(12.dp))
                        Text(if (local) "일반·긴급·사진 탭에서 파일을 선택하고 다운로드하세요."
                            else if (state.connected) "다른 분류를 선택하거나 목록을 새로고침하세요."
                            else "DVR 연결 → 파일 선택 → 다운로드 → 공유", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaRow(name: String, thumbnailPath: String? = null, detail: String, selected: Boolean, enabled: Boolean, toggle: () -> Unit) {
    Surface(color = if (selected) Color(0xFF203F3B) else MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = toggle)) {
        Row(Modifier.padding(12.dp).heightIn(min = 58.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = selected, onCheckedChange = { toggle() }, enabled = enabled)
            Thumbnail(path = thumbnailPath)
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(detail, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Thumbnail(path: String?) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, path) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            path?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() }
        }
    }
    if (bitmap != null) Image(bitmap!!, contentDescription = "썸네일", contentScale = ContentScale.Crop,
        modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)))
    else Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp),
        modifier = Modifier.size(72.dp)) { Box(contentAlignment = Alignment.Center) { Text("미리보기", fontSize = 11.sp) } }
}

private fun formatTimestamp(value: Long): String? {
    if (value <= 0) return null
    val millis = if (value < 100_000_000_000L) value * 1000 else value
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(millis))
}
