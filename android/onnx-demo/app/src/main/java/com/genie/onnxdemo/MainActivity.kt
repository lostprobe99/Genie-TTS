package com.genie.onnxdemo

import android.content.ClipData
import android.content.ClipboardManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import ai.onnxruntime.OrtEnvironment
import com.genie.onnxdemo.g2p.ChineseFrontend
import com.genie.onnxdemo.g2p.G2pFiles
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
    private var cachedRefBert: OwnedTensor? = null
    private var frontend: ChineseFrontend? = null
    private lateinit var logView: TextView
    private lateinit var sentence: EditText
    private lateinit var button: Button
    private lateinit var prosodyButton: Button
    private lateinit var pickZipButton: Button
    private lateinit var characters: Spinner
    private var shown: List<CharacterInfo> = emptyList()
    private var selectedId: String? = null
    private var bindingSpinner = false
    private val pickZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importZip(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        logView = findViewById(R.id.log)
        sentence = findViewById(R.id.sentence)
        button = findViewById(R.id.synthesize)
        prosodyButton = findViewById(R.id.downloadProsody)
        pickZipButton = findViewById(R.id.pickZip)
        characters = findViewById(R.id.characters)
        CharacterPack.migrateLegacy(filesDir)
        selectedId = File(filesDir, "selected-character.txt").takeIf { it.isFile }?.readText()?.trim()
        refreshCharacters(announce = true)
        showInterruptedStep()
        characters.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (bindingSpinner) return
                val info = shown.getOrNull(position) ?: return
                if (info.id == selectedId) return
                engine?.close()
                engine = null
                cachedRefBert = null
                selectedId = info.id
                persistSelection()
                showSelection()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        prosodyButton.setOnClickListener { downloadProsody() }
        pickZipButton.setOnClickListener {
            pickZip.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        }
        button.setOnClickListener { synthesize(selectedDir(), sentence.text?.toString()?.trim().orEmpty()) }
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

    private fun downloadProsody() {
        setBusy(true)
        worker.execute {
            try {
                if (!Prosody.isReady(filesDir)) {
                    prosody?.close()
                    prosody = null
                    cachedRefBert = null
                }
                CaseFetcher.downloadRoberta(filesDir) { line -> ui { log(line) } }
                ui { log("韵律模型已放进应用目录。点「合成」即可。") }
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
                val result = contentResolver.openInputStream(uri)?.use { input ->
                    CharacterPack.importZip(filesDir, input)
                } ?: throw IllegalStateException("读不到选择的压缩包")
                if (selectedId == result.info.id) {
                    engine?.close()
                    engine = null
                    cachedRefBert = null
                }
                selectedId = result.info.id
                persistSelection()
                ui {
                    refreshCharacters(announce = false)
                    val verb = if (result.replaced) "已替换" else "已导入"
                    log("$verb ${result.info.name}。现在可以选它来合成。")
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
            log(getString(R.string.empty_characters))
            return
        }
        if (typed.isBlank()) {
            log("请先输入一句中文。")
            return
        }
        val pieces = TextSplitter().split(typed)
        if (pieces.isEmpty()) {
            log("这句话切完没有可合成的内容")
            return
        }
        setBusy(true)
        worker.execute {
            try {
                val total = pieces.size
                ui { log("分成 $total 句") }
                val threads = min(6, max(1, Runtime.getRuntime().availableProcessors()))
                var refBert: OwnedTensor? = null
                var refReady = false
                var played = 0
                pieces.forEachIndexed { index, piece ->
                    val nth = index + 1
                    ui { log("第 $nth/$total 句") }
                    mark("加载中文前端")
                    val phones = phonesOf(piece) ?: return@forEachIndexed
                    val textBert = textBert(caseDir, phones, threads)
                    if (!refReady) {
                        refBert = referenceBert(caseDir)
                        refReady = true
                    }
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
                    val report = current.synthesize(
                        GenieCase(caseDir),
                        textSeq,
                        textBert,
                        { line -> ui { log(line) } },
                        refBert,
                    )
                    val wav = File(caseDir, if (total == 1) "phone.wav" else "phone-$nth.wav")
                    writeWav(wav, report.samples, report.sampleRate)
                    mark("播放")
                    play(report.samples, report.sampleRate)
                    clearMark()
                    played += 1
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
                }
                if (played == total && total > 1) ui { log("这 $total 句都已播放") }
                clearMark()
            } catch (error: Throwable) {
                val summary = error.javaClass.simpleName + ": " + error.message.orEmpty()
                val version = try {
                    installedVersion()
                } catch (_: Exception) {
                    "未知"
                }
                mark("失败 $version $summary")
                ui { logFailure(error) }
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

    private fun refreshCharacters(announce: Boolean) {
        shown = CharacterPack.list(filesDir)
        if (selectedId !in shown.map { it.id }) selectedId = shown.firstOrNull()?.id
        bindingSpinner = true
        characters.adapter = ArrayAdapter(this, R.layout.spinner_item, shown.map { it.name })
        val index = shown.indexOfFirst { it.id == selectedId }
        if (index >= 0) characters.setSelection(index)
        bindingSpinner = false
        persistSelection()
        showSelection()
        if (!announce) return
        if (shown.isEmpty()) log(getString(R.string.empty_characters))
        else log("已有角色：" + shown.joinToString("、") { it.name })
        if (Prosody.isReady(filesDir)) log("韵律模型已在")
        else log("还没有韵律模型。点「下载韵律模型」，下完再点「合成」。")
    }

    private fun showSelection() {
        val dir = selectedDir()
        findViewById<TextView>(R.id.casePath).text = dir?.absolutePath ?: CharacterPack.directory(filesDir).absolutePath
    }

    private fun selectedDir(): File? {
        val id = selectedId ?: return null
        val dir = File(CharacterPack.directory(filesDir), id)
        return dir.takeIf { File(it, "manifest.json").isFile }
    }

    private fun persistSelection() {
        val file = File(filesDir, "selected-character.txt")
        val id = selectedId
        if (id.isNullOrBlank()) {
            file.delete()
        } else {
            file.writeText(id)
        }
    }

    private fun setBusy(busy: Boolean) {
        prosodyButton.isEnabled = !busy
        pickZipButton.isEnabled = !busy
        characters.isEnabled = !busy
        button.isEnabled = !busy && selectedDir() != null
    }

    private fun textBert(caseDir: File, phones: com.genie.onnxdemo.g2p.PhoneResult, threads: Int): OwnedTensor {
        val length = phones.ids.size
        fun zeros() = OwnedTensor.fromFloats(longArrayOf(length.toLong(), 1024), FloatArray(length * 1024))
        if (!Prosody.isReady(filesDir)) {
            ui { log("韵律模型还没放进来，text_bert 仍为零") }
            return zeros()
        }
        mark("加载韵律模型")
        val current = prosody ?: run {
            val started = SystemClock.elapsedRealtime()
            val loaded = Prosody(
                OrtEnvironment.getEnvironment(),
                Prosody.modelFile(filesDir),
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

    private fun referenceBert(caseDir: File): OwnedTensor? {
        cachedRefBert?.let {
            ui { log("参考韵律 ${it.shape[0]}") }
            return it
        }
        val current = prosody
        if (current == null) {
            ui { log("参考韵律仍为零") }
            return null
        }
        mark("计算参考韵律")
        val spoken = frontend?.phones(ReferenceLine.text(caseDir))
        if (spoken == null) {
            ui { log("参考韵律仍为零") }
            return null
        }
        val stored = ReferenceLine.storedIds(caseDir)
        if (!spoken.ids.contentEquals(stored)) {
            ui { log("参考音素和测试包对不上，参考韵律仍为零") }
            return null
        }
        val started = SystemClock.elapsedRealtime()
        val values = current.features(spoken)
        val elapsed = SystemClock.elapsedRealtime() - started
        if (values == null) {
            ui { log("参考韵律对齐不上，仍为零") }
            return null
        }
        val tensor = OwnedTensor.fromFloats(longArrayOf(spoken.ids.size.toLong(), 1024), values)
        cachedRefBert = tensor
        ui { log("参考韵律 ${spoken.ids.size}，$elapsed ms") }
        return tensor
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

    private fun logFailure(error: Throwable) {
        val context = try {
            failureContext()
        } catch (failure: Exception) {
            "版本读取失败 ${failure.message.orEmpty()}"
        }
        log(context)
        log(error.javaClass.simpleName + ": " + error.message.orEmpty())
        log(error.stackTraceToString())
    }

    private fun installedVersion(): String {
        val (name, code) = packageVersion()
        return "$name（$code）"
    }

    private fun failureContext(): String {
        val (name, code) = packageVersion()
        return formatFailureContext(
            RuntimeFacts(
                versionName = name,
                versionCode = code,
                androidRelease = Build.VERSION.RELEASE ?: "未知",
                api = Build.VERSION.SDK_INT,
                manufacturer = Build.MANUFACTURER ?: "未知",
                model = Build.MODEL ?: "未知",
                abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "未知",
                cores = Runtime.getRuntime().availableProcessors(),
                maxMemoryMb = Runtime.getRuntime().maxMemory() / (1024 * 1024),
                characterId = selectedId,
                prosodyReady = Prosody.isReady(filesDir),
            )
        )
    }

    private fun packageVersion(): Pair<String, Long> {
        val info = packageManager.getPackageInfo(packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        return (info.versionName ?: "未知") to code
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
