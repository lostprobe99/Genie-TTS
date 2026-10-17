package com.genie.onnxdemo

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import ai.onnxruntime.OrtEnvironment
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var engine: TtsEngine? = null
    private lateinit var logView: TextView
    private lateinit var button: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        logView = findViewById(R.id.log)
        button = findViewById(R.id.synthesize)
        val caseDir = locateCase()
        findViewById<TextView>(R.id.casePath).text = caseDir?.absolutePath ?: expectedPath().absolutePath
        if (caseDir == null) {
            log(
                "还没有模型。在电脑上执行：\n\n" +
                    "adb push android_case ${expectedPath().parentFile?.absolutePath}/\n\n" +
                    "推送的是 android_case 目录本身，里面要有 manifest.json、models 和 tensors。"
            )
        } else {
            log("已找到 ${caseDir.absolutePath}")
        }
        button.setOnClickListener { synthesize(locateCase()) }
    }

    override fun onDestroy() {
        engine?.close()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun synthesize(caseDir: File?) {
        if (caseDir == null) {
            log(
                "还没有模型。在电脑上执行：\n\n" +
                    "adb push android_case ${expectedPath().parentFile?.absolutePath}/"
            )
            return
        }
        button.isEnabled = false
        worker.execute {
            try {
                val threads = min(6, max(1, Runtime.getRuntime().availableProcessors()))
                val current = engine ?: TtsEngine(OrtEnvironment.getEnvironment(), caseDir, threads).also {
                    engine = it
                    val loadMs = it.load { line -> ui { log(line) } }
                    ui { log("模型加载 ${loadMs} ms，线程 $threads") }
                }
                val report = current.synthesize(GenieCase(caseDir)) { line -> ui { log(line) } }
                val wav = File(caseDir, "phone.wav")
                writeWav(wav, report.samples, report.sampleRate)
                play(report.samples, report.sampleRate)
                ui {
                    log(
                        "encoder ${report.encoderMs} ms\n" +
                            "decoder ${report.decoderSteps} 步，${report.decoderMs} ms\n" +
                            "声码器 ${report.vocoderMs} ms\n" +
                            "总计 ${report.totalMs} ms\n" +
                            "音频 ${"%.2f".format(report.durationSec)} s\n" +
                            "实时率 ${"%.2f".format(report.realTimeFactor)}\n" +
                            "已保存 ${wav.absolutePath}"
                    )
                }
            } catch (error: Exception) {
                ui { log(error.stackTraceToString()) }
            } finally {
                ui { button.isEnabled = true }
            }
        }
    }

    private fun locateCase(): File? {
        val external = getExternalFilesDir(null)
        val candidates = listOfNotNull(external, filesDir).map { File(it, "android_case") }
        return candidates.firstOrNull { File(it, "manifest.json").isFile }
    }

    private fun expectedPath(): File {
        val base = getExternalFilesDir(null) ?: filesDir
        return File(base, "android_case")
    }

    private fun log(line: String) {
        logView.append(line.trimEnd() + "\n")
    }

    private fun ui(block: () -> Unit) {
        runOnUiThread(block)
    }

    private fun play(samples: FloatArray, sampleRate: Int) {
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
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(max(minBuffer, samples.size * 4))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        track.play()
        val durationMs = samples.size * 1000L / sampleRate
        Thread.sleep(durationMs + 200)
        track.stop()
        track.release()
    }

    private fun writeWav(file: File, samples: FloatArray, sampleRate: Int) {
        val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) {
            val clamped = sample.coerceIn(-1f, 1f)
            pcm.putShort((clamped * 32767f).toInt().toShort())
        }
        val data = pcm.array()
        file.outputStream().use { out ->
            out.write(wavHeader(data.size, sampleRate))
            out.write(data)
        }
    }

    private fun wavHeader(dataBytes: Int, sampleRate: Int): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + dataBytes)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray())
        header.putInt(dataBytes)
        return header.array()
    }
}
