package com.polestar.dashcamexporter

import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.nio.file.Files

class DvrProtocolTest {
    private lateinit var server: MockWebServer
    private lateinit var base: String
    private lateinit var root: File
    private val requests = mutableListOf<String>()
    private val rangeHeaders = mutableListOf<String?>()
    private val routes = mutableMapOf<String, (RecordedRequest) -> MockResponse>()
    @Before fun setup() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(requests) { requests += "${request.method} ${request.path}" }
                synchronized(rangeHeaders) { rangeHeaders += request.getHeader("Range") }
                return routes[request.path!!.substringBefore('?')]?.invoke(request) ?: MockResponse().setResponseCode(404)
            }
        }
        server.start()
        base = "http://127.0.0.1:${server.port}"
        root = Files.createTempDirectory("dashcam-test").toFile()
    }
    @After fun cleanup() { server.shutdown(); root.deleteRecursively() }
    private fun response(path: String, body: ByteArray, type: String = "application/json", status: Int = 200) {
        routes[path] = { MockResponse().setHeader("Content-Type", type).setResponseCode(status).setBody(Buffer().write(body)) }
    }
    private fun media(name: String = "clip.mp4", size: Long = 5L, kind: MediaKind = MediaKind.NORMAL) =
        DvrMedia(kind, "1", name, size, 1789080000, 60, "$base/media/$name")

    @Test fun parsesOemMediaListAndUsesMediaTypeNotType() {
        val dirs = DvrJson.directories("""{"mediaList":[{"type":"video","mediaType":"normal","mediaPath":"DCIM/Normal","fileCount":2},{"type":"image","mediaType":"photo","mediaPath":"DCIM/Photo","fileCount":0}]}""")
        assertEquals(listOf(MediaKind.NORMAL, MediaKind.PHOTO), dirs.map { it.kind })
        assertEquals(2, dirs.first().count)
    }
    @Test fun missingAndErrorSchemasAreNotShownAsEmptyLists() {
        assertThrows(IOException::class.java) { DvrJson.directories("""{"directories":[]}""") }
        assertThrows(IOException::class.java) { DvrJson.directories("""{"error":4,"message":"SD unavailable"}""") }
        assertThrows(IOException::class.java) { DvrJson.status("{}") }
        assertNull(DvrJson.statusOrNull("{}"))
        assertThrows(IOException::class.java) { DvrJson.status("<html>error</html>") }
        assertEquals(emptyList<MediaDirectory>(), DvrJson.directories("""{"mediaList":[]}"""))
    }
    @Test fun acceptsNestedVehicleStatusAndNonStatusJsonForProbe() {
        assertEquals(DvrStatus(true, "normal"),
            DvrJson.statusOrNull("""{"status":{"usable":true,"recording":"normal"}}"""))
        assertEquals(DvrStatus(true, "in-file-list"),
            DvrJson.statusOrNull("""{"result":{"usable":true,"recording":"in-file-list"}}"""))
        assertNull(DvrJson.statusOrNull("""{"result":"ok","message":"alive"}"""))
    }
    @Test fun acceptsActualPolestarStatusStrings() {
        val status = DvrJson.status("""{"usable":"yes","recording":"normal","parkingRecording":"off","isEmmc":"TF"}""")
        assertEquals(DvrStatus(true, "normal"), status)
        assertEquals(DvrStatus(false, "normal"), DvrJson.status("""{"usable":"no","recording":"normal"}"""))
    }
    @Test fun encodesKoreanSpaceHashQuestionAndPercentOnce() {
        val url = DvrJson.mediaUrl(base, "/DCIM/Normal/", "주차 #1?100%.mp4")
        assertEquals("$base/DCIM/Normal/%EC%A3%BC%EC%B0%A8%20%231%3F100%25.mp4", url)
    }
    @Test fun rejectsTraversalAndOffDevicePaths() {
        listOf("../clip.mp4", "a/b.mp4", "..", "").forEach { name ->
            assertThrows(IOException::class.java) { DvrJson.mediaUrl(base, "DCIM", name) }
        }
        listOf("../secret", "http://evil.test/a", "DCIM/../secret").forEach { path ->
            assertThrows(IOException::class.java) { DvrJson.mediaUrl(base, path, "clip.mp4") }
        }
        assertThrows(IOException::class.java) { DvrJson.baseUrl("http://198.18.37.20@evil.test") }
        assertThrows(IOException::class.java) { DvrJson.baseUrl("http://198.18.37.20/path") }
    }
    @Test fun requestsExactOemPaginationAndParsesNameAndLongSize() {
        response("/filelist", """{"fileList":[{"mediaType":"normal","name":"clip.mp4","id":"42","size":5000000000,"dateTime":1789080000,"duration":60}]}""".toByteArray())
        val files = DvrApi(base).files(MediaDirectory(MediaKind.NORMAL, "DCIM/N", 1), 50)
        assertEquals(5_000_000_000L, files.single().size)
        assertEquals("$base/DCIM/N/clip.mp4", files.single().url)
        assertEquals("GET /filelist?app=gallery&type=normal&startIndex=50&count=50&sort=newest-first&infoLevel=2", requests.single())
    }
    @Test fun refusesCrossCategoryFileResponse() {
        assertThrows(IOException::class.java) {
            DvrJson.files("""{"fileList":[{"name":"clip.mp4","mediaType":"emergency"}]}""",
                MediaDirectory(MediaKind.NORMAL, "DCIM/N", 1), base)
        }
    }
    @Test fun downloadPreservesBytesAndSurvivesStoreRecreation() {
        val payload = ByteArray(500_000) { (it % 251).toByte() }
        response("/media/clip.mp4", payload, "video/mp4")
        val item = media(size = payload.size.toLong())
        val file = DownloadStore(root).download(item, StopToken()) { _, _ -> }
        assertArrayEquals(payload, file.file!!.readBytes())
        assertEquals(1, DownloadStore(root).saved().size)
        DownloadStore(root).download(item, StopToken()) { _, _ -> }
        assertEquals("Verified existing download is reused", 1, requests.size)
        assertFalse(root.walkTopDown().any { it.name.endsWith(".part") })
    }
    @Test fun identicalNamesFromDifferentCategoriesDoNotCollide() {
        response("/media/clip.mp4", "12345".toByteArray(), "video/mp4")
        val store = DownloadStore(root)
        val first = store.download(media(), StopToken()) { _, _ -> }
        val second = store.download(media(kind = MediaKind.EMERGENCY), StopToken()) { _, _ -> }
        assertNotEquals(first.file, second.file)
        assertEquals(2, store.saved().size)
    }
    @Test fun rejectsHttp200ErrorDocument() {
        response("/media/clip.mp4", "error".toByteArray(), "text/html")
        assertThrows(IOException::class.java) { DownloadStore(root).download(media(), StopToken()) { _, _ -> } }
        assertTrue(DownloadStore(root).saved().isEmpty())
    }
    @Test fun rejectsChangedSizeAndDoesNotLeavePartialFile() {
        response("/media/clip.mp4", "123".toByteArray(), "video/mp4")
        assertThrows(IOException::class.java) { DownloadStore(root).download(media(), StopToken()) { _, _ -> } }
        assertTrue(DownloadStore(root).saved().isEmpty())
        assertFalse(root.walkTopDown().any { it.name.endsWith(".part") })
    }
    @Test fun refusesRedirectsAndHttpErrors() {
        response("/media/clip.mp4", "redirect".toByteArray(), status = 302)
        assertThrows(IOException::class.java) { DownloadStore(root).download(media(), StopToken()) { _, _ -> } }
        response("/status", "denied".toByteArray(), status = 403)
        val error = assertThrows(IOException::class.java) { DvrApi(base).status() }
        assertTrue(error.message!!.contains("403"))
    }
    @Test fun cancelledDownloadRemovesPartialAndNoFinalFileIsPublished() {
        response("/media/clip.mp4", ByteArray(500_000), "video/mp4")
        val token = StopToken()
        assertThrows(UserCancelledException::class.java) {
            DownloadStore(root).download(media(size = 500_000), token) { _, _ -> token.cancel() }
        }
        assertTrue(DownloadStore(root).saved().isEmpty())
        assertFalse(root.walkTopDown().any { it.name.endsWith(".part") })
    }
    @Test fun truncatedAndEmptyStreamsAreRejectedIncludingUnknownLength() {
        assertThrows(IOException::class.java) {
            StreamCopy.copy(ByteArrayInputStream(byteArrayOf(1, 2)), ByteArrayOutputStream(), 3, StopToken()) { _, _ -> }
        }
        assertThrows(IOException::class.java) {
            StreamCopy.copy(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), 0, StopToken()) { _, _ -> }
        }
    }
    @Test fun chunkedUnknownLengthDownloadWorks() {
        routes["/media/clip.mp4"] = { MockResponse().setHeader("Content-Type", "application/octet-stream").setChunkedBody("stream", 2) }
        val file = DownloadStore(root).download(media(size = 0), StopToken()) { _, _ -> }
        assertEquals("stream", file.file!!.readText())
    }
    @Test fun retriesA60MbPlusFileAfterUnexpectedEndOfStream() {
        val payload = ByteArray(60 * 1024 * 1024 + 123) { (it * 31 % 251).toByte() }
        var interrupted = true
        routes["/media/clip.mp4"] = { request ->
            val start = request.getHeader("Range")?.substringAfter("bytes=")?.substringBefore("-")?.toIntOrNull() ?: 0
            val end = minOf(payload.lastIndex, start + 32 * 1024 * 1024 - 1)
            if (interrupted) {
                interrupted = false
                MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes $start-$end/${payload.size}")
                    .setHeader("Content-Length", (end - start + 1).toString())
                    .setBody(Buffer().write(payload, start, 4 * 1024 * 1024))
                    .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
            } else {
                MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes $start-$end/${payload.size}")
                    .setBody(Buffer().write(payload, start, end - start + 1))
            }
        }
        val saved = DownloadStore(root).download(media(size = payload.size.toLong()), StopToken()) { _, _ -> }
        assertArrayEquals(payload, saved.file!!.readBytes())
        assertTrue(requests.count { it.startsWith("GET /media/clip.mp4") } >= 3)
        assertTrue(rangeHeaders.filterNotNull().all { it.matches(Regex("bytes=\\d+-")) })
    }
    @Test fun usesOemStyleOpenEndedRangesForLargeDownloads() {
        val payload = ByteArray(40 * 1024 * 1024 + 17) { (it * 13 % 251).toByte() }
        routes["/media/clip.mp4"] = { request ->
            val range = request.getHeader("Range").orEmpty()
            val start = range.substringAfter("bytes=", "0-").substringBefore("-").toIntOrNull() ?: 0
            if (range.isBlank() || !range.matches(Regex("bytes=\\d+-"))) MockResponse().setResponseCode(403)
            else {
                val end = minOf(payload.lastIndex, start + 32 * 1024 * 1024 - 1)
                MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes $start-$end/${payload.size}")
                    .setHeader("Content-Length", (end - start + 1).toString())
                    .setBody(Buffer().write(payload, start, end - start + 1))
            }
        }
        val saved = DownloadStore(root).download(media(size = payload.size.toLong()), StopToken()) { _, _ -> }
        assertArrayEquals(payload, saved.file!!.readBytes())
        val mediaRanges = rangeHeaders.filterNotNull()
        assertEquals(listOf("bytes=0-", "bytes=33554432-"), mediaRanges)
    }
    @Test fun preservesDvrSessionCookieAcrossRangeRequests() {
        val previous = CookieHandler.getDefault()
        CookieHandler.setDefault(CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER))
        try {
            val payload = ByteArray(40 * 1024 * 1024 + 17) { (it * 7 % 251).toByte() }
            var first = true
            routes["/media/clip.mp4"] = { request ->
                val range = request.getHeader("Range").orEmpty()
                val start = range.substringAfter("bytes=", "0-").substringBefore("-").toIntOrNull() ?: 0
                if (start > 0 && !request.getHeader("Cookie").orEmpty().contains("dvrSession=ok"))
                    MockResponse().setResponseCode(403)
                else {
                    val end = minOf(payload.lastIndex, start + 32 * 1024 * 1024 - 1)
                    MockResponse().setResponseCode(206)
                        .setHeader("Content-Range", "bytes $start-$end/${payload.size}")
                        .setHeader("Content-Length", (end - start + 1).toString())
                        .apply { if (first) { first = false; setHeader("Set-Cookie", "dvrSession=ok; Path=/") } }
                        .setBody(Buffer().write(payload, start, end - start + 1))
                }
            }
            val saved = DownloadStore(root).download(media(size = payload.size.toLong()), StopToken()) { _, _ -> }
            assertArrayEquals(payload, saved.file!!.readBytes())
        } finally {
            CookieHandler.setDefault(previous)
        }
    }

    @Test fun fallsBackToOemDirectStreamWhenDvrRejectsRange() {
        val payload = ByteArray(2 * 1024 * 1024 + 17) { (it * 19 % 251).toByte() }
        routes["/media/clip.mp4"] = { request ->
            if (request.getHeader("Range") != null) MockResponse().setResponseCode(403)
            else MockResponse().setHeader("Content-Type", "video/mp4").setBody(Buffer().write(payload))
        }
        val saved = DownloadStore(root).download(media(size = payload.size.toLong()), StopToken()) { _, _ -> }
        assertArrayEquals(payload, saved.file!!.readBytes())
        assertEquals(listOf("bytes=0-", null), rangeHeaders)
    }

    @Test fun setModeSendsJsonBodyAndVerifiesReadback() {
        var recording = "normal"
        var sent = ""
        routes["/status"] = { request ->
            val body = if (request.method == "POST") {
                sent = request.body.readUtf8()
                recording = "in-file-list"
                """{"result":"ok"}"""
            } else """{"usable":true,"recording":"$recording"}"""
            MockResponse().setBody(body)
        }
        DvrApi(base).setMode("enter-file-list")
        val json = org.json.JSONObject(sent)
        assertEquals("gallery", json.getString("app"))
        assertEquals("enter-file-list", json.getString("recording"))
    }
}
