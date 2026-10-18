package com.genie.onnxdemo

import android.content.ClipData
import android.content.ClipboardManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import ai.onnxruntime.OrtEnvironment
import com.genie.onnxdemo.g2p.ChineseFrontend
import com.genie.onnxdemo.g2p.G2pFiles
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(null, runnable, "genie-synth", 2L * 1024 * 1024)
    }
    private var engine: TtsEngine? = null
    private var prosody: Prosody? = null
    private var frontend: ChineseFrontend? = null
    private lateinit var logView: TextView
    private lateinit var sentence: EditText
    private lateinit var button: Button
    private lateinit var downloadButton: Button
    private lateinit var prosodyButton: Button
    private lateinit var pickButton: Button
    private lateinit var pickZipButton: Button
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) importFolder(uri)
    }
    private val pickZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importZip(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        logView = findViewById(R.id.log)
        sentence = findViewById(R.id.sentence)
        button = findViewById(R.id.synthesize)
        downloadButton = findViewById(R.id.download)
        prosodyButton = findViewById(R.id.downloadProsody)
        pickButton = findViewById(R.id.pickFolder)
        pickZipButton = findViewById(R.id.pickZip)
        showCase()
        showInterruptedStep()
        downloadButton.setOnClickListener { downloadCase() }
        prosodyButton.setOnClickListener { downloadProsody() }
        pickButton.setOnClickListener { pickFolder.launch(null) }
        pickZipButton.setOnClickListener {
            pickZip.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        }
        button.setOnClickListener { synthesize(locateCase(), sentence.text?.toString()?.trim().orEmpty()) }
        findViewById<Button>(R.id.copyLog).setOnClickListener { copyLog() }
        logView.setOnLongClickListener {
            copyLog()
            true
        }
    }

    override fun onDestroy() {
        engine?.close()
        prosody?.close()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun downloadCase() {
        setBusy(true)
        worker.execute {
            try {
                engine?.close()
                engine = null
                CaseFetcher.download(this) { line -> ui { log(line) } }
                ui {
                    showCase()
                    log("测试数据已放进应用目录。连点两次「合成」，第二次的时间才算数。")
                }
            } catch (error: Exception) {
                ui { logFailure(error) }
            } finally {
                ui { setBusy(false) }
            }
        }
    }

    private fun downloadProsody() {
        setBusy(true)
        worker.execute {
            try {
                val caseDir = locateCase() ?: File(filesDir, "android_case")
                if (!Prosody.isReady(caseDir)) {
                    prosody?.close()
                    prosody = null
                }
                CaseFetcher.downloadRoberta(caseDir) { line -> ui { log(line) } }
                ui { log("韵律模型已放进应用目录。点「合成」即可。声学模型不用重新下载。") }
            } catch (error: Exception) {
                ui { logFailure(error) }
            } finally {
                ui { setBusy(false) }
            }
        }
    }

    private fun importZip(uri: Uri) {
        setBusy(true)
        worker.execute {
            try {
                engine?.close()
                engine = null
                CaseFetcher.importArchive(this, uri) { line -> ui { log(line) } }
                ui {
                    showCase()
                    log("测试数据已解压到应用目录。连点两次「合成」，第二次的时间才算数。")
                }
            } catch (error: Exception) {
                ui { logFailure(error) }
            } finally {
                ui { setBusy(false) }
            }
        }
    }

    private fun importFolder(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // 本次选择已经带有读取授权，复制完成前不需要长期保存。
        }
        setBusy(true)
        worker.execute {
            try {
                engine?.close()
                engine = null
                CaseFetcher.importTree(this, uri) { line -> ui { log(line) } }
                ui {
                    showCase()
                    log("目录已复制到应用里。连点两次「合成」，第二次的时间才算数。")
                }
            } catch (error: Exception) {
                ui { logFailure(error) }
            } finally {
                ui { setBusy(false) }
            }
        }
    }

    private fun synthesize(caseDir: File?, typed: String) {
        if (caseDir == null) {
            log("还没有模型。请先单独下载 feibi-case.zip，再点「导入」。")
            return
        }
        if (typed.isBlank()) {
            log("请先输入一句中文。")
            return
        }
        setBusy(true)
        worker.execute {
            try {
                mark("加载中文前端")
                val phones = phonesOf(typed)
                if (phones == null) {
                    clearMark()
                    return@execute
                }
                val threads = min(6, max(1, Runtime.getRuntime().availableProcessors()))
                val textBert = textBert(caseDir, phones, threads)
                mark("加载声学模型")
                val current = engine ?: TtsEngine(OrtEnvironment.getEnvironment(), caseDir, threads).also {
                    engine = it
                    val loadMs = it.load { line -> ui { log(line) } }
                    ui { log("模型加载 ${loadMs} ms，线程 $threads") }
                }
                mark("合成")
                val length = phones.ids.size
                val textSeq = OwnedTensor.fromLongs(
                    longArrayOf(1, length.toLong()),
                    LongArray(length) { phones.ids[it].toLong() },
                )
                val report = current.synthesize(GenieCase(caseDir), textSeq, textBert) { line -> ui { log(line) } }
                val wav = File(caseDir, "phone.wav")
                writeWav(wav, report.samples, report.sampleRate)
                mark("播放")
                play(report.samples, report.sampleRate)
                clearMark()
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
            } catch (error: Throwable) {
                val summary = error.javaClass.simpleName + ": " + error.message.orEmpty()
                mark("失败 $summary")
                ui {
                    log(summary)
                    if (error is Exception) logFailure(error)
                }
            } finally {
                ui { setBusy(false) }
            }
        }
    }

    private fun mark(step: String) {
        try {
            FileOutputStream(File(filesDir, "last_step.txt")).use { out ->
                out.write(step.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
        } catch (_: Exception) {
        }
    }

    private fun clearMark() {
        File(filesDir, "last_step.txt").delete()
    }

    private fun showInterruptedStep() {
        val file = File(filesDir, "last_step.txt")
        if (!file.isFile) return
        val step = file.readText().trim()
        if (step.isNotEmpty()) log("上次没有正常结束：$step")
    }

    private fun showCase() {
        val caseDir = locateCase()
        findViewById<TextView>(R.id.casePath).text = caseDir?.absolutePath ?: expectedPath().absolutePath
        sentence.setText(readSentence(caseDir)?.removePrefix("。") ?: getString(R.string.sentence))
        logView.text = ""
        if (caseDir == null) {
            log("还没有模型。请先单独下载 feibi-case.zip，再点「导入」。应用会把它解压到应用目录。")
        } else {
            log("已找到 ${caseDir.absolutePath}")
            if (Prosody.isReady(caseDir)) log("韵律模型已在")
            else log("还没有韵律模型。点「下载韵律模型」后，停顿和重音会跟着句子走。没有它也能合成。")
        }
    }

    private fun setBusy(busy: Boolean) {
        downloadButton.isEnabled = !busy
        prosodyButton.isEnabled = !busy
        pickButton.isEnabled = !busy
        pickZipButton.isEnabled = !busy
        button.isEnabled = !busy
    }

    private fun textBert(caseDir: File, phones: com.genie.onnxdemo.g2p.PhoneResult, threads: Int): OwnedTensor {
        val length = phones.ids.size
        fun zeros() = OwnedTensor.fromFloats(longArrayOf(length.toLong(), 1024), FloatArray(length * 1024))
        if (!Prosody.isReady(caseDir)) {
            ui { log("韵律模型还没放进来，text_bert 仍为零") }
            return zeros()
        }
        mark("加载韵律模型")
        val current = prosody ?: run {
            val started = SystemClock.elapsedRealtime()
            val loaded = Prosody(
                OrtEnvironment.getEnvironment(),
                Prosody.modelFile(caseDir),
                threads,
                assets.open("roberta/vocab.txt"),
            )
            prosody = loaded
            ui { log("韵律模型加载 ${SystemClock.elapsedRealtime() - started} ms") }
            loaded
        }
        mark("计算韵律")
        val started = SystemClock.elapsedRealtime()
        val values = current.features(phones)
        val elapsed = SystemClock.elapsedRealtime() - started
        if (values == null) {
            ui {
                log("韵律对齐不上（音素 ${phones.ids.size}，字 ${phones.word2ph.size}），text_bert 仍为零")
            }
            return zeros()
        }
        ui { log("韵律 $elapsed ms") }
        return OwnedTensor.fromFloats(longArrayOf(length.toLong(), 1024), values)
    }

    private fun phonesOf(typed: String): com.genie.onnxdemo.g2p.PhoneResult? {
        val current = frontend ?: run {
            val started = SystemClock.elapsedRealtime()
            val loaded = ChineseFrontend(G2pFiles { name -> assets.open("g2p/$name") })
            frontend = loaded
            ui { log("前端加载 ${SystemClock.elapsedRealtime() - started} ms") }
            loaded
        }
        val result = current.infer(typed)
        if (result.ids.isEmpty()) {
            ui { log("这句话没有得到音素") }
            return null
        }
        ui {
            log("归一化 ${result.normalized}")
            log("音素 ${result.ids.size}")
            log(result.phones.joinToString(" "))
        }
        return result
    }

    private fun readSentence(caseDir: File?): String? {
        if (caseDir == null) return null
        return try {
            JSONObject(File(caseDir, "manifest.json").readText()).optString("text").ifBlank { null }
        } catch (_: Exception) {
            null
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

    private fun logFailure(error: Exception) {
        val summary = error.message?.lineSequence()?.firstOrNull { it.isNotBlank() }
        if (!summary.isNullOrBlank()) log(summary)
        log(error.stackTraceToString())
    }

    private fun copyLog() {
        val text = logView.text?.toString().orEmpty().trim()
        if (text.isEmpty()) {
            Toast.makeText(this, "还没有日志", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("genie-onnx-log", text))
        Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show()
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
