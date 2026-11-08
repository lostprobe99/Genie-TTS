package com.genie.tts

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CharacterPackTest {
    @Test
    fun importAddsACharacterAndReplaceKeepsTheOther() {
        val root = File("build/character-pack-test").apply { deleteRecursively(); mkdirs() }
        val files = File(root, "files").apply { mkdirs() }
        val store = InMemoryCharacterStore()
        val first = zip(File(root, "feibi.zip"), "feibi", "菲比")
        val imported = CharacterPack.importZip(files, first.inputStream(), store = store)
        assertEquals("feibi", imported.info.id)
        assertEquals("菲比", imported.info.name)
        assertFalse(imported.replaced)

        val second = zip(File(root, "sanyueqi.zip"), "sanyueqi", "三月七")
        CharacterPack.importZip(files, second.inputStream(), store = store)
        val again = CharacterPack.importZip(files, first.inputStream(), store = store)
        assertTrue(again.replaced)
        assertEquals(listOf("feibi", "sanyueqi"), CharacterPack.list(files, store).map { it.id })
    }

    @Test
    fun reimportKeepsARenamedCharacterUnlessANewNameIsGiven() {
        val root = File("build/character-rename-test").apply { deleteRecursively(); mkdirs() }
        val files = File(root, "files").apply { mkdirs() }
        val store = InMemoryCharacterStore()
        val packed = zip(File(root, "feibi.zip"), "feibi", "菲比")
        CharacterPack.importZip(files, packed.inputStream(), "小菲", store)
        assertEquals("小菲", CharacterPack.list(files, store).single().name)
        val again = CharacterPack.importZip(files, packed.inputStream(), store = store)
        assertEquals("小菲", again.info.name)
        val renamed = CharacterPack.importZip(files, packed.inputStream(), "菲比二号", store)
        assertEquals("菲比二号", renamed.info.name)
    }

    @Test
    fun overwriteReplacesTheFilesAndTheStoredFacts() {
        val root = File("build/character-overwrite-test").apply { deleteRecursively(); mkdirs() }
        val files = File(root, "files").apply { mkdirs() }
        val store = InMemoryCharacterStore()
        val old = zip(File(root, "feibi-old.zip"), "feibi", "菲比", fill = 1, refText = "旧原文")
        val other = zip(File(root, "sanyueqi.zip"), "sanyueqi", "三月七", fill = 9)
        val fresh = zip(File(root, "feibi-new.zip"), "feibi", "菲比", fill = 2, refText = "新原文")
        CharacterPack.importZip(files, old.inputStream(), store = store)
        CharacterPack.importZip(files, other.inputStream(), store = store)

        val again = CharacterPack.importZip(files, fresh.inputStream(), store = store)

        assertTrue(again.replaced)
        assertEquals("新原文", again.info.refText)
        assertEquals("新原文", store.find("feibi")?.refText)
        assertEquals(2.toByte(), vitsBytes(files, "feibi")[0])
        assertEquals(9.toByte(), vitsBytes(files, "sanyueqi")[0])
        assertNoImportLeftovers(files, "feibi")
    }

    @Test
    fun brokenOverwriteKeepsTheOldCharacter() {
        val root = File("build/character-broken-overwrite-test").apply { deleteRecursively(); mkdirs() }
        val files = File(root, "files").apply { mkdirs() }
        val store = InMemoryCharacterStore()
        val old = zip(File(root, "feibi-old.zip"), "feibi", "菲比", fill = 1, refText = "旧原文")
        val broken = zip(File(root, "feibi-broken.zip"), "feibi", "菲比", fill = 2, refText = "新原文", skip = "models/vits_fp32.onnx")
        CharacterPack.importZip(files, old.inputStream(), store = store)

        assertThrows(IllegalStateException::class.java) {
            CharacterPack.importZip(files, broken.inputStream(), store = store)
        }

        assertEquals(1.toByte(), vitsBytes(files, "feibi")[0])
        assertEquals("旧原文", store.find("feibi")?.refText)
        assertNoImportLeftovers(files, "feibi")
    }

    @Test
    fun overwriteKeepsThePickedAvatar() {
        val root = File("build/character-avatar-overwrite-test").apply { deleteRecursively(); mkdirs() }
        val files = File(root, "files").apply { mkdirs() }
        val store = InMemoryCharacterStore()
        val packed = zip(File(root, "feibi.zip"), "feibi", "菲比")
        CharacterPack.importZip(files, packed.inputStream(), store = store)
        store.setAvatar("feibi", 5)

        val again = CharacterPack.importZip(files, packed.inputStream(), store = store)

        assertEquals(5, again.info.avatarIndex)
        assertEquals(5, store.find("feibi")?.avatarIndex)
    }

    @Test
    fun renameStoresTheTrimmedNameAndRejectsBlank() {
        val store = InMemoryCharacterStore()
        val info = CharacterInfo("feibi", "菲比", File("characters/feibi"), "原文")
        store.upsert(File("."), info)

        CharacterPack.rename(info, "  小菲 ", store)
        assertEquals("小菲", store.find("feibi")?.name)

        assertThrows(IllegalStateException::class.java) { CharacterPack.rename(info, "   ", store) }
        assertEquals("小菲", store.find("feibi")?.name)
    }

    @Test
    fun packagedZipsImportAsTwoChoices() {
        if (System.getenv("GENIE_IMPORT_REAL_PACKS") != "1") return
        val dist = listOf(File("../../../dist"), File("../../../../dist")).firstOrNull { it.isDirectory }
            ?: return
        val feibi = File(dist, "feibi.zip")
        val sanyueqi = File(dist, "sanyueqi.zip")
        if (!feibi.isFile || !sanyueqi.isFile) return
        val root = File("build/real-pack-import").apply { deleteRecursively(); mkdirs() }
        val files = File(root, "files")
        val store = InMemoryCharacterStore()
        val first = CharacterPack.importZip(files, feibi.inputStream(), store = store)
        val second = CharacterPack.importZip(files, sanyueqi.inputStream(), store = store)
        assertEquals(listOf("feibi", "sanyueqi"), CharacterPack.list(files, store).map { it.id })
        assertEquals("在此之前，请您务必继续享受旅居拉古那的时光。", first.info.refText)
        assertEquals("倒是不忙，可他不喜欢聊天", second.info.refText)
        assertTrue(File(files, "characters/feibi/models/vits_fp32.onnx").length() > 1_000_000)
        assertTrue(File(files, "characters/sanyueqi/models/vits_fp32.onnx").length() > 1_000_000)
        root.deleteRecursively()
    }

    @Test
    fun peekReadsTheManifestAndPrefersTheReferenceClip() {
        val root = File("build/character-peek-test").apply { deleteRecursively(); mkdirs() }
        val packed = File(root, "feibi.zip")
        val clip = pcmWav(1)
        val manifest = """{"id":"feibi","name":"菲比","ref_text":"参考原文feibi"}""".toByteArray()
        ZipOutputStream(packed.outputStream()).use { zip ->
            putStored(zip, "noise.mp3", byteArrayOf(1, 2, 3, 4))
            putStored(zip, "voice/sample.wav", clip)
            zip.putNextEntry(ZipEntry("models/manifest.json"))
            zip.write(manifest)
            zip.closeEntry()
        }
        FileChannel.open(packed.toPath()).use { channel ->
            val preview = CharacterPack.peek(channel)
            assertEquals("feibi", preview.id)
            assertEquals("菲比", preview.name)
            assertEquals("参考原文feibi", preview.refText)
            assertEquals("voice/sample.wav", preview.audioName)
        }
        FileChannel.open(packed.toPath()).use { channel ->
            val bytes = CharacterPack.readNamed(channel, "voice/sample.wav")
            assertEquals(clip.size, bytes.size)
            assertEquals(1.0, CharacterPack.wavSeconds(bytes)!!, 0.001)
        }
        assertNull(CharacterPack.wavSeconds(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun caseAllowsManifestWithoutSampleSentence() {
        val root = File("build/case-no-text").apply { deleteRecursively(); mkdirs() }
        File(root, "manifest.json").writeText("""{"sample_rate":32000,"tensors":{}}""")
        val case = GenieCase(root)
        assertEquals("", case.text)
        assertEquals(32000, case.sampleRate)
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

    private fun putStored(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        val entry = ZipEntry(name)
        entry.method = ZipEntry.STORED
        entry.size = bytes.size.toLong()
        entry.compressedSize = bytes.size.toLong()
        entry.crc = CRC32().apply { update(bytes) }.value
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun pcmWav(seconds: Int): ByteArray {
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

    private fun vitsBytes(files: File, id: String): ByteArray =
        File(files, "characters/$id/models/vits_fp32.onnx").readBytes()

    private fun assertNoImportLeftovers(files: File, id: String) {
        assertFalse(File(files, "import-staging").exists())
        assertFalse(File(files, "import-backup-$id").exists())
    }

    private fun zip(
        dest: File,
        id: String,
        name: String,
        fill: Byte = 0,
        refText: String = "参考原文$id",
        skip: String? = null,
    ): File {
        val manifest = JSONObject()
            .put("format", 1)
            .put("id", id)
            .put("name", name)
            .put("ref_text", refText)
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
        CharacterPack.MODEL_NAMES.forEach { files["models/$it"] = ByteArray(1024) { fill } }
        files["tensors/ref_seq.bin"] = ByteArray(16)
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

    private fun spec(file: String, dtype: String, vararg shape: Int): JSONObject {
        return JSONObject()
            .put("file", file)
            .put("dtype", dtype)
            .put("shape", org.json.JSONArray(shape.toList()))
    }
}
