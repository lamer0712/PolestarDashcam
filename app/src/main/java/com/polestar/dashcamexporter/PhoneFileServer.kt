package com.polestar.dashcamexporter

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.io.BufferedReader
import java.io.FilterInputStream
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
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
    private val context: Context,
    private val files: () -> List<SavedMedia>,
    private val open: (SavedMedia) -> InputStream,
    private val dvrFiles: () -> List<DvrMedia> = { emptyList() },
    private val openDvr: (DvrMedia, Long) -> InputStream = { _, _ -> throw IllegalStateException("DVR relay is unavailable.") },
    private val savedThumbnail: (SavedMedia) -> ByteArray? = { null },
    private val dvrThumbnail: (DvrMedia) -> ByteArray? = { null },
    private val onTailcatAddress: (String) -> Unit = {}
) {
    companion object { val PORTS = listOf(8080, 8787, 8000, 8888, 5000) }
    private val executor = Executors.newFixedThreadPool(4)
    @Volatile private var socket: ServerSocket? = null
    @Volatile private var snapshot: List<SavedMedia> = emptyList()
    @Volatile private var dvrSnapshot: List<DvrMedia> = emptyList()

    fun start(): String {
        stop()
        snapshot = files()
        val server = openServerSocket()
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

    private fun openServerSocket(): ServerSocket {
        var lastError: Exception? = null
        for (port in PORTS) {
            try { return ServerSocket(port, 50) }
            catch (error: Exception) { lastError = error }
        }
        throw IllegalStateException("No share port is available: ${lastError?.message ?: "unknown"}")
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
        snapshot = emptyList()
        dvrSnapshot = emptyList()
    }

    fun hasNetworkAddress(): Boolean = localIpv4() != null

    fun diagnostics(): List<String> {
        val preferred = wifiIpv4Candidates().map { it.copy(source = "preferred") }
        val all = allInterfaceIpv4Candidates().map { it.copy(source = "interface") }
        val candidates = (preferred + all).distinctBy { "${it.interfaceName}/${it.address}/${it.source}" }
        if (candidates.isEmpty()) return listOf("No IPv4 candidates from Wi-Fi APIs, WifiManager, default route, or NetworkInterface.")
        return candidates.map { candidate ->
            val reason = rejectReason(candidate)
            "${candidate.source} ${candidate.interfaceName}: ${candidate.address} - $reason"
        }
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            try {
            client.soTimeout = 15_000
            client.tcpNoDelay = true
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
            val request = reader.readLine() ?: return
            var range: String? = null
            var contentLength = 0
            while (true) {
                val header = reader.readLine() ?: break
                if (header.isEmpty()) break
                if (header.startsWith("Range:", ignoreCase = true)) range = header.substringAfter(':').trim()
                if (header.startsWith("Content-Length:", ignoreCase = true)) contentLength = header.substringAfter(':').trim().toIntOrNull() ?: 0
            }
            val parts = request.split(' ', limit = 3)
            if (parts.size < 2) return response(client, 400, "Bad Request", "Invalid request.")
            val method = parts[0]
            val target = parts[1]
            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "").split('&').mapNotNull {
                val pair = it.split('=', limit = 2)
                if (pair.size == 2) URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8") else null
            }.toMap()
            if (method == "POST" && path == "/tailcat-register") {
                val body = CharArray(contentLength.coerceIn(0, 4096))
                var read = 0
                while (read < body.size) {
                    val count = reader.read(body, read, body.size - read)
                    if (count < 0) break
                    read += count
                }
                val addr = String(body, 0, read).trim()
                if (!addr.startsWith("tc")) return response(client, 400, "Bad Request", "Invalid Tailcat address.")
                onTailcatAddress(addr)
                return response(client, 200, "OK", "Tailcat address registered.")
            }
            if (method != "GET") return response(client, 405, "Method Not Allowed", "Only GET and Tailcat registration POST are supported.")
            if (path == "/") return listing(client)
            if (path == "/tailcat" || path.startsWith("/tailcat/")) return tailcatAsset(client, path)
            if (path == "/dvr-list") return dvrListing(client)
            if (path == "/download") {
                val index = query["i"]?.toIntOrNull()
                if (index == null || index !in snapshot.indices) return response(client, 404, "Not Found", "File not found.")
                return download(client, snapshot[index], range, attachment = true)
            }
            if (path == "/stream") {
                val index = query["i"]?.toIntOrNull()
                if (index == null || index !in snapshot.indices) return response(client, 404, "Not Found", "File not found.")
                val item = snapshot[index]
                if (item.mime.startsWith("video/")) return response(client, 404, "Not Found", "Saved video playback is hidden on the phone web page.")
                return download(client, item, range, attachment = false)
            }
            if (path == "/saved-thumb") {
                val index = query["i"]?.toIntOrNull()
                if (index == null || index !in snapshot.indices) return response(client, 404, "Not Found", "File not found.")
                return image(client, savedThumbnail(snapshot[index]))
            }
            if (path == "/dvr-download") {
                val index = query["i"]?.toIntOrNull()
                if (index == null || index !in dvrSnapshot.indices) return response(client, 404, "Not Found", "DVR file not found.")
                return downloadDvr(client, dvrSnapshot[index], range, attachment = true)
            }
            if (path == "/dvr-stream") return response(client, 404, "Not Found", "DVR playback is hidden on the phone web page.")
            if (path == "/dvr-thumb") {
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

    private fun tailcatAsset(socket: Socket, path: String) {
        val relative = path.removePrefix("/tailcat").removePrefix("/").substringBefore('?')
        val name = if (relative.isBlank()) "index.html" else relative
        if (name.contains("..") || name.contains('/')) return response(socket, 404, "Not Found", "Not found.")
        val asset = "tailcat/$name"
        val bytes = try { context.assets.open(asset).use { it.readBytes() } }
            catch (_: Exception) { return response(socket, 404, "Not Found", "Not found.") }
        val type = when {
            name.endsWith(".html") -> "text/html; charset=utf-8"
            name.endsWith(".js") -> "application/javascript; charset=utf-8"
            name.endsWith(".wasm") -> "application/wasm"
            name.endsWith(".gz") -> "application/gzip"
            else -> "application/octet-stream"
        }
        val out = socket.getOutputStream()
        writeHeaders(out, 200, "OK", type, bytes.size.toLong())
        out.write(bytes); out.flush()
    }

    private fun listing(socket: Socket) {
        snapshot = files()
        // Keep DVR loading lazy so Saved opens immediately. DVR thumbnails and downloads
        // are loaded only when the phone user opens the DVR tab; DVR playback stays hidden.
        dvrSnapshot = emptyList()
        fun savedCard(item: SavedMedia, index: Int): String {
            val media = escape(item.mime)
            val url = "/download?i=$index"
            val stream = "/stream?i=$index"
            val thumb = "/saved-thumb?i=$index"
            return card(item.name, item.size, media, thumb, url, stream, playable = !media.startsWith("video/"))
        }
        val savedCards = snapshot.mapIndexed { index, item -> savedCard(item, index) }.joinToString("\n")
        val html = """
            <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>Gallery+</title><style>
            :root{color-scheme:dark;--bg:#121212;--panel:#1b1b1b;--line:#343434;--muted:#aeb4b9;--orange:#ff7a00}
            [hidden]{display:none!important}*{box-sizing:border-box}body{margin:0;background:var(--bg);color:#fff;font-family:Arial,sans-serif}
            header{height:76px;border-bottom:1px solid var(--line);display:flex;align-items:center;padding:0 28px;gap:18px;position:sticky;top:0;background:var(--bg);z-index:2}
            header .logo{font-size:29px;color:var(--orange);font-weight:700}header h1{font-size:26px;margin:0;font-weight:500}.tabs{display:flex;gap:6px;margin-left:20px}.tabs a{color:var(--muted);text-decoration:none;padding:10px 18px;border-bottom:3px solid transparent}.tabs a.active{color:var(--orange);border-color:var(--orange)}
            main{padding:28px;max-width:1500px;margin:0 auto}.section{display:none}.section.active{display:block}.section h2{font-size:23px;font-weight:500;margin:0 0 18px}
            .grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(250px,1fr));gap:18px}.card{background:var(--panel);border:1px solid var(--line);border-radius:8px;overflow:hidden}
            .preview{position:relative;display:block;width:100%;aspect-ratio:16/9;padding:0;border:0;border-radius:0;background:#252525;overflow:hidden;cursor:pointer}.preview:focus-visible{outline:3px solid var(--orange);outline-offset:-3px}.play-icon{position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);width:56px;height:56px;border-radius:50%;background:#000a;display:grid;place-items:center;pointer-events:none}.play-icon svg{width:28px;height:28px;fill:white}.thumb{width:100%;aspect-ratio:16/9;background:#252525;object-fit:cover;display:block;cursor:pointer}.body{padding:12px;display:grid;grid-template-columns:minmax(0,1fr) 40px;grid-template-rows:auto auto;column-gap:8px;row-gap:7px}.name{font-size:16px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.meta{grid-column:1;grid-row:2;color:var(--muted);font-size:13px}.download{grid-column:2;grid-row:1 / 3;align-self:center;display:grid;place-items:center;width:40px;height:40px;color:var(--orange);text-decoration:none;border-radius:4px}.download svg{width:24px;height:24px;fill:none;stroke:currentColor;stroke-width:2;stroke-linecap:round;stroke-linejoin:round}.download:hover{background:#ffffff12}.download:focus-visible{outline:2px solid var(--orange)}
            dialog{background:#181818;color:#fff;border:1px solid var(--line);border-radius:8px;max-width:90vw}dialog::backdrop{background:#000b}dialog video,dialog .full-photo{max-width:82vw;max-height:75vh;object-fit:contain}.full-photo{display:block;margin:0}#photoViewer{min-width:160px;min-height:100px}#photoError{padding:60px 20px 20px;margin:0;max-width:480px}button{background:var(--orange);border:0;padding:8px 14px;border-radius:4px}
            #player,#photoViewer{padding:0;overflow:hidden}#player video{display:block}.player-close{position:absolute;top:8px;right:8px;z-index:1;display:grid;place-items:center;width:44px;height:44px;padding:10px;border-radius:50%;background:#000b;color:white;cursor:pointer}.player-close svg{width:24px;height:24px;fill:none;stroke:currentColor;stroke-width:2;stroke-linecap:round}.player-close:focus-visible{outline:2px solid var(--orange)}
            .empty{color:var(--muted);padding:20px 0}@media(max-width:700px){main{padding:16px}.grid{grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}}
            </style></head><body><header><span class="logo">Gallery+</span><nav class="tabs"><a href="#dvr">DVR</a><a class="active" href="#saved">Saved</a></nav></header>
            <main><section class="section" id="dvr" data-loaded="false"><h2>DVR <small>…</small></h2><p class=empty>Open the DVR tab to load vehicle files.</p></section><section class="section active" id="saved"><h2>Saved <small>(${snapshot.size})</small></h2><div class="grid">${savedCards.ifBlank { "<div class=empty>No saved files</div>" }}</div></section>
            </main><dialog id="player"><video id="video" controls playsinline></video><button class="player-close" type="button" aria-label="Close video" title="Close video" onclick="player.close()"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18"/></svg></button></dialog>
            <dialog id="photoViewer"><img id="fullPhoto" class="full-photo" alt="Saved photo"><p id="photoError" hidden>Unable to display this photo in your browser. Use Download to save the original.</p><button class="player-close" type="button" aria-label="Close photo" title="Close photo" onclick="photoViewer.close()"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="m6 6 12 12M18 6 6 18"/></svg></button></dialog>
            <script>const player=document.getElementById('player'),video=document.getElementById('video');const photoViewer=document.getElementById('photoViewer'),fullPhoto=document.getElementById('fullPhoto'),photoError=document.getElementById('photoError');player.addEventListener('close',()=>{video.pause();video.removeAttribute('src');video.load()});function play(url){video.src=url;player.showModal();video.play().catch(()=>{})}function showPhoto(url){photoError.hidden=true;fullPhoto.hidden=false;fullPhoto.src=url;photoViewer.showModal()}fullPhoto.onerror=()=>{fullPhoto.hidden=true;photoError.hidden=false};async function loadDvr(){const dvr=document.getElementById('dvr');if(dvr.dataset.loaded==='true')return;dvr.innerHTML='<h2>DVR <small>…</small></h2><p class=empty>Loading DVR files…</p>';const c=new AbortController();const t=setTimeout(()=>c.abort(),15000);try{const r=await fetch('/dvr-list',{cache:'no-store',signal:c.signal});clearTimeout(t);dvr.innerHTML=await r.text();dvr.dataset.loaded='true'}catch(e){clearTimeout(t);dvr.innerHTML='<h2>DVR</h2><p class=empty>DVR unavailable. Open the album in Gallery+ first, then try again.</p>'}}document.querySelectorAll('.tabs a').forEach(tab=>tab.addEventListener('click',event=>{event.preventDefault();document.querySelectorAll('.tabs a').forEach(x=>x.classList.toggle('active',x===tab));document.querySelectorAll('.section').forEach(x=>x.classList.toggle('active','#'+x.id===tab.getAttribute('href')));if(tab.getAttribute('href')==='#dvr')loadDvr()}));</script></body></html>
        """.trimIndent()
        val bytes = html.toByteArray(StandardCharsets.UTF_8)
        val out = socket.getOutputStream()
        writeHeaders(out, 200, "OK", "text/html; charset=utf-8", bytes.size.toLong())
        out.write(bytes); out.flush()
    }

    private fun dvrListing(socket: Socket) {
        val body = try {
            dvrSnapshot = dvrFiles()
            val cards = dvrSnapshot.mapIndexed { index, item ->
                card(item.name, item.size, mimeFor(item.name, item.kind), "/dvr-thumb?i=$index", "/dvr-download?i=$index", playable = false)
            }.joinToString("\n")
            "<h2>DVR <small>${dvrSnapshot.size}</small></h2><div class=grid>${cards.ifBlank { "<div class=empty>No DVR files found</div>" }}</div>"
        } catch (error: Exception) {
            "<h2>DVR</h2><p class=empty>DVR unavailable: ${escape(error.message ?: "No response")}</p>"
        }
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val out = socket.getOutputStream()
        writeHeaders(out, 200, "OK", "text/html; charset=utf-8", bytes.size.toLong())
        out.write(bytes); out.flush()
    }

    private fun card(name: String, size: Long, mime: String, thumb: String, url: String, playUrl: String = url, playable: Boolean = true): String {
        val safeName = escape(name)
        val sizeText = if (size >= 1024 * 1024) "%.1f MB".format(size / 1024f / 1024f) else "%.0f KB".format(size / 1024f)
        val isVideo = mime.startsWith("video/")
        val action = if (isVideo) "play" else "showPhoto"
        val label = if (!playable) "Preview only: $safeName" else if (isVideo) "Play video: $safeName" else "View photo: $safeName"
        val overlay = if (playable && isVideo) "<span class=play-icon aria-hidden=true><svg viewBox='0 0 24 24'><path d='M8 5v14l11-7z'/></svg></span>" else ""
        val click = if (playable) " onclick=\"$action('$playUrl')\"" else " disabled"
        val visual = """<button class=preview type=button aria-label="$label"$click><img class=thumb src="$thumb" alt="" loading="lazy" onerror="this.style.visibility='hidden'">$overlay</button>"""
        return """<article class=card>$visual<div class=body><div class=name title="$safeName">$safeName</div><div class=meta>$sizeText</div><a class=download download href="$url" aria-label="Download: $safeName" title="Download"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 3v12m-5-5 5 5 5-5M5 16v4h14v-4"/></svg></a></div></article>"""
    }

    private fun image(socket: Socket, bytes: ByteArray?) {
        if (bytes == null || bytes.isEmpty()) return response(socket, 404, "Not Found", "Thumbnail unavailable.")
        val out = socket.getOutputStream()
        val type = if (bytes.size >= 4 && bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() && bytes[2] == 0x4e.toByte() && bytes[3] == 0x47.toByte()) "image/png" else "image/jpeg"
        writeHeaders(out, 200, "OK", type, bytes.size.toLong())
        out.write(bytes); out.flush()
    }

    private fun download(socket: Socket, item: SavedMedia, range: String?, attachment: Boolean) {
        val total = item.size
        val resolved = if (range == null) null else resolveRange(range, total)
        if (range != null && resolved == null) return rangeNotSatisfiable(socket, total)
        val start = resolved?.first ?: 0L
        val end = resolved?.second ?: (total - 1L)
        val length = if (total > 0L) end - start + 1L else -1L
        val partial = resolved != null
        val out = socket.getOutputStream()
        writeHeaders(out, if (partial) 206 else 200, if (partial) "Partial Content" else "OK",
            item.mime, length, if (attachment) item.name else null, total, start,
            rangeEnd = if (partial) end else null)
        openSavedAt(item, start).use { input ->
            copyBounded(input, out, length)
        }
        out.flush()
    }

    private fun openSavedAt(item: SavedMedia, start: Long): InputStream {
        item.file?.let { file ->
            return FileInputStream(file).also { stream ->
                if (start > 0L) stream.channel.position(start)
            }
        }
        item.uri?.let { uri ->
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw java.io.IOException("Unable to open file: ${item.name}")
            val stream = FileInputStream(descriptor.fileDescriptor)
            if (start > 0L) stream.channel.position(start)
            return object : FilterInputStream(stream) {
                override fun close() {
                    try { super.close() } finally { descriptor.close() }
                }
            }
        }
        return open(item).also { if (start > 0L) skipFully(it, start) }
    }

    private fun copyBounded(input: InputStream, out: OutputStream, length: Long) {
        val buffer = ByteArray(1024 * 1024)
        var remaining = length
        while (remaining != 0L) {
            val read = input.read(buffer, 0, if (remaining < 0) buffer.size else minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) break
            out.write(buffer, 0, read)
            if (remaining > 0) remaining -= read
        }
    }

    private fun downloadDvr(socket: Socket, item: DvrMedia, range: String?, attachment: Boolean) {
        val total = item.size
        val resolved = if (range == null) null else resolveRange(range, total)
        if (range != null && resolved == null) return rangeNotSatisfiable(socket, total)
        val start = resolved?.first ?: 0L
        val end = resolved?.second ?: (total - 1L)
        val length = if (total > 0L) end - start + 1L else -1L
        val out = socket.getOutputStream()
        writeHeaders(out, if (resolved != null) 206 else 200, if (resolved != null) "Partial Content" else "OK",
            mimeFor(item.name, item.kind), length, if (attachment) item.name else null, total, start,
            rangeEnd = if (resolved != null) end else null)
        openDvr(item, start).use { input ->
            copyBounded(input, out, length)
        }
        out.flush()
    }

    private fun resolveRange(header: String, total: Long): Pair<Long, Long>? {
        if (total <= 0L || !header.startsWith("bytes=", ignoreCase = true)) return null
        val spec = header.substringAfter('=').trim()
        if (',' in spec || '-' !in spec) return null
        val first = spec.substringBefore('-').trim()
        val last = spec.substringAfter('-', "").trim()
        if (first.isEmpty()) {
            val suffix = last.toLongOrNull()?.takeIf { it > 0 } ?: return null
            return (total - suffix).coerceAtLeast(0L) to (total - 1L)
        }
        val start = first.toLongOrNull()?.takeIf { it >= 0L } ?: return null
        if (start >= total) return null
        val end = if (last.isEmpty()) total - 1L else last.toLongOrNull()?.coerceAtMost(total - 1L) ?: return null
        if (end < start) return null
        return start to end
    }

    private fun rangeNotSatisfiable(socket: Socket, total: Long) {
        val bytes = "<h3>Range Not Satisfiable</h3>".toByteArray(StandardCharsets.UTF_8)
        val out = socket.getOutputStream()
        writeHeaders(out, 416, "Range Not Satisfiable", "text/html; charset=utf-8", bytes.size.toLong(), total = total)
        out.write(bytes); out.flush()
    }

    private fun response(socket: Socket, code: Int, status: String, message: String) {
        val bytes = "<h3>$status</h3><p>${escape(message)}</p>".toByteArray(StandardCharsets.UTF_8)
        val out = socket.getOutputStream()
        writeHeaders(out, code, status, "text/html; charset=utf-8", bytes.size.toLong())
        out.write(bytes); out.flush()
    }

    private fun writeHeaders(out: OutputStream, code: Int, status: String, type: String, length: Long,
                             name: String? = null, total: Long = -1L, start: Long = 0L,
                             rangeEnd: Long? = null) {
        val builder = StringBuilder("HTTP/1.1 $code $status\r\n")
            .append("Content-Type: $type\r\n")
        if (length >= 0) builder.append("Content-Length: $length\r\n")
        builder.append("Cache-Control: no-store\r\n")
        if (name != null) builder.append("Content-Disposition: attachment; filename*=UTF-8''${URLEncoder.encode(name, "UTF-8").replace("+", "%20")}\r\n")
        if (total > 0) {
            builder.append("Accept-Ranges: bytes\r\n")
            if (code == 206 && rangeEnd != null) builder.append("Content-Range: bytes $start-$rangeEnd/$total\r\n")
            if (code == 416) builder.append("Content-Range: bytes */$total\r\n")
        }
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

    private data class CandidateAddress(val address: String, val interfaceName: String, val source: String = "")

    private fun localIpv4(): String? = (wifiIpv4Candidates() + allInterfaceIpv4Candidates())
        .distinctBy { "${it.interfaceName}/${it.address}" }
        .filter { isPhoneReachableCandidate(it) }
        .minByOrNull { shareAddressScore(it) }
        ?.address

    private fun wifiIpv4Candidates(): List<CandidateAddress> {
        val candidates = mutableListOf<CandidateAddress>()
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        manager?.allNetworks?.forEach { network ->
            val capabilities = manager.getNetworkCapabilities(network)
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                val linkProperties = manager.getLinkProperties(network)
                linkProperties?.linkAddresses.orEmpty()
                    .mapNotNull { link -> link.address as? Inet4Address }
                    .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                    .forEach { candidates += CandidateAddress(it.hostAddress ?: "", linkProperties?.interfaceName ?: "wifi") }
            }
        }
        wifiManagerIpv4()?.let { candidates += CandidateAddress(it, "wifi-manager") }
        routedIpv4()?.let { candidates += CandidateAddress(it, "default-route") }
        return candidates.distinctBy { it.address }
    }

    private fun allInterfaceIpv4Candidates(): List<CandidateAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { iface ->
            val state = runCatching {
                listOfNotNull(
                    if (iface.isUp) "up" else "down",
                    if (iface.isLoopback) "loopback" else null,
                    if (iface.isVirtual) "virtual" else null
                ).joinToString(",")
            }.getOrDefault("unknown")
            iface.inetAddresses.toList()
                .filterIsInstance<Inet4Address>()
                .map { CandidateAddress(it.hostAddress ?: "", "${iface.name}[$state]") }
        }
    }.getOrElse { listOf(CandidateAddress("error", "NetworkInterface ${it.message ?: it.javaClass.simpleName}")) }

    @Suppress("DEPRECATION")
    private fun wifiManagerIpv4(): String? = runCatching {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return@runCatching null
        val raw = wifi.connectionInfo?.ipAddress ?: 0
        if (raw == 0) return@runCatching null
        listOf(raw and 0xff, raw shr 8 and 0xff, raw shr 16 and 0xff, raw shr 24 and 0xff).joinToString(".")
    }.getOrNull()

    private fun routedIpv4(): String? = runCatching {
        DatagramSocket().use { socket ->
            socket.connect(InetAddress.getByName("8.8.8.8"), 80)
            (socket.localAddress as? Inet4Address)?.hostAddress
        }
    }.getOrNull()

    private fun isPhoneReachableAddress(address: String): Boolean = rejectReason(address) == "usable"

    private fun isPhoneReachableCandidate(candidate: CandidateAddress): Boolean = rejectReason(candidate).startsWith("usable")

    private fun rejectReason(candidate: CandidateAddress): String {
        val baseReason = rejectReason(candidate.address)
        if (baseReason == "not private LAN" && candidate.interfaceName.lowercase().contains("wlan")) {
            return "usable wlan candidate"
        }
        return baseReason
    }

    private fun rejectReason(address: String): String {
        val octets = address.split('.').mapNotNull { it.toIntOrNull() }
        if (octets.size != 4) return "invalid IPv4"
        val a = octets[0]
        val b = octets[1]
        if (a == 0 || a == 127) return "loopback/unspecified"
        if (a == 169 && b == 254) return "link-local"
        // The Polestar DVR/internal network uses the 198.18.0.0/15 benchmarking range.
        // A phone connected to a hotspot cannot normally reach this address from the car.
        if (a == 198 && (b == 18 || b == 19)) return "DVR/internal 198.18/15"
        if (isTailscaleIpv4(octets)) return "usable Tailscale"
        // 10.0.x.1 addresses observed in the vehicle behave like internal gateway/bridge addresses,
        // not phone-reachable client addresses. Avoid publishing them as QR share URLs.
        if (a == 10 && b == 0 && octets[3] == 1) return "gateway-like 10.0.x.1"
        return if (isPrivateIpv4(octets)) "usable" else "not private LAN"
    }

    private fun isPrivateIpv4(octets: List<Int>): Boolean {
        val a = octets[0]
        val b = octets[1]
        return a == 10 || a == 192 && b == 168 || a == 172 && b in 16..31
    }

    private fun isTailscaleIpv4(octets: List<Int>): Boolean {
        val a = octets[0]
        val b = octets[1]
        return a == 100 && b in 64..127
    }

    private fun shareAddressScore(candidate: CandidateAddress): Int {
        val name = candidate.interfaceName.lowercase()
        val address = candidate.address
        var score = 100
        if (name.startsWith("wlan") || name.startsWith("wifi") || name.startsWith("swlan")) score -= 50
        if (name.contains("tailscale") || name.startsWith("tun") || address.startsWith("100.")) score -= 80
        if (name.contains("wlan")) score -= 60
        if (address.startsWith("172.20.10.")) score -= 40 // iPhone Personal Hotspot default range.
        if (address.startsWith("192.168.")) score -= 30
        if (address.startsWith("192.0.0.")) score -= 25
        if (address.startsWith("172.")) score -= 20
        if (address.startsWith("10.")) score -= 5
        return score
    }

    private fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
