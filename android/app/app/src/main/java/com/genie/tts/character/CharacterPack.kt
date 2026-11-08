package com.genie.tts.character

import com.genie.tts.speech.Prosody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import java.util.zip.ZipInputStream

data class CharacterInfo(
    val id: String,
    val name: String,
    val root: File,
    val refText: String,
    val sampleRate: Int = 32000,
    val avatarIndex: Int? = null,
)

data class ImportResult(
    val info: CharacterInfo,
    val replaced: Boolean,
)

data class PackPreview(
    val id: String,
    val name: String,
    val refText: String,
    val audioName: String?,
)

/** 解到暂存目录并校验过的角色包。[root] 就是还没安装的角色目录。 */
internal class StagedPack(val root: File, val info: CharacterInfo)

/** 一个角色包的读取、校验和解压。安装、改名、删除走 [CharacterRepository]。 */
internal object CharacterPack : CharacterDisk {
    private const val MAX_ENTRY_BYTES = 2_000_000_000L
    private const val MAX_AUDIO_BYTES = 32L * 1024 * 1024
    private val AUDIO_EXT = listOf(".wav", ".mp3", ".ogg", ".flac", ".m4a", ".aac")

    fun directory(filesDir: File): File = File(filesDir, "characters")

    fun avatarImage(root: File): File? =
        CharacterPackSchema.AVATAR_IMAGES.map { File(root, it) }.firstOrNull { it.isFile && it.length() > 0L }

    /** 包里留下的参考音频。模型目录里的大文件不扫。 */
    fun referenceAudio(root: File): File? {
        if (!root.isDirectory) return null
        return root.walkTopDown()
            .onEnter { dir -> dir.name != CharacterPackSchema.MODELS_DIR }
            .filter { it.isFile && isAudioName(it.name) }
            .minWithOrNull(compareBy<File>({ audioRank(it.name) }, { it.path }))
    }

    override fun scan(filesDir: File): List<CharacterInfo> {
        val root = directory(filesDir)
        if (!root.isDirectory) return emptyList()
        return root.listFiles().orEmpty()
            .filter { CharacterPackSchema.manifestFile(it).isFile }
            .mapNotNull { file -> runCatching { read(file) }.getOrNull() }
            .sortedBy { it.id }
    }

    override fun migrateLegacy(filesDir: File) {
        val legacy = File(filesDir, "android_case")
        if (!legacy.isDirectory) return
        moveRoberta(filesDir, legacy)
        val manifest = CharacterPackSchema.manifestFile(legacy)
        if (!manifest.isFile) return
        val id = legacyId(manifest)
        val dest = File(directory(filesDir), id)
        if (CharacterPackSchema.manifestFile(dest).isFile) return
        dest.parentFile?.mkdirs()
        if (!legacy.renameTo(dest)) return
        stamp(CharacterPackSchema.manifestFile(dest), id)
    }

    fun peek(channel: FileChannel): PackPreview {
        val items = catalog(channel)
        val manifest = items
            .filter { it.name.substringAfterLast('/') == CharacterPackSchema.MANIFEST && safeZipPath(it.name) }
            .minByOrNull { it.name.count { char -> char == '/' } }
            ?: error("压缩包里没有 manifest.json")
        val json = JSONObject(readZipEntry(channel, manifest, 1_000_000).toString(Charsets.UTF_8))
        val id = CharacterPackSchema.normalizeId(json.optString("id"))
        if (!CharacterPackSchema.isValidId(id)) error("角色 id 不合法")
        val packName = json.optString("name").ifBlank { displayName(id) }
        val refText = json.optString(CharacterPackSchema.KEY_REF_TEXT)
        val audio = items
            .filter { safeZipPath(it.name) && isAudioName(it.name) }
            .minWithOrNull(compareBy({ audioRank(it.name) }, { it.name }))
        return PackPreview(id, packName, refText, audio?.name)
    }

    fun readNamed(channel: FileChannel, name: String): ByteArray {
        val item = catalog(channel).firstOrNull { it.name == name && safeZipPath(it.name) }
            ?: error("压缩包里没有参考音频")
        return readZipEntry(channel, item, MAX_AUDIO_BYTES)
    }

    /** PCM wav 的时长。不是 wav，或者头不完整，就返回 null。 */
    fun wavSeconds(bytes: ByteArray): Double? {
        if (bytes.size < 44) return null
        if (ascii(bytes, 0, 4) != "RIFF" || ascii(bytes, 8, 4) != "WAVE") return null
        var offset = 12
        var rate = 0
        var channels = 0
        var bits = 0
        var dataBytes = -1L
        while (offset + 8 <= bytes.size) {
            val id = ascii(bytes, offset, 4) ?: return null
            val size = ByteBuffer.wrap(bytes, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
            val body = offset + 8
            if (body + size > bytes.size) return null
            if (id == "fmt " && size >= 16) {
                val fmt = ByteBuffer.wrap(bytes, body, 16).slice().order(ByteOrder.LITTLE_ENDIAN)
                channels = fmt.getShort(2).toInt() and 0xffff
                rate = fmt.getInt(4)
                bits = fmt.getShort(14).toInt() and 0xffff
            } else if (id == "data") {
                dataBytes = size
            }
            val padded = size + (size and 1L)
            offset = body + padded.toInt()
        }
        val frame = channels * (bits / 8)
        if (rate <= 0 || frame <= 0 || dataBytes < 0) return null
        return dataBytes.toDouble() / (rate * frame)
    }

    /** 把 zip 解到 [staging],拼好分片,校验清单、张量和模型。通过后返回包根目录和里面的角色信息。 */
    fun stage(input: InputStream, staging: File): StagedPack {
        unzip(input, staging)
        val manifestFile = findManifest(staging)
        val packedRoot = manifestFile.parentFile ?: staging
        assembleParts(packedRoot)
        val manifest = JSONObject(manifestFile.readText())
        validate(packedRoot, manifest)
        return StagedPack(packedRoot, read(packedRoot))
    }

    private fun legacyId(manifest: File): String {
        val json = JSONObject(manifest.readText())
        val raw = json.optString("id").ifBlank { json.optString("character") }.ifBlank { "feibi" }
        val id = CharacterPackSchema.normalizeId(raw)
        if (!CharacterPackSchema.isValidId(id)) error("旧目录里的角色 id 不合法")
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
        json.put("format", CharacterPackSchema.FORMAT)
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
        val direct = CharacterPackSchema.manifestFile(staging)
        if (direct.isFile) return direct
        val nested = staging.listFiles().orEmpty().filter { it.isDirectory && CharacterPackSchema.manifestFile(it).isFile }
        if (nested.size == 1) return CharacterPackSchema.manifestFile(nested[0])
        error("压缩包里没有 manifest.json")
    }

    private fun assembleParts(root: File) {
        val partsFile = File(root, CharacterPackSchema.MODEL_PARTS)
        if (!partsFile.isFile) return
        val parts = JSONObject(partsFile.readText())
        val models = CharacterPackSchema.modelsDir(root)
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
        if (manifest.optInt("format", 0) != CharacterPackSchema.FORMAT) error("这个包不是可导入的角色包")
        val id = CharacterPackSchema.normalizeId(manifest.optString("id"))
        if (!CharacterPackSchema.isValidId(id)) error("角色 id 不合法")
        if (manifest.optString(CharacterPackSchema.KEY_REF_TEXT).isBlank()) error("角色包缺少参考原文")
        if (manifest.optInt(CharacterPackSchema.KEY_SAMPLE_RATE, 0) <= 0) error("角色包缺少采样率")
        val tensors = manifest.optJSONObject(CharacterPackSchema.KEY_TENSORS) ?: error("角色包缺少张量")
        for (name in CharacterPackSchema.REQUIRED_TENSORS) {
            val spec = tensors.optJSONObject(name) ?: error("角色包缺少 $name")
            val file = File(root, spec.getString("file"))
            if (!file.isFile) error("角色包缺少 ${spec.getString("file")}")
            val shape = spec.getJSONArray("shape")
            val dims = LongArray(shape.length()) { shape.getLong(it) }
            val expected = CharacterPackSchema.tensorBytes(spec.getString("dtype"), dims)
            if (file.length() != expected) error("$name 大小是 ${file.length()} 字节，应为 $expected")
        }
        val sizes = manifest.optJSONObject("model_sizes")
        for (name in CharacterPackSchema.MODEL_NAMES) {
            val file = CharacterPackSchema.modelFile(root, name)
            if (!file.isFile) error("角色包缺少 $name")
            rejectPointer(file)
            if (file.length() < CharacterPackSchema.MIN_MODEL_BYTES) error("$name 太小，不是模型")
            if (sizes != null && sizes.has(name) && file.length() != sizes.getLong(name)) {
                error("$name 大小和说明不一致")
            }
        }
    }

    fun read(root: File): CharacterInfo {
        val manifest = JSONObject(CharacterPackSchema.manifestFile(root).readText())
        val id = CharacterPackSchema.normalizeId(manifest.optString("id").ifBlank { manifest.optString("character") })
        if (!CharacterPackSchema.isValidId(id)) error("角色 id 不合法")
        val saved = File(root, CharacterPackSchema.LEGACY_NAME_FILE).takeIf { it.isFile }?.readText()?.trim()
        val name = saved?.takeIf { it.isNotEmpty() } ?: manifest.optString("name").ifBlank { displayName(id) }
        val refText = manifest.optString(CharacterPackSchema.KEY_REF_TEXT)
        val rate = manifest.optInt(CharacterPackSchema.KEY_SAMPLE_RATE, 32000).coerceAtLeast(1)
        val avatar = File(root, CharacterPackSchema.LEGACY_AVATAR_FILE).takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
        return CharacterInfo(id, name, root, refText, rate, avatar)
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

    private data class ZipItem(
        val name: String,
        val method: Int,
        val compressedSize: Long,
        val size: Long,
        val offset: Long,
    )

    private fun catalog(channel: FileChannel): List<ZipItem> {
        val fileSize = channel.size()
        if (fileSize < 22) error("压缩包不完整")
        val tailLen = minOf(fileSize, 22L + 65535L).toInt()
        val tail = readAt(channel, fileSize - tailLen, tailLen)
        val bytes = ByteArray(tail.remaining())
        tail.get(bytes)
        var eocd = -1
        for (index in bytes.size - 22 downTo 0) {
            if (bytes[index] != 0x50.toByte() || bytes[index + 1] != 0x4b.toByte()) continue
            if (bytes[index + 2] != 0x05.toByte() || bytes[index + 3] != 0x06.toByte()) continue
            val commentLen = (bytes[index + 20].toInt() and 0xff) or ((bytes[index + 21].toInt() and 0xff) shl 8)
            if (index + 22 + commentLen == bytes.size) {
                eocd = index
                break
            }
        }
        if (eocd < 0) error("压缩包不完整")
        val header = ByteBuffer.wrap(bytes, eocd, 22).slice().order(ByteOrder.LITTLE_ENDIAN)
        val cdSize = header.getInt(12).toLong() and 0xffffffffL
        val cdOffset = header.getInt(16).toLong() and 0xffffffffL
        if (cdSize == 0xffffffffL || cdOffset == 0xffffffffL || cdSize > 20_000_000L) error("压缩包过大")
        if (cdOffset < 0 || cdOffset + cdSize > fileSize) error("压缩包不完整")
        val directory = readAt(channel, cdOffset, cdSize.toInt())
        val items = mutableListOf<ZipItem>()
        while (directory.remaining() >= 46) {
            val start = directory.position()
            if (directory.getInt(start) != 0x02014b50) break
            val method = directory.getShort(start + 10).toInt() and 0xffff
            val compressed = directory.getInt(start + 20).toLong() and 0xffffffffL
            val size = directory.getInt(start + 24).toLong() and 0xffffffffL
            val nameLen = directory.getShort(start + 28).toInt() and 0xffff
            val extraLen = directory.getShort(start + 30).toInt() and 0xffff
            val commentLen = directory.getShort(start + 32).toInt() and 0xffff
            val offset = directory.getInt(start + 42).toLong() and 0xffffffffL
            if (compressed == 0xffffffffL || size == 0xffffffffL || offset == 0xffffffffL) error("压缩包过大")
            val nameBytes = ByteArray(nameLen)
            directory.position(start + 46)
            if (directory.remaining() < nameLen) error("压缩包不完整")
            directory.get(nameBytes)
            val name = String(nameBytes, Charsets.UTF_8).trimStart('/', '\\').replace('\\', '/')
            items += ZipItem(name, method, compressed, size, offset)
            val next = start + 46 + nameLen + extraLen + commentLen
            if (next < start || next > directory.limit()) error("压缩包不完整")
            directory.position(next)
        }
        return items
    }

    private fun readZipEntry(channel: FileChannel, item: ZipItem, limit: Long): ByteArray {
        if (item.size > limit || item.compressedSize > limit) error("压缩包里的文件太大")
        if (item.method != 0 && item.method != 8) error("参考音频读不出来")
        val local = readAt(channel, item.offset, 30)
        if (local.getInt(0) != 0x04034b50) error("压缩包不完整")
        val nameLen = local.getShort(26).toInt() and 0xffff
        val extraLen = local.getShort(28).toInt() and 0xffff
        val dataStart = item.offset + 30L + nameLen + extraLen
        if (item.compressedSize > Int.MAX_VALUE) error("参考音频太大")
        val compressed = readAt(channel, dataStart, item.compressedSize.toInt())
        val raw = ByteArray(item.compressedSize.toInt())
        compressed.get(raw)
        if (item.method == 0) return raw
        val inflater = Inflater(true)
        return try {
            inflater.setInput(raw)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var produced = 0L
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                    continue
                }
                produced += count
                if (produced > limit) error("压缩包里的文件太大")
                output.write(buffer, 0, count)
            }
            if (!inflater.finished()) error("参考音频读不出来")
            output.toByteArray()
        } catch (error: DataFormatException) {
            error("参考音频读不出来")
        } finally {
            inflater.end()
        }
    }

    private fun readAt(channel: FileChannel, position: Long, length: Int): ByteBuffer {
        if (length < 0 || position < 0 || position + length > channel.size()) error("压缩包不完整")
        val buffer = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN)
        var got = 0
        while (got < length) {
            val count = channel.read(buffer, position + got)
            if (count < 0) error("压缩包不完整")
            got += count
        }
        buffer.flip()
        return buffer
    }

    private fun safeZipPath(name: String): Boolean = name.isNotEmpty() && name.split('/').none { it == ".." }

    private fun isAudioName(name: String): Boolean {
        val file = name.substringAfterLast('/').lowercase()
        return AUDIO_EXT.any { file.endsWith(it) }
    }

    private fun audioRank(name: String): Int {
        val file = name.substringAfterLast('/').lowercase()
        val named = listOf("sample", "ref", "prompt", "audio").any { file.contains(it) }
        return when {
            named && file.endsWith(".wav") -> 0
            named -> 1
            file.endsWith(".wav") -> 2
            else -> 3
        }
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String? {
        if (offset < 0 || length < 0 || offset + length > bytes.size) return null
        return String(bytes, offset, length, Charsets.US_ASCII)
    }
}
