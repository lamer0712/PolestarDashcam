package com.polestar.dashcamexporter

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/** Small read-only HTTP server for transferring saved media to a phone on the same hotspot. */
class PhoneFileServer(
    private val files: () -> List<SavedMedia>,
    private val open: (SavedMedia) -> InputStream,
    private val dvrFiles: () -> List<DvrMedia> = { emptyList() },
    private val openDvr: (DvrMedia, Long) -> InputStream = { _, _ -> throw IllegalStateException("DVR relay is unavailable.") }
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
            response(client, 404, "Not Found", "Not found.")
        }
    }

    private fun listing(socket: Socket) {
        snapshot = files()
        dvrSnapshot = dvrFiles()
        val rows = snapshot.mapIndexed { index, item ->
            val name = escape(item.name)
            "<li><a href=\"/download?i=$index\">$name</a> <small>${item.size / 1024} KB · ${escape(item.mime)}</small></li>"
        }.joinToString("\n")
        val dvrRows = dvrSnapshot.mapIndexed { index, item ->
            "<li><a href=\"/dvr-download?i=$index\">${escape(item.name)}</a> <small>${item.size / 1024} KB · ${escape(mimeFor(item.name, item.kind))}</small></li>"
        }.joinToString("\n")
        val html = """
            <!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Gallery+ Saved</title><h2>Gallery+ Saved</h2>
            <p>Select a saved file to download.</p><ul>$rows</ul>
            <h2>DVR files currently loaded in Gallery+</h2><ul>$dvrRows</ul>
        """.trimIndent()
        val bytes = html.toByteArray(StandardCharsets.UTF_8)
        val out = socket.getOutputStream()
        writeHeaders(out, 200, "OK", "text/html; charset=utf-8", bytes.size.toLong())
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
