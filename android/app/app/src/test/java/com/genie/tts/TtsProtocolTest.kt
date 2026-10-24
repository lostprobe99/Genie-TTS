package com.genie.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

class TtsProtocolTest {
    @Test
    fun parsesWebttsBody() {
        val request = TtsProtocol.parseBody(
            """{"character_name":"Feibi","text":" 你好。 ","split_sentence":true}""",
        )
        assertEquals("feibi", request.characterName)
        assertEquals("你好。", request.text)
        assertTrue(request.splitSentence)
    }

    @Test
    fun splitDefaultsOff() {
        val request = TtsProtocol.parseBody("""{"character_name":"sanyueqi","text":"你好"}""")
        assertFalse(request.splitSentence)
    }

    @Test
    fun emptyTextIs400() {
        val error = runCatching { TtsProtocol.parseBody("""{"character_name":"feibi","text":"  "}""") }
            .exceptionOrNull() as TtsException
        assertEquals(400, error.status)
        assertEquals("Text is required", error.message)
    }

    @Test
    fun wavHeaderMatchesWebtts() {
        val header = TtsProtocol.wavHeader(32000)
        assertEquals(44, header.size)
        val view = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(header, 0, 4, Charsets.US_ASCII))
        assertEquals(-1, view.getInt(4))
        assertEquals("WAVE", String(header, 8, 4, Charsets.US_ASCII))
        assertEquals(1.toShort(), view.getShort(20))
        assertEquals(1.toShort(), view.getShort(22))
        assertEquals(32000, view.getInt(24))
        assertEquals(64000, view.getInt(28))
        assertEquals(2.toShort(), view.getShort(32))
        assertEquals(16.toShort(), view.getShort(34))
        assertEquals("data", String(header, 36, 4, Charsets.US_ASCII))
        assertEquals(-1, view.getInt(40))
    }

    @Test
    fun listenPortAcceptsSavedValue() {
        val dir = kotlin.io.path.createTempDirectory("tts-port").toFile()
        assertEquals(8001, TtsPorts.read(dir))
        TtsPorts.write(dir, 9000)
        assertEquals(9000, TtsPorts.read(dir))
        assertNull(TtsPorts.parse("0"))
        assertNull(TtsPorts.parse("65536"))
        assertEquals(80, TtsPorts.parse(" 80 "))
    }

    @Test
    fun pcmUsesInt16() {
        val pcm = TtsProtocol.pcm16(floatArrayOf(0.5f, -1f))
        val view = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(16383.toShort(), view.short)
        assertEquals((-32767).toShort(), view.short)
    }

    @Test
    fun segmentLineShowsPlaybackGap() {
        assertEquals(
            "第 1 段 首段等待 3.6 秒，音频 1.1 秒",
            segmentLine(1, 3600.0, 1100.0, null),
        )
        assertEquals(
            "第 2 段 合成 2.4 秒，音频 1.1 秒，播放会空等 1.3 秒",
            segmentLine(2, 2400.0, 1100.0, 1100.0),
        )
        assertEquals(
            "第 2 段 合成 0.8 秒，音频 1.1 秒",
            segmentLine(2, 800.0, 1100.0, 2000.0),
        )
    }

    @Test
    fun machineStatsCoverCpuMemoryAndTemperature() {
        val stat = "cpu  100 0 50 800 50 0 0 0\ncpu0 100 0 50 800 50 0 0 0\n"
        assertEquals(1000L to 850L, parseCpuTotal(stat))
        val self = "42 (genie tts) R 1 1 1 0 -1 0 0 0 0 0 10 20 0 0\n"
        assertEquals(30L, parseProcessJiffies(self))
        val previous = CpuSample(1000, 850, 30)
        val current = CpuSample(1400, 950, 230)
        assertEquals(75 to 200, cpuPercents(previous, current, 4))
        assertEquals(41.7f, parseZoneTemp("41700")!!, 0.05f)
        assertEquals(36.5f, parseZoneTemp("36.5")!!, 0.05f)
        val temps = temperatureClause(
            36.2f,
            listOf(ZoneTemp("cpu-0-0", 48.0f), ZoneTemp("skin-therm", 38.4f), ZoneTemp("battery", 36.0f)),
        )
        assertEquals("电池 36.2°C 机身 38.4°C CPU温 48.0°C", temps)
        assertEquals("严重", thermalStatusText(3))
        val line = machineLine("请求后", 75, 200, 980, 1400, 5600, temps, "中等", "本段")
        assertEquals(
            "请求后 本段整机CPU 75% 本进程 200% 内存 本进程 980MB 可用 1400/5600MB 电池 36.2°C 机身 38.4°C CPU温 48.0°C 热状态 中等",
            line,
        )
    }

    @Test
    fun postStreamsOneWav() {
        val seen = mutableListOf<TtsRequest>()
        val logs = mutableListOf<String>()
        val server = TtsServer(
            port = 0,
            characters = { mapOf("feibi" to 32000) },
            speaker = TtsSpeaker { request, _, emit ->
                seen.add(request)
                emit(floatArrayOf(0.25f, 0.25f))
            },
            log = { logs.add(it) },
        )
        server.start()
        try {
            val response = post(server.localPort(), """{"character_name":"feibi","text":"你好"}""")
            assertTrue(response.startsWith("HTTP/1.1 200 OK"))
            assertTrue(response.contains("Content-Type: audio/wav"))
            val audio = response.substringAfter("\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
            assertEquals("RIFF", String(audio, 0, 4, Charsets.US_ASCII))
            assertEquals(48, audio.size)
            assertEquals("feibi", seen.single().characterName)
            assertFalse(seen.single().splitSentence)
            assertTrue(logs.any { it.contains("第 1 段") && it.contains("音频") })
            assertTrue(logs.any { it.contains("完成") && it.contains("1段") })
        } finally {
            server.stop()
        }
    }

    @Test
    fun unknownCharacterIs404() {
        val server = TtsServer(0, { emptyMap() }, TtsSpeaker { _, _, _ -> })
        server.start()
        try {
            val response = post(server.localPort(), """{"character_name":"mika","text":"你好"}""")
            assertTrue(response.startsWith("HTTP/1.1 404"))
            assertTrue(response.contains("character 'mika' not found"))
        } finally {
            server.stop()
        }
    }

    private fun post(port: Int, json: String): String {
        val body = json.toByteArray(Charsets.UTF_8)
        val request = "POST /tts HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        var last: Exception? = null
        repeat(40) {
            try {
                Socket("127.0.0.1", port).use { socket ->
                    val output = socket.getOutputStream()
                    output.write(request.toByteArray(Charsets.US_ASCII))
                    output.write(body)
                    output.flush()
                    return socket.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
                }
            } catch (error: Exception) {
                last = error
                Thread.sleep(25)
            }
        }
        throw last ?: IllegalStateException("connect failed")
    }
}
