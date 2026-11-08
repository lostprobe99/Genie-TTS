package com.genie.tts.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ServiceActionsTest {
    @Test
    fun restorePortReadsTheSavedValue() {
        val harness = harness()
        File(harness.dir, "tts-port.txt").writeText("8123")
        harness.actions.restorePort()
        assertEquals(8123, harness.states.current().port)
    }

    @Test
    fun toggleStartsWhenPermissionIsGranted() {
        val harness = harness()
        harness.states.update { it.copy(error = "端口 8001 已被占用") }
        assertEquals(ServiceStart.Started, harness.actions.toggle(permissionGranted = true))
        assertEquals(1, harness.started)
        assertEquals(0, harness.stopped)
        assertNull(harness.states.current().error)
    }

    @Test
    fun toggleAsksForPermissionBeforeStarting() {
        val harness = harness()
        assertEquals(ServiceStart.NeedPermission, harness.actions.toggle(permissionGranted = false))
        assertEquals(0, harness.started)
        assertFalse(harness.states.current().running)
    }

    @Test
    fun toggleStopsARunningServiceWithoutAsking() {
        val harness = harness()
        harness.states.update { it.copy(running = true) }
        assertEquals(ServiceStart.Stopped, harness.actions.toggle(permissionGranted = false))
        assertEquals(1, harness.stopped)
        assertEquals(0, harness.started)
    }

    @Test
    fun anInvalidPortNotifiesAndLeavesTheSavedPort() {
        val harness = harness()
        harness.actions.restorePort()
        assertEquals(PortCommit.Invalid, harness.actions.commitPort("0"))
        assertEquals(listOf("端口要在 1 到 65535 之间"), harness.notices)
        assertEquals(TtsPorts.DEFAULT, harness.states.current().port)
        assertFalse(File(harness.dir, "tts-port.txt").isFile)
        assertEquals(0, harness.started)
    }

    @Test
    fun anUnchangedPortDoesNothing() {
        val harness = harness()
        harness.actions.restorePort()
        assertEquals(PortCommit.Unchanged, harness.actions.commitPort("8001"))
        assertTrue(harness.notices.isEmpty())
        assertEquals(0, harness.started)
    }

    @Test
    fun changingThePortWhileStoppedOnlyWritesIt() {
        val harness = harness()
        harness.actions.restorePort()
        assertEquals(PortCommit.Saved, harness.actions.commitPort(" 9000 "))
        assertEquals(9000, harness.states.current().port)
        assertEquals("9000", File(harness.dir, "tts-port.txt").readText())
        assertFalse(harness.states.current().switching)
        assertEquals(0, harness.started)
    }

    @Test
    fun changingThePortWhileRunningRestartsWithoutClearingTheError() {
        val harness = harness()
        harness.states.update { it.copy(port = 8001, running = true, error = "还在") }
        assertEquals(PortCommit.Saved, harness.actions.commitPort("9001"))
        assertEquals(9001, harness.states.current().port)
        assertTrue(harness.states.current().switching)
        assertEquals("还在", harness.states.current().error)
        assertEquals(1, harness.started)
    }

    private fun harness(): Harness = Harness()

    private class Harness {
        val dir: File = Files.createTempDirectory("port").toFile()
        val states = ServiceStateStore(post = { it() })
        val notices = mutableListOf<String>()
        var started = 0
        var stopped = 0
        val actions = ServiceActions(
            filesDir = dir,
            states = states,
            launch = { started += 1 },
            halt = { stopped += 1 },
            notice = { notices += it },
        )
    }
}
