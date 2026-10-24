package com.genie.tts

import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

internal class TtsException(val status: Int, message: String) : Exception(message)

internal data class TtsRequest(
    val characterName: String,
    val text: String,
    val splitSentence: Boolean,
)

internal fun secondsText(ms: Double): String = "%.1f".format(Locale.US, ms / 1000.0)

internal fun segmentLine(index: Int, synthMs: Double, audioMs: Double, previousAudioMs: Double?): String {
    val head = if (index == 1) "首段等待 ${secondsText(synthMs)} 秒" else "合成 ${secondsText(synthMs)} 秒"
    val stall = if (previousAudioMs != null && synthMs - previousAudioMs > 50.0) {
        "，播放会空等 ${secondsText(synthMs - previousAudioMs)} 秒"
    } else {
        ""
    }
    return "第 $index 段 $head，音频 ${secondsText(audioMs)} 秒$stall"
}

internal object TtsPorts {
    const val DEFAULT = 8001

    fun parse(raw: String?): Int? {
        val value = raw?.trim()?.toIntOrNull() ?: return null
        return value.takeIf { it in 1..65535 }
    }

    fun read(filesDir: File): Int {
        val file = File(filesDir, "tts-port.txt")
        return parse(file.takeIf { it.isFile }?.readText()) ?: DEFAULT
    }

    fun write(filesDir: File, port: Int) {
        File(filesDir, "tts-port.txt").writeText(port.toString())
    }
}

internal object TtsProtocol {
    fun parseBody(raw: String): TtsRequest {
        val json = try {
            JSONObject(raw)
        } catch (_: Exception) {
            throw TtsException(400, "Text is required")
        }
        val characterName = json.optString("character_name").trim().lowercase()
        val text = json.optString("text").trim()
        if (text.isEmpty()) throw TtsException(400, "Text is required")
        if (characterName.isEmpty()) throw TtsException(404, "character '' not found")
        val splitSentence = when (val value = if (json.has("split_sentence")) json.get("split_sentence") else false) {
            is Boolean -> value
            else -> value.toString().equals("true", ignoreCase = true)
        }
        return TtsRequest(characterName, text, splitSentence)
    }

    fun wavHeader(sampleRate: Int, dataSize: Int = -1): ByteArray {
        val channels = 1
        val bits = 16
        val blockAlign = channels * bits / 8
        val byteRate = sampleRate * blockAlign
        val data = if (dataSize < 0) 0xFFFFFFFF.toInt() else dataSize
        val riff = if (dataSize < 0) 0xFFFFFFFF.toInt() else 36 + dataSize
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(riff)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bits.toShort())
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(data)
        return header.array()
    }

    fun pcm16(samples: FloatArray): ByteArray {
        val out = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) {
            val clamped = sample.coerceIn(-1f, 1f)
            out.putShort((clamped * 32767f).toInt().toShort())
        }
        return out.array()
    }
}
