package com.genie.onnxdemo

import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

data class CharacterInfo(
    val id: String,
    val name: String,
    val root: File,
    val refText: String,
)

data class ImportResult(
    val info: CharacterInfo,
    val replaced: Boolean,
)

/** 一个角色包解压后的目录。安装包本身不带角色。 */
object CharacterPack {
    const val FORMAT = 1
    val MODEL_NAMES = listOf(
        "t2s_encoder_fp32.onnx",
        "t2s_first_stage_decoder_fp32.onnx",
        "t2s_stage_decoder_fp32.onnx",
        "vits_fp32.onnx",
    )
    val REQUIRED_TENSORS = listOf("ref_seq", "ref_bert", "ssl_content", "ge", "ge_advanced")
    private val ID_PATTERN = Regex("[a-z][a-z0-9_]{0,31}")
    private const val MIN_MODEL_BYTES = 1024L
    private const val MAX_ENTRY_BYTES = 2_000_000_000L

    fun directory(filesDir: File): File = File(filesDir, "characters")

    fun list(filesDir: File): List<CharacterInfo> {
        val root = directory(filesDir)
        if (!root.isDirectory) return emptyList()
        return root.listFiles().orEmpty()
            .filter { File(it, "manifest.json").isFile }
            .mapNotNull { file -> runCatching { read(file) }.getOrNull() }
            .sortedBy { it.id }
    }

    fun migrateLegacy(filesDir: File) {
        val legacy = File(filesDir, "android_case")
        if (!legacy.isDirectory) return
        moveRoberta(filesDir, legacy)
        val manifest = File(legacy, "manifest.json")
        if (!manifest.isFile) return
        val id = legacyId(manifest)
        val dest = File(directory(filesDir), id)
        if (File(dest, "manifest.json").isFile) return
        dest.parentFile?.mkdirs()
        if (!legacy.renameTo(dest)) return
        stamp(File(dest, "manifest.json"), id)
    }

    fun importZip(filesDir: File, input: InputStream): ImportResult {
        val staging = File(filesDir, "import-staging").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            unzip(input, staging)
            val manifestFile = findManifest(staging)
            val packedRoot = manifestFile.parentFile ?: staging
            assembleParts(packedRoot)
            val manifest = JSONObject(manifestFile.readText())
            validate(packedRoot, manifest)
            val info = read(packedRoot)
            val dest = File(directory(filesDir), info.id)
            val replaced = dest.isDirectory
            val backup = File(filesDir, "import-backup-${info.id}")
            if (replaced) {
                backup.deleteRecursively()
                if (!dest.renameTo(backup)) error("无法替换已有角色 ${info.id}")
            } else {
                dest.parentFile?.mkdirs()
            }
            if (!packedRoot.renameTo(dest)) {
                if (replaced) backup.renameTo(dest)
                error("无法保存角色 ${info.id}")
            }
            backup.deleteRecursively()
            staging.deleteRecursively()
            return ImportResult(read(dest), replaced)
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
    }

    private fun legacyId(manifest: File): String {
        val json = JSONObject(manifest.readText())
        val raw = json.optString("id").ifBlank { json.optString("character") }.ifBlank { "feibi" }
        val id = raw.trim().lowercase()
        if (!ID_PATTERN.matches(id)) error("旧目录里的角色 id 不合法")
        return id
    }

    private fun moveRoberta(filesDir: File, legacy: File) {
        val shared = Prosody.modelFile(filesDir)
        if (shared.isFile && shared.length() == Prosody.MODEL_BYTES) return
        val old = File(legacy, "roberta/${Prosody.FILE_NAME}")
        if (!old.isFile || old.length() != Prosody.MODEL_BYTES) return
        shared.parentFile?.mkdirs()
        if (!old.renameTo(shared)) old.copyTo(shared, overwrite = false)
    }

    private fun stamp(manifest: File, id: String) {
        val json = JSONObject(manifest.readText())
        json.put("format", FORMAT)
        json.put("id", id)
        if (json.optString("name").isBlank()) json.put("name", displayName(id))
        manifest.writeText(json.toString(2))
    }

    private fun displayName(id: String): String = when (id) {
        "feibi" -> "菲比"
        "sanyueqi" -> "三月七"
        else -> id
    }

    private fun findManifest(staging: File): File {
        val direct = File(staging, "manifest.json")
        if (direct.isFile) return direct
        val nested = staging.listFiles().orEmpty().filter { it.isDirectory && File(it, "manifest.json").isFile }
        if (nested.size == 1) return File(nested[0], "manifest.json")
        error("压缩包里没有 manifest.json")
    }

    private fun assembleParts(root: File) {
        val partsFile = File(root, "models/parts.json")
        if (!partsFile.isFile) return
        val parts = JSONObject(partsFile.readText())
        val models = File(root, "models")
        for (name in parts.keys()) {
            val spec = parts.getJSONObject(name)
            val dest = File(models, name)
            val expected = spec.getLong("size")
            if (dest.isFile && dest.length() == expected) continue
            val pieces = spec.getJSONArray("parts")
            val temp = File(models, "$name.part")
            temp.outputStream().use { output ->
                for (index in 0 until pieces.length()) {
                    val relative = pieces.getString(index)
                    val piece = File(models, relative)
                    if (!piece.isFile) error("缺少分片 $relative")
                    if (piece.canonicalFile == dest.canonicalFile) continue
                    piece.inputStream().use { input -> input.copyTo(output, 256 * 1024) }
                }
            }
            if (temp.length() != expected) {
                temp.delete()
                error("$name 大小不对")
            }
            if (dest.exists() && !dest.delete()) error("无法覆盖 $name")
            if (!temp.renameTo(dest)) error("无法保存 $name")
        }
    }

    private fun validate(root: File, manifest: JSONObject) {
        if (manifest.optInt("format", 0) != FORMAT) error("这个包不是可导入的角色包")
        val id = manifest.optString("id").trim().lowercase()
        if (!ID_PATTERN.matches(id)) error("角色 id 不合法")
        if (manifest.optString("ref_text").isBlank()) error("角色包缺少参考原文")
        if (manifest.optInt("sample_rate", 0) <= 0) error("角色包缺少采样率")
        val tensors = manifest.optJSONObject("tensors") ?: error("角色包缺少张量")
        for (name in REQUIRED_TENSORS) {
            val spec = tensors.optJSONObject(name) ?: error("角色包缺少 $name")
            val file = File(root, spec.getString("file"))
            if (!file.isFile) error("角色包缺少 ${spec.getString("file")}")
            val shape = spec.getJSONArray("shape")
            val dims = LongArray(shape.length()) { shape.getLong(it) }
            val expected = expectedBytes(spec.getString("dtype"), dims)
            if (file.length() != expected) error("$name 大小是 ${file.length()} 字节，应为 $expected")
        }
        val sizes = manifest.optJSONObject("model_sizes")
        for (name in MODEL_NAMES) {
            val file = File(root, "models/$name")
            if (!file.isFile) error("角色包缺少 $name")
            rejectPointer(file)
            if (file.length() < MIN_MODEL_BYTES) error("$name 太小，不是模型")
            if (sizes != null && sizes.has(name) && file.length() != sizes.getLong(name)) {
                error("$name 大小和说明不一致")
            }
        }
    }

    private fun read(root: File): CharacterInfo {
        val manifest = JSONObject(File(root, "manifest.json").readText())
        val id = manifest.optString("id").ifBlank { manifest.optString("character") }.trim().lowercase()
        if (!ID_PATTERN.matches(id)) error("角色 id 不合法")
        val name = manifest.optString("name").ifBlank { displayName(id) }
        val refText = manifest.optString("ref_text")
        return CharacterInfo(id, name, root, refText)
    }

    private fun expectedBytes(dtype: String, shape: LongArray): Long {
        val width = when (dtype) {
            "float32" -> 4L
            "int64" -> 8L
            else -> error("不支持的 dtype: $dtype")
        }
        return shape.fold(1L) { acc, dim -> acc * dim } * width
    }

    private fun rejectPointer(file: File) {
        val head = file.inputStream().use { input ->
            val bytes = ByteArray(40)
            val count = input.read(bytes)
            if (count <= 0) "" else String(bytes, 0, count, Charsets.US_ASCII)
        }
        if (head.startsWith("version https://git-lfs")) error("${file.name} 只是 Git LFS 指针，不是模型")
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
                        if (written > MAX_ENTRY_BYTES) error("压缩包过大")
                        output.write(buffer, 0, count)
                    }
                }
            }
        }
    }
}
