package com.genie.onnxdemo

import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile

data class Sampling(
    val temperature: Double,
    val topK: Int,
    val repetitionPenalty: Double,
) {
    val stamp: String
        get() = "t${(temperature * 100).toInt()}-k$topK-p${(repetitionPenalty * 100).toInt()}"
}

internal enum class DecoderKind { STAGE, FIRST }

internal fun samplingEdits(kind: DecoderKind, temperature: Double, topK: Int, penalty: Double): List<Pair<ByteArray, ByteArray>> {
    val penaltyBytes = floatLe(penalty)
    val temperatureBytes = floatLe(temperature)
    val topBytes = longLe(topK.toLong())
    val names = if (kind == DecoderKind.STAGE) {
        listOf("/Constant_13", "/Constant_14", "/Constant_15", "Constant_2249")
    } else {
        listOf("/Constant_9", "/Constant_10", "/Constant_11", "Constant_2256")
    }
    return listOf(
        floatMarker(names[0]) to penaltyBytes,
        floatMarker(names[1]) to penaltyBytes,
        floatMarker(names[2]) to temperatureBytes,
        intMarker(names[3]) to topBytes,
    )
}

internal fun patchModel(source: File, dest: File, edits: List<Pair<ByteArray, ByteArray>>): Boolean {
    val offsets = edits.map { (marker, _) ->
        val at = findOffset(source, marker)
        if (at < 0) return false
        at + marker.size
    }
    dest.parentFile?.mkdirs()
    source.copyTo(dest, overwrite = true)
    RandomAccessFile(dest, "rw").use { file ->
        edits.forEachIndexed { index, (_, payload) ->
            file.seek(offsets[index])
            file.write(payload)
        }
    }
    return true
}

internal fun findOffset(file: File, marker: ByteArray): Long {
    FileInputStream(file).use { input ->
        val window = ByteArray(marker.size + 64 * 1024)
        var filled = 0
        var base = 0L
        while (true) {
            val read = input.read(window, filled, window.size - filled)
            if (read < 0) break
            filled += read
            val at = indexOfSlice(window, filled, marker)
            if (at >= 0) return base + at
            val keep = (marker.size - 1).coerceAtMost(filled)
            val drop = filled - keep
            if (drop > 0) {
                System.arraycopy(window, drop, window, 0, keep)
                base += drop
                filled = keep
            }
        }
    }
    return -1
}

internal fun indexOfSlice(data: ByteArray, length: Int, marker: ByteArray): Int {
    if (marker.isEmpty() || marker.size > length) return -1
    val last = length - marker.size
    for (start in 0..last) {
        var matches = true
        for (index in marker.indices) {
            if (data[start + index] != marker[index]) {
                matches = false
                break
            }
        }
        if (matches) return start
    }
    return -1
}

private fun floatMarker(name: String): ByteArray {
    return (name + "\"\u0008Constant*\u0014\n\u0005value*\u0008\u0010\u0001J\u0004").toByteArray(Charsets.ISO_8859_1)
}

private fun intMarker(name: String): ByteArray {
    return (name + "\"\u0008Constant*\u0018\n\u0005value*\u000c\u0010\u0007J\u0008").toByteArray(Charsets.ISO_8859_1)
}

private fun floatLe(value: Double): ByteArray {
    val bits = java.lang.Float.floatToIntBits(value.toFloat())
    return byteArrayOf(
        (bits and 0xff).toByte(),
        (bits shr 8 and 0xff).toByte(),
        (bits shr 16 and 0xff).toByte(),
        (bits shr 24 and 0xff).toByte(),
    )
}

private fun longLe(value: Long): ByteArray {
    return ByteArray(8) { index -> (value shr (index * 8) and 0xff).toByte() }
}
