package com.genie.tts

import java.io.File
import java.util.TreeMap

/** 只给 JVM 单测用。排序规则和 SQLite 版的 ORDER BY id 一致。 */
internal class InMemoryCharacterStore : CharacterStore {
    private val rows = TreeMap<String, CharacterInfo>()

    override fun attach(filesDir: File) = Unit

    override fun list(): List<CharacterInfo> = rows.values.toList()

    override fun find(id: String): CharacterInfo? = rows[id]

    override fun upsert(filesDir: File, info: CharacterInfo) {
        rows[info.id] = info
    }

    override fun rename(id: String, name: String) {
        rows[id]?.let { rows[id] = it.copy(name = name) }
    }

    override fun setAvatar(id: String, index: Int) {
        rows[id]?.let { rows[id] = it.copy(avatarIndex = index) }
    }

    override fun remove(id: String) {
        rows.remove(id)
    }
}
