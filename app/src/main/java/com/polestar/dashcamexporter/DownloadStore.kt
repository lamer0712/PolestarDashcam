package com.polestar.dashcamexporter

import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection

data class SavedMedia(
    val file: File?,
    val kind: MediaKind,
    val uri: Uri? = null,
    val displayName: String? = null,
    val byteSize: Long = 0L,
    val modifiedAt: Long = 0L,
    val mimeType: String? = null
) {
    val key: String get() = uri?.toString() ?: file?.absolutePath.orEmpty()
    val name: String get() = displayName ?: file?.name.orEmpty()
    val size: Long get() = if (byteSize > 0L) byteSize else file?.length() ?: 0L
    val mime: String get() = mimeType?.takeIf { it.isNotBlank() } ?: mimeFor(name, kind)
}

fun mimeFor(name: String, kind: MediaKind): String = when (name.substringAfterLast('.', "").lowercase()) {
    "mp4", "m4v" -> "video/mp4"
    "mov" -> "video/quicktime"
    "ts" -> "video/mp2t"
    "avi" -> "video/x-msvideo"
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "heic" -> "image/heic"
    else -> if (kind == MediaKind.PHOTO) "image/*" else "video/*"
}

object StreamCopy {
    fun copy(input: InputStream, output: OutputStream, expected: Long, stop: StopToken,
             progress: (Long, Long) -> Unit): Long {
        val buffer = ByteArray(128 * 1024)
        var total = 0L
        var lastReport = 0L
        while (true) {
            stop.check()
            val read = input.read(buffer)
            if (read < 0) break
            stop.check()
            if (expected > 0 && total + read > expected)
                throw IOException("파일 크기가 예상보다 큽니다. 목록을 새로고침하세요.")
            output.write(buffer, 0, read)
            total += read
            val now = System.nanoTime()
            if (now - lastReport > 150_000_000L) { progress(total, expected); lastReport = now }
        }
        stop.check()
        if (total == 0L || (expected > 0 && total != expected))
            throw IOException("파일이 불완전합니다: $total / $expected bytes")
        progress(total, expected)
        return total
    }

    /** Copies one bounded HTTP range without waiting for the server to close a long response. */
    fun copyChunk(input: InputStream, output: OutputStream, expected: Long, stop: StopToken,
                  progress: (Long, Long) -> Unit): Long {
        val buffer = ByteArray(128 * 1024)
        var total = 0L
        while (total < expected) {
            stop.check()
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), expected - total).toInt())
            if (read < 0) throw IOException("파일 스트림이 중간에 끝났습니다: $total / $expected bytes")
            if (read == 0) continue
            output.write(buffer, 0, read)
            total += read
            progress(total, expected)
        }
        return total
    }
}

class ThumbnailStore(private val root: File) {
    init { root.mkdirs() }

    fun existing(media: DvrMedia): File? =
        File(root, "${media.key}.thumb").takeIf { it.isFile && it.length() > 0 }

    fun fetch(api: DvrApi, media: DvrMedia, stop: StopToken): File {
        existing(media)?.let { return it }
        stop.check()
        val target = File(root, "${media.key}.thumb")
        val partial = File(root, "${media.key}.thumb.part")
        try {
            val bytes = api.thumbnail(media)
            stop.check()
            FileOutputStream(partial).use { output -> output.write(bytes); output.fd.sync() }
            if (!partial.renameTo(target)) throw IOException("썸네일 캐시를 저장하지 못했습니다.")
            return target
        } finally { partial.delete() }
    }
}

class DownloadStore(private val root: File) {
    init { root.mkdirs() }
    fun cleanPartialFiles() { root.walkTopDown().filter { it.isFile && it.name.endsWith(".part") }.forEach { it.delete() } }
    fun saved(): List<SavedMedia> = MediaKind.entries.flatMap { kind ->
        File(root, kind.api).listFiles().orEmpty().filter { it.isDirectory }.flatMap { folder ->
            folder.listFiles().orEmpty().filter { it.isFile && !it.name.endsWith(".part") && it.length() > 0 }
                .map { SavedMedia(it, kind) }
        }
    }.sortedByDescending { it.modifiedAt.takeIf { value -> value > 0L } ?: it.file?.lastModified() ?: 0L }

    fun target(media: DvrMedia): File {
        val directory = File(root, "${media.kind.api}/${media.key}")
        directory.mkdirs()
        // Preserve useful extension while staying below the filesystem name length limit.
        val name = media.name.replace(Regex("[^\\p{L}\\p{N}._ ()-]"), "_").let {
            if (it.toByteArray().size <= 220) it else "clip.${it.substringAfterLast('.', "bin").take(12)}"
        }
        return File(directory, name)
    }

    fun download(media: DvrMedia, stop: StopToken, progress: (Long, Long) -> Unit): SavedMedia {
        stop.check()
        val file = target(media)
        if (file.isFile && file.length() > 0 && (media.size == 0L || file.length() == media.size))
            return SavedMedia(file, media.kind)
        val partial = File(file.parentFile, "${file.name}.part")
        partial.delete()
        var expectedTotal = media.size
        var failuresWithoutProgress = 0
        var connectionCount = 0
        var openEndedRange = false
        var completed = false
        try {
            val reserve = 32L * 1024 * 1024
            if (root.usableSpace < expectedTotal.coerceAtLeast(0) + reserve) throw IOException("앱 저장 공간이 부족합니다.")
            while (true) {
                stop.check()
                val offset = partial.length()
                if (expectedTotal > 0 && offset == expectedTotal) break
                if (expectedTotal > 0 && offset > expectedTotal)
                    throw IOException("받은 파일이 목록 크기보다 큽니다. 목록을 새로고침하세요.")
                if (++connectionCount > MAX_CONNECTIONS)
                    throw IOException("긴 파일 다운로드 연결 횟수가 너무 많습니다.")

                val requestedEnd = if (expectedTotal > 0)
                    minOf(expectedTotal - 1, offset + RANGE_CHUNK_BYTES - 1) else null
                val connection = DvrApi.connection(media.url)
                if (offset > 0 || requestedEnd != null) {
                    val end = if (openEndedRange) "" else requestedEnd?.toString().orEmpty()
                    connection.setRequestProperty("Range", "bytes=$offset-$end")
                }
                val before = offset
                try {
                    val code = connection.responseCode
                    if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL)
                        DvrApi.requireOk(connection)
                    if (offset > 0 && code != HttpURLConnection.HTTP_PARTIAL)
                        throw IOException("DVR이 긴 파일 이어받기(Range)를 지원하지 않습니다.")

                    val contentType = connection.contentType.orEmpty().lowercase()
                    if (contentType.startsWith("text/") || "json" in contentType || "xml" in contentType)
                        throw IOException("영상 대신 오류 문서를 받았습니다: $contentType")

                    val length = connection.getHeaderFieldLong("Content-Length", -1)
                    val expectedResponse = if (code == HttpURLConnection.HTTP_PARTIAL) {
                        val range = parseContentRange(connection.getHeaderField("Content-Range"))
                            ?: throw IOException("DVR의 Content-Range 응답이 없습니다.")
                        if (range.first != offset) throw IOException("DVR 이어받기 위치가 다릅니다: ${range.first} / $offset")
                        if (range.total > 0) {
                            if (media.size > 0 && range.total != media.size)
                                throw IOException("목록과 다운로드 파일 크기가 다릅니다. 새로고침 후 다시 시도하세요.")
                            expectedTotal = range.total
                        }
                        val available = range.last - range.first + 1
                        if (openEndedRange) minOf(available, RANGE_CHUNK_BYTES) else available
                    } else {
                        if (media.size > 0 && length > 0 && length != media.size)
                            throw IOException("목록과 다운로드 파일 크기가 다릅니다. 새로고침 후 다시 시도하세요.")
                        if (expectedTotal == 0L && length > 0) expectedTotal = length
                        if (length > 0) length else expectedTotal
                    }
                    if (length > 0 && expectedResponse > 0 && !openEndedRange && length != expectedResponse)
                        throw IOException("DVR 구간 응답 크기가 요청과 다릅니다.")

                    connection.inputStream.use { input ->
                        FileOutputStream(partial, offset > 0).use { output ->
                            if (openEndedRange && code == HttpURLConnection.HTTP_PARTIAL)
                                StreamCopy.copyChunk(input, output, expectedResponse, stop) { done, _ ->
                                    progress(offset + done, expectedTotal)
                                }
                            else StreamCopy.copy(input, output, expectedResponse, stop) { done, _ ->
                                progress(offset + done, expectedTotal)
                            }
                            output.fd.sync()
                        }
                    }
                    failuresWithoutProgress = 0
                    if (code == HttpURLConnection.HTTP_OK ||
                        (expectedTotal > 0 && partial.length() == expectedTotal)) break
                } catch (e: UserCancelledException) {
                    throw e
                } catch (e: IOException) {
                    if (!openEndedRange && e.message?.contains("HTTP 403") == true) {
                        // Some DVR firmware rejects bounded ranges but accepts bytes=start-.
                        openEndedRange = true
                        failuresWithoutProgress = 0
                        continue
                    }
                    if (partial.length() > before) {
                        failuresWithoutProgress = 0
                        continue
                    }
                    failuresWithoutProgress++
                    if (failuresWithoutProgress >= MAX_STALLED_RETRIES) throw e
                    Thread.sleep(250L * failuresWithoutProgress)
                } finally {
                    connection.disconnect()
                }
            }
            stop.check()
            if (partial.length() == 0L || (expectedTotal > 0 && partial.length() != expectedTotal))
                throw IOException("파일이 불완전합니다: ${partial.length()} / $expectedTotal bytes")
            progress(partial.length(), expectedTotal)
            if (!partial.renameTo(file)) throw IOException("완료 파일을 저장하지 못했습니다.")
            completed = true
            return SavedMedia(file, media.kind)
        } finally {
            if (!completed) partial.delete()
        }
    }

    private data class ContentRange(val first: Long, val last: Long, val total: Long)

    private fun parseContentRange(value: String?): ContentRange? {
        val match = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE)
            .matchEntire(value?.trim().orEmpty()) ?: return null
        val first = match.groupValues[1].toLongOrNull() ?: return null
        val last = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].takeUnless { it == "*" }?.toLongOrNull() ?: 0L
        if (last < first || (total > 0 && last >= total)) return null
        return ContentRange(first, last, total)
    }

    companion object {
        private const val RANGE_CHUNK_BYTES = 32L * 1024 * 1024
        private const val MAX_STALLED_RETRIES = 4
        private const val MAX_CONNECTIONS = 512
    }
}
