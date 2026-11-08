package com.genie.tts.service

import java.io.File

internal enum class ServiceStart { Started, Stopped, NeedPermission }

internal enum class PortCommit { Saved, Unchanged, Invalid }

/**
 * 朗读接口的开关和端口。
 * 通知权限由界面判断，这里只在已经允许时去启动。
 */
internal class ServiceActions(
    private val filesDir: File,
    private val states: ServiceStateStore,
    private val launch: () -> Unit,
    private val halt: () -> Unit,
    private val notice: (String) -> Unit,
    private val parsePort: (String?) -> Int? = TtsPorts::parse,
    private val readPort: (File) -> Int = TtsPorts::read,
    private val writePort: (File, Int) -> Unit = TtsPorts::write,
) {
    fun restorePort() {
        val port = readPort(filesDir)
        states.update { it.copy(port = port) }
    }

    fun start() {
        states.update { it.copy(error = null) }
        launch()
    }

    fun toggle(permissionGranted: Boolean): ServiceStart {
        if (states.current().running) {
            halt()
            return ServiceStart.Stopped
        }
        if (!permissionGranted) return ServiceStart.NeedPermission
        start()
        return ServiceStart.Started
    }

    fun commitPort(raw: String): PortCommit {
        val value = parsePort(raw)
        if (value == null) {
            notice("端口要在 1 到 65535 之间")
            return PortCommit.Invalid
        }
        val now = states.current()
        if (value == now.port) return PortCommit.Unchanged
        writePort(filesDir, value)
        states.update { it.copy(port = value, switching = now.running) }
        if (now.running) launch()
        return PortCommit.Saved
    }
}
