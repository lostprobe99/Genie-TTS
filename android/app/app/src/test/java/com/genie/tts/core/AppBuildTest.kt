package com.genie.tts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppBuildTest {
    @Test
    fun anUpdateIsLoggedOnceUntilTheBuildChanges() {
        val dir = File("build/app-build").apply { deleteRecursively(); mkdirs() }
        val build = AppBuild("0.0.19", 19, 1_700_000_000_000, 1_700_000_100_000)
        val lines = mutableListOf<String>()
        noteInstalledBuild(dir, build) { lines.add(it) }
        noteInstalledBuild(dir, build) { lines.add(it) }
        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("0.0.19（19）"))
        assertTrue(lines.single().contains(appBuildClock(build.builtAt)))
        assertTrue(lines.single().contains(appBuildClock(build.installedAt)))
        noteInstalledBuild(dir, build.copy(builtAt = build.builtAt + 1)) { lines.add(it) }
        assertEquals(2, lines.size)
        assertFalse(appBuildChanged(appBuildMarker(build), build))
    }
}
