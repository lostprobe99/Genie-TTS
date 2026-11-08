package com.genie.tts.character

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 单测里造角色包用的小工具。 */
internal object PackFixtures {
    fun zip(
        dest: File,
        id: String,
        name: String,
        fill: Byte = 0,
        refText: String = "参考原文$id",
        skip: String? = null,
    ): File {
        val manifest = JSONObject()
            .put("format", CharacterPackSchema.FORMAT)
            .put("id", id)
            .put("name", name)
            .put(CharacterPackSchema.KEY_REF_TEXT, refText)
            .put(CharacterPackSchema.KEY_SAMPLE_RATE, 32000)
            .put("model_sizes", JSONObject().apply {
                CharacterPackSchema.MODEL_NAMES.forEach { put(it, 1024) }
            })
        val tensors = JSONObject()
        tensors.put("ref_seq", spec("tensors/ref_seq.bin", "int64", 1, 2))
        tensors.put("ref_bert", spec("tensors/ref_bert.bin", "float32", 2, 1024))
        tensors.put("ssl_content", spec("tensors/ssl_content.bin", "float32", 1, 768, 2))
        tensors.put("ge", spec("tensors/ge.bin", "float32", 1, 1024, 1))
        tensors.put("ge_advanced", spec("tensors/ge_advanced.bin", "float32", 1, 512, 1))
        manifest.put(CharacterPackSchema.KEY_TENSORS, tensors)
        val files = linkedMapOf(CharacterPackSchema.MANIFEST to manifest.toString().toByteArray())
        CharacterPackSchema.MODEL_NAMES.forEach {
            files["${CharacterPackSchema.MODELS_DIR}/$it"] = ByteArray(1024) { fill }
        }
        files[CharacterPackSchema.REF_SEQ] = ByteArray(16)
        files["tensors/ref_bert.bin"] = ByteArray(2 * 1024 * 4)
        files["tensors/ssl_content.bin"] = ByteArray(1 * 768 * 2 * 4)
        files["tensors/ge.bin"] = ByteArray(1024 * 4)
        files["tensors/ge_advanced.bin"] = ByteArray(512 * 4)
        skip?.let { files.remove(it) }
        ZipOutputStream(dest.outputStream()).use { zip ->
            for ((path, bytes) in files) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return dest
    }

    fun putStored(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        val entry = ZipEntry(name)
        entry.method = ZipEntry.STORED
        entry.size = bytes.size.toLong()
        entry.compressedSize = bytes.size.toLong()
        entry.crc = CRC32().apply { update(bytes) }.value
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    fun pcmWav(seconds: Int): ByteArray {
        val rate = 8000
        val data = ByteArray(rate * seconds)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + data.size)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(rate)
        header.putInt(rate)
        header.putShort(1)
        header.putShort(8)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(data.size)
        return header.array() + data
    }

    private fun spec(file: String, dtype: String, vararg shape: Int): JSONObject {
        return JSONObject()
            .put("file", file)
            .put("dtype", dtype)
            .put("shape", JSONArray(shape.toList()))
    }
}
