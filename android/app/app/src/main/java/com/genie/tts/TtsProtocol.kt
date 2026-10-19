package com.genie.tts

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class TtsException(val status: Int, message: String) : Exception(message)

internal data class TtsRequest(
    val characterName: String,
    val text: String,
    val splitSentence: Boolean,
)

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
