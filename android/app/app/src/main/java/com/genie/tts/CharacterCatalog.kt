package com.genie.tts

import android.database.sqlite.SQLiteDatabase
import java.io.File

internal object CharacterCatalog : CharacterStore {
    private val gate = Any()
    private var database: SQLiteDatabase? = null
    private var directory: File? = null
    private var rows = emptyList<CharacterInfo>()

    override fun attach(filesDir: File) {
        synchronized(gate) {
            if (database != null && directory == filesDir) return
            CharacterPack.migrateLegacy(filesDir)
            val file = File(filesDir, "characters.db")
            val opened = SQLiteDatabase.openOrCreateDatabase(file, null)
            opened.enableWriteAheadLogging()
            opened.execSQL(
                """
                CREATE TABLE IF NOT EXISTS characters (
                    id TEXT PRIMARY KEY NOT NULL,
                    name TEXT NOT NULL,
                    sample_rate INTEGER NOT NULL,
                    ref_text TEXT NOT NULL,
                    root TEXT NOT NULL,
                    avatar_index INTEGER
                )
                """.trimIndent(),
            )
            opened.execSQL(
                """
                CREATE TABLE IF NOT EXISTS meta (
                    key TEXT PRIMARY KEY NOT NULL,
                    value TEXT NOT NULL
                )
                """.trimIndent(),
            )
            database = opened
            directory = filesDir
            if (!flag(opened, MIGRATED)) {
                migrate(filesDir, opened)
                setFlag(opened, MIGRATED)
            }
            rows = load(opened)
            if (!flag(opened, NAMES_CLEARED)) {
                rows.forEach { forgetNameFiles(it.root) }
                setFlag(opened, NAMES_CLEARED)
            }
        }
    }

    override fun list(): List<CharacterInfo> = synchronized(gate) { rows.toList() }

    override fun find(id: String): CharacterInfo? = synchronized(gate) { rows.find { it.id == id } }

    override fun upsert(filesDir: File, info: CharacterInfo) {
        synchronized(gate) {
            attach(filesDir)
            val opened = database ?: return
            opened.execSQL(
                """
                INSERT INTO characters (id, name, sample_rate, ref_text, root, avatar_index)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    name = excluded.name,
                    sample_rate = excluded.sample_rate,
                    ref_text = excluded.ref_text,
                    root = excluded.root,
                    avatar_index = excluded.avatar_index
                """.trimIndent(),
                arrayOf(info.id, info.name, info.sampleRate, info.refText, info.root.absolutePath, info.avatarIndex),
            )
            rows = load(opened)
        }
    }

    override fun rename(id: String, name: String) {
        synchronized(gate) {
            val opened = database ?: error("角色表还没有打开")
            opened.execSQL("UPDATE characters SET name = ? WHERE id = ?", arrayOf(name, id))
            rows = rows.map { if (it.id == id) it.copy(name = name) else it }
        }
    }

    override fun setAvatar(id: String, index: Int) {
        synchronized(gate) {
            val opened = database ?: return
            opened.execSQL("UPDATE characters SET avatar_index = ? WHERE id = ?", arrayOf(index, id))
            rows = rows.map { if (it.id == id) it.copy(avatarIndex = index) else it }
        }
    }

    override fun remove(id: String) {
        synchronized(gate) {
            val opened = database ?: return
            opened.execSQL("DELETE FROM characters WHERE id = ?", arrayOf(id))
            rows = rows.filterNot { it.id == id }
        }
    }

    private fun migrate(filesDir: File, opened: SQLiteDatabase) {
        val found = CharacterPack.scanDisk(filesDir)
        opened.beginTransaction()
        try {
            found.forEach { info ->
                opened.execSQL(
                    """
                    INSERT OR REPLACE INTO characters (id, name, sample_rate, ref_text, root, avatar_index)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    arrayOf(info.id, info.name, info.sampleRate, info.refText, info.root.absolutePath, info.avatarIndex),
                )
            }
            opened.setTransactionSuccessful()
        } finally {
            opened.endTransaction()
        }
    }

    private fun load(opened: SQLiteDatabase): List<CharacterInfo> {
        opened.rawQuery(
            "SELECT id, name, sample_rate, ref_text, root, avatar_index FROM characters ORDER BY id",
            null,
        ).use { cursor ->
            val loaded = ArrayList<CharacterInfo>()
            while (cursor.moveToNext()) {
                val avatar = if (cursor.isNull(5)) null else cursor.getInt(5)
                loaded.add(
                    CharacterInfo(
                        id = cursor.getString(0),
                        name = cursor.getString(1),
                        root = File(cursor.getString(4)),
                        refText = cursor.getString(3),
                        sampleRate = cursor.getInt(2).coerceAtLeast(1),
                        avatarIndex = avatar,
                    ),
                )
            }
            return loaded
        }
    }

    private fun flag(opened: SQLiteDatabase, key: String): Boolean {
        opened.rawQuery("SELECT value FROM meta WHERE key = ?", arrayOf(key)).use { cursor ->
            return cursor.moveToFirst() && cursor.getString(0) == "1"
        }
    }

    private fun setFlag(opened: SQLiteDatabase, key: String) {
        opened.execSQL("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)", arrayOf(key, "1"))
    }

    private fun forgetNameFiles(root: File) {
        File(root, "display-name.txt").delete()
        File(root, "avatar-icon.txt").delete()
    }

    private const val MIGRATED = "migrated"
    private const val NAMES_CLEARED = "names_cleared"
}
