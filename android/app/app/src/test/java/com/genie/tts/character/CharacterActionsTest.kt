package com.genie.tts.character

import com.genie.tts.core.StateStore
import com.genie.tts.core.TaskLane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files

class CharacterActionsTest {
    @Test
    fun importKeepsTheSelectionAndReleasesTheLane() {
        val harness = harness()
        harness.directory.items["a"] = info("a")
        harness.actions.refresh()
        harness.actions.select("a")
        harness.dropped.clear()
        harness.directory.incomingId = "c"
        harness.actions.import("丙") { ByteArrayInputStream(byteArrayOf()) }
        assertEquals(listOf("c"), harness.dropped)
        assertTrue(harness.logs.any { it == "已导入 c" })
        assertEquals("a", harness.actions.current().selectedId)
        assertEquals("丙", harness.actions.current().roster.first { it.id == "c" }.name)
        assertFalse(harness.lane.isBusy())
    }

    @Test
    fun replacingACharacterLogsAnUpdate() {
        val harness = harness()
        harness.directory.items["a"] = info("a", "旧")
        harness.directory.incomingId = "a"
        harness.actions.import("新") { ByteArrayInputStream(byteArrayOf()) }
        assertTrue(harness.logs.any { it == "已更新 a" })
        assertEquals("新", harness.actions.current().roster.single().name)
        assertEquals(listOf("a"), harness.dropped)
    }

    @Test
    fun aBusyLaneRefusesImport() {
        val harness = harness()
        harness.lane.occupy()
        var opened = false
        harness.actions.import("甲") {
            opened = true
            ByteArrayInputStream(byteArrayOf())
        }
        assertFalse(opened)
        assertTrue(harness.dropped.isEmpty())
        assertTrue(harness.lane.isBusy())
    }

    @Test
    fun aFailedImportReleasesTheLaneAndShowsTheMessage() {
        val harness = harness()
        harness.directory.importError = IllegalStateException("打不开这个文件")
        harness.actions.import("甲") { ByteArrayInputStream(byteArrayOf()) }
        assertFalse(harness.lane.isBusy())
        assertTrue(harness.dropped.isEmpty())
        assertEquals("打不开这个文件", harness.actions.current().alert)
        assertTrue(harness.logs.any { it == "导入失败 打不开这个文件" })
    }

    @Test
    fun removingTheSelectedCharacterClearsIt() {
        val harness = harness()
        val selected = info("a")
        harness.directory.items["a"] = selected
        harness.directory.items["b"] = info("b")
        harness.actions.refresh()
        harness.actions.select("a")
        harness.invalidated.set(0)
        harness.droppedEngine.set(0)
        harness.actions.remove(listOf(selected))
        assertNull(harness.actions.current().selectedId)
        assertEquals(1, harness.droppedEngine.get())
        assertEquals(1, harness.invalidated.get())
        assertFalse(harness.selectionFile().isFile)
        assertEquals(listOf("b"), harness.actions.current().roster.map { it.id })
        assertEquals(setOf("a"), harness.stopped.single())
        assertTrue(harness.logs.any { it == "已删除 a" })
        assertFalse(harness.lane.isBusy())
    }

    @Test
    fun removingAnotherCharacterLeavesTheSelection() {
        val harness = harness()
        harness.directory.items["a"] = info("a")
        val other = info("b")
        harness.directory.items["b"] = other
        harness.actions.refresh()
        harness.actions.select("a")
        harness.invalidated.set(0)
        harness.droppedEngine.set(0)
        harness.actions.remove(listOf(other))
        assertEquals("a", harness.actions.current().selectedId)
        assertEquals(0, harness.droppedEngine.get())
        assertEquals(0, harness.invalidated.get())
        assertEquals("a", harness.selectionFile().readText())
        assertEquals(setOf("b"), harness.stopped.single())
    }

    @Test
    fun anEmptyNameDoesNotOccupyTheLane() {
        val harness = harness()
        val role = info("a")
        assertEquals(SaveStart.EmptyName, harness.actions.save(role, "  ", true))
        assertFalse(harness.lane.isBusy())
        assertTrue(harness.lane.tryOccupy())
    }

    @Test
    fun anUnchangedSaveReleasesTheLane() {
        val harness = harness()
        val role = info("a")
        harness.directory.items["a"] = role
        harness.actions.refresh()
        harness.actions.select("a")
        harness.droppedEngine.set(0)
        assertEquals(SaveStart.Unchanged, harness.actions.save(role, role.name, true))
        assertFalse(harness.lane.isBusy())
        assertEquals(0, harness.droppedEngine.get())
        assertTrue(harness.logs.isEmpty())
    }

    @Test
    fun renamingUpdatesTheRoster() {
        val harness = harness()
        val role = info("a", "甲")
        harness.directory.items["a"] = role
        harness.actions.refresh()
        assertEquals(SaveStart.Started, harness.actions.save(role, "新名", false))
        assertEquals("新名", harness.actions.current().roster.single().name)
        assertTrue(harness.logs.any { it == "a 显示名改为 新名" })
        assertEquals(0, harness.invalidated.get())
        assertEquals(0, harness.droppedEngine.get())
        assertFalse(harness.lane.isBusy())
    }

    @Test
    fun turningOffTheCurrentSelectionDropsTheEngine() {
        val harness = harness()
        val role = info("a")
        harness.directory.items["a"] = role
        harness.actions.refresh()
        harness.actions.select("a")
        harness.invalidated.set(0)
        harness.droppedEngine.set(0)
        assertEquals(SaveStart.Started, harness.actions.save(role, role.name, false))
        assertNull(harness.actions.current().selectedId)
        assertEquals(1, harness.invalidated.get())
        assertEquals(1, harness.droppedEngine.get())
        assertFalse(harness.selectionFile().isFile)
    }

    @Test
    fun aBusyLaneRefusesSave() {
        val harness = harness()
        harness.lane.occupy()
        assertEquals(SaveStart.Busy, harness.actions.save(info("a"), "新名", false))
        assertTrue(harness.directory.items.isEmpty())
    }

    @Test
    fun inspectIsIgnoredWhileBusyOrAlreadyInspecting() {
        val harness = harness()
        harness.lane.occupy()
        harness.actions.inspect("one", "a.zip", 1) { preview("a") }
        assertFalse(harness.actions.current().inspecting)
        assertNull(harness.actions.current().offer)
        harness.lane.release()
        harness.async = false
        harness.actions.inspect("one", "a.zip", 1) { preview("a") }
        assertTrue(harness.actions.current().inspecting)
        harness.actions.inspect("two", "b.zip", 2) { preview("b") }
        assertEquals(1, harness.queued.size)
        harness.queued.removeFirst().invoke()
        assertEquals("one", harness.actions.current().offer?.key)
    }

    @Test
    fun aFailedInspectRaisesAnAlert() {
        val harness = harness()
        harness.actions.inspect("one", "a.zip", 1) { error("打不开这个文件") }
        assertFalse(harness.actions.current().inspecting)
        assertNull(harness.actions.current().offer)
        assertEquals("打不开这个文件", harness.actions.current().alert)
    }

    @Test
    fun aSuccessfulInspectIsConsumedOnce() {
        val harness = harness()
        val key = Any()
        harness.actions.inspect(key, "a.zip", 12) { preview("a") }
        val offer = harness.actions.consumeOffer()
        assertEquals(key, offer?.key)
        assertEquals("a.zip", offer?.fileName)
        assertEquals(12L, offer?.fileBytes)
        assertEquals("a", offer?.preview?.id)
        assertNull(harness.actions.current().offer)
        assertNull(harness.actions.consumeOffer())
    }

    @Test
    fun batchStartsOnlyWhenThereAreRolesAndTheLaneIsFree() {
        val harness = harness()
        harness.lane.occupy()
        assertEquals(BatchStart.Busy, harness.actions.enterBatch())
        harness.lane.release()
        assertEquals(BatchStart.Empty, harness.actions.enterBatch())
        harness.directory.items["a"] = info("a")
        harness.directory.items["b"] = info("b")
        harness.actions.refresh()
        assertEquals(BatchStart.Started, harness.actions.enterBatch())
        assertTrue(harness.actions.current().batching)
        harness.actions.toggleShown(listOf("a", "b"))
        assertEquals(setOf("a", "b"), harness.actions.current().picked)
        harness.actions.toggleShown(listOf("a"))
        assertEquals(setOf("b"), harness.actions.current().picked)
        harness.actions.leaveBatch()
        assertFalse(harness.actions.current().batching)
        assertTrue(harness.actions.current().picked.isEmpty())
        harness.actions.leaveBatch()
    }

    @Test
    fun removingTheLastRoleLeavesTheBatch() {
        val harness = harness()
        val only = info("a")
        harness.directory.items["a"] = only
        harness.actions.refresh()
        harness.actions.enterBatch()
        harness.actions.togglePicked("a")
        harness.actions.remove(listOf(only))
        assertFalse(harness.actions.current().batching)
        assertTrue(harness.actions.current().picked.isEmpty())
        assertTrue(harness.actions.current().roster.isEmpty())
    }

    @Test
    fun refreshRestoresTheSelectionWithoutDroppingTheEngine() {
        val harness = harness()
        harness.directory.items["a"] = info("a")
        harness.selectionFile().writeText("a")
        harness.actions.refresh()
        assertEquals("a", harness.actions.current().selectedId)
        assertEquals(0, harness.invalidated.get())
        assertEquals(0, harness.droppedEngine.get())
        assertEquals("a", harness.selectionFile().readText())
        harness.selectionFile().writeText("gone")
        harness.actions.refresh()
        assertNull(harness.actions.current().selectedId)
        assertFalse(harness.selectionFile().isFile)
    }

    private fun info(id: String, name: String = "甲") = CharacterInfo(id, name, File(id), "参考")

    private fun preview(id: String) = PackPreview(id, "甲", "参考", null)

    private fun harness(): Harness = Harness()

    private class Harness {
        val directory = MemoryDirectory()
        val lane = TaskLane(StateStore(false) { it() })
        val logs = mutableListOf<String>()
        val dropped = mutableListOf<String>()
        val droppedEngine = java.util.concurrent.atomic.AtomicInteger()
        val invalidated = java.util.concurrent.atomic.AtomicInteger()
        val stopped = mutableListOf<Set<String>>()
        val queued = mutableListOf<() -> Unit>()
        var async = true
        private val files = Files.createTempDirectory("roles").toFile()
        val actions = CharacterActions(
            filesDir = files,
            directory = directory,
            lane = lane,
            invalidateAudio = { invalidated.incrementAndGet() },
            dropEngine = { droppedEngine.incrementAndGet() },
            dropIfLoaded = { dropped += it },
            stopPreview = { stopped += it },
            log = { _, message -> logs += message },
            runAsync = { task -> if (async) task() else queued += task },
            post = { it() },
        )

        fun selectionFile() = File(files, "selected-character.txt")
    }

    private class MemoryDirectory : CharacterDirectory {
        val items = linkedMapOf<String, CharacterInfo>()
        var incomingId = "new"
        var importError: Exception? = null

        override fun list(filesDir: File): List<CharacterInfo> = items.values.toList()

        override fun importZip(filesDir: File, input: InputStream, displayName: String?): ImportResult {
            importError?.let { throw it }
            input.close()
            val replaced = items.containsKey(incomingId)
            val name = displayName?.trim()?.takeIf { it.isNotEmpty() } ?: items[incomingId]?.name ?: incomingId
            val info = CharacterInfo(incomingId, name, File(incomingId), "参考")
            items[incomingId] = info
            return ImportResult(info, replaced)
        }

        override fun rename(info: CharacterInfo, name: String) {
            val current = items[info.id] ?: error("没有这个角色")
            items[info.id] = current.copy(name = name.trim())
        }

        override fun remove(targets: List<CharacterInfo>) {
            targets.forEach { items.remove(it.id) }
        }

        override fun avatarIndex(id: String, choices: Int): Int = 0
    }
}
