package com.genie.tts

import java.io.File

/** 角色清单的存取。正式实现落在 SQLite，测试用内存实现替换。 */
internal interface CharacterStore {
    fun attach(filesDir: File)
    fun list(): List<CharacterInfo>
    fun find(id: String): CharacterInfo?
    fun upsert(filesDir: File, info: CharacterInfo)
    fun rename(id: String, name: String)
    fun setAvatar(id: String, index: Int)
    fun remove(id: String)
}
