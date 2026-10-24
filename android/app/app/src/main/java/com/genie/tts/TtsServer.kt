package com.genie.tts

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

internal fun interface TtsSpeaker {
    fun speak(request: TtsRequest, stop: () -> Boolean, emit: (FloatArray) -> Unit)
}

internal class TtsServer(
    private val port: Int,
    private val characters: () -> Map<String, Int>,
    private val speaker: TtsSpeaker,
    private val log: (String) -> Unit = {},
) {
    private var socket: ServerSocket? = null
    private var loop: Thread? = null

    @Volatile
    private var stopRequested = false

    @Volatile
    private var active: Socket? = null

    fun start() {
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(port))
        socket = server
        stopRequested = false
        loop = thread(name = "genie-http", isDaemon = true) { acceptLoop(server) }
    }

    fun localPort(): Int = socket?.localPort ?: port

    fun stop() {
        stopRequested = true
        active?.close()
        socket?.close()
        loop?.join(5000)
        loop = null
        socket = null
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!stopRequested) {
            val client = try {
                server.accept()
            } catch (_: Exception) {
                break
            }
            active = client
            try {
                handle(client)
            } catch (_: Exception) {
            } finally {
                if (active === client) active = null
                try {
                    client.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun handle(client: Socket) {
        val host = client.inetAddress?.hostAddress ?: "?"
        client.soTimeout = 15_000
        val input = client.getInputStream()
        val output = client.getOutputStream()
        val head = readHeaders(input)
        val lines = head.split("\r\n")
        val requestLine = lines.firstOrNull().orEmpty().split(" ")
        val method = requestLine.getOrNull(0).orEmpty()
        val path = requestLine.getOrNull(1).orEmpty().substringBefore("?")
        if (method != "POST") {
            log("$host ${method.ifBlank { "请求" }} $path 405")
            writeText(output, 405, "Method Not Allowed")
            return
        }
        if (path != "/tts") {
            log("$host $method $path 404")
            writeText(output, 404, "Not Found")
            return
        }
        val length = contentLength(lines)
        if (length < 0 || length > MAX_BODY) {
            log("$host 正文长度不对")
            writeText(output, 400, "Text is required")
            return
        }
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        val request = try {
            TtsProtocol.parseBody(String(body, 0, read, Charsets.UTF_8))
        } catch (error: TtsException) {
            log("$host ${error.status} ${error.message}")
            writeText(output, error.status, error.message ?: "Text is required")
            return
        }
        val sampleRate = characters()[request.characterName]
        if (sampleRate == null) {
            log("$host 404 character '${request.characterName}' not found")
            writeText(output, 404, "character '${request.characterName}' not found")
            return
        }
        val begun = System.nanoTime()
        log(
            "$host 请求 ${request.characterName} split=${request.splitSentence} " +
                "${request.text.length}字 ${preview(request.text)}",
        )
        client.soTimeout = 0
        val cancelled = AtomicBoolean(false)
        val watch = thread(name = "genie-http-watch", isDaemon = true) {
            try {
                if (input.read() < 0) cancelled.set(true)
            } catch (_: Exception) {
                cancelled.set(true)
            }
        }
        var started = false
        var count = 0
        var previousAudioMs: Double? = null
        var totalAudioMs = 0.0
        var mark = begun
        var failed: String? = null
        try {
            speaker.speak(request, { cancelled.get() || stopRequested }) { samples ->
                val now = System.nanoTime()
                val synthMs = (now - mark) / 1_000_000.0
                mark = now
                if (cancelled.get() || stopRequested) throw SynthesisCancelled()
                if (!started) {
                    writeWavStart(output, sampleRate)
                    started = true
                }
                val audioMs = if (sampleRate <= 0) 0.0 else samples.size * 1000.0 / sampleRate
                count += 1
                totalAudioMs += audioMs
                log(segmentLine(count, synthMs, audioMs, previousAudioMs))
                previousAudioMs = audioMs
                output.write(TtsProtocol.pcm16(samples))
                output.flush()
            }
            if (!started) writeWavStart(output, sampleRate)
        } catch (error: TtsException) {
            failed = "${error.status} ${error.message}"
            if (!started) writeText(output, error.status, error.message ?: "failed")
        } catch (_: SynthesisCancelled) {
            failed = "客户端断开"
        } catch (error: Exception) {
            failed = error.message ?: "失败"
            if (!started) {
                writeText(output, 503, "failed to load character '${request.characterName}': ${error.message}")
            }
        } finally {
            val wallMs = (System.nanoTime() - begun) / 1_000_000.0
            val ending = failed ?: "完成"
            log(
                "$host $ending ${count}段 音频 ${secondsText(totalAudioMs)} 秒 用时 ${secondsText(wallMs)} 秒",
            )
            try {
                client.close()
            } catch (_: Exception) {
            }
            watch.join(1000)
        }
    }

    private fun preview(text: String): String {
        val flat = text.replace("\n", "")
        return if (flat.length <= 24) flat else flat.take(24) + "…"
    }

    private fun readHeaders(input: InputStream): String {
        val out = ByteArrayOutputStream()
        while (out.size() < 16 * 1024) {
            val value = input.read()
            if (value < 0) break
            out.write(value)
            val bytes = out.toByteArray()
            val n = bytes.size
            if (
                n >= 4 &&
                bytes[n - 4] == '\r'.code.toByte() &&
                bytes[n - 3] == '\n'.code.toByte() &&
                bytes[n - 2] == '\r'.code.toByte() &&
                bytes[n - 1] == '\n'.code.toByte()
            ) {
                break
            }
        }
        return out.toString(Charsets.ISO_8859_1)
    }

    private fun contentLength(lines: List<String>): Int {
        val line = lines.firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) } ?: return -1
        return line.substringAfter(":").trim().toIntOrNull() ?: -1
    }

    private fun writeWavStart(output: OutputStream, sampleRate: Int) {
        val head = "HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nConnection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.US_ASCII))
        output.write(TtsProtocol.wavHeader(sampleRate))
        output.flush()
    }

    private fun writeText(output: OutputStream, status: Int, message: String) {
        val reason = when (status) {
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            503 -> "Service Unavailable"
            else -> "Error"
        }
        val body = message.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status $reason\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    private companion object {
        const val MAX_BODY = 1024 * 1024
    }
}

internal fun interfaceAddresses(): List<String> {
    val found = LinkedHashSet<String>()
    found.add("127.0.0.1")
    val interfaces = NetworkInterface.getNetworkInterfaces() ?: return found.toList()
    for (nif in interfaces) {
        if (!nif.isUp || nif.isLoopback) continue
        for (address in nif.inetAddresses) {
            if (address is Inet4Address && !address.isLoopbackAddress) {
                address.hostAddress?.let { found.add(it) }
            }
        }
    }
    return found.toList()
}
