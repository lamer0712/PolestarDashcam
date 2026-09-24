package com.polestar.dashcamexporter

import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.URLEncoder
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

enum class MediaKind(val api: String, val label: String) {
    NORMAL("normal", "Normal"), EMERGENCY("emergency", "Emergency"), PHOTO("photo", "Photo");
    companion object { fun from(value: String) = entries.firstOrNull { it.api == value } }
}

val MediaKind.galleryTitle: String get() = when (this) {
    MediaKind.NORMAL -> "Loop videos"
    MediaKind.EMERGENCY -> "Emergency videos"
    MediaKind.PHOTO -> "Photos"
}

data class MediaDirectory(val kind: MediaKind, val path: String, val count: Int)
data class DvrStatus(val usable: Boolean, val recording: String)
data class DvrMedia(
    val kind: MediaKind, val id: String, val name: String, val size: Long,
    val dateTime: Long, val duration: Int, val url: String
) {
    val key: String get() = MessageDigest.getInstance("SHA-256")
        .digest("$url|$id|$dateTime|$size".toByteArray()).joinToString("") { "%02x".format(it) }
    val displayRange: String get() {
        if (kind == MediaKind.PHOTO || dateTime <= 0L) return name
        val start = (if (dateTime < 100_000_000_000L) dateTime * 1000 else dateTime) - 3_600_000L
        val end = start + duration.coerceAtLeast(0) * 1000L
        return java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).let {
            "${it.format(java.util.Date(start))}-${it.format(java.util.Date(end))}"
        }
    }
}

class UserCancelledException : IOException("Operation cancelled.")

class StopToken {
    private val stopped = AtomicBoolean(false)
    fun cancel() { stopped.set(true) }
    fun isCancelled(): Boolean = stopped.get()
    fun check() { if (stopped.get()) throw UserCancelledException() }
}

object DvrJson {
    fun objectFrom(text: String): JSONObject {
        val json = try { JSONObject(text) } catch (e: Exception) {
            throw IOException("Unable to parse DVR JSON response.", e)
        }
        val result = json.opt("result")
        if ((json.has("error") && json.optInt("error", -1) != 0) ||
            (result is String && result != "ok")) {
            val code = json.optInt("error", -1)
            val detail = json.optString("message", json.toString()).take(240)
            throw IOException("DVR error${if (code >= 0) " (code $code)" else ""}: $detail")
        }
        return json
    }

    fun statusOrNull(text: String): DvrStatus? {
        val json = objectFrom(text)
        val candidates = listOf(json, json.optJSONObject("state"), json.optJSONObject("status"),
            json.optJSONObject("data"), json.optJSONObject("result")).filterNotNull()
        val state = candidates.firstOrNull {
            usableValue(it.opt("usable")) != null && it.optString("recording").isNotBlank()
        } ?: return null
        return DvrStatus(usableValue(state.opt("usable"))!!, state.getString("recording"))
    }

    private fun usableValue(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is Number -> when (value.toInt()) { 0 -> false; 1 -> true; else -> null }
        is String -> when (value.trim().lowercase()) {
            "yes", "true", "1", "on", "usable" -> true
            "no", "false", "0", "off", "unusable" -> false
            else -> null
        }
        else -> null
    }

    fun status(text: String): DvrStatus = statusOrNull(text)
        ?: throw IOException("status response is missing usable/recording.")

    fun directories(text: String): List<MediaDirectory> {
        val array = objectFrom(text).optJSONArray("mediaList")
            ?: throw IOException("mediaDirList response is missing mediaList.")
        val dirs = (0 until array.length()).mapNotNull { index ->
            val obj = array.getJSONObject(index)
            val kind = MediaKind.from(obj.optString("mediaType")) ?: return@mapNotNull null
            val path = obj.optString("mediaPath")
            if (path.isBlank()) throw IOException("${kind.api}: mediaPath is empty.")
            MediaDirectory(kind, path, obj.optInt("fileCount", -1))
        }
        if (dirs.map { it.kind }.distinct().size != dirs.size)
            throw IOException("Multiple mediaPath entries exist for the same category. Check the response.")
        return dirs
    }

    fun files(text: String, directory: MediaDirectory, base: String): List<DvrMedia> {
        val array = responseArray(text, "fileList", "files", "filelist", "items")
            ?: throw IOException("filelist response is missing fileList.")
        return (0 until array.length()).map { index ->
            val obj = array.getJSONObject(index)
            val name = obj.optString("name")
            val type = obj.optString("mediaType", directory.kind.api)
            if (type != directory.kind.api) throw IOException("File category differs from request: $type")
            DvrMedia(directory.kind, obj.optString("id", name), name,
                obj.optLong("size", 0).coerceAtLeast(0), obj.optLong("dateTime", 0),
                obj.optInt("duration", 0), mediaUrl(base, directory.path, name))
        }
    }

    /** Accept the OEM object shape and wrappers used by newer DVR firmware. */
    private fun responseArray(text: String, vararg names: String): JSONArray? {
        val trimmed = text.trim()
        if (trimmed.startsWith("[")) return runCatching { JSONArray(trimmed) }.getOrNull()
        val root = objectFrom(text)
        fun find(obj: JSONObject): JSONArray? {
            for (key in obj.keys()) {
                if (names.any { it.equals(key, ignoreCase = true) }) {
                    obj.optJSONArray(key)?.let { return it }
                    obj.optJSONObject(key)?.let { nested ->
                        for (nestedKey in nested.keys()) {
                            if (names.any { it.equals(nestedKey, ignoreCase = true) }) {
                                nested.optJSONArray(nestedKey)?.let { return it }
                            }
                        }
                    }
                }
            }
            return null
        }
        find(root)?.let { return it }
        for (wrapper in listOf("data", "response", "result")) {
            root.optJSONArray(wrapper)?.let { return it }
            val nested = root.optJSONObject(wrapper) ?: continue
            find(nested)?.let { return it }
        }
        return null
    }

    fun baseUrl(value: String): String {
        val uri = try { URI(value.trim()) } catch (e: Exception) { throw IOException("Invalid DVR address format.", e) }
        if (uri.scheme != "http" || uri.host !in setOf("198.18.37.20", "127.0.0.1", "localhost", "10.0.2.2") ||
            uri.userInfo != null || uri.query != null || uri.fragment != null ||
            uri.path !in listOf("", "/") || uri.port !in -1..65535 || uri.port == 0)
            throw IOException("Enter the DVR address or test address (http://127.0.0.1:8765).")
        return uri.toString().trimEnd('/')
    }

    fun mediaUrl(base: String, mediaPath: String, name: String): String {
        if (name.isBlank() || name in listOf(".", "..") || name.any { it == '/' || it == '\\' || it.code < 32 })
            throw IOException("Invalid DVR filename.")
        val segments = mediaPath.trim('/').split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty() || segments.any { it == "." || it == ".." || ':' in it || '\\' in it || it.any { ch -> ch.code < 32 } })
            throw IOException("Invalid mediaPath.")
        val path = (segments + name).joinToString("/") {
            // API fields are raw filenames; escape once, including literal percent signs.
            URI(null, null, "/$it", null).toASCIIString().removePrefix("/")
        }
        return "${baseUrl(base)}/$path"
    }
}

class DvrApi(val base: String) {
    init { DvrJson.baseUrl(base) }
    fun status() = DvrJson.status(json("/status?app=gallery"))
    fun statusOrNull() = DvrJson.statusOrNull(json("/status?app=gallery"))
    fun directories() = DvrJson.directories(json("/mediaDirList?app=gallery"))
    fun files(directory: MediaDirectory, start: Int, count: Int = PAGE_SIZE): List<DvrMedia> =
        DvrJson.files(json("/filelist?app=gallery&type=${directory.kind.api}&startIndex=$start&count=$count&sort=newest-first&infoLevel=2"), directory, base)

    fun thumbnail(media: DvrMedia): ByteArray {
        val id = URLEncoder.encode(media.id, Charsets.UTF_8.name()).replace("+", "%20")
        val connection = connection("$base/thumbnail?app=gallery&mediaType=${media.kind.api}&id=$id&time=${media.dateTime}")
        try {
            requireOk(connection)
            val contentType = connection.contentType.orEmpty().lowercase()
            if (!contentType.startsWith("image/"))
                throw IOException("Received a non-thumbnail response: ${contentType.ifBlank { "unknown format" }}")
            val declared = connection.getHeaderFieldLong("Content-Length", -1)
            if (declared > MAX_THUMBNAIL_BYTES) throw IOException("DVR thumbnail is too large.")
            val bytes = connection.inputStream.use { readLimited(it, MAX_THUMBNAIL_BYTES + 1) }
            if (bytes.isEmpty() || bytes.size > MAX_THUMBNAIL_BYTES) throw IOException("Invalid DVR thumbnail response.")
            return bytes
        } finally { connection.disconnect() }
    }

    fun setMode(recording: String) {
        require(recording == "normal" || recording == "enter-file-list")
        val body = JSONObject().put("app", "gallery").put("recording", recording).toString()
        val result = DvrJson.objectFrom(json("/status", body))
        if (result.optString("result") != "ok") throw IOException("DVR mode change was not confirmed.")
        val expected = if (recording == "normal") "normal" else "in-file-list"
        repeat(5) {
            if (status().recording == expected) return
            Thread.sleep(250)
        }
        throw IOException("DVR state did not switch to $expected.")
    }

    private fun json(path: String, body: String? = null): String {
        val connection = connection("$base$path")
        try {
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            requireOk(connection)
            val bytes = connection.inputStream.use { readLimited(it, 2 * 1024 * 1024 + 1) }
            if (bytes.size > 2 * 1024 * 1024) throw IOException("DVR list response is too large.")
            return bytes.toString(Charsets.UTF_8)
        } finally { connection.disconnect() }
    }

    companion object {
        const val DEFAULT_BASE = "http://198.18.37.20"
        const val PAGE_SIZE = 50
        private const val MAX_THUMBNAIL_BYTES = 5 * 1024 * 1024
        init {
            // The DVR can issue a short-lived session cookie before serving media ranges.
            // Keep it for subsequent status/list/range requests just like the OEM browser does.
            if (CookieHandler.getDefault() == null)
                CookieHandler.setDefault(CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER))
        }
        fun connection(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            // The OEM Gallery keeps the DVR stream open for up to 60 seconds.
            readTimeout = 60_000
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Accept-Encoding", "identity")
        }
        fun requireOk(connection: HttpURLConnection) {
            if (connection.responseCode != 200) {
                val detail = connection.errorStream?.use { readLimited(it, 512).toString(Charsets.UTF_8) }.orEmpty()
                throw IOException("HTTP ${connection.responseCode} ${connection.url.path}: ${detail.take(240)}")
            }
        }
        private fun readLimited(input: InputStream, limit: Int): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() < limit) {
                val read = input.read(buffer, 0, minOf(buffer.size, limit - output.size()))
                if (read < 0) break
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        }
    }
}
