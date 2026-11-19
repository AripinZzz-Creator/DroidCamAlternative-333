package com.aripin.webcam.network

import android.util.Log
import com.aripin.webcam.protocol.PacketProtocol
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class StreamServerManager(
    private val onRemoteCommand: (JSONObject) -> Unit,
    private val onClientsChanged: (List<String>) -> Unit
) {
    companion object {
        private const val TAG = "StreamServerManager"
        private const val MJPEG_BOUNDARY = "droidcam_alt_frame"
    }

    private val isRunning = AtomicBoolean(false)
    private var tcpServerSocket: ServerSocket? = null
    private var httpServerSocket: ServerSocket? = null

    private val executor = Executors.newCachedThreadPool()
    private val tcpClients = CopyOnWriteArrayList<TcpClientSession>()
    private val mjpegClients = CopyOnWriteArrayList<MjpegClientSession>()
    private val wavAudioClients = CopyOnWriteArrayList<WavClientSession>()
    private val activeClientIps = ConcurrentHashMap<String, Int>()

    @Volatile
    private var cachedH264ConfigPacket: ByteArray? = null

    @Volatile
    private var currentStreamInfoJson: String = "{}"

    @Volatile
    private var audioSampleRate: Int = 48000

    @Volatile
    private var audioChannels: Int = 1

    private val bytesSentCounter = AtomicLong(0)
    private val framesSentCounter = AtomicLong(0)

    private class TcpClientSession(
        val socket: Socket,
        val out: BufferedOutputStream,
        val remoteIp: String
    ) {
        val writeLock = Any()
    }

    private class MjpegClientSession(
        val socket: Socket,
        val out: BufferedOutputStream,
        val remoteIp: String
    ) {
        val writeLock = Any()
    }

    private class WavClientSession(
        val socket: Socket,
        val out: BufferedOutputStream,
        val remoteIp: String
    ) {
        val writeLock = Any()
    }

    fun start(httpPort: Int = 4747, tcpPort: Int = 4748) {
        if (isRunning.getAndSet(true)) return

        executor.execute { runTcpServer(tcpPort) }
        executor.execute { runHttpServer(httpPort) }
    }

    fun stop() {
        if (!isRunning.getAndSet(false)) return
        try {
            tcpServerSocket?.close()
        } catch (_: Exception) {
        }
        try {
            httpServerSocket?.close()
        } catch (_: Exception) {
        }

        tcpClients.forEach { closeQuietly(it.socket) }
        mjpegClients.forEach { closeQuietly(it.socket) }
        wavAudioClients.forEach { closeQuietly(it.socket) }

        tcpClients.clear()
        mjpegClients.clear()
        wavAudioClients.clear()
        activeClientIps.clear()
        notifyClientUpdate()
    }

    fun updateStreamMetadata(width: Int, height: Int, fps: Int, codec: String, sampleRate: Int, channels: Int) {
        this.audioSampleRate = sampleRate
        this.audioChannels = channels
        val json = JSONObject().apply {
            put("width", width)
            put("height", height)
            put("fps", fps)
            put("codec", codec)
            put("audioSampleRate", sampleRate)
            put("audioChannels", channels)
        }
        currentStreamInfoJson = json.toString()

        val infoBytes = currentStreamInfoJson.toByteArray(StandardCharsets.UTF_8)
        val packet = PacketProtocol.buildPacket(PacketProtocol.TYPE_STREAM_INFO, System.nanoTime() / 1000L, infoBytes)
        broadcastTcpPacket(packet)
    }

    fun broadcastH264Unit(nalData: ByteArray, ptsUs: Long, isConfig: Boolean) {
        val packet = PacketProtocol.buildPacket(PacketProtocol.TYPE_H264_NAL, ptsUs, nalData)
        if (isConfig) {
            cachedH264ConfigPacket = packet
        }
        if (tcpClients.isNotEmpty()) {
            broadcastTcpPacket(packet)
            framesSentCounter.incrementAndGet()
        }
    }

    fun broadcastMjpegFrame(jpegData: ByteArray, ptsUs: Long) {
        if (mjpegClients.isNotEmpty()) {
            val header = (
                "--$MJPEG_BOUNDARY\r\n" +
                    "Content-Type: image/jpeg\r\n" +
                    "Content-Length: ${jpegData.size}\r\n\r\n"
                ).toByteArray(StandardCharsets.US_ASCII)
            val footer = "\r\n".toByteArray(StandardCharsets.US_ASCII)

            val dead = mutableListOf<MjpegClientSession>()
            for (client in mjpegClients) {
                try {
                    synchronized(client.writeLock) {
                        client.out.write(header)
                        client.out.write(jpegData)
                        client.out.write(footer)
                        client.out.flush()
                    }
                    bytesSentCounter.addAndGet((header.size + jpegData.size + footer.size).toLong())
                } catch (_: Exception) {
                    dead.add(client)
                }
            }
            if (dead.isNotEmpty()) {
                dead.forEach {
                    mjpegClients.remove(it)
                    unregisterClientIp(it.remoteIp)
                    closeQuietly(it.socket)
                }
            }
            if (tcpClients.isEmpty()) {
                framesSentCounter.incrementAndGet()
            }
        }

        if (tcpClients.isNotEmpty()) {
            val packet = PacketProtocol.buildPacket(PacketProtocol.TYPE_MJPEG_FRAME, ptsUs, jpegData)
            broadcastTcpPacket(packet)
        }
    }

    fun broadcastAudioPcm(pcmData: ByteArray, length: Int, ptsUs: Long) {
        if (tcpClients.isNotEmpty()) {
            val packet = PacketProtocol.buildPacket(PacketProtocol.TYPE_PCM_AUDIO, ptsUs, pcmData, 0, length)
            broadcastTcpPacket(packet)
        }

        if (wavAudioClients.isNotEmpty()) {
            val dead = mutableListOf<WavClientSession>()
            for (client in wavAudioClients) {
                try {
                    synchronized(client.writeLock) {
                        client.out.write(pcmData, 0, length)
                        client.out.flush()
                    }
                    bytesSentCounter.addAndGet(length.toLong())
                } catch (_: Exception) {
                    dead.add(client)
                }
            }
            if (dead.isNotEmpty()) {
                dead.forEach {
                    wavAudioClients.remove(it)
                    unregisterClientIp(it.remoteIp)
                    closeQuietly(it.socket)
                }
            }
        }
    }

    fun consumeStatsSnapshot(): Pair<Long, Long> {
        val bytes = bytesSentCounter.getAndSet(0)
        val frames = framesSentCounter.getAndSet(0)
        return Pair(bytes, frames)
    }

    private fun broadcastTcpPacket(packet: ByteArray) {
        val dead = mutableListOf<TcpClientSession>()
        for (client in tcpClients) {
            try {
                synchronized(client.writeLock) {
                    client.out.write(packet)
                    client.out.flush()
                }
                bytesSentCounter.addAndGet(packet.size.toLong())
            } catch (_: Exception) {
                dead.add(client)
            }
        }
        if (dead.isNotEmpty()) {
            dead.forEach {
                tcpClients.remove(it)
                unregisterClientIp(it.remoteIp)
                closeQuietly(it.socket)
            }
        }
    }

    private fun runTcpServer(port: Int) {
        try {
            val server = ServerSocket(port).apply { reuseAddress = true }
            tcpServerSocket = server
            Log.i(TAG, "Low-latency TCP stream server listening on port $port")

            while (isRunning.get()) {
                val socket = server.accept()
                socket.tcpNoDelay = true
                socket.sendBufferSize = 256 * 1024
                val remoteIp = socket.inetAddress?.hostAddress ?: "unknown"
                val session = TcpClientSession(socket, BufferedOutputStream(socket.getOutputStream(), 65536), remoteIp)

                try {
                    synchronized(session.writeLock) {
                        val infoBytes = currentStreamInfoJson.toByteArray(StandardCharsets.UTF_8)
                        val infoPacket = PacketProtocol.buildPacket(
                            PacketProtocol.TYPE_STREAM_INFO,
                            System.nanoTime() / 1000L,
                            infoBytes
                        )
                        session.out.write(infoPacket)
                        cachedH264ConfigPacket?.let { session.out.write(it) }
                        session.out.flush()
                    }
                } catch (_: Exception) {
                }

                tcpClients.add(session)
                registerClientIp(remoteIp)

                executor.execute { listenTcpClientCommands(session) }
            }
        } catch (e: Exception) {
            if (isRunning.get()) {
                Log.e(TAG, "TCP Server error: ${e.message}")
            }
        }
    }

    private fun listenTcpClientCommands(session: TcpClientSession) {
        try {
            val input = BufferedInputStream(session.socket.getInputStream())
            val lineBuf = ByteArrayOutputStream()
            while (isRunning.get() && !session.socket.isClosed) {
                val b = input.read()
                if (b == -1) break
                if (b == '\n'.code) {
                    val line = lineBuf.toString(StandardCharsets.UTF_8.name()).trim()
                    lineBuf.reset()
                    if (line.startsWith("{")) {
                        try {
                            onRemoteCommand(JSONObject(line))
                        } catch (_: Exception) {
                        }
                    }
                } else {
                    lineBuf.write(b)
                }
            }
        } catch (_: Exception) {
        } finally {
            tcpClients.remove(session)
            unregisterClientIp(session.remoteIp)
            closeQuietly(session.socket)
        }
    }

    private fun runHttpServer(port: Int) {
        try {
            val server = ServerSocket(port).apply { reuseAddress = true }
            httpServerSocket = server
            Log.i(TAG, "HTTP/MJPEG/Web server listening on port $port")

            while (isRunning.get()) {
                val socket = server.accept()
                socket.tcpNoDelay = true
                executor.execute { handleHttpClient(socket) }
            }
        } catch (e: Exception) {
            if (isRunning.get()) {
                Log.e(TAG, "HTTP Server error: ${e.message}")
            }
        }
    }

    private fun handleHttpClient(socket: Socket) {
        val remoteIp = socket.inetAddress?.hostAddress ?: "unknown"
        try {
            val input = BufferedInputStream(socket.getInputStream())
            val requestLines = mutableListOf<String>()
            val lineBuf = ByteArrayOutputStream()
            var contentLength = 0

            while (true) {
                val b = input.read()
                if (b == -1) break
                if (b == '\n'.code) {
                    val line = lineBuf.toString(StandardCharsets.UTF_8.name()).trimEnd('\r')
                    lineBuf.reset()
                    if (line.isEmpty()) break
                    requestLines.add(line)
                    if (line.lowercase().startsWith("content-length:")) {
                        contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                } else {
                    lineBuf.write(b)
                }
            }

            if (requestLines.isEmpty()) {
                closeQuietly(socket)
                return
            }

            val parts = requestLines[0].split(" ")
            val method = parts.getOrElse(0) { "GET" }
            val path = parts.getOrElse(1) { "/" }.substringBefore("?")
            val out = BufferedOutputStream(socket.getOutputStream())

            when {
                path == "/video" || path == "/mjpegfeed" -> {
                    val headers = (
                        "HTTP/1.1 200 OK\r\n" +
                            "Connection: close\r\n" +
                            "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                            "Pragma: no-cache\r\n" +
                            "Content-Type: multipart/x-mixed-replace; boundary=$MJPEG_BOUNDARY\r\n\r\n"
                        ).toByteArray(StandardCharsets.US_ASCII)
                    out.write(headers)
                    out.flush()
                    registerClientIp(remoteIp)
                    mjpegClients.add(MjpegClientSession(socket, out, remoteIp))
                }

                path == "/audio.wav" -> {
                    val headers = (
                        "HTTP/1.1 200 OK\r\n" +
                            "Connection: close\r\n" +
                            "Cache-Control: no-cache\r\n" +
                            "Content-Type: audio/wav\r\n\r\n"
                        ).toByteArray(StandardCharsets.US_ASCII)
                    out.write(headers)
                    out.write(createStreamingWavHeader(audioSampleRate, audioChannels, 16))
                    out.flush()
                    registerClientIp(remoteIp)
                    wavAudioClients.add(WavClientSession(socket, out, remoteIp))
                }

                path == "/status" -> {
                    val body = currentStreamInfoJson.toByteArray(StandardCharsets.UTF_8)
                    val response = (
                        "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/json\r\n" +
                            "Access-Control-Allow-Origin: *\r\n" +
                            "Content-Length: ${body.size}\r\n\r\n"
                        ).toByteArray(StandardCharsets.US_ASCII)
                    out.write(response)
                    out.write(body)
                    out.flush()
                    closeQuietly(socket)
                }

                path == "/control" && method == "POST" -> {
                    val bodyBytes = ByteArray(contentLength)
                    var readTotal = 0
                    while (readTotal < contentLength) {
                        val r = input.read(bodyBytes, readTotal, contentLength - readTotal)
                        if (r <= 0) break
                        readTotal += r
                    }
                    val bodyStr = String(bodyBytes, 0, readTotal, StandardCharsets.UTF_8)
                    if (bodyStr.startsWith("{")) {
                        onRemoteCommand(JSONObject(bodyStr))
                    }
                    val ack = """{"ok":true}""".toByteArray(StandardCharsets.UTF_8)
                    val response = (
                        "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/json\r\n" +
                            "Access-Control-Allow-Origin: *\r\n" +
                            "Content-Length: ${ack.size}\r\n\r\n"
                        ).toByteArray(StandardCharsets.US_ASCII)
                    out.write(response)
                    out.write(ack)
                    out.flush()
                    closeQuietly(socket)
                }

                else -> {
                    val html = buildWebDashboardHtml().toByteArray(StandardCharsets.UTF_8)
                    val response = (
                        "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: text/html; charset=utf-8\r\n" +
                            "Content-Length: ${html.size}\r\n\r\n"
                        ).toByteArray(StandardCharsets.US_ASCII)
                    out.write(response)
                    out.write(html)
                    out.flush()
                    closeQuietly(socket)
                }
            }
        } catch (_: Exception) {
            closeQuietly(socket)
        }
    }

    private fun createStreamingWavHeader(sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = (channels * bitsPerSample / 8).toShort()
        val buf = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(StandardCharsets.US_ASCII))
        buf.putInt(0x7FFFFFFF)
        buf.put("WAVE".toByteArray(StandardCharsets.US_ASCII))
        buf.put("fmt ".toByteArray(StandardCharsets.US_ASCII))
        buf.putInt(16)
        buf.putShort(1)
        buf.putShort(channels.toShort())
        buf.putInt(sampleRate)
        buf.putInt(byteRate)
        buf.putShort(blockAlign)
        buf.putShort(bitsPerSample.toShort())
        buf.put("data".toByteArray(StandardCharsets.US_ASCII))
        buf.putInt(0x7FFFFFFF)
        return buf.array()
    }

    private fun buildWebDashboardHtml(): String = """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="UTF-8" />
            <meta name="viewport" content="width=device-width, initial-scale=1.0" />
            <title>DroidCam Alternative - Web & OBS Receiver</title>
            <style>
                body { margin: 0; background: #0f1115; color: #f1f5f9; font-family: system-ui, sans-serif; display: flex; flex-direction: column; align-items: center; }
                header { width: 100%; padding: 14px 24px; background: #181b22; display: flex; justify-content: space-between; align-items: center; box-sizing: border-box; border-bottom: 1px solid #2a2f3d; }
                .controls { display: flex; gap: 10px; flex-wrap: wrap; padding: 14px; }
                button { background: #2563eb; color: white; border: none; padding: 10px 16px; border-radius: 8px; cursor: pointer; font-weight: 600; }
                button:hover { background: #1d4ed8; }
                img { max-width: 95vw; max-height: 78vh; border-radius: 12px; background: #000; border: 1px solid #2a2f3d; }
                .hint { font-size: 13px; color: #94a3b8; margin-bottom: 16px; }
            </style>
        </head>
        <body>
            <header>
                <strong>DroidCam Alternative - Live Feed</strong>
                <span id="meta">Connected</span>
            </header>
            <div class="controls">
                <button onclick="sendCmd({action:'switch_camera'})">Switch Camera</button>
                <button onclick="sendCmd({action:'toggle_torch'})">Toggle Torch</button>
                <button onclick="sendCmd({action:'toggle_mute'})">Mute/Unmute Mic</button>
                <button onclick="document.getElementById('aud').play()">Enable Live Audio</button>
            </div>
            <img src="/video" alt="Live Camera Stream" />
            <audio id="aud" src="/audio.wav" preload="none"></audio>
            <p class="hint">OBS Studio Direct URL: <code>http://&lt;PHONE_IP&gt;:4747/video</code> (Media Source or Browser Source)</p>
            <script>
                function sendCmd(payload) {
                    fetch('/control', { method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(payload) });
                }
            </script>
        </body>
        </html>
    """.trimIndent()

    private fun registerClientIp(ip: String) {
        activeClientIps.compute(ip) { _, count -> (count ?: 0) + 1 }
        notifyClientUpdate()
    }

    private fun unregisterClientIp(ip: String) {
        activeClientIps.computeIfPresent(ip) { _, count -> if (count <= 1) null else count - 1 }
        notifyClientUpdate()
    }

    private fun notifyClientUpdate() {
        onClientsChanged(activeClientIps.keys().toList())
    }

    private fun closeQuietly(socket: Socket?) {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
    }
}
