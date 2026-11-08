package com.genie.tts.character

import java.io.File

/** 角色包的文件布局和规则。读包、校验、加载都从这里取名字和规则,不各写一份。 */
internal object CharacterPackSchema {
    const val FORMAT = 1
    const val MANIFEST = "manifest.json"
    const val MODELS_DIR = "models"
    const val MODEL_PARTS = "models/parts.json"
    const val REF_SEQ = "tensors/ref_seq.bin"
    const val LEGACY_NAME_FILE = "display-name.txt"
    const val LEGACY_AVATAR_FILE = "avatar-icon.txt"
    const val MIN_MODEL_BYTES = 1024L

    const val KEY_REF_TEXT = "ref_text"
    const val KEY_SAMPLE_RATE = "sample_rate"
    const val KEY_TENSORS = "tensors"

    val MODEL_NAMES = listOf(
        "t2s_encoder_fp32.onnx",
        "t2s_first_stage_decoder_fp32.onnx",
        "t2s_stage_decoder_fp32.onnx",
        "vits_fp32.onnx",
    )
    val REQUIRED_TENSORS = listOf("ref_seq", "ref_bert", "ssl_content", "ge", "ge_advanced")
    val AVATAR_IMAGES = listOf("avatar.webp", "avatar.png", "avatar.jpg", "avatar.jpeg")

    private val ID_PATTERN = Regex("[a-z][a-z0-9_]{0,31}")

    fun normalizeId(raw: String): String = raw.trim().lowercase()

    fun isValidId(id: String): Boolean = ID_PATTERN.matches(id)

    fun manifestFile(root: File): File = File(root, MANIFEST)

    fun modelsDir(root: File): File = File(root, MODELS_DIR)

    fun modelFile(root: File, name: String): File = File(modelsDir(root), name)

    fun refSeqFile(root: File): File = File(root, REF_SEQ)

    fun tensorBytes(dtype: String, shape: LongArray): Long {
        val width = when (dtype) {
            "float32" -> 4L
            "int64" -> 8L
            else -> error("不支持的 dtype: $dtype")
        }
        return shape.fold(1L) { acc, dim -> acc * dim } * width
    }
}
