package com.genie.tts.speech

import com.genie.tts.core.StateStore
import com.genie.tts.core.TaskLane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class ProsodyActionsTest {
    @Test
    fun downloadReportsProgressThenReleasesTheLane() {
        val harness = harness()
        harness.actions.download()
        assertEquals(1, harness.dropped)
        assertEquals(listOf(10L to 100L), harness.progress)
        assertTrue(harness.logs.any { it == "已下载韵律模型" })
        assertFalse(harness.actions.current().downloading)
        assertEquals(0L, harness.actions.current().downloadDone)
        assertFalse(harness.lane.isBusy())
        assertTrue(harness.notices.isEmpty())
    }

    @Test
    fun aBusyLaneRefusesDownloadImportAndDelete() {
        val harness = harness()
        harness.lane.occupy()
        harness.actions.download()
        harness.actions.import("model.onnx") { ByteArrayInputStream(byteArrayOf()) }
        harness.actions.delete()
        assertEquals(0, harness.dropped)
        assertTrue(harness.imported.isEmpty())
        assertEquals(0, harness.deleted)
        assertFalse(harness.actions.current().downloading)
    }

    @Test
    fun aFailedDownloadNotifiesAndReleasesTheLane() {
        val harness = harness(download = { error("下载失败（404）") })
        harness.actions.download()
        assertEquals(listOf("下载失败（404）"), harness.notices)
        assertTrue(harness.logs.any { it == "下载失败 下载失败（404）" })
        assertFalse(harness.actions.current().downloading)
        assertFalse(harness.lane.isBusy())
    }

    @Test
    fun importDropsTheLoadedModelAndLogs() {
        val harness = harness()
        var opened = false
        harness.actions.import("prosody.zip") {
            opened = true
            ByteArrayInputStream(byteArrayOf(1))
        }
        assertTrue(opened)
        assertEquals(listOf("prosody.zip"), harness.imported)
        assertEquals(1, harness.dropped)
        assertTrue(harness.logs.any { it == "已导入韵律模型" })
        assertFalse(harness.lane.isBusy())
    }

    @Test
    fun aFailedImportNotifiesWithoutLoggingSuccess() {
        val harness = harness()
        harness.actions.import("prosody.zip") { error("打不开这个文件") }
        assertEquals(listOf("打不开这个文件"), harness.notices)
        assertTrue(harness.imported.isEmpty())
        assertTrue(harness.logs.any { it == "导入失败 打不开这个文件" })
        assertFalse(harness.lane.isBusy())
    }

    @Test
    fun deleteDropsTheLoadedModelAndLogs() {
        val harness = harness()
        harness.actions.delete()
        assertEquals(1, harness.deleted)
        assertEquals(1, harness.dropped)
        assertTrue(harness.logs.any { it == "已删除韵律模型" })
        assertFalse(harness.lane.isBusy())
    }

    private fun harness(
        download: ((Long, Long) -> Unit) -> Unit = { onProgress ->
            onProgress(10, 100)
        },
    ): Harness = Harness(download)

    private class Harness(download: ((Long, Long) -> Unit) -> Unit) {
        val lane = TaskLane(StateStore(false) { it() })
        val logs = mutableListOf<String>()
        val notices = mutableListOf<String>()
        val progress = mutableListOf<Pair<Long, Long>>()
        val imported = mutableListOf<String>()
        var dropped = 0
        var deleted = 0
        val actions = ProsodyActions(
            filesDir = File("prosody"),
            lane = lane,
            dropProsody = { dropped += 1 },
            downloadPack = { onProgress ->
                download { done, total ->
                    progress += done to total
                    onProgress(done, total)
                }
            },
            importPack = { _, name -> imported += name },
            deletePack = { deleted += 1 },
            readyCheck = { false },
            notice = { notices += it },
            log = { _, message -> logs += message },
            runAsync = { it() },
            post = { it() },
        )
    }
}
