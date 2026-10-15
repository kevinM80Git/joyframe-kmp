// SPDX-License-Identifier: Apache-2.0
package io.github.rehaancubess.joyframe.input.phone

import io.github.rehaancubess.joyframe.input.ConnectedGamepad
import io.github.rehaancubess.joyframe.input.GamepadState
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Desktop and Android: a [ServerSocket] on the first free port from [port] (when [secure], a TLS one
 * there and a plain one on the port after it), one daemon thread accepting on each, and per phone one
 * reader and one writer thread. HTTP is just enough for one page; the WebSocket side handles text,
 * ping and close frames (RFC 6455). The game thread only reads the published snapshot and queues
 * messages.
 */
actual class PhonePads actual constructor(private val port: Int, private val files: PhonePadFiles, private val secure: Boolean) {
    actual val supported: Boolean = true

    @Volatile private var address: String? = null
    actual val url: String? get() = address

    private var servers: List<ServerSocket> = emptyList()
    private val running = AtomicBoolean(false)
    /** Connected phones by pad id ("phone-<id>"). */
    private val phones = ConcurrentHashMap<String, Phone>()
    /** Join order, so the pad list (and seats) stay stable. */
    private val order = AtomicInteger()
    /** What the game last asked each pad to show, resent when that phone reconnects. */
    private val infos = ConcurrentHashMap<String, PhonePadInfo>()
    private val changed = AtomicBoolean(true)
    @Volatile private var snapshot: List<ConnectedGamepad> = emptyList()
    private var polls = 0

    private inner class Phone(val padId: String, val socket: Socket, private val out: OutputStream) {
        val joined = order.incrementAndGet()
        @Volatile var state = GamepadState()
        @Volatile var input = PhonePadProtocol.Input(0f, 0f, 0f, 0)
        /** Buttons that went down since the last poll: a press shorter than a frame is still seen once. */
        val latched = AtomicInteger()
        @Volatile var lastSeen = System.nanoTime()
        @Volatile var rtt = -1
        @Volatile var lastVibration = 0L
        val queue = LinkedBlockingQueue<String>(QUEUE)
        @Volatile var open = true
        val writer = Thread({
            try {
                while (open) {
                    val m = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    writeFrame(0x1, m.encodeToByteArray())
                }
            } catch (_: Exception) { }
            disconnect(this)
        }, "JoyframePhonePadOut").apply { isDaemon = true }

        fun writeFrame(opcode: Int, payload: ByteArray) = synchronized(out) { WebSocketFrames.write(out, opcode, payload); out.flush() }
        fun send(message: String) { if (open) queue.offer(message) }
    }

    actual fun start(): Boolean {
        if (running.get()) return true
        val lan = lanAddress()
        val tls = if (secure) runCatching { PhonePadTls.load(phonePadKeyDir(), listOfNotNull(lan)).context() }.getOrNull() ?: return false else null
        fun open(p: Int, tls: javax.net.ssl.SSLContext?): ServerSocket? = try {
            (tls?.serverSocketFactory?.createServerSocket() ?: ServerSocket()).apply { reuseAddress = true; bind(InetSocketAddress(p)) }
        } catch (_: IOException) { null }
        for (p in port until port + 10) {
            val main = open(p, tls) ?: continue
            val plain = if (tls != null) open(p + 1, null) ?: run { runCatching { main.close() }; null } ?: continue else null
            servers = listOfNotNull(main, plain)
            running.set(true)
            address = lan?.let { (if (tls != null) "https" else "http") + "://$it:$p/" }
            for (s in servers) Thread({ acceptLoop(s) }, "JoyframePhonePads").apply { isDaemon = true; start() }
            return true
        }
        return false
    }

    private fun acceptLoop(s: ServerSocket) {
        while (running.get()) {
            val socket = try { s.accept() } catch (_: IOException) { break }
            Thread({ handle(socket) }, "JoyframePhonePadIn").apply { isDaemon = true; start() }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = READ_TIMEOUT_MS
            val input = DataInputStream(java.io.BufferedInputStream(socket.getInputStream(), 4096))
            // Buffered: a frame or a response goes out as one write (one TLS record, one packet), on flush.
            val out = java.io.BufferedOutputStream(socket.getOutputStream(), 16384)
            val request = readHeaders(input) ?: return socket.close()
            val path = request.first
            val headers = request.second
            if (path.startsWith("/ws") && headers["upgrade"]?.equals("websocket", ignoreCase = true) == true) {
                val key = headers["sec-websocket-key"] ?: return socket.close()
                out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: ${WebSocketFrames.accept(key)}\r\n\r\n").encodeToByteArray())
                out.flush()
                socketLoop(socket, input, out)
            } else {
                serveFile(out, path, headers)
                socket.close()
            }
        } catch (_: Exception) {
            runCatching { socket.close() }
        }
    }

    private class Served(val bytes: ByteArray, val gzip: Boolean, val etag: String)
    /** Files read once and kept (the Compose controller is a few MB; reading it per phone would be slow on a TV). */
    private val served = ConcurrentHashMap<String, Served>()

    /**
     * `/` is [files]' index.html (or the light page), `/lite` the light page, anything else a file by
     * name (letters, digits, `.`, `-`, `_`). Gzip variants for browsers that accept them; ETags, so a
     * phone scanning again reuses what it downloaded.
     */
    private fun serveFile(out: OutputStream, rawPath: String, headers: Map<String, String>) {
        val path = rawPath.substringBefore('?').trimStart('/')
        val name = if (path.isEmpty()) "index.html" else path
        val acceptsGzip = headers["accept-encoding"]?.contains("gzip") == true
        val file: Served? = when {
            name == "lite" -> Served(PhonePadPage.HTML.encodeToByteArray(), false, "lite")
            !name.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' || it == '/' } || ".." in name -> null
            else -> load(name, acceptsGzip) ?: if (name == "index.html") Served(PhonePadPage.HTML.encodeToByteArray(), false, "lite") else null
        }
        if (file == null) {
            val body = "Not found".encodeToByteArray()
            out.write("HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".encodeToByteArray())
            out.write(body); out.flush(); return
        }
        val type = contentType(if (name == "lite") "lite.html" else name)
        if (headers["if-none-match"] == file.etag) {
            out.write("HTTP/1.1 304 Not Modified\r\nETag: ${file.etag}\r\nConnection: close\r\n\r\n".encodeToByteArray())
            out.flush(); return
        }
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${file.bytes.size}\r\n" +
            (if (file.gzip) "Content-Encoding: gzip\r\nVary: Accept-Encoding\r\n" else "") +
            "ETag: ${file.etag}\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n").encodeToByteArray())
        out.write(file.bytes)
        out.flush()
    }

    private fun load(name: String, gzip: Boolean): Served? {
        val key = if (gzip) "$name.gz" else name
        served[key]?.let { return it }
        val packed = if (gzip) runCatching { files.open("$name.gz") ?: files.open("$name.gzip") }.getOrNull() else null
        val bytes = packed ?: runCatching { files.open(name) }.getOrNull() ?: return null
        val crc = java.util.zip.CRC32().apply { update(bytes) }.value
        return Served(bytes, packed != null, "\"${bytes.size}-${crc.toString(16)}\"").also { served[key] = it }
    }

    private fun contentType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html" -> "text/html; charset=utf-8"
        "js", "mjs" -> "text/javascript; charset=utf-8"
        "wasm" -> "application/wasm"
        "json", "map" -> "application/json"
        "css" -> "text/css"
        "png" -> "image/png"
        "svg" -> "image/svg+xml"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "woff2" -> "font/woff2"
        else -> "application/octet-stream"
    }

    /** One phone's WebSocket: the first text message must be its `hello`. */
    private fun socketLoop(socket: Socket, input: DataInputStream, out: OutputStream) {
        var phone: Phone? = null
        try {
            while (running.get()) {
                val frame = WebSocketFrames.read(input) ?: break
                when (frame.opcode) {
                    0x8 -> break
                    0x9 -> synchronized(out) { WebSocketFrames.write(out, 0xA, frame.payload); out.flush() }
                    0x1 -> {
                        val text = frame.payload.decodeToString()
                        val p = phone
                        if (p == null) {
                            val id = PhonePadProtocol.phoneId(text) ?: continue
                            val padId = "phone-$id"
                            phones.remove(padId)?.let { disconnect(it) } // the same phone, reconnected
                            val fresh = Phone(padId, socket, out)
                            phones[padId] = fresh
                            phone = fresh
                            fresh.writer.start()
                            infos[padId]?.let { fresh.send(PhonePadProtocol.info(it)) }
                            changed.set(true)
                            continue
                        }
                        p.lastSeen = System.nanoTime()
                        val input = PhonePadProtocol.parse(text)
                        when {
                            input != null -> {
                                val pressed = input.buttons and p.input.buttons.inv()
                                if (pressed != 0) p.latched.getAndUpdate { it or pressed }
                                p.input = input
                                val state = input.state()
                                if (state != p.state) { p.state = state; changed.set(true) }
                            }
                            text.startsWith("ping ") -> p.send("pong " + text.substring(5))
                            text.startsWith("rtt ") -> p.rtt = text.substring(4).trim().toIntOrNull() ?: p.rtt
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        phone?.let { disconnect(it) } ?: runCatching { socket.close() }
    }

    private fun disconnect(p: Phone) {
        if (!p.open) return
        p.open = false
        phones.remove(p.padId, p)
        runCatching { p.socket.close() }
        changed.set(true)
    }

    actual fun pollAll(): List<ConnectedGamepad> {
        // A phone that went quiet (asleep, out of range) is gone; its page reconnects as the same pad.
        if (++polls % 15 == 0) {
            val now = System.nanoTime()
            for (p in phones.values) if (now - p.lastSeen > SILENT_NS) disconnect(p)
        }
        // Presses already released: shown down for this one poll (and the list rebuilt next poll without them).
        var tapped = false
        for (p in phones.values) if (p.latched.get() and p.input.buttons.inv() != 0) { tapped = true; break }
        if (tapped || changed.getAndSet(false)) {
            snapshot = phones.values.sortedBy { it.joined }.map {
                val extra = it.latched.getAndSet(0)
                ConnectedGamepad(it.padId, "Phone", if (extra and it.input.buttons.inv() != 0) it.input.state(extra) else it.state)
            }
            if (tapped) changed.set(true)
        } else for (p in phones.values) p.latched.set(0)
        return snapshot
    }

    actual fun show(id: String, info: PhonePadInfo) {
        if (infos[id] == info) return
        infos[id] = info
        phones[id]?.send(PhonePadProtocol.info(info))
    }

    actual fun rumble(id: String, durationMs: Int, intensity: Float) {
        val p = phones[id] ?: return
        val now = System.nanoTime()
        if (now - p.lastVibration < 60_000_000L) return
        p.lastVibration = now
        p.send("vib " + (durationMs * (0.5f + 0.5f * intensity.coerceIn(0f, 1f))).toInt().coerceIn(10, 600))
    }

    actual fun roundTripMs(id: String): Int = phones[id]?.rtt ?: -1

    actual fun close() {
        running.set(false)
        for (s in servers) runCatching { s.close() }
        servers = emptyList()
        for (p in phones.values) disconnect(p)
        snapshot = emptyList()
    }

    /** "GET /path" and the headers (lower-case names), or null. */
    private fun readHeaders(input: InputStream): Pair<String, Map<String, String>>? {
        val buf = StringBuilder()
        while (buf.length < 8192) {
            val c = input.read()
            if (c < 0) return null
            buf.append(c.toChar())
            if (buf.endsWith("\r\n\r\n")) break
        }
        val lines = buf.split("\r\n")
        val first = lines.firstOrNull()?.split(' ') ?: return null
        if (first.size < 2 || first[0] != "GET") return null
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        return first[1] to headers
    }

    private companion object {
        const val QUEUE = 64
        const val READ_TIMEOUT_MS = 5000
        const val SILENT_NS = 3_000_000_000L

        /** A site-local IPv4 address, Wi-Fi and Ethernet first. */
        fun lanAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .sortedBy { n -> if (n.name.startsWith("wlan") || n.name.startsWith("en") || n.name.startsWith("eth")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()
    }
}

/** RFC 6455 framing, just what a controller needs: text, ping, pong, close; unfragmented. */
internal object WebSocketFrames {
    class Frame(val opcode: Int, val payload: ByteArray)

    fun accept(key: String): String = Base64.getEncoder().encodeToString(
        MessageDigest.getInstance("SHA-1").digest((key.trim() + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encodeToByteArray()))

    /** The next frame, or null at the end of the stream. Payloads over 64 KiB are refused. */
    fun read(input: DataInputStream): Frame? {
        val b0 = input.read()
        if (b0 < 0) return null
        val b1 = input.readUnsignedByte()
        var length = (b1 and 0x7F).toLong()
        if (length == 126L) length = input.readUnsignedShort().toLong()
        else if (length == 127L) length = input.readLong()
        if (length > 65536) throw IOException("frame too large")
        val mask = if (b1 and 0x80 != 0) ByteArray(4).also { input.readFully(it) } else null
        val payload = ByteArray(length.toInt())
        input.readFully(payload)
        if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
        return Frame(b0 and 0x0F, payload)
    }

    /** A server frame (unmasked). */
    fun write(out: OutputStream, opcode: Int, payload: ByteArray) {
        out.write(0x80 or opcode)
        when {
            payload.size < 126 -> out.write(payload.size)
            payload.size < 65536 -> { out.write(126); out.write(payload.size ushr 8); out.write(payload.size and 0xFF) }
            else -> { out.write(127); for (i in 7 downTo 0) out.write(((payload.size.toLong() ushr (i * 8)) and 0xFF).toInt()) }
        }
        out.write(payload)
    }
}
