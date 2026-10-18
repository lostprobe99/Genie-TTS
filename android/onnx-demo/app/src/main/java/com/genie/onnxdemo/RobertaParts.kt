package com.genie.onnxdemo

/** Names of the manually downloaded RoBERTa pieces. */
object RobertaParts {
    const val COUNT = 13
    const val TOTAL_BYTES = 599034176L
    private val PART = Regex("""model_fp16\.onnx\.(\d{2})""")

    fun fileTitle(name: String): String {
        var file = name.substringAfterLast('/')
        val paren = file.indexOf(" (")
        if (paren >= 0) file = file.substring(0, paren)
        if (file.endsWith(".bin")) file = file.removeSuffix(".bin")
        return file
    }

    fun partIndex(name: String): Int? {
        val match = PART.matchEntire(fileTitle(name)) ?: return null
        val index = match.groupValues[1].toInt()
        return index.takeIf { it in 0 until COUNT }
    }

    fun isWholeModel(name: String): Boolean = fileTitle(name) == "model_fp16.onnx"

    fun missing(indexes: Collection<Int>): List<Int> = (0 until COUNT).filter { it !in indexes }

    fun duplicates(indexes: Collection<Int>): List<Int> {
        return indexes.groupingBy { it }.eachCount().filter { it.value > 1 }.keys.sorted()
    }
}
