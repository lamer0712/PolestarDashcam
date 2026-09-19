package com.polestar.dashcamexporter

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/** Small read-only HTTP server for transferring saved media to a phone on the same hotspot. */
class PhoneFileServer(
    private val files: () -> List<SavedMedia>,
    private val open: (SavedMedia) -> InputStream,
    private val dvrFiles: () -> List<DvrMedia> = { emptyList() },
    private val openDvr: (DvrMedia, Long) -> InputStream = { _, _ -> throw IllegalStateException("DVR relay is unavailable.") },
    private val savedThumbnail: (SavedMedia) -> ByteArray? = { null },
    private val dvrThumbnail: (DvrMedia) -> ByteArray? = { null }
) {
    companion object { const val PORT = 8787 }
    private val executor = Executors.newFixedThreadPool(4)
    @Volatile private var socket: ServerSocket? = null
    @Volatile private var snapshot: List<SavedMedia> = emptyList()
    @Volatile private var dvrSnapshot: List<DvrMedia> = emptyList()

    fun start(): String {
        stop()
        snapshot = files()
        val server = ServerSocket(PORT)
        socket = server
        Thread({
            while (!server.isClosed) {
                try {
                    val client = server.accept()
                    executor.execute { handle(client) }
                }
                catch (_: Exception) { if (!server.isClosed) continue }
            }
        }, "phone-file-server-accept").also { it.isDaemon = true; it.start() }
        val address = localIpv4() ?: throw IllegalStateException("No Wi-Fi address is available.")
        return "http://$address:${server.localPort}/"
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
        snapshot = emptyList()
        dvrSnapshot = emptyList()
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            try {
            client.soTimeout = 15_000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
            val request = reader.readLine() ?: return
            // Consume headers before writing the response.
            var range: String? = null
            while (true) {
                val header = reader.readLine() ?: break
                if (header.isEmpty()) break
                if (header.startsWith("Range:", ignoreCase = true)) range = header.substringAfter(':').trim()
            }
            val parts = request.split(' ', limit = 3)
            if (parts.size < 2 || parts[0] != "GET") return response(client, 405, "Method Not Allowed", "Only GET is supported.")
            val target = parts[1]
            val query = target.substringAfter('?', "").split('&').mapNotNull {
                val pair = it.split('=', limit = 2)
                if (pair.size == 2) URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8") else null
            }.toMap()
            if (target.substringBefore('?') == "/") return listing(client)
            if (target.substringBefore('?') == "/download") {
                val index = query["i"]?.toIntOrNull()
                if (index == null || index !in snapshot.indices) return response(client, 404, "Not Found", "File not found.")
                return download(client, snapshot[index], range)
            }
            if (target.substringBefore('?') == "/dvr-download") {
                val index = query["i"]?.toIntOrNull()
                if (index == null || index !in dvrSnapshot.indices) return response(client, 404, "Not Found", "DVR file not found.")
                return downloadDvr(client, dvrSnapshot[index], range)
            }
            if (target.substringBefore('?') == "/saved-thumb") {
                val index = query["i"]?.toIntOrNull()
                if (index == null || index !in snapshot.indices) return response(client, 404, "Not Found", "File not found.")
                return image(client, savedThumbnail(snapshot[index]))
            }
            if (target.substringBefore('?') == "/dvr-thumb") {
                val index = query["i"]?.toIntOrNull()
                if (index == null || index !in dvrSnapshot.indices) return response(client, 404, "Not Found", "DVR file not found.")
                return image(client, dvrThumbnail(dvrSnapshot[index]))
            }
            response(client, 404, "Not Found", "Not found.")
            } catch (_: SocketTimeoutException) {
                // A browser or port scanner may connect without sending a full request.
            } catch (error: Exception) {
                runCatching { response(client, 500, "Internal Server Error", error.message ?: "Request failed.") }
            }
        }
    }

    private fun listing(socket: Socket) {
        snapshot = files()
        val dvrError = runCatching { dvrSnapshot = dvrFiles() }.exceptionOrNull()
        fun savedCard(item: SavedMedia, index: Int): String {
            val media = escape(item.mime)
            val url = "/download?i=$index"
            val thumb = "/saved-thumb?i=$index"
            return card(item.name, item.size, media, thumb, url)
        }
        fun dvrCard(item: DvrMedia, index: Int): String {
            val media = escape(mimeFor(item.name, item.kind))
            val url = "/dvr-download?i=$index"
            val thumb = "/dvr-thumb?i=$index"
            return card(item.name, item.size, media, thumb, url)
        }
        val savedCards = snapshot.mapIndexed { index, item -> savedCard(item, index) }.joinToString("\n")
        val dvrCards = dvrSnapshot.mapIndexed { index, item -> dvrCard(item, index) }.joinToString("\n")
        val html = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Gallery+</title><style>
            :root{color-scheme:dark;--bg:#121212;--panel:#1b1b1b;--line:#343434;--muted:#aeb4b9;--orange:#ff7a00}
            *{box-sizing:border-box}body{margin:0;background:var(--bg);color:#fff;font-family:Arial,sans-serif}
            header{height:76px;border-bottom:1px solid var(--line);display:flex;align-items:center;padding:0 28px;gap:18px;position:sticky;top:0;background:var(--bg);z-index:2}
            header .logo{font-size:29px;color:var(--orange);font-weight:700}header h1{font-size:26px;margin:0;font-weight:500}
            .layout{display:flex;min-height:calc(100vh - 77px)}aside{width:190px;background:#181818;padding-top:28px;flex:none}
            aside a{display:block;color:#b8b8b8;text-decoration:none;font-size:19px;padding:20px 24px}aside a.active{color:var(--orange);background:#242424}
            main{padding:28px;flex:1;max-width:1500px}.section{margin-bottom:42px}.section h2{font-size:23px;font-weight:500;margin:0 0 18px}
            .grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(250px,1fr));gap:18px}.card{background:var(--panel);border:1px solid var(--line);border-radius:8px;overflow:hidden}
            .thumb{width:100%;aspect-ratio:16/9;background:#252525;object-fit:cover;display:block;cursor:pointer}.body{padding:12px}.name{font-size:16px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.meta{color:var(--muted);font-size:13px;margin-top:7px;display:flex;justify-content:space-between;gap:8px}.download{display:inline-block;margin-top:11px;color:var(--orange);text-decoration:none;font-size:14px}
            dialog{background:#181818;color:#fff;border:1px solid var(--line);border-radius:8px;max-width:90vw}dialog::backdrop{background:#000b}dialog video{max-width:82vw;max-height:75vh}button{background:var(--orange);border:0;padding:8px 14px;border-radius:4px}
            .empty{color:var(--muted);padding:20px 0}@media(max-width:700px){aside{width:120px}aside a{font-size:15px;padding:18px 12px}main{padding:16px}.grid{grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}}
            </style></head><body><header><span class="logo">Gallery+</span><h1>Saved & DVR</h1></header>
            <div class="layout"><aside><a class="active" href="#saved">▣<br>Saved</a><a href="#dvr">▤<br>DVR</a></aside><main>
            <section class="section" id="saved"><h2>Saved <small>(${snapshot.size})</small></h2><div class="grid">${savedCards.ifBlank { "<div class=empty>No saved files</div>" }}</div></section>
            <section class="section" id="dvr"><h2>DVR <small>(${dvrSnapshot.size})</small></h2>${if (dvrError != null) "<p class=empty>DVR is unavailable: ${escape(dvrError.message ?: "No response")}</p>" else "<div class=grid>${dvrCards.ifBlank { "<div class=empty>No DVR files found</div>" }}</div>"}</section>
            </main></div><dialog id="player"><video id="video" controls playsinline></video><br><button onclick="player.close();video.pause()">Close</button></dialog>
            <script>const player=document.getElementById('player'),video=document.getElementById('video');function play(url){video.src=url;player.showModal();video.play().catch(()=>{})}</script></body></html>
        """.trimIndent()
        val bytes = html.toByteArray(StandardCharsets.UTF_8)
        val out = socket.getOutputStream()
        writeHeaders(out, 200, "OK", "text/html; charset=utf-8", bytes.size.toLong())
        out.write(bytes); out.flush()
    }

    private fun card(name: String, size: Long, mime: String, thumb: String, url: String): String {
        val safeName = escape(name)
        val sizeText = if (size >= 1024 * 1024) "%.1f MB".format(size / 1024f / 1024f) else "%.0f KB".format(size / 1024f)
        val visual = if (mime.startsWith("video/")) "<img class=thumb src=\"$thumb\" loading=\"lazy\" onerror=\"this.style.display='none'\" onclick=\"play('$url')\">"
        else "<img class=thumb src=\"$thumb\" loading=\"lazy\" onclick=\"window.open('$url','_blank')\">"
        return "<article class=card>$visual<div class=body><div class=name title=\"$safeName\">$safeName</div><div class=meta><span>$sizeText</span><span>$mime</span></div><a class=download download href=\"$url\">Download</a></div></article>"
    }

    private fun image(socket: Socket, bytes: ByteArray?) {
        if (bytes == null || bytes.isEmpty()) return response(socket, 404, "Not Found", "Thumbnail unavailable.")
        val out = socket.getOutputStream()
        writeHeaders(out, 200, "OK", "image/jpeg", bytes.size.toLong())
        out.write(bytes); out.flush()
    }

    private fun download(socket: Socket, item: SavedMedia, range: String?) {
        val total = item.size
        val start = range?.substringAfter("bytes=", "")?.substringBefore('-')?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        if (start >= total && total > 0) return response(socket, 416, "Range Not Satisfiable", "Invalid range.")
        val length = if (total > 0) total - start else -1L
        val out = socket.getOutputStream()
        writeHeaders(out, if (start > 0) 206 else 200, if (start > 0) "Partial Content" else "OK",
            item.mime, length, item.name, total, start)
        open(item).use { input ->
            skipFully(input, start)
            val buffer = ByteArray(128 * 1024)
            var remaining = length
            while (remaining != 0L) {
                val read = input.read(buffer, 0, if (remaining < 0) buffer.size else minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) break
                out.write(buffer, 0, read)
                if (remaining > 0) remaining -= read
            }
        }
        out.flush()
    }

    private fun downloadDvr(socket: Socket, item: DvrMedia, range: String?) {
        val total = item.size
        val start = range?.substringAfter("bytes=", "")?.substringBefore('-')?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        if (start >= total && total > 0) return response(socket, 416, "Range Not Satisfiable", "Invalid range.")
        val length = if (total > 0) total - start else -1L
        val out = socket.getOutputStream()
        writeHeaders(out, if (start > 0) 206 else 200, if (start > 0) "Partial Content" else "OK",
            mimeFor(item.name, item.kind), length, item.name, total, start)
        openDvr(item, start).use { input ->
            skipFully(input, start)
            val buffer = ByteArray(128 * 1024)
            var remaining = length
            while (remaining != 0L) {
                val read = input.read(buffer, 0, if (remaining < 0) buffer.size else minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) break
                out.write(buffer, 0, read)
                if (remaining > 0) remaining -= read
            }
        }
        out.flush()
    }

    private fun response(socket: Socket, code: Int, status: String, message: String) {
        val bytes = "<h3>$status</h3><p>${escape(message)}</p>".toByteArray(StandardCharsets.UTF_8)
        val out = socket.getOutputStream()
        writeHeaders(out, code, status, "text/html; charset=utf-8", bytes.size.toLong())
        out.write(bytes); out.flush()
    }

    private fun writeHeaders(out: OutputStream, code: Int, status: String, type: String, length: Long,
                             name: String? = null, total: Long = -1L, start: Long = 0L) {
        val builder = StringBuilder("HTTP/1.1 $code $status\r\n")
            .append("Content-Type: $type\r\n")
        if (length >= 0) builder.append("Content-Length: $length\r\n")
        builder.append("Cache-Control: no-store\r\n")
        if (name != null) builder.append("Content-Disposition: attachment; filename*=UTF-8''${URLEncoder.encode(name, "UTF-8").replace("+", "%20")}\r\n")
        if (total > 0) builder.append("Accept-Ranges: bytes\r\nContent-Range: bytes $start-${start + length - 1}/$total\r\n")
        builder.append("Connection: close\r\n\r\n")
        out.write(builder.toString().toByteArray(StandardCharsets.UTF_8))
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) remaining -= skipped else if (input.read() < 0) break else remaining--
        }
    }

    private fun localIpv4(): String? = NetworkInterface.getNetworkInterfaces().toList()
        .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
        ?.hostAddress

    private fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
