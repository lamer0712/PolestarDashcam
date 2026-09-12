package com.polestar.dashcamexporter

import android.content.Intent
import android.provider.MediaStore
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Requires tools/mock_dvr.py and adb reverse tcp:8765 tcp:8765. Never targets a real DVR. */
@RunWith(AndroidJUnit4::class)
class ExporterInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val controller get() = (compose.activity.application as ExporterApplication).controller
    private val base = "http://127.0.0.1:8765"
    private fun control(json: String): JSONObject {
        val connection = URL("$base/__control").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(json.toByteArray()) }
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }
    private fun mockState(): JSONObject = URL("$base/__state").openStream().bufferedReader().use { JSONObject(it.readText()) }
    private fun readSaved(item: SavedMedia): ByteArray = item.file?.readBytes()
        ?: compose.activity.contentResolver.openInputStream(item.uri!!)!!.use { it.readBytes() }
    private fun idle() { compose.waitUntil(30_000) { !controller.state.value.busy } }
    private fun refresh(mode: Boolean = false) {
        compose.runOnUiThread { assertTrue(controller.configure(base, mode)); controller.refresh() }
        idle()
    }

    @Before fun setup() {
        idle()
        control("""{"recording":"normal","requireMode":false,"failRestore":false,"delay":0,"failCategory":"","posts":[]}""")
        if (controller.state.value.recoveryBase != null) {
            compose.runOnUiThread { controller.recoverRecording() }; idle()
        }
        compose.runOnUiThread { assertTrue(controller.configure(base, false)) }
    }

    @Test fun uiConnectSelectDownloadAndShareProducesReadableContentUris() {
        compose.runOnUiThread { controller.refresh() }
        idle()
        assertEquals(50, controller.state.value.pages[MediaKind.NORMAL]!!.entries.size)
        assertEquals(2, controller.state.value.pages[MediaKind.EMERGENCY]!!.entries.size)
        assertEquals(2, controller.state.value.pages[MediaKind.PHOTO]!!.entries.size)
        compose.onNodeWithText("Loop videos").performClick()
        compose.onNodeWithText("✎").performClick()
        compose.onNodeWithText("normal_000.mp4").performClick()
        compose.onNodeWithText("Export").performClick()
        idle()
        val saved = controller.state.value.saved.first { it.name == "normal_000.mp4" }
        val publicCount = compose.activity.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
            arrayOf("normal_000.mp4", "Movies/Polestar Dashcam/normal/"), null
        )!!.use { it.count }
        assertEquals(1, publicCount)
        val hash = MessageDigest.getInstance("SHA-256").digest(readSaved(saved)).joinToString("") { "%02x".format(it) }
        assertEquals(mockState().getString("videoSha256"), hash)
        val intent = ShareFiles.intent(compose.activity, listOf(saved))
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("video/mp4", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val uri = intent.clipData!!.getItemAt(0).uri
        assertEquals("content", uri.scheme)
        assertArrayEquals(readSaved(saved), compose.activity.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
        assertEquals("Default flow must not mutate recording", 0, mockState().getJSONArray("posts").length())
        compose.activityRule.scenario.recreate()
        idle()
        assertTrue(controller.state.value.saved.any { it.key == saved.key })
    }

    @Test fun pagingReachesLastFileAndStopsAfterEmptyPage() {
        refresh()
        compose.runOnUiThread { controller.more(MediaKind.NORMAL) }; idle()
        assertEquals(53, controller.state.value.pages[MediaKind.NORMAL]!!.entries.size)
        compose.runOnUiThread { controller.more(MediaKind.NORMAL) }; idle()
        assertFalse(controller.state.value.pages[MediaKind.NORMAL]!!.hasMore)
    }

    @Test fun defaultFlowAutomaticallyEntersAndRestoresRequiredListMode() {
        control("""{"requireMode":true}""")
        refresh(mode = false)
        assertEquals(50, controller.state.value.pages[MediaKind.NORMAL]!!.entries.size)
        assertEquals("normal", mockState().getString("recording"))
        val posts = mockState().getJSONArray("posts")
        assertEquals("enter-file-list", posts.getString(0))
        assertEquals("normal", posts.getString(posts.length() - 1))
        assertNull(controller.state.value.recoveryBase)
    }

    @Test fun failedCategoryCanRetryWithoutDiscardingOtherCategories() {
        control("""{"failCategory":"emergency"}""")
        refresh()
        assertEquals(50, controller.state.value.pages[MediaKind.NORMAL]!!.entries.size)
        assertTrue(controller.state.value.pages[MediaKind.EMERGENCY]!!.error!!.contains("503"))
        control("""{"failCategory":""}""")
        compose.runOnUiThread { controller.more(MediaKind.EMERGENCY) }; idle()
        assertEquals(2, controller.state.value.pages[MediaKind.EMERGENCY]!!.entries.size)
        assertNull(controller.state.value.pages[MediaKind.EMERGENCY]!!.error)
    }

    @Test fun ownedModeIsRestoredAfterCancelledDownload() {
        control("""{"requireMode":true}""")
        refresh(mode = true)
        assertEquals("normal", mockState().getString("recording"))
        val media = controller.state.value.pages[MediaKind.NORMAL]!!.entries[1].copy(id = "cancel-${System.nanoTime()}")
        control("""{"delay":0.02}""")
        compose.runOnUiThread { controller.download(listOf(media)) }
        compose.waitUntil(20_000) { (controller.state.value.fraction ?: 0f) > 0f }
        compose.runOnUiThread { controller.cancel() }; idle()
        assertEquals("normal", mockState().getString("recording"))
        assertNull(controller.state.value.recoveryBase)
        assertFalse(controller.state.value.saved.any { it.name == media.name })
        assertTrue(controller.state.value.message.contains("취소"))
    }

    @Test fun failedRestorePersistsRecoveryAndRetryClearsItOnlyAfterReadback() {
        control("""{"failRestore":true}""")
        refresh(mode = true)
        assertEquals(base, controller.state.value.recoveryBase)
        assertEquals(base, compose.activity.getSharedPreferences("exporter", 0).getString("recoveryBase", null))
        compose.runOnUiThread { assertFalse(controller.configure(DvrApi.DEFAULT_BASE, false)) }
        control("""{"failRestore":false}""")
        compose.runOnUiThread { controller.recoverRecording() }; idle()
        assertNull(controller.state.value.recoveryBase)
        assertEquals("normal", mockState().getString("recording"))
    }

    @Test fun preexistingOemListSessionIsNotChanged() {
        control("""{"recording":"in-file-list","requireMode":true}""")
        refresh(mode = true)
        assertEquals("in-file-list", mockState().getString("recording"))
        assertEquals(0, mockState().getJSONArray("posts").length())
        assertNull(controller.state.value.recoveryBase)
    }

    @Test fun mixedMultipleShareIncludesEveryUriAndFileProviderRejectsPrivatePaths() {
        refresh()
        val items = listOf(controller.state.value.pages[MediaKind.NORMAL]!!.entries.first(),
            controller.state.value.pages[MediaKind.PHOTO]!!.entries.first())
        compose.runOnUiThread { controller.download(items) }; idle()
        val saved = items.map { item -> controller.state.value.saved.first { it.name == item.name } }
        val intent = ShareFiles.intent(compose.activity, saved)
        assertEquals(Intent.ACTION_SEND_MULTIPLE, intent.action)
        assertEquals("*/*", intent.type)
        assertEquals(2, intent.clipData!!.itemCount)
        assertEquals(2, intent.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!.size)
        assertThrows(IllegalArgumentException::class.java) {
            androidx.core.content.FileProvider.getUriForFile(compose.activity,
                "${compose.activity.packageName}.files", File(compose.activity.filesDir, "private.txt"))
        }
    }
}
