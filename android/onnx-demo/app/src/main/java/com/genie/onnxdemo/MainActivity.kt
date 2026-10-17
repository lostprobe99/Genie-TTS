package com.genie.onnxdemo

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
    private lateinit var downloadButton: Button
    private lateinit var pickButton: Button
    private lateinit var pickZipButton: Button
    private lateinit var openPackageButton: Button
    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) importFolder(uri)
    }
    private val pickZip = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (!uris.isNullOrEmpty()) importZip(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        logView = findViewById(R.id.log)
        button = findViewById(R.id.synthesize)
        downloadButton = findViewById(R.id.download)
        pickButton = findViewById(R.id.pickFolder)
        pickZipButton = findViewById(R.id.pickZip)
        openPackageButton = findViewById(R.id.openPackage)
        showCase()
        downloadButton.setOnClickListener { downloadCase() }
        pickButton.setOnClickListener { pickFolder.launch(null) }
        pickZipButton.setOnClickListener { pickZip.launch(arrayOf("*/*")) }
        openPackageButton.setOnClickListener { openPackage() }
        button.setOnClickListener { synthesize(locateCase()) }
        findViewById<Button>(R.id.copyLog).setOnClickListener { copyLog() }
        logView.setOnLongClickListener {
            copyLog()
            true
        }
    }

    override fun onDestroy() {
        engine?.close()
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

    private fun openPackage() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(CaseFetcher.PACKAGE_PAGE))
        try {
            startActivity(intent)
        } catch (error: Exception) {
            log(CaseFetcher.PACKAGE_PAGE)
            logFailure(error)
        }
    }

    private fun importZip(uris: List<Uri>) {
        setBusy(true)
        worker.execute {
            try {
                engine?.close()
                engine = null
                CaseFetcher.importArchives(this, uris) { line -> ui { log(line) } }
                ui {
                    showCase()
                    log("压缩包已解压。连点两次「合成」，第二次的时间才算数。")
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

    private fun synthesize(caseDir: File?) {
        if (caseDir == null) {
            log("还没有模型。先在浏览器下载全部分卷，再点「选择压缩包」一次全选。")
            return
        }
        setBusy(true)
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
                ui { logFailure(error) }
            } finally {
                ui { setBusy(false) }
            }
        }
    }

    private fun showCase() {
        val caseDir = locateCase()
        findViewById<TextView>(R.id.casePath).text = caseDir?.absolutePath ?: expectedPath().absolutePath
        logView.text = ""
        if (caseDir == null) {
            log("还没有模型。先点「在浏览器打开压缩包」，把 feibi-case 全部分卷下载到手机，再点「选择压缩包」一次全选。")
        } else {
            log("已找到 ${caseDir.absolutePath}")
        }
    }

    private fun setBusy(busy: Boolean) {
        downloadButton.isEnabled = !busy
        pickButton.isEnabled = !busy
        pickZipButton.isEnabled = !busy
        openPackageButton.isEnabled = !busy
        button.isEnabled = !busy
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
