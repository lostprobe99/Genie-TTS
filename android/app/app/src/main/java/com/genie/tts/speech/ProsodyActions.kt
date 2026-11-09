package com.genie.tts.speech

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

/** 韵律下载进行到哪。文件在不在，界面打开时自己看磁盘。 */
internal data class ProsodyState(
    val downloading: Boolean = false,
    val downloadDone: Long = 0,
    val downloadTotal: Long = 0,
)

/**
 * 韵律模型的下载、导入和删除。
 * 界面上已经有别的耗时事情时，这次直接放弃。
 */
internal class ProsodyActions(
    private val filesDir: File,
    private val lane: TaskLane,
    private val dropProsody: () -> Unit,
    private val downloadPack: ((Long, Long) -> Unit) -> Unit = { onProgress ->
        ProsodyPack.download(filesDir, onProgress)
    },
    private val importPack: (InputStream, String) -> Unit = { input, name ->
        ProsodyPack.importFile(filesDir, input, name)
    },
    private val deletePack: () -> Unit = { ProsodyPack.delete(filesDir) },
    private val readyCheck: () -> Boolean = { Prosody.isReady(filesDir) },
    private val notice: (String) -> Unit,
    private val log: LogSink = moduleLog(LogModule.PROSODY),
    private val runAsync: (() -> Unit) -> Unit = { task -> executor.execute(task) },
    post: (() -> Unit) -> Unit = { block ->
        android.os.Handler(android.os.Looper.getMainLooper()).post(block)
    },
) {
    private val store = StateStore(ProsodyState(), post)

    fun current(): ProsodyState = store.current()

    fun listen(listener: (ProsodyState) -> Unit): () -> Unit = store.listen(listener)

    fun ready(): Boolean = readyCheck()

    fun download() {
        if (!lane.tryOccupy()) return
        store.update { it.copy(downloading = true, downloadDone = 0, downloadTotal = 0) }
        runAsync {
            try {
                dropProsody()
                downloadPack { done, total ->
                    store.update { it.copy(downloading = true, downloadDone = done, downloadTotal = total) }
                }
                log(LogLevel.INFO, "已下载韵律模型")
            } catch (error: Exception) {
                log(LogLevel.ERROR, "下载失败 ${detail(error)}", error)
                notice(uiText(error))
            } finally {
                finish()
            }
        }
    }

    fun import(name: String, open: () -> InputStream) {
        if (!lane.tryOccupy()) return
        runAsync {
            try {
                dropProsody()
                open().use { importPack(it, name) }
                log(LogLevel.INFO, "已导入韵律模型")
            } catch (error: Exception) {
                log(LogLevel.ERROR, "导入失败 ${detail(error)}", error)
                notice(uiText(error))
            } finally {
                finish()
            }
        }
    }

    fun delete() {
        if (!lane.tryOccupy()) return
        runAsync {
            try {
                dropProsody()
                deletePack()
                log(LogLevel.INFO, "已删除韵律模型")
            } catch (error: Exception) {
                log(LogLevel.ERROR, "删除失败 ${detail(error)}", error)
                notice(uiText(error))
            } finally {
                finish()
            }
        }
    }

    private fun finish() {
        store.update { ProsodyState() }
        lane.release()
    }

    private fun detail(error: Exception): String = error.message?.trim().orEmpty().ifBlank { "失败" }

    private companion object {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "genie-prosody").apply { isDaemon = true }
        }
    }
}
