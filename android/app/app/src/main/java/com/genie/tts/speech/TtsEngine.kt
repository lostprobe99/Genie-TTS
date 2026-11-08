package com.genie.tts.speech

import com.genie.tts.character.CharacterPackSchema
import com.genie.tts.core.LogLevel
import android.os.SystemClock
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.ByteOrder

private const val MAX_DECODE_STEPS = 500

internal class SynthesisCancelled : Exception()

internal fun discardOptimizedModels(caseDir: File) {
    File(caseDir, "optimized").deleteRecursively()
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

    internal fun load(log: (LogLevel, String) -> Unit): Long {
        val started = SystemClock.elapsedRealtime()
        discardOptimizedModels(caseDir)
        for (name in CharacterPackSchema.MODEL_NAMES) {
            if (sessions.containsKey(name)) continue
            sessions[name] = openSession(name, name == "vits_fp32.onnx", log)
        }
        return SystemClock.elapsedRealtime() - started
    }

    private fun openSession(name: String, useXnnpack: Boolean, log: (LogLevel, String) -> Unit): OrtSession {
        val model = CharacterPackSchema.modelFile(caseDir, name).absolutePath
        if (useXnnpack) {
            val options = sessionOptions()
            try {
                options.addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
                val session = env.createSession(model, options)
                log(LogLevel.INFO, "已加载 $name（XNNPACK）")
                return session
            } catch (error: Exception) {
                log(LogLevel.WARN, "声码器 XNNPACK 不可用，改用 CPU：${error.message}")
            } finally {
                options.close()
            }
        }
        val options = sessionOptions()
        try {
            val session = env.createSession(model, options)
            log(LogLevel.INFO, "已加载 $name（CPU）")
            return session
        } finally {
            options.close()
        }
    }

    private fun sessionOptions(): OrtSession.SessionOptions {
        return OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
    }

    internal fun synthesize(
        case: GenieCase,
        textSeq: OwnedTensor,
        textBert: OwnedTensor,
        log: (LogLevel, String) -> Unit,
        refBert: OwnedTensor? = null,
        speed: Double = 1.0,
        cancelled: () -> Boolean = { false },
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
        var yTensor: OnnxTensor? = null
        var yEmbTensor: OnnxTensor? = null
        var cacheTensors = emptyList<OnnxTensor>()
        var closeInputs = true
        var stoppedY: OwnedTensor? = null
        try {
            yTensor = first[0].toTensor(env)
            yEmbTensor = first[1].toTensor(env)
            val built = ArrayList<OnnxTensor>()
            cacheTensors = built
            for (tensor in first.drop(2)) built.add(tensor.toTensor(env))
            for (idx in 0 until MAX_DECODE_STEPS) {
                if (cancelled()) throw SynthesisCancelled()
                steps = idx
                val feed = HashMap<String, OnnxTensor>(STAGE_INPUTS.size)
                feed[STAGE_INPUTS[0]] = yTensor ?: error("解码没有输出")
                feed[STAGE_INPUTS[1]] = yEmbTensor ?: error("解码没有输出")
                cacheTensors.forEachIndexed { index, tensor -> feed[STAGE_INPUTS[index + 2]] = tensor }
                val result = stage.run(feed)
                if (closeInputs) {
                    yTensor?.close()
                    yEmbTensor?.close()
                    cacheTensors.forEach { it.close() }
                }
                previous?.close()
                previous = result
                closeInputs = false
                yTensor = result.get(0) as OnnxTensor
                yEmbTensor = result.get(1) as OnnxTensor
                val stop = readBool(result.get(2) as OnnxTensor)
                cacheTensors = List(result.size() - 3) { result.get(it + 3) as OnnxTensor }
                if (idx % 20 == 0) log(LogLevel.DEBUG, "decoder $idx")
                if (stop) break
            }
            if (cancelled()) throw SynthesisCancelled()
            stoppedY = OwnedTensor.fromOutput(yTensor ?: error("解码没有输出"))
        } finally {
            if (closeInputs) {
                yTensor?.close()
                yEmbTensor?.close()
                cacheTensors.forEach { it.close() }
            }
            previous?.close()
        }
        val decoderMs = SystemClock.elapsedRealtime() - decoderStart

        val semantic = finishSemantic(stoppedY ?: error("解码没有输出"), steps)
        if (semantic == null) {
            return SynthesisReport(
                samples = FloatArray(0),
                sampleRate = case.sampleRate,
                encoderMs = encoderMs,
                decoderSteps = 0,
                semanticTokens = 0,
                decoderMs = decoderMs,
                vocoderMs = 0,
                totalMs = SystemClock.elapsedRealtime() - totalStart,
            )
        }
        val paced = applySpeed(semantic, speed)
        val semanticTokens = paced.shape.last().toInt()
        val vocoderStart = SystemClock.elapsedRealtime()
        val audioTensor = run(
            sessions.getValue("vits_fp32.onnx"),
            mapOf(
                "text_seq" to textSeq,
                "pred_semantic" to paced,
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
            semanticTokens = semanticTokens,
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
    val semanticTokens: Int,
    val decoderMs: Long,
    val vocoderMs: Long,
    val totalMs: Long,
) {
    val durationSec: Double = samples.size.toDouble() / sampleRate
    val realTimeFactor: Double = (totalMs / 1000.0) / durationSec
}

private fun applySpeed(semantic: OwnedTensor, speed: Double): OwnedTensor {
    val length = semantic.shape.last().toInt()
    val buf = semantic.bytes.duplicate().order(ByteOrder.nativeOrder())
    buf.position(0)
    val tokens = LongArray(length) { buf.long }
    val stretched = stretchTokens(tokens, speed)
    if (stretched.size == tokens.size) return semantic
    return OwnedTensor.fromLongs(longArrayOf(1, 1, stretched.size.toLong()), stretched)
}

private fun readBool(tensor: OnnxTensor): Boolean {
    return tensor.value == true
}

private fun finishSemantic(y: OwnedTensor, idx: Int): OwnedTensor? {
    val length = y.shape.last().toInt()
    val buf = y.bytes.duplicate().order(ByteOrder.nativeOrder())
    buf.position(0)
    val values = LongArray(length) { buf.long }
    val spoken = newlySpokenTokens(values, idx)
    if (spoken.isEmpty()) return null
    return OwnedTensor.fromLongs(longArrayOf(1, 1, spoken.size.toLong()), spoken)
}

/** 第 0 步就结束时没有新语义。这时不能把参考句留在结果里。 */
internal fun newlySpokenTokens(values: LongArray, steps: Int): LongArray {
    if (values.isEmpty() || steps <= 0) return LongArray(0)
    val tokens = values.copyOf()
    tokens[tokens.lastIndex] = 0L
    val start = (tokens.size - steps).coerceAtLeast(0)
    var sliced = tokens.copyOfRange(start, tokens.size)
    val eos = sliced.indexOfFirst { it >= 1024 }
    if (eos >= 0) sliced = sliced.copyOfRange(0, eos)
    return sliced
}
