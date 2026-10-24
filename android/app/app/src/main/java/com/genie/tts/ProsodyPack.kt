package com.genie.tts

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

object ProsodyPack {
    fun importZip(filesDir: File, input: InputStream) {
        val staging = File(filesDir, "prosody-staging").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            unzip(input, staging)
            val model = staging.walkTopDown().firstOrNull { it.isFile && it.name == Prosody.FILE_NAME }
                ?: error("韵律包里没有 ${Prosody.FILE_NAME}")
            val manifest = staging.walkTopDown().firstOrNull { it.isFile && it.name == "manifest.json" }
            if (manifest != null) {
                val json = JSONObject(manifest.readText())
                if (json.optString("id") != "prosody") error("这不是韵律包")
                if (json.optLong("size") != Prosody.MODEL_BYTES) error("韵律包说明里的大小不对")
            }
            install(filesDir, model)
        } finally {
            staging.deleteRecursively()
        }
    }

    fun importFile(filesDir: File, input: InputStream, displayName: String) {
        if (displayName.endsWith(".onnx", ignoreCase = true)) {
            val temp = File(filesDir, "roberta/import.onnx")
            temp.parentFile?.mkdirs()
            temp.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
            try {
                install(filesDir, temp)
            } finally {
                temp.delete()
            }
        } else {
            importZip(filesDir, input)
        }
    }

    fun delete(filesDir: File) {
        Prosody.modelFile(filesDir).parentFile?.deleteRecursively()
    }

    fun download(filesDir: File, onProgress: (done: Long, total: Long) -> Unit) {
        val dest = File(filesDir, "roberta/incoming.zip")
        dest.parentFile?.mkdirs()
        val connection = open(Release.PROSODY_URL, dest.length())
        try {
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                dest.delete()
                error("这次 release 里还没有韵律包，可以先从文件导入")
            }
            if (code !in listOf(HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_PARTIAL)) {
                dest.delete()
                error("下载失败（$code）")
            }
            val append = code == HttpURLConnection.HTTP_PARTIAL
            if (!append) dest.delete()
            val total = if (append) {
                dest.length() + connection.contentLengthLong.coerceAtLeast(0)
            } else {
                connection.contentLengthLong
            }
            var done = if (append && dest.isFile) dest.length() else 0L
            FileOutputStream(dest, append).use { output ->
                connection.inputStream.use { stream ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        done += count
                        onProgress(done, total)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        dest.inputStream().use { importZip(filesDir, it) }
        dest.delete()
    }

    private fun install(filesDir: File, model: File) {
        if (model.length() != Prosody.MODEL_BYTES) {
            error("韵律模型大小是 ${model.length()} 字节，应为 ${Prosody.MODEL_BYTES}")
        }
        val dest = Prosody.modelFile(filesDir)
        dest.parentFile?.mkdirs()
        val temp = File(dest.parentFile, "${dest.name}.part")
        model.copyTo(temp, overwrite = true)
        if (dest.exists() && !dest.delete()) error("无法替换韵律模型")
        if (!temp.renameTo(dest)) error("无法保存韵律模型")
    }

    private fun open(url: String, existing: Long): HttpURLConnection {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20000
            readTimeout = 60000
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }
        return connection
    }

    private fun unzip(input: InputStream, root: File) {
        var written = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.trimStart('/', '\\').replace('\\', '/')
                if (name.isEmpty() || name.split('/').any { it == ".." }) error("压缩包路径不安全")
                val dest = File(root, name)
                dest.parentFile?.mkdirs()
                val rootPath = root.canonicalPath + File.separator
                if (!dest.canonicalPath.startsWith(rootPath)) error("压缩包路径不安全")
                dest.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        written += count
                        if (written > 2_000_000_000L) error("压缩包过大")
                        output.write(buffer, 0, count)
                    }
                }
            }
        }
    }
}
