package com.genie.tts.character

import com.genie.tts.speech.GenieCase
import com.genie.tts.speech.Prosody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import com.genie.tts.core.AppFailure
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.channels.FileChannel
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CharacterPackTest {
    @Test
    fun stageReturnsTheInfoOfAValidPack() {
        val root = File("build/character-stage-test").apply { deleteRecursively(); mkdirs() }
        val staging = File(root, "staging").apply { mkdirs() }
        val packed = PackFixtures.zip(File(root, "feibi.zip"), "feibi", "菲比", refText = "参考原文")

        val staged = CharacterPack.stage(packed.inputStream(), staging)

        assertEquals("feibi", staged.info.id)
        assertEquals("菲比", staged.info.name)
        assertEquals("参考原文", staged.info.refText)
        assertEquals(32000, staged.info.sampleRate)
        assertTrue(CharacterPackSchema.manifestFile(staged.root).isFile)
    }

    @Test
    fun stageRejectsAPackWithoutAModel() {
        val root = File("build/character-stage-broken-test").apply { deleteRecursively(); mkdirs() }
        val staging = File(root, "staging").apply { mkdirs() }
        val broken = PackFixtures.zip(
            File(root, "feibi.zip"), "feibi", "菲比",
            skip = "${CharacterPackSchema.MODELS_DIR}/vits_fp32.onnx",
        )

        val error = assertThrows(AppFailure.PackIncomplete::class.java) {
            CharacterPack.stage(broken.inputStream(), staging)
        }

        assertTrue(error.detail.contains("vits_fp32.onnx"))
    }

    @Test
    fun peekReadsTheManifestAndPrefersTheReferenceClip() {
        val root = File("build/character-peek-test").apply { deleteRecursively(); mkdirs() }
        val packed = File(root, "feibi.zip")
        val clip = PackFixtures.pcmWav(1)
        val manifest = """{"id":"feibi","name":"菲比","ref_text":"参考原文feibi"}""".toByteArray()
        ZipOutputStream(packed.outputStream()).use { zip ->
            PackFixtures.putStored(zip, "noise.mp3", byteArrayOf(1, 2, 3, 4))
            PackFixtures.putStored(zip, "voice/sample.wav", clip)
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
    fun scanFindsOnlyFoldersWithAManifestSortedById() {
        val root = File("build/character-scan-test").apply { deleteRecursively(); mkdirs() }
        val files = File(root, "files").apply { mkdirs() }
        val characters = CharacterPack.directory(files)
        listOf("sanyueqi", "feibi").forEach { id ->
            val dir = File(characters, id).apply { mkdirs() }
            CharacterPackSchema.manifestFile(dir).writeText(
                JSONObject().put("id", id).put("name", id).put("ref_text", "原文").toString(),
            )
        }
        File(characters, "stray").mkdirs()

        assertEquals(listOf("feibi", "sanyueqi"), CharacterPack.scan(files).map { it.id })
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
}
