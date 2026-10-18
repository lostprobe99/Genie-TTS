package com.genie.onnxdemo.g2p

import java.io.File
import java.io.InputStream

/** Reads the frontend tables exported next to this source. */
class G2pFiles(private val open: (String) -> InputStream) {
    fun lines(name: String): List<String> {
        val out = ArrayList<String>()
        forEachLine(name) { out.add(it) }
        return out
    }

    /** Stream a table so the whole file is not kept beside the parsed map. */
    fun forEachLine(name: String, accept: (String) -> Unit) {
        open(name).bufferedReader(Charsets.UTF_8).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isNotEmpty()) accept(line)
            }
        }
    }

    fun bytes(name: String): ByteArray = open(name).use { it.readBytes() }

    companion object {
        fun fromDirectory(dir: File): G2pFiles = G2pFiles { name -> File(dir, name).inputStream() }
    }
}
