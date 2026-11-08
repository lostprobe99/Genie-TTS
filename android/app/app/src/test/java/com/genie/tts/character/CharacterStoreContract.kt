package com.genie.tts.character

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** 任何 CharacterStore 实现都要满足的行为。换一种实现，只需继承并给出 create()。 */
internal abstract class CharacterStoreContract {
    abstract fun create(): CharacterStore

    private val files = File("build/character-store-contract")

    private fun info(
        id: String,
        name: String = id,
        refText: String = "参考$id",
        sampleRate: Int = 32000,
        avatarIndex: Int? = null,
    ) = CharacterInfo(id, name, File(files, "characters/$id"), refText, sampleRate, avatarIndex)

    private fun store(vararg initial: CharacterInfo): CharacterStore {
        val store = create()
        store.attach(files)
        initial.forEach { store.upsert(files, it) }
        return store
    }

    @Test
    fun listIsSortedById() {
        val store = store(info("sanyueqi"), info("feibi"), info("aaa"))
        assertEquals(listOf("aaa", "feibi", "sanyueqi"), store.list().map { it.id })
    }

    @Test
    fun upsertReplacesTheSameId() {
        val store = store(info("feibi", name = "菲比", refText = "旧原文", sampleRate = 32000, avatarIndex = 2))
        store.upsert(files, info("feibi", name = "小菲", refText = "新原文", sampleRate = 44100, avatarIndex = null))
        val only = store.list().single()
        assertEquals("小菲", only.name)
        assertEquals("新原文", only.refText)
        assertEquals(44100, only.sampleRate)
        assertNull(only.avatarIndex)
    }

    @Test
    fun findReturnsNullForUnknownId() {
        val store = store(info("feibi"))
        assertNull(store.find("missing"))
        assertEquals("feibi", store.find("feibi")?.id)
    }

    @Test
    fun renameChangesOnlyTheNameOfThatCharacter() {
        val store = store(
            info("feibi", name = "菲比", refText = "原文", avatarIndex = 3),
            info("sanyueqi", name = "三月七"),
        )
        store.rename("feibi", "小菲")
        val feibi = store.find("feibi")!!
        assertEquals("小菲", feibi.name)
        assertEquals("原文", feibi.refText)
        assertEquals(3, feibi.avatarIndex)
        assertEquals("三月七", store.find("sanyueqi")?.name)
    }

    @Test
    fun renameOfUnknownIdChangesNothing() {
        val store = store(info("feibi", name = "菲比"))
        store.rename("missing", "谁")
        assertEquals(listOf("菲比"), store.list().map { it.name })
    }

    @Test
    fun setAvatarKeepsTheOtherFields() {
        val store = store(info("feibi", name = "菲比", refText = "原文"))
        store.setAvatar("feibi", 7)
        val feibi = store.find("feibi")!!
        assertEquals(7, feibi.avatarIndex)
        assertEquals("菲比", feibi.name)
        assertEquals("原文", feibi.refText)
    }

    @Test
    fun removeDropsOnlyThatCharacter() {
        val store = store(info("feibi"), info("sanyueqi"))
        store.remove("feibi")
        assertEquals(listOf("sanyueqi"), store.list().map { it.id })
        store.remove("missing")
        assertEquals(listOf("sanyueqi"), store.list().map { it.id })
    }
}
