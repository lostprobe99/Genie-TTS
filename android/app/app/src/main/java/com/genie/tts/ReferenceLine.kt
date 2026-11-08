package com.genie.tts

import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Transcript of the baked-in 菲比 reference clip. RoBERTa reads these characters, not ref_seq. */
object ReferenceLine {
    const val FEIBI = "在此之前，请您务必继续享受旅居拉古那的时光。"

    fun text(caseDir: File): String {
        return try {
            JSONObject(CharacterPackSchema.manifestFile(caseDir).readText())
                .optString(CharacterPackSchema.KEY_REF_TEXT)
                .ifBlank { FEIBI }
        } catch (_: Exception) {
            FEIBI
        }
    }

    fun storedIds(caseDir: File): IntArray {
        val bytes = CharacterPackSchema.refSeqFile(caseDir).readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return IntArray(bytes.size / 8) { buffer.long.toInt() }
    }
}
