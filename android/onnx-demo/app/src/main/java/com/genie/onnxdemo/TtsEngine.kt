package com.genie.onnxdemo

import android.os.SystemClock
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class OwnedTensor(
    val shape: LongArray,
    val type: OnnxJavaType,
    val bytes: ByteBuffer,
) {
    fun toTensor(env: OrtEnvironment): OnnxTensor {
        val view = bytes.duplicate().order(ByteOrder.nativeOrder())
        view.position(0)
        view.limit(view.capacity())
        return OnnxTensor.createTensor(env, view, shape, type)
    }

    companion object {
        fun fromFile(file: File, dtype: String, shape: LongArray): OwnedTensor {
            val type = when (dtype) {
                "float32" -> OnnxJavaType.FLOAT
                "int64" -> OnnxJavaType.INT64
                else -> error("不支持的 dtype: $dtype")
            }
            val expected = shape.fold(1L) { acc, dim -> acc * dim } * typeSize(type)
            val bytes = readDirect(file)
            if (bytes.capacity().toLong() != expected) {
                error("${file.name} 大小是 ${bytes.capacity()} 字节，按 shape 应为 $expected")
            }
            return OwnedTensor(shape, type, bytes)
        }

        fun fromLongs(shape: LongArray, values: LongArray): OwnedTensor {
            val copy = ByteBuffer.allocateDirect(values.size * 8).order(ByteOrder.nativeOrder())
            values.forEach { copy.putLong(it) }
            copy.flip()
            return OwnedTensor(shape, OnnxJavaType.INT64, copy)
        }

        fun fromFloats(shape: LongArray, values: FloatArray): OwnedTensor {
            val copy = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
            values.forEach { copy.putFloat(it) }
            copy.flip()
            return OwnedTensor(shape, OnnxJavaType.FLOAT, copy)
        }

        fun fromOutput(tensor: OnnxTensor): OwnedTensor {
            val info = tensor.info as TensorInfo
            if (info.type == OnnxJavaType.BOOL) {
                val bit: Byte = if (tensor.value == true) 1 else 0
                val copy = ByteBuffer.allocateDirect(1).order(ByteOrder.nativeOrder())
                copy.put(bit)
                copy.flip()
                return OwnedTensor(info.shape.copyOf(), OnnxJavaType.BOOL, copy)
            }
            val src = tensor.byteBuffer.duplicate().order(ByteOrder.nativeOrder())
            src.position(0)
            val copy = ByteBuffer.allocateDirect(src.remaining()).order(ByteOrder.nativeOrder())
            copy.put(src)
            copy.flip()
            return OwnedTensor(info.shape.copyOf(), info.type, copy)
        }

        private fun readDirect(file: File): ByteBuffer {
            FileInputStream(file).channel.use { channel ->
                val buffer = ByteBuffer.allocateDirect(channel.size().toInt()).order(ByteOrder.nativeOrder())
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer) < 0) break
                }
                buffer.flip()
                return buffer
            }
        }

        private fun typeSize(type: OnnxJavaType): Int = when (type) {
            OnnxJavaType.FLOAT -> 4
            OnnxJavaType.INT64 -> 8
            else -> error("不支持的类型: $type")
        }
    }
}

class GenieCase(val root: File) {
    val text: String
    val sampleRate: Int
    val tensors: Map<String, OwnedTensor>
    val modelDir: File = File(root, "models")

    init {
        val manifest = JSONObject(File(root, "manifest.json").readText())
        text = manifest.optString("text")
        sampleRate = manifest.getInt("sample_rate")
        val tensorJson = manifest.getJSONObject("tensors")
        val loaded = HashMap<String, OwnedTensor>()
        for (name in tensorJson.keys()) {
            val spec = tensorJson.getJSONObject(name)
            val shapeJson = spec.getJSONArray("shape")
            val shape = LongArray(shapeJson.length()) { shapeJson.getLong(it) }
            loaded[name] = OwnedTensor.fromFile(File(root, spec.getString("file")), spec.getString("dtype"), shape)
        }
        tensors = loaded
    }

    fun model(name: String): File = File(modelDir, name)

    companion object {
        val MODEL_NAMES = listOf(
            "t2s_encoder_fp32.onnx",
            "t2s_first_stage_decoder_fp32.onnx",
            "t2s_stage_decoder_fp32.onnx",
            "vits_fp32.onnx",
        )

        fun find(candidates: List<File>): GenieCase? {
            return candidates.firstOrNull { File(it, "manifest.json").isFile }?.let { GenieCase(it) }
        }
    }
}

private val STAGE_INPUTS: List<String> = buildList {
    add("iy")
    add("iy_emb")
    repeat(24) { layer ->
        add("past_k_layer_$layer")
        add("past_v_layer_$layer")
    }
}

class TtsEngine(
    private val env: OrtEnvironment,
    private val caseDir: File,
    private val threads: Int,
) : AutoCloseable {
    private val sessions = HashMap<String, OrtSession>()

    fun load(log: (String) -> Unit): Long {
        val started = SystemClock.elapsedRealtime()
        val optionsDir = File(caseDir, "optimized").apply { mkdirs() }
        for (name in GenieCase.MODEL_NAMES) {
            if (sessions.containsKey(name)) continue
            sessions[name] = openSession(name, optionsDir, name == "vits_fp32.onnx", log)
        }
        return SystemClock.elapsedRealtime() - started
    }

    private fun openSession(name: String, optionsDir: File, useXnnpack: Boolean, log: (String) -> Unit): OrtSession {
        val model = File(caseDir, "models/$name").absolutePath
        if (useXnnpack) {
            val options = sessionOptions(File(optionsDir, "$name.xnnpack.opt").absolutePath)
            try {
                options.addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
                val session = env.createSession(model, options)
                log("已加载 $name（XNNPACK）")
                return session
            } catch (error: Exception) {
                log("声码器 XNNPACK 不可用，改用 CPU：${error.message}")
            } finally {
                options.close()
            }
        }
        val options = sessionOptions(File(optionsDir, "$name.opt").absolutePath)
        try {
            val session = env.createSession(model, options)
            log("已加载 $name（CPU）")
            return session
        } finally {
            options.close()
        }
    }

    private fun sessionOptions(optimizedPath: String): OrtSession.SessionOptions {
        return OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setOptimizedModelFilePath(optimizedPath)
        }
    }

    fun synthesize(
        case: GenieCase,
        textSeq: OwnedTensor,
        textBert: OwnedTensor,
        log: (String) -> Unit,
        refBert: OwnedTensor? = null,
    ): SynthesisReport {
        val totalStart = SystemClock.elapsedRealtime()
        val encoderStart = SystemClock.elapsedRealtime()
        val encoded = run(
            sessions.getValue("t2s_encoder_fp32.onnx"),
            mapOf(
                "ref_seq" to case.tensors.getValue("ref_seq"),
                "text_seq" to textSeq,
                "ref_bert" to (refBert ?: case.tensors.getValue("ref_bert")),
                "text_bert" to textBert,
                "ssl_content" to case.tensors.getValue("ssl_content"),
            ),
        )
        val encoderMs = SystemClock.elapsedRealtime() - encoderStart
        val x = encoded[0]
        val prompts = encoded[1]

        val first = run(
            sessions.getValue("t2s_first_stage_decoder_fp32.onnx"),
            mapOf("x" to x, "prompts" to prompts),
        )
        val stage = sessions.getValue("t2s_stage_decoder_fp32.onnx")
        val decoderStart = SystemClock.elapsedRealtime()
        var steps = 0
        var previous: OrtSession.Result? = null
        var yTensor = first[0].toTensor(env)
        var yEmbTensor = first[1].toTensor(env)
        var cacheTensors = first.drop(2).map { it.toTensor(env) }
        var closeInputs = true
        var stoppedY: OwnedTensor? = null
        try {
            for (idx in 0 until 500) {
                steps = idx
                val feed = HashMap<String, OnnxTensor>(STAGE_INPUTS.size)
                feed[STAGE_INPUTS[0]] = yTensor
                feed[STAGE_INPUTS[1]] = yEmbTensor
                cacheTensors.forEachIndexed { index, tensor -> feed[STAGE_INPUTS[index + 2]] = tensor }
                val result = stage.run(feed)
                if (closeInputs) {
                    yTensor.close()
                    yEmbTensor.close()
                    cacheTensors.forEach { it.close() }
                }
                previous?.close()
                previous = result
                closeInputs = false
                yTensor = result.get(0) as OnnxTensor
                yEmbTensor = result.get(1) as OnnxTensor
                val stop = readBool(result.get(2) as OnnxTensor)
                cacheTensors = List(result.size() - 3) { result.get(it + 3) as OnnxTensor }
                if (idx % 20 == 0) log("decoder $idx")
                if (stop) break
            }
            stoppedY = OwnedTensor.fromOutput(yTensor)
        } finally {
            previous?.close()
        }
        val decoderMs = SystemClock.elapsedRealtime() - decoderStart

        val semantic = finishSemantic(stoppedY ?: error("解码没有输出"), steps)
        val vocoderStart = SystemClock.elapsedRealtime()
        val audioTensor = run(
            sessions.getValue("vits_fp32.onnx"),
            mapOf(
                "text_seq" to textSeq,
                "pred_semantic" to semantic,
                "ge" to case.tensors.getValue("ge"),
                "ge_advanced" to case.tensors.getValue("ge_advanced"),
            ),
        ).single()
        val vocoderMs = SystemClock.elapsedRealtime() - vocoderStart
        val samples = audioTensor.bytes.duplicate().order(ByteOrder.nativeOrder()).let { buf ->
            buf.position(0)
            FloatArray(buf.remaining() / 4) { buf.float }
        }
        val totalMs = SystemClock.elapsedRealtime() - totalStart
        return SynthesisReport(
            samples = samples,
            sampleRate = case.sampleRate,
            encoderMs = encoderMs,
            decoderSteps = steps + 1,
            decoderMs = decoderMs,
            vocoderMs = vocoderMs,
            totalMs = totalMs,
        )
    }

    override fun close() {
        sessions.values.forEach { it.close() }
        sessions.clear()
    }

    private fun run(session: OrtSession, inputs: Map<String, OwnedTensor>): List<OwnedTensor> {
        val tensors = inputs.mapValues { it.value.toTensor(env) }
        try {
            session.run(tensors).use { result ->
                return List(result.size()) { index ->
                    OwnedTensor.fromOutput(result.get(index) as OnnxTensor)
                }
            }
        } finally {
            tensors.values.forEach { it.close() }
        }
    }
}

data class SynthesisReport(
    val samples: FloatArray,
    val sampleRate: Int,
    val encoderMs: Long,
    val decoderSteps: Int,
    val decoderMs: Long,
    val vocoderMs: Long,
    val totalMs: Long,
) {
    val durationSec: Double = samples.size.toDouble() / sampleRate
    val realTimeFactor: Double = (totalMs / 1000.0) / durationSec
}

private fun readBool(tensor: OnnxTensor): Boolean {
    return tensor.value == true
}

private fun finishSemantic(y: OwnedTensor, idx: Int): OwnedTensor {
    val length = y.shape.last().toInt()
    val buf = y.bytes.duplicate().order(ByteOrder.nativeOrder())
    buf.position(0)
    val values = LongArray(length) { buf.long }
    values[values.lastIndex] = 0L
    val start = if (idx == 0) 0 else (length - idx).coerceAtLeast(0)
    var sliced = values.copyOfRange(start, length)
    val eos = sliced.indexOfFirst { it >= 1024 }
    if (eos >= 0) sliced = sliced.copyOfRange(0, eos)
    val out = ByteBuffer.allocateDirect(sliced.size * 8).order(ByteOrder.nativeOrder())
    sliced.forEach { out.putLong(it) }
    out.flip()
    return OwnedTensor(longArrayOf(1, 1, sliced.size.toLong()), OnnxJavaType.INT64, out)
}
