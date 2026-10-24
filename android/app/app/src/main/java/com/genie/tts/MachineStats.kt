package com.genie.tts

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

internal data class CpuSample(val total: Long, val idle: Long, val process: Long)

internal data class ZoneTemp(val type: String, val celsius: Float)

internal fun parseCpuTotal(stat: String): Pair<Long, Long>? {
    val line = stat.lineSequence().firstOrNull { it.startsWith("cpu ") } ?: return null
    val parts = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
    if (parts.size < 4) return null
    val idle = parts[3] + (parts.getOrNull(4) ?: 0L)
    return parts.sum() to idle
}

internal fun parseProcessJiffies(selfStat: String): Long? {
    val end = selfStat.lastIndexOf(')')
    if (end < 0 || end + 2 >= selfStat.length) return null
    val fields = selfStat.substring(end + 2).trim().split(Regex("\\s+"))
    if (fields.size < 13) return null
    val utime = fields[11].toLongOrNull() ?: return null
    val stime = fields[12].toLongOrNull() ?: return null
    return utime + stime
}

internal fun cpuPercents(previous: CpuSample, current: CpuSample, cores: Int): Pair<Int, Int>? {
    val total = current.total - previous.total
    if (total <= 0L) return null
    val idle = (current.idle - previous.idle).coerceAtLeast(0L)
    val machine = ((total - idle) * 100.0 / total).roundToInt().coerceIn(0, 100)
    val proc = (current.process - previous.process).coerceAtLeast(0L)
    val process = if (cores > 0) {
        (proc * cores * 100.0 / total).roundToInt().coerceAtLeast(0)
    } else {
        (proc * 100.0 / total).roundToInt().coerceAtLeast(0)
    }
    return machine to process
}

internal fun parseZoneTemp(text: String): Float? {
    val raw = text.trim().toFloatOrNull() ?: return null
    val celsius = if (kotlin.math.abs(raw) > 200f) raw / 1000f else raw
    if (celsius < -40f || celsius > 150f) return null
    return celsius
}

internal fun temperatureClause(battery: Float?, zones: List<ZoneTemp>): String {
    val parts = ArrayList<String>()
    if (battery != null) parts.add("电池 ${celsiusText(battery)}°C")
    val skin = zones.filter { zone ->
        val type = zone.type.lowercase(Locale.US)
        type.contains("skin") || type.contains("quiet") || type.contains("board")
    }.maxByOrNull { it.celsius }
    val cpu = zones.filter { zone ->
        val type = zone.type.lowercase(Locale.US)
        type.contains("cpu") || type.contains("soc")
    }.maxByOrNull { it.celsius }
    if (skin != null) parts.add("机身 ${celsiusText(skin.celsius)}°C")
    if (cpu != null) parts.add("CPU温 ${celsiusText(cpu.celsius)}°C")
    if (skin == null && cpu == null) {
        val hottest = zones.maxByOrNull { it.celsius }
        if (hottest != null) parts.add("最高 ${celsiusText(hottest.celsius)}°C ${hottest.type}")
    }
    return parts.joinToString(" ")
}

internal fun thermalStatusText(status: Int): String = when (status) {
    0 -> "正常"
    1 -> "轻微"
    2 -> "中等"
    3 -> "严重"
    4 -> "临界"
    5 -> "紧急"
    6 -> "关机"
    else -> status.toString()
}

internal fun machineLine(
    label: String,
    machineCpu: Int?,
    processCpu: Int?,
    processMb: Int,
    availMb: Int,
    totalMb: Int,
    temps: String,
    thermal: String?,
    cpuPrefix: String,
): String {
    val cpu = if (machineCpu == null || processCpu == null) {
        "CPU 采样中"
    } else {
        "${cpuPrefix}整机CPU ${machineCpu}% 本进程 ${processCpu}%"
    }
    val heat = listOfNotNull(temps.takeIf { it.isNotBlank() }, thermal?.let { "热状态 $it" })
    return (listOf(label, cpu, "内存 本进程 ${processMb}MB 可用 ${availMb}/${totalMb}MB") + heat)
        .joinToString(" ")
}

private fun celsiusText(value: Float): String = "%.1f".format(Locale.US, value)

internal class MachineProbe(private val context: Context) {
    private var last: CpuSample? = null
    private val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    fun snapshot(label: String, cpuPrefix: String): String {
        val current = readCpu()
        val percents = if (current != null && last != null) cpuPercents(last!!, current, cores) else null
        if (current != null) last = current
        val memory = readMemory()
        val temps = temperatureClause(readBattery(), readZones())
        return machineLine(
            label,
            percents?.first,
            percents?.second,
            memory.processMb,
            memory.availMb,
            memory.totalMb,
            temps,
            readThermalStatus(),
            cpuPrefix,
        )
    }

    private fun readCpu(): CpuSample? {
        val total = parseCpuTotal(readText(File("/proc/stat")) ?: return null) ?: return null
        val process = parseProcessJiffies(readText(File("/proc/self/stat")) ?: return null) ?: return null
        return CpuSample(total.first, total.second, process)
    }

    private fun readMemory(): MemoryMb {
        val manager = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo()
        manager.getMemoryInfo(info)
        val pssKb = runCatching {
            manager.getProcessMemoryInfo(intArrayOf(android.os.Process.myPid())).firstOrNull()?.totalPss ?: 0
        }.getOrDefault(0)
        return MemoryMb(
            processMb = (pssKb / 1024).coerceAtLeast(0),
            availMb = (info.availMem / (1024 * 1024)).toInt().coerceAtLeast(0),
            totalMb = (info.totalMem / (1024 * 1024)).toInt().coerceAtLeast(0),
        )
    }

    private fun readBattery(): Float? {
        val intent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return null
        val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        if (tenths == Int.MIN_VALUE) return null
        return tenths / 10f
    }

    private fun readZones(): List<ZoneTemp> {
        val dirs = File("/sys/class/thermal").listFiles() ?: return emptyList()
        return dirs.mapNotNull { dir ->
            if (!dir.isDirectory || !dir.name.startsWith("thermal_zone")) return@mapNotNull null
            val type = readText(File(dir, "type"))?.trim().orEmpty()
            val temp = readText(File(dir, "temp"))?.let(::parseZoneTemp) ?: return@mapNotNull null
            if (type.isEmpty()) return@mapNotNull null
            ZoneTemp(type, temp)
        }
    }

    private fun readThermalStatus(): String? {
        if (Build.VERSION.SDK_INT < 29) return null
        val status = runCatching {
            context.getSystemService(PowerManager::class.java).currentThermalStatus
        }.getOrNull() ?: return null
        return thermalStatusText(status)
    }

    private fun readText(file: File): String? {
        return runCatching { if (file.isFile) file.readText() else null }.getOrNull()
    }

    private data class MemoryMb(val processMb: Int, val availMb: Int, val totalMb: Int)
}
