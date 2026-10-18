package com.genie.onnxdemo

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CharacterPackTest {
    @Test
    fun importAddsACharacterAndReplaceKeepsTheOther() {
        val root = File("build/character-pack-test").apply { deleteRecursively(); mkdirs() }
        val files = File(root, "files").apply { mkdirs() }
        val first = zip(File(root, "feibi.zip"), "feibi", "菲比")
        val imported = CharacterPack.importZip(files, first.inputStream())
        assertEquals("feibi", imported.info.id)
        assertEquals("菲比", imported.info.name)
        assertFalse(imported.replaced)

        val second = zip(File(root, "sanyueqi.zip"), "sanyueqi", "三月七")
        CharacterPack.importZip(files, second.inputStream())
        val again = CharacterPack.importZip(files, first.inputStream())
        assertTrue(again.replaced)
        assertEquals(listOf("feibi", "sanyueqi"), CharacterPack.list(files).map { it.id })
    }

    @Test
    fun packagedZipsImportAsTwoChoices() {
        val dist = listOf(File("../../../dist"), File("../../../../dist")).firstOrNull { it.isDirectory }
            ?: return
        val feibi = File(dist, "feibi.zip")
        val sanyueqi = File(dist, "sanyueqi.zip")
        if (!feibi.isFile || !sanyueqi.isFile) return
        val root = File("build/real-pack-import").apply { deleteRecursively(); mkdirs() }
        val files = File(root, "files")
        val first = CharacterPack.importZip(files, feibi.inputStream())
        val second = CharacterPack.importZip(files, sanyueqi.inputStream())
        assertEquals(listOf("feibi", "sanyueqi"), CharacterPack.list(files).map { it.id })
        assertEquals("在此之前，请您务必继续享受旅居拉古那的时光。", first.info.refText)
        assertEquals("倒是不忙，可他不喜欢聊天", second.info.refText)
        assertTrue(File(files, "characters/feibi/models/vits_fp32.onnx").length() > 1_000_000)
        assertTrue(File(files, "characters/sanyueqi/models/vits_fp32.onnx").length() > 1_000_000)
        root.deleteRecursively()
    }

    @Test
    fun migrateMovesLegacyFeibiAndRoberta() {
        val root = File("build/character-migrate-test").apply { deleteRecursively(); mkdirs() }
        val files = File(root, "files").apply { mkdirs() }
        val legacy = File(files, "android_case").apply { mkdirs() }
        File(legacy, "manifest.json").writeText(
            """{"character":"feibi","ref_text":"在此之前。","sample_rate":32000}""",
        )
        val roberta = File(legacy, "roberta").apply { mkdirs() }
        File(roberta, Prosody.FILE_NAME).writeBytes(ByteArray(8))
        // 大小对不上就不搬走，避免把半截文件当成韵律模型。
        CharacterPack.migrateLegacy(files)
        assertTrue(File(files, "characters/feibi/manifest.json").isFile)
        assertFalse(File(files, "roberta/${Prosody.FILE_NAME}").isFile)
        val manifest = JSONObject(File(files, "characters/feibi/manifest.json").readText())
        assertEquals(1, manifest.getInt("format"))
        assertEquals("feibi", manifest.getString("id"))
        assertEquals("菲比", manifest.getString("name"))
    }

    private fun zip(dest: File, id: String, name: String): File {
        val manifest = JSONObject()
            .put("format", 1)
            .put("id", id)
            .put("name", name)
            .put("ref_text", "参考原文$id")
            .put("sample_rate", 32000)
            .put("model_sizes", JSONObject().apply {
                CharacterPack.MODEL_NAMES.forEach { put(it, 1024) }
            })
        val tensors = JSONObject()
        tensors.put("ref_seq", spec("tensors/ref_seq.bin", "int64", 1, 2))
        tensors.put("ref_bert", spec("tensors/ref_bert.bin", "float32", 2, 1024))
        tensors.put("ssl_content", spec("tensors/ssl_content.bin", "float32", 1, 768, 2))
        tensors.put("ge", spec("tensors/ge.bin", "float32", 1, 1024, 1))
        tensors.put("ge_advanced", spec("tensors/ge_advanced.bin", "float32", 1, 512, 1))
        manifest.put("tensors", tensors)
        val files = linkedMapOf("manifest.json" to manifest.toString().toByteArray())
        CharacterPack.MODEL_NAMES.forEach { files["models/$it"] = ByteArray(1024) }
        files["tensors/ref_seq.bin"] = ByteArray(16)
        files["tensors/ref_bert.bin"] = ByteArray(2 * 1024 * 4)
        files["tensors/ssl_content.bin"] = ByteArray(1 * 768 * 2 * 4)
        files["tensors/ge.bin"] = ByteArray(1024 * 4)
        files["tensors/ge_advanced.bin"] = ByteArray(512 * 4)
        ZipOutputStream(dest.outputStream()).use { zip ->
            for ((path, bytes) in files) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return dest
    }

    private fun spec(file: String, dtype: String, vararg shape: Int): JSONObject {
        return JSONObject()
            .put("file", file)
            .put("dtype", dtype)
            .put("shape", org.json.JSONArray(shape.toList()))
    }
}
