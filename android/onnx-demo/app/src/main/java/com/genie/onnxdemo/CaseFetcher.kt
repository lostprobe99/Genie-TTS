package com.genie.onnxdemo

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object CaseFetcher {
    const val RAW_BASE =
        "https://github.com/lostprobe99/Genie-TTS/raw/cursor/android-onnx-demo-doc-3875/android_case"

    fun download(context: Context, onStatus: (String) -> Unit): File {
        val root = File(context.filesDir, "android_case")
        root.mkdirs()
        downloadFile("$RAW_BASE/manifest.json", File(root, "manifest.json"), "manifest.json", onStatus)
        val manifest = JSONObject(File(root, "manifest.json").readText())
        val tensors = manifest.getJSONObject("tensors")
        for (key in tensors.keys()) {
            val relative = tensors.getJSONObject(key).getString("file")
            downloadFile("$RAW_BASE/$relative", File(root, relative), relative, onStatus)
        }
        downloadFile("$RAW_BASE/models/parts.json", File(root, "models/parts.json"), "parts.json", onStatus)
        val parts = JSONObject(File(root, "models/parts.json").readText())
        val models = manifest.getJSONArray("models")
        for (index in 0 until models.length()) {
            val name = models.getString(index)
            assembleModel(root, name, parts.getJSONObject(name), onStatus)
        }
        downloadFile("$RAW_BASE/desktop.wav", File(root, "desktop.wav"), "desktop.wav", onStatus)
        return root
    }

    fun importTree(context: Context, tree: Uri, onStatus: (String) -> Unit): File {
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val entries = listChildren(context, tree, rootId)
        val manifest = entries.firstOrNull { !it.isDir && it.name == "manifest.json" }
            ?: throw IllegalStateException("这个目录里没有 manifest.json。请选择解压后的 android_case。")
        val root = File(context.filesDir, "android_case")
        root.mkdirs()
        copyDocument(context, tree, manifest, File(root, "manifest.json"), onStatus)
        copyChildren(context, tree, entries, "tensors", File(root, "tensors"), onStatus)
        copyChildren(context, tree, entries, "models", File(root, "models"), onStatus)
        entries.firstOrNull { !it.isDir && it.name == "desktop.wav" }?.let {
            copyDocument(context, tree, it, File(root, "desktop.wav"), onStatus)
        }
        return root
    }

    private data class Entry(val id: String, val name: String, val isDir: Boolean)

    private fun listChildren(context: Context, tree: Uri, documentId: String): List<Entry> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        val result = mutableListOf<Entry>()
        context.contentResolver.query(
            uri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                result += Entry(
                    cursor.getString(0),
                    cursor.getString(1) ?: "",
                    cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                )
            }
        }
        return result
    }

    private fun copyChildren(
        context: Context,
        tree: Uri,
        parentEntries: List<Entry>,
        name: String,
        dest: File,
        onStatus: (String) -> Unit,
    ) {
        val directory = parentEntries.firstOrNull { it.isDir && it.name == name }
            ?: throw IllegalStateException("目录不完整，缺少 $name")
        dest.mkdirs()
        for (child in listChildren(context, tree, directory.id)) {
            if (!child.isDir) copyDocument(context, tree, child, File(dest, child.name), onStatus)
        }
    }

    private fun copyDocument(context: Context, tree: Uri, source: Entry, dest: File, onStatus: (String) -> Unit) {
        dest.parentFile?.mkdirs()
        onStatus("正在复制 ${dest.name}")
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, source.id)
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "读不到 ${dest.name}" }
            dest.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
        }
    }

    private fun assembleModel(root: File, name: String, spec: JSONObject, onStatus: (String) -> Unit) {
        val dest = File(root, "models/$name")
        val expected = spec.getLong("size")
        if (dest.isFile && dest.length() == expected) {
            onStatus("已有 $name")
            return
        }
        val pieces = spec.getJSONArray("parts")
        val temp = File(dest.parentFile, dest.name + ".part")
        temp.outputStream().use { output ->
            for (index in 0 until pieces.length()) {
                val relative = pieces.getString(index)
                appendUrl(
                    "$RAW_BASE/models/$relative",
                    output,
                    "$name ${index + 1}/${pieces.length()}",
                    onStatus,
                )
            }
        }
        if (temp.length() != expected) {
            temp.delete()
            throw IllegalStateException("$name 大小不对，下载不完整。")
        }
        if (dest.exists() && !dest.delete()) throw IllegalStateException("无法覆盖 $name")
        if (!temp.renameTo(dest)) throw IllegalStateException("无法保存 $name")
        onStatus("已完成 $name")
    }

    private fun appendUrl(url: String, output: java.io.OutputStream, label: String, onStatus: (String) -> Unit) {
        val connection = open(url)
        try {
            connection.inputStream.use { input ->
                val buffer = ByteArray(256 * 1024)
                var readTotal = 0L
                val total = connection.contentLengthLong
                var lastReport = -1
                var checked = false
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (!checked) {
                        val head = String(buffer, 0, minOf(count, 40), Charsets.US_ASCII)
                        if (head.startsWith("version https://git-lfs")) {
                            throw IllegalStateException("$label 只下到了 Git LFS 指针。")
                        }
                        checked = true
                    }
                    output.write(buffer, 0, count)
                    readTotal += count
                    if (total > 0) {
                        val percent = ((readTotal * 100) / total).toInt()
                        if (percent != lastReport && percent % 10 == 0) {
                            lastReport = percent
                            onStatus("下载 $label $percent%")
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 300_000
            setRequestProperty("User-Agent", "GenieOnnxDemo")
        }
        connection.connect()
        if (connection.responseCode !in 200..299) {
            val code = connection.responseCode
            connection.disconnect()
            throw IllegalStateException("下载失败，HTTP $code：$url")
        }
        return connection
    }

    private fun downloadFile(url: String, dest: File, label: String, onStatus: (String) -> Unit) {
        dest.parentFile?.mkdirs()
        val temp = File(dest.parentFile, dest.name + ".part")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 300_000
            setRequestProperty("User-Agent", "GenieOnnxDemo")
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("下载 $label 失败，HTTP ${connection.responseCode}")
            }
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var readTotal = 0L
                    var lastReport = -1
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        readTotal += count
                        if (total > 0) {
                            val percent = ((readTotal * 100) / total).toInt()
                            if (percent != lastReport && percent % 5 == 0) {
                                lastReport = percent
                                onStatus("下载 $label $percent%")
                            }
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        val head = temp.inputStream().use { input ->
            val bytes = ByteArray(40)
            val count = input.read(bytes)
            if (count <= 0) "" else String(bytes, 0, count, Charsets.US_ASCII)
        }
        if (head.startsWith("version https://git-lfs")) {
            temp.delete()
            throw IllegalStateException("$label 只下到了 Git LFS 指针，模型文件还没有传到仓库。")
        }
        if (temp.length() < 32) {
            temp.delete()
            throw IllegalStateException("$label 文件过小，下载不完整。")
        }
        if (dest.exists() && !dest.delete()) throw IllegalStateException("无法覆盖 ${dest.name}")
        if (!temp.renameTo(dest)) throw IllegalStateException("无法保存 ${dest.name}")
        onStatus("已完成 $label")
    }
}
