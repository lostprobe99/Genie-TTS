package com.genie.onnxdemo

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

object CaseFetcher {
    const val RAW_BASE =
        "https://raw.githubusercontent.com/lostprobe99/Genie-TTS/cursor/android-onnx-demo-doc-3875/android_case"
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

    fun importArchive(context: Context, uri: Uri, onStatus: (String) -> Unit): File {
        val root = File(context.filesDir, "android_case")
        root.mkdirs()
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("读不到选择的压缩包")
        unzip(input, root, onStatus)
        if (!File(root, "manifest.json").isFile || !File(root, "models/vits_fp32.onnx").isFile) {
            throw IllegalStateException("压缩包里没有完整的测试数据。")
        }
        return root
    }

    private fun unzip(input: InputStream, root: File, onStatus: (String) -> Unit) {
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.trimStart('/')
                if (name.isEmpty() || name.contains("..")) throw IllegalStateException("压缩包路径不安全")
                val dest = File(root, name)
                dest.parentFile?.mkdirs()
                val rootPath = root.canonicalPath + File.separator
                if (!dest.canonicalPath.startsWith(rootPath)) throw IllegalStateException("压缩包路径不安全")
                onStatus("正在解压 ${dest.name}")
                dest.outputStream().use { output -> zip.copyTo(output, 256 * 1024) }
            }
        }
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
        val cache = File(root, "models/cache")
        temp.outputStream().use { output ->
            for (index in 0 until pieces.length()) {
                val relative = pieces.getString(index)
                val label = "$name ${index + 1}/${pieces.length()}"
                val piece = File(cache, relative.replace('/', '_'))
                downloadFile("$RAW_BASE/models/$relative", piece, label, onStatus)
                piece.inputStream().use { input -> input.copyTo(output, 256 * 1024) }
                piece.delete()
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

    private fun open(url: String): HttpURLConnection {
        var current = url
        repeat(5) {
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 30_000
                readTimeout = 300_000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) GenieOnnxDemo")
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Encoding", "identity")
            }
            try {
                connection.connect()
                val code = connection.responseCode
                if (code in 300..399) {
                    val next = connection.getHeaderField("Location")
                        ?: throw IllegalStateException("下载重定向没有目标")
                    connection.disconnect()
                    current = if (next.startsWith("http")) next else URL(URL(current), next).toString()
                    return@repeat
                }
                if (code !in 200..299) {
                    connection.disconnect()
                    throw IllegalStateException("下载失败，HTTP $code")
                }
                return connection
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
        }
        throw IllegalStateException("下载重定向次数过多")
    }

    private fun <T> withRetry(label: String, onStatus: (String) -> Unit, block: () -> T): T {
        var last: Exception? = null
        repeat(4) { attempt ->
            try {
                return block()
            } catch (error: java.io.IOException) {
                last = error
                onStatus("$label 连接中断，正在重试 ${attempt + 1}/4")
                Thread.sleep(1500L * (attempt + 1))
            }
        }
        throw IllegalStateException(
            "和 GitHub 的连接被中断。请再点一次「下载测试数据」，或换 Wi-Fi / 移动数据。",
            last,
        )
    }

    private fun downloadFile(url: String, dest: File, label: String, onStatus: (String) -> Unit) {
        dest.parentFile?.mkdirs()
        onStatus("正在下载 $label")
        val temp = File(dest.parentFile, dest.name + ".part")
        withRetry(label, onStatus) {
            if (temp.exists() && !temp.delete()) throw IllegalStateException("无法清理 ${temp.name}")
            val connection = open(url)
            try {
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
                                if (percent != lastReport && percent % 10 == 0) {
                                    lastReport = percent
                                    onStatus("下载 $label $percent%")
                                }
                            }
                        }
                    }
                }
            } catch (error: Exception) {
                temp.delete()
                throw error
            } finally {
                connection.disconnect()
            }
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
