package com.genie.tts.character

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import com.genie.tts.core.AppFailure
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 前几次写入照做，之后的写入失败，用来模拟清单写到一半出错。 */
private class FailUpsertAfter(
    private val delegate: InMemoryCharacterStore,
    private var remaining: Int,
) : CharacterStore by delegate {
    override fun upsert(filesDir: File, info: CharacterInfo) {
        if (remaining == 0) error("清单写不进去")
        remaining -= 1
        delegate.upsert(filesDir, info)
    }
}

class CharacterRepositoryTest {
    private fun sandbox(name: String): File = File("build/$name").apply { deleteRecursively(); mkdirs() }

    private fun filesIn(root: File): File = File(root, "files").apply { mkdirs() }

    private fun vitsBytes(files: File, id: String): ByteArray =
        CharacterPackSchema.modelFile(File(CharacterPack.directory(files), id), "vits_fp32.onnx").readBytes()

    private fun assertNoImportLeftovers(files: File, id: String) {
        assertFalse(File(files, "import-staging").exists())
        assertFalse(File(files, "import-backup-$id").exists())
    }

    @Test
    fun importAddsACharacterAndReplaceKeepsTheOther() {
        val root = sandbox("repo-import-test")
        val files = filesIn(root)
        val repo = CharacterRepository(InMemoryCharacterStore())
        val first = PackFixtures.zip(File(root, "feibi.zip"), "feibi", "菲比")
        val imported = repo.importZip(files, first.inputStream())
        assertEquals("feibi", imported.info.id)
        assertEquals("菲比", imported.info.name)
        assertFalse(imported.replaced)

        val second = PackFixtures.zip(File(root, "sanyueqi.zip"), "sanyueqi", "三月七")
        repo.importZip(files, second.inputStream())
        val again = repo.importZip(files, first.inputStream())
        assertTrue(again.replaced)
        assertEquals(listOf("feibi", "sanyueqi"), repo.list(files).map { it.id })
    }

    @Test
    fun importedCharacterCanBeFoundAndListedForTheService() {
        val root = sandbox("repo-find-test")
        val files = filesIn(root)
        val repo = CharacterRepository(InMemoryCharacterStore())
        repo.importZip(files, PackFixtures.zip(File(root, "feibi.zip"), "feibi", "菲比").inputStream())

        assertEquals("菲比", repo.find("feibi")?.name)
        assertNull(repo.find("missing"))
        assertEquals(listOf("feibi"), repo.all().map { it.id })
    }

    @Test
    fun reimportKeepsARenamedCharacterUnlessANewNameIsGiven() {
        val root = sandbox("repo-rename-test")
        val files = filesIn(root)
        val repo = CharacterRepository(InMemoryCharacterStore())
        val packed = PackFixtures.zip(File(root, "feibi.zip"), "feibi", "菲比")
        repo.importZip(files, packed.inputStream(), "小菲")
        assertEquals("小菲", repo.list(files).single().name)
        val again = repo.importZip(files, packed.inputStream())
        assertEquals("小菲", again.info.name)
        val renamed = repo.importZip(files, packed.inputStream(), "菲比二号")
        assertEquals("菲比二号", renamed.info.name)
    }

    @Test
    fun overwriteReplacesTheFilesAndTheStoredFacts() {
        val root = sandbox("repo-overwrite-test")
        val files = filesIn(root)
        val store = InMemoryCharacterStore()
        val repo = CharacterRepository(store)
        val old = PackFixtures.zip(File(root, "feibi-old.zip"), "feibi", "菲比", fill = 1, refText = "旧原文")
        val other = PackFixtures.zip(File(root, "sanyueqi.zip"), "sanyueqi", "三月七", fill = 9)
        val fresh = PackFixtures.zip(File(root, "feibi-new.zip"), "feibi", "菲比", fill = 2, refText = "新原文")
        repo.importZip(files, old.inputStream())
        repo.importZip(files, other.inputStream())

        val again = repo.importZip(files, fresh.inputStream())

        assertTrue(again.replaced)
        assertEquals("新原文", again.info.refText)
        assertEquals("新原文", store.find("feibi")?.refText)
        assertEquals(2.toByte(), vitsBytes(files, "feibi")[0])
        assertEquals(9.toByte(), vitsBytes(files, "sanyueqi")[0])
        assertNoImportLeftovers(files, "feibi")
    }

    @Test
    fun brokenOverwriteKeepsTheOldCharacter() {
        val root = sandbox("repo-broken-overwrite-test")
        val files = filesIn(root)
        val store = InMemoryCharacterStore()
        val repo = CharacterRepository(store)
        val old = PackFixtures.zip(File(root, "feibi-old.zip"), "feibi", "菲比", fill = 1, refText = "旧原文")
        val broken = PackFixtures.zip(
            File(root, "feibi-broken.zip"), "feibi", "菲比", fill = 2, refText = "新原文",
            skip = "${CharacterPackSchema.MODELS_DIR}/vits_fp32.onnx",
        )
        repo.importZip(files, old.inputStream())

        assertThrows(AppFailure.PackIncomplete::class.java) { repo.importZip(files, broken.inputStream()) }

        assertEquals(1.toByte(), vitsBytes(files, "feibi")[0])
        assertEquals("旧原文", store.find("feibi")?.refText)
        assertNoImportLeftovers(files, "feibi")
    }

    @Test
    fun failedSaveRollsBackAnOverwrite() {
        val root = sandbox("repo-rollback-overwrite-test")
        val files = filesIn(root)
        val memory = InMemoryCharacterStore()
        val repo = CharacterRepository(FailUpsertAfter(memory, 1))
        val old = PackFixtures.zip(File(root, "feibi-old.zip"), "feibi", "菲比", fill = 1, refText = "旧原文")
        val fresh = PackFixtures.zip(File(root, "feibi-new.zip"), "feibi", "菲比", fill = 2, refText = "新原文")
        repo.importZip(files, old.inputStream())

        assertThrows(IllegalStateException::class.java) { repo.importZip(files, fresh.inputStream()) }

        assertEquals(1.toByte(), vitsBytes(files, "feibi")[0])
        assertEquals("旧原文", memory.find("feibi")?.refText)
        assertNoImportLeftovers(files, "feibi")
    }

    @Test
    fun failedSaveRemovesABrandNewCharacter() {
        val root = sandbox("repo-rollback-new-test")
        val files = filesIn(root)
        val memory = InMemoryCharacterStore()
        val repo = CharacterRepository(FailUpsertAfter(memory, 0))
        val packed = PackFixtures.zip(File(root, "feibi.zip"), "feibi", "菲比", fill = 2)

        assertThrows(IllegalStateException::class.java) { repo.importZip(files, packed.inputStream()) }

        assertFalse(File(CharacterPack.directory(files), "feibi").exists())
        assertEquals(emptyList<CharacterInfo>(), memory.list())
        assertNoImportLeftovers(files, "feibi")
    }

    @Test
    fun overwriteKeepsThePickedAvatar() {
        val root = sandbox("repo-avatar-overwrite-test")
        val files = filesIn(root)
        val store = InMemoryCharacterStore()
        val repo = CharacterRepository(store)
        val packed = PackFixtures.zip(File(root, "feibi.zip"), "feibi", "菲比")
        repo.importZip(files, packed.inputStream())
        store.setAvatar("feibi", 5)

        val again = repo.importZip(files, packed.inputStream())

        assertEquals(5, again.info.avatarIndex)
        assertEquals(5, store.find("feibi")?.avatarIndex)
    }

    @Test
    fun renameStoresTheTrimmedNameAndRejectsBlank() {
        val store = InMemoryCharacterStore()
        val repo = CharacterRepository(store)
        val info = CharacterInfo("feibi", "菲比", File("characters/feibi"), "原文")
        store.upsert(File("."), info)

        repo.rename(info, "  小菲 ")
        assertEquals("小菲", store.find("feibi")?.name)

        assertThrows(IllegalStateException::class.java) { repo.rename(info, "   ") }
        assertEquals("小菲", store.find("feibi")?.name)
    }

    @Test
    fun removeDeletesTheFolderAndTheRecordOfTheTargetsOnly() {
        val root = sandbox("repo-remove-test")
        val files = filesIn(root)
        val repo = CharacterRepository(InMemoryCharacterStore())
        val feibi = repo.importZip(files, PackFixtures.zip(File(root, "feibi.zip"), "feibi", "菲比").inputStream())
        repo.importZip(files, PackFixtures.zip(File(root, "sanyueqi.zip"), "sanyueqi", "三月七").inputStream())

        repo.remove(listOf(feibi.info))

        assertFalse(feibi.info.root.exists())
        assertNull(repo.find("feibi"))
        assertEquals(listOf("sanyueqi"), repo.list(files).map { it.id })
        assertTrue(CharacterPackSchema.manifestFile(File(CharacterPack.directory(files), "sanyueqi")).isFile)
    }

    @Test
    fun avatarIndexReusesAValidSavedChoice() {
        val store = InMemoryCharacterStore()
        store.upsert(File("."), CharacterInfo("feibi", "菲比", File("x"), "原文", avatarIndex = 3))

        val index = CharacterRepository(store).avatarIndex("feibi", 10) { error("不该再挑") }

        assertEquals(3, index)
    }

    @Test
    fun avatarIndexReplacesAnOutOfRangeChoiceAndRemembersIt() {
        val store = InMemoryCharacterStore()
        store.upsert(File("."), CharacterInfo("feibi", "菲比", File("x"), "原文", avatarIndex = 12))

        val index = CharacterRepository(store).avatarIndex("feibi", 10) { 4 }

        assertEquals(4, index)
        assertEquals(4, store.find("feibi")?.avatarIndex)
    }

    @Test
    fun avatarIndexPicksOnceAndStaysStable() {
        val store = InMemoryCharacterStore()
        store.upsert(File("."), CharacterInfo("feibi", "菲比", File("x"), "原文"))
        val repo = CharacterRepository(store)

        assertEquals(7, repo.avatarIndex("feibi", 10) { 7 })
        assertEquals(7, repo.avatarIndex("feibi", 10) { 0 })
        assertEquals(7, store.find("feibi")?.avatarIndex)
    }

    @Test
    fun packagedZipsImportAsTwoChoices() {
        if (System.getenv("GENIE_IMPORT_REAL_PACKS") != "1") return
        val dist = listOf(File("../../../dist"), File("../../../../dist")).firstOrNull { it.isDirectory }
            ?: return
        val feibi = File(dist, "feibi.zip")
        val sanyueqi = File(dist, "sanyueqi.zip")
        if (!feibi.isFile || !sanyueqi.isFile) return
        val root = sandbox("real-pack-import")
        val files = File(root, "files")
        val repo = CharacterRepository(InMemoryCharacterStore())
        val first = repo.importZip(files, feibi.inputStream())
        val second = repo.importZip(files, sanyueqi.inputStream())
        assertEquals(listOf("feibi", "sanyueqi"), repo.list(files).map { it.id })
        assertEquals("在此之前，请您务必继续享受旅居拉古那的时光。", first.info.refText)
        assertEquals("倒是不忙，可他不喜欢聊天", second.info.refText)
        assertTrue(File(files, "characters/feibi/models/vits_fp32.onnx").length() > 1_000_000)
        assertTrue(File(files, "characters/sanyueqi/models/vits_fp32.onnx").length() > 1_000_000)
        root.deleteRecursively()
    }
}
