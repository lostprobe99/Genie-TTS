package com.genie.onnxdemo.g2p

import java.io.File
import java.io.InputStream

/** Reads the frontend tables exported next to this source. */
class G2pFiles(private val open: (String) -> InputStream) {
    fun lines(name: String): List<String> {
        return open(name).bufferedReader(Charsets.UTF_8).use { reader ->
            reader.readLines().filter { it.isNotEmpty() }
        }
    }

    fun bytes(name: String): ByteArray = open(name).use { it.readBytes() }

    companion object {
        fun fromDirectory(dir: File): G2pFiles = G2pFiles { name -> File(dir, name).inputStream() }
    }
}
