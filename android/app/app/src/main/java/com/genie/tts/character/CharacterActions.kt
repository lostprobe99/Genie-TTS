package com.genie.tts.character

import com.genie.tts.core.LogLevel
import com.genie.tts.core.LogModule
import com.genie.tts.core.LogSink
import com.genie.tts.core.StateStore
import com.genie.tts.core.TaskLane
import com.genie.tts.core.invoke
import com.genie.tts.core.moduleLog
import com.genie.tts.core.uiText
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** 角色页这一刻的名单、当前角色和批量选择。导入预览用过一次就清掉。 */
internal data class CharacterState(
    val roster: List<CharacterInfo> = emptyList(),
    val selectedId: String? = null,
    val batching: Boolean = false,
    val picked: Set<String> = emptySet(),
    val inspecting: Boolean = false,
    val offer: ImportOffer? = null,
    val alert: String? = null,
    val alertId: Long = 0,
)

/** 角色包已经看过，等界面打开确认页。[key] 是调用方自己认得的文件。 */
internal data class ImportOffer(
    val key: Any,
    val preview: PackPreview,
    val fileName: String,
    val fileBytes: Long,
    val seq: Long,
)

internal enum class BatchStart { Started, Busy, Empty }

internal enum class SaveStart { Started, EmptyName, Busy, Unchanged }

/**
 * 角色名单、当前角色、导入、删除和改名。
 * 界面上已经有别的耗时事情时，这次直接放弃。
 */
internal class CharacterActions(
    private val filesDir: File,
    private val directory: CharacterDirectory,
    private val lane: TaskLane,
    private val invalidateAudio: () -> Unit,
    private val dropEngine: () -> Unit,
    private val dropIfLoaded: (String) -> Unit,
    private val stopPreview: (Set<String>) -> Unit,
    private val log: LogSink = moduleLog(LogModule.CHARACTER),
    private val runAsync: (() -> Unit) -> Unit = { task -> executor.execute(task) },
    post: (() -> Unit) -> Unit = { block ->
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    },
) {
    private val store = StateStore(CharacterState(), post)
    private val offers = AtomicLong(0)

    fun current(): CharacterState = store.current()

    fun listen(listener: (CharacterState) -> Unit): () -> Unit = store.listen(listener)

    fun avatarIndex(id: String, choices: Int): Int = directory.avatarIndex(id, choices)

    fun refresh() {
        val saved = selectionFile().takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null }
        reload(saved)
    }

    /** 看角色包。已经在看，或者界面正忙，就放弃。 */
    fun inspect(key: Any, fileName: String, fileBytes: Long, read: () -> PackPreview) {
        val now = store.current()
        if (lane.isBusy() || now.inspecting) return
        store.update { it.copy(inspecting = true) }
        runAsync {
            try {
                val preview = read()
                val offer = ImportOffer(key, preview, fileName, fileBytes, offers.incrementAndGet())
                store.update { it.copy(inspecting = false, offer = offer) }
            } catch (error: Exception) {
                store.update {
                    it.copy(
                        inspecting = false,
                        alert = uiText(error),
                        alertId = it.alertId + 1,
                    )
                }
            }
        }
    }

    fun consumeOffer(): ImportOffer? {
        val offer = store.current().offer ?: return null
        store.update { it.copy(offer = null) }
        return offer
    }

    fun import(name: String, open: () -> InputStream) {
        if (!lane.tryOccupy()) return
        runAsync {
            try {
                val result = open().use { directory.importZip(filesDir, it, name) }
                dropIfLoaded(result.info.id)
                val action = if (result.replaced) "已更新" else "已导入"
                log(LogLevel.INFO, "$action ${result.info.id}")
                reload(store.current().selectedId)
            } catch (error: Exception) {
                log(LogLevel.ERROR, "导入失败 ${detail(error)}", error)
                alert(error)
            } finally {
                lane.release()
            }
        }
    }

    fun remove(targets: List<CharacterInfo>) {
        if (targets.isEmpty() || !lane.tryOccupy()) return
        val ids = targets.map { it.id }.toSet()
        val dropsSelection = store.current().selectedId in ids
        stopPreview(ids)
        runAsync {
            try {
                if (dropsSelection) dropEngine()
                directory.remove(targets)
                log(LogLevel.INFO, "已删除 ${targets.joinToString("、") { it.id }}")
                if (dropsSelection) {
                    selectionFile().delete()
                    invalidateAudio()
                }
                reload(if (dropsSelection) null else store.current().selectedId)
            } catch (error: Exception) {
                log(LogLevel.ERROR, "删除失败 ${detail(error)}", error)
                alert(error)
            } finally {
                lane.release()
            }
        }
    }

    fun save(info: CharacterInfo, typed: String, makeCurrent: Boolean): SaveStart {
        val name = typed.trim()
        if (name.isEmpty()) return SaveStart.EmptyName
        if (!lane.tryOccupy()) return SaveStart.Busy
        val nameChanged = name != info.name
        val wasCurrent = store.current().selectedId == info.id
        val selectionChanged = makeCurrent != wasCurrent
        if (!nameChanged && !selectionChanged) {
            lane.release()
            return SaveStart.Unchanged
        }
        val next = when {
            !selectionChanged -> store.current().selectedId
            makeCurrent -> info.id
            else -> null
        }
        runAsync {
            try {
                if (nameChanged) {
                    directory.rename(info, name)
                    log(LogLevel.INFO, "${info.id} 显示名改为 $name")
                }
                if (selectionChanged) {
                    writeSelection(next)
                    invalidateAudio()
                }
                reload(next)
                if (selectionChanged) dropEngine()
            } catch (error: Exception) {
                log(LogLevel.ERROR, "改名失败 ${info.id} ${detail(error)}", error)
                alert(error)
            } finally {
                lane.release()
            }
        }
        return SaveStart.Started
    }

    fun select(id: String?) {
        if (id == store.current().selectedId) return
        writeSelection(id)
        store.update { it.copy(selectedId = id) }
        invalidateAudio()
        runAsync { dropEngine() }
    }

    fun enterBatch(): BatchStart {
        val now = store.current()
        if (lane.isBusy()) return BatchStart.Busy
        if (now.roster.isEmpty()) return BatchStart.Empty
        store.update { it.copy(batching = true, picked = emptySet()) }
        return BatchStart.Started
    }

    fun exitBatch() {
        if (!store.current().batching) return
        store.update { it.copy(batching = false, picked = emptySet()) }
    }

    fun leaveBatch() {
        val now = store.current()
        if (!now.batching && now.picked.isEmpty()) return
        store.update { it.copy(batching = false, picked = emptySet()) }
    }

    fun togglePicked(id: String) {
        store.update { state ->
            val picked = state.picked.toMutableSet()
            if (!picked.add(id)) picked.remove(id)
            state.copy(picked = picked)
        }
    }

    fun toggleShown(shownIds: List<String>) {
        store.update { state ->
            val picked = state.picked.toMutableSet()
            if (shownIds.isNotEmpty() && shownIds.all { it in picked }) picked.removeAll(shownIds.toSet())
            else picked.addAll(shownIds)
            state.copy(picked = picked)
        }
    }

    private fun reload(selected: String?) {
        val roster = directory.list(filesDir)
        val kept = selected?.takeIf { id -> roster.any { it.id == id } }
        if (kept == null) selectionFile().delete()
        store.update { state ->
            state.copy(
                roster = roster,
                selectedId = kept,
                picked = state.picked.filter { id -> roster.any { it.id == id } }.toSet(),
                batching = state.batching && roster.isNotEmpty(),
            )
        }
    }

    private fun writeSelection(id: String?) {
        if (id == null) selectionFile().delete() else selectionFile().writeText(id)
    }

    private fun alert(error: Exception) {
        store.update { it.copy(alert = uiText(error), alertId = it.alertId + 1) }
    }

    private fun detail(error: Exception): String = error.message?.trim().orEmpty().ifBlank { "失败" }

    private fun selectionFile() = File(filesDir, "selected-character.txt")

    private companion object {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "genie-roles").apply { isDaemon = true }
        }
    }
}
