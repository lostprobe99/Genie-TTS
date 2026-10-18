package com.genie.onnxdemo

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.genie.onnxdemo.g2p.BertAlign
import com.genie.onnxdemo.g2p.BertTokenizer
import com.genie.onnxdemo.g2p.PhoneResult
import java.io.File
import java.io.InputStream

/** Chinese RoBERTa session that fills text_bert. The reference bert stays untouched. */
class Prosody(
    private val env: OrtEnvironment,
    model: File,
    threads: Int,
    vocab: InputStream,
) : AutoCloseable {
    private val tokenizer = BertTokenizer.load(vocab)
    private val session: OrtSession

    init {
        val options = OrtSession.SessionOptions()
        try {
            options.setIntraOpNumThreads(threads)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            session = env.createSession(model.absolutePath, options)
        } finally {
            options.close()
        }
    }

    fun features(result: PhoneResult): FloatArray? {
        val ids = tokenizer.encode(result.normalized)
        if (ids.size != result.word2ph.size + 2) return null
        val (matrix, rows) = run(ids)
        return BertAlign.expand(matrix, rows, result.word2ph, result.ids.size)
    }

    private fun run(ids: IntArray): Pair<FloatArray, Int> {
        val shape = longArrayOf(1, ids.size.toLong())
        val inputs = hashMapOf(
            "input_ids" to OwnedTensor.fromLongs(shape, LongArray(ids.size) { ids[it].toLong() }).toTensor(env),
            "token_type_ids" to OwnedTensor.fromLongs(shape, LongArray(ids.size)).toTensor(env),
            "attention_mask" to OwnedTensor.fromLongs(shape, LongArray(ids.size) { 1L }).toTensor(env),
        )
        val result = session.run(inputs)
        try {
            val tensor = result.get(0) as OnnxTensor
            val info = tensor.info as TensorInfo
            val (rows, cols) = when (info.shape.size) {
                2 -> info.shape[0].toInt() to info.shape[1].toInt()
                3 -> {
                    if (info.shape[0] != 1L) error("韵律输出的 batch 不是 1")
                    info.shape[1].toInt() to info.shape[2].toInt()
                }
                else -> error("韵律输出维度不对")
            }
            if (cols != BertAlign.DIM) error("韵律维度是 $cols")
            val buffer = tensor.floatBuffer
            buffer.rewind()
            val data = FloatArray(rows * cols)
            if (buffer.remaining() < data.size) error("韵律输出长度对不上")
            buffer.get(data)
            return data to rows
        } finally {
            result.close()
            inputs.values.forEach { it.close() }
        }
    }

    override fun close() {
        session.close()
    }

    companion object {
        const val MODEL_BYTES = 599034176L
        const val FILE_NAME = "model_fp16.onnx"

        fun modelFile(caseDir: File): File = File(caseDir, "roberta/$FILE_NAME")

        fun isReady(caseDir: File): Boolean {
            val file = modelFile(caseDir)
            return file.isFile && file.length() == MODEL_BYTES
        }
    }
}
