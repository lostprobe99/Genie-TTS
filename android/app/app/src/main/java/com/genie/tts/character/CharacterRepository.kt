package com.genie.tts.character

import java.io.File
import java.io.InputStream
import kotlin.random.Random

/**
 * 角色文件和角色清单的唯一入口。导入、改名、删除、头像和查询都从这里走,
 * 界面和服务不再各自去动目录或数据库。
 */
internal class CharacterRepository(private val store: CharacterStore) {
    fun attach(filesDir: File) = store.attach(filesDir)

    fun list(filesDir: File): List<CharacterInfo> {
        store.attach(filesDir)
        return store.list()
    }

    fun all(): List<CharacterInfo> = store.list()

    fun find(id: String): CharacterInfo? = store.find(id)

    fun importZip(filesDir: File, input: InputStream, displayName: String? = null): ImportResult {
        val staging = File(filesDir, STAGING).apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val staged = CharacterPack.stage(input, staging)
            val info = staged.info
            val dest = File(CharacterPack.directory(filesDir), info.id)
            val replaced = dest.isDirectory
            val previous = store.find(info.id)
            val backup = File(filesDir, "$BACKUP_PREFIX${info.id}")
            if (replaced) {
                backup.deleteRecursively()
                if (!dest.renameTo(backup)) error("无法替换已有角色 ${info.id}")
            } else {
                dest.parentFile?.mkdirs()
            }
            if (!staged.root.renameTo(dest)) {
                if (replaced) backup.renameTo(dest)
                error("无法保存角色 ${info.id}")
            }
            val chosen = displayName?.trim()?.takeIf { it.isNotEmpty() } ?: previous?.name?.trim()?.takeIf { it.isNotEmpty() }
            val base = CharacterPack.read(dest)
            val avatar = if (CharacterPack.avatarImage(dest) != null) null else previous?.avatarIndex ?: base.avatarIndex
            val stored = base.copy(name = chosen ?: base.name, avatarIndex = avatar)
            File(dest, CharacterPackSchema.LEGACY_NAME_FILE).delete()
            File(dest, CharacterPackSchema.LEGACY_AVATAR_FILE).delete()
            store.upsert(filesDir, stored)
            backup.deleteRecursively()
            staging.deleteRecursively()
            return ImportResult(stored, replaced)
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
    }

    fun rename(info: CharacterInfo, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) error("名字不能是空的")
        store.rename(info.id, trimmed)
    }

    /** 删除目录,目录确实没了才去掉清单里的记录。 */
    fun remove(targets: List<CharacterInfo>) {
        targets.forEach {
            val gone = it.root.deleteRecursively() || !it.root.exists()
            if (gone) store.remove(it.id)
        }
    }

    /** 已记的头像序号在范围内就用它,否则随机挑一个并记下来。 */
    fun avatarIndex(id: String, choices: Int, pick: (Int) -> Int = { Random.nextInt(it) }): Int {
        val saved = store.find(id)?.avatarIndex
        if (saved != null && saved in 0 until choices) return saved
        val chosen = pick(choices)
        store.setAvatar(id, chosen)
        return chosen
    }

    companion object {
        private const val STAGING = "import-staging"
        private const val BACKUP_PREFIX = "import-backup-"

        val shared: CharacterRepository by lazy { CharacterRepository(SqliteCharacterStore(CharacterPack)) }
    }
}
