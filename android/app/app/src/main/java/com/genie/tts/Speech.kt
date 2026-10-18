package com.genie.tts

import ai.onnxruntime.OrtEnvironment
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.genie.tts.g2p.ChineseFrontend
import com.genie.tts.g2p.G2pFiles
import java.io.File
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

internal data class Clip(
    val text: String,
    val samples: FloatArray?,
    val sampleRate: Int,
    val error: String?,
)

internal class RefMismatch : Exception("参考音素和角色包里的原文对不上，请重新导入这个角色")

internal class Speech(
    private val filesDir: File,
    private val openAsset: (String) -> InputStream,
) : AutoCloseable {
    private var engine: TtsEngine? = null
    private var engineKey: String? = null
    private var prosody: Prosody? = null
    private var frontend: ChineseFrontend? = null
    private var refBert: OwnedTensor? = null
    private var refKey: String? = null

    fun synthesize(
        character: CharacterInfo,
        pieces: List<String>,
        speed: Double,
        threads: Int,
        onPiece: (Int, Clip) -> Unit = { _, _ -> },
    ): List<Clip> {
        val ready = Prosody.isReady(filesDir)
        val ref = if (ready) referenceBert(character, threads) else null
        val loaded = engineFor(character, threads)
        return pieces.mapIndexed { index, piece ->
            val clip = try {
                speakPiece(loaded, character, piece, speed, threads, ready, ref)
            } catch (error: Exception) {
                Clip(piece, null, characterSampleRate(character), sentenceError(error))
            }
            onPiece(index, clip)
            clip
        }
    }

    private fun speakPiece(
        loaded: TtsEngine,
        character: CharacterInfo,
        piece: String,
        speed: Double,
        threads: Int,
        ready: Boolean,
        ref: OwnedTensor?,
    ): Clip {
        val phones = frontend().infer(piece)
        if (phones.ids.isEmpty()) {
            return Clip(piece, null, characterSampleRate(character), "这句没有读出来")
        }
        val length = phones.ids.size
        val textSeq = OwnedTensor.fromLongs(
            longArrayOf(1, length.toLong()),
            LongArray(length) { phones.ids[it].toLong() },
        )
        val bert = textBert(phones, threads, ready)
        val report = loaded.synthesize(GenieCase(character.root), textSeq, bert, {}, ref, speed)
        return Clip(piece, report.samples, report.sampleRate, null)
    }

    fun preview(character: CharacterInfo, threads: Int): Clip {
        val text = ReferenceLine.text(character.root)
        return synthesize(character, listOf(text), 1.0, threads).single()
    }

    override fun close() {
        engine?.close()
        engine = null
        prosody?.close()
        prosody = null
        refBert = null
    }

    fun dropEngine() {
        engine?.close()
        engine = null
        engineKey = null
        refBert = null
        refKey = null
    }

    fun dropProsody() {
        prosody?.close()
        prosody = null
        refBert = null
        refKey = null
    }

    private fun engineFor(character: CharacterInfo, threads: Int): TtsEngine {
        val key = "${character.id}|$threads"
        if (engine != null && engineKey == key) return engine!!
        dropEngine()
        val created = TtsEngine(OrtEnvironment.getEnvironment(), character.root, threads)
        created.load { }
        engine = created
        engineKey = key
        return created
    }

    private fun frontend(): ChineseFrontend {
        frontend?.let { return it }
        val loaded = ChineseFrontend(G2pFiles { name -> openAsset("g2p/$name") })
        frontend = loaded
        return loaded
    }

    private fun prosodySession(threads: Int): Prosody {
        prosody?.let { return it }
        val loaded = Prosody(
            OrtEnvironment.getEnvironment(),
            Prosody.modelFile(filesDir),
            threads,
            openAsset("roberta/vocab.txt"),
        )
        prosody = loaded
        return loaded
    }

    private fun textBert(phones: com.genie.tts.g2p.PhoneResult, threads: Int, ready: Boolean): OwnedTensor {
        val length = phones.ids.size
        fun zeros() = OwnedTensor.fromFloats(longArrayOf(length.toLong(), 1024), FloatArray(length * 1024))
        if (!ready) return zeros()
        val values = prosodySession(threads).features(phones) ?: return zeros()
        return OwnedTensor.fromFloats(longArrayOf(length.toLong(), 1024), values)
    }

    private fun referenceBert(character: CharacterInfo, threads: Int): OwnedTensor? {
        if (refBert != null && refKey == character.id) return refBert
        refBert = null
        refKey = null
        val spoken = frontend().phones(ReferenceLine.text(character.root))
        val stored = ReferenceLine.storedIds(character.root)
        if (!spoken.ids.contentEquals(stored)) throw RefMismatch()
        val values = prosodySession(threads).features(spoken) ?: throw RefMismatch()
        val tensor = OwnedTensor.fromFloats(longArrayOf(spoken.ids.size.toLong(), 1024), values)
        refBert = tensor
        refKey = character.id
        return tensor
    }

    private fun characterSampleRate(character: CharacterInfo): Int {
        return runCatching { GenieCase(character.root).sampleRate }.getOrDefault(32000)
    }

    private fun sentenceError(error: Exception): String {
        val message = error.message.orEmpty()
        if (message.contains("缺少") || message.contains("不完整") || message.contains("太小")) {
            return "角色包不完整，请重新导入"
        }
        return "这句没有读出来"
    }
}

internal fun playSamples(samples: FloatArray, sampleRate: Int, stop: () -> Boolean) {
    val minBuffer = AudioTrack.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_FLOAT,
    )
    val track = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
        )
        .setBufferSizeInBytes(max(minBuffer, sampleRate * 4))
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()
    try {
        track.play()
        var offset = 0
        while (offset < samples.size && !stop()) {
            val count = min(4096, samples.size - offset)
            val wrote = track.write(samples, offset, count, AudioTrack.WRITE_BLOCKING)
            if (wrote <= 0) break
            offset += wrote
        }
        while (!stop() && track.playbackHeadPosition < offset) {
            Thread.sleep(40)
        }
    } finally {
        track.pause()
        track.flush()
        track.release()
    }
}

internal fun speedOf(progress: Int): Double = 0.60 + progress * 0.05
