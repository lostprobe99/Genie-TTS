package com.genie.tts

import android.content.ClipData
import android.content.ClipboardManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private enum class Page { Speak, Characters, Prosody, About }

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "genie").apply { isDaemon = true }
    }
    private val playGen = AtomicInteger(0)
    private val splitter = TextSplitter()

    private lateinit var speech: Speech
    private lateinit var drawer: DrawerLayout
    private lateinit var drawerPanel: View
    private lateinit var menuButton: android.widget.ImageButton
    private lateinit var pageTitle: TextView
    private lateinit var banner: TextView
    private lateinit var speakPage: View
    private lateinit var characterPage: View
    private lateinit var prosodyPage: View
    private lateinit var aboutPage: View
    private lateinit var placeholder: TextView
    private lateinit var characters: android.widget.Spinner
    private lateinit var sentence: EditText
    private lateinit var sentenceScroll: View
    private lateinit var sentenceList: LinearLayout
    private lateinit var speedLabel: TextView
    private lateinit var speed: SeekBar
    private lateinit var synthesizeButton: Button
    private lateinit var playButton: Button
    private lateinit var characterCards: LinearLayout
    private lateinit var importCharacter: Button
    private lateinit var prosodyStatus: TextView
    private lateinit var prosodyDetail: TextView
    private lateinit var downloadProsody: Button
    private lateinit var importProsody: Button
    private lateinit var deleteProsody: Button
    private lateinit var aboutText: TextView

    private var page = Page.Speak
    private var roster: List<CharacterInfo> = emptyList()
    private var selectedId: String? = null
    private var clips: MutableList<Clip?> = mutableListOf()
    private var active = -1
    private var busy = false
    private var playing = false
    private var suppress = false
    private var spinnerGen = 0
    private var downloading = false

    private val pickCharacter = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onCharacterPicked(uri)
    }
    private val pickProsody = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onProsodyPicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        speech = Speech(filesDir) { assets.open(it) }
        CharacterPack.migrateLegacy(filesDir)
        bindViews()
        restoreDraft()
        showInterrupted()
        bindActions()
        refreshCharacters()
        renderSpeed()
        showPage(Page.Speak)
        findViewById<TextView>(R.id.drawerVersion).text = versionName()
    }

    override fun onDestroy() {
        playGen.incrementAndGet()
        if (isFinishing) worker.execute { speech.close() }
        super.onDestroy()
    }

    private fun bindViews() {
        drawer = findViewById(R.id.drawer)
        drawerPanel = findViewById(R.id.drawerPanel)
        menuButton = findViewById(R.id.menuButton)
        pageTitle = findViewById(R.id.pageTitle)
        banner = findViewById(R.id.banner)
        speakPage = findViewById(R.id.speakPage)
        characterPage = findViewById(R.id.characterPage)
        prosodyPage = findViewById(R.id.prosodyPage)
        aboutPage = findViewById(R.id.aboutPage)
        placeholder = findViewById(R.id.characterPlaceholder)
        characters = findViewById(R.id.characters)
        sentence = findViewById(R.id.sentence)
        sentenceScroll = findViewById(R.id.sentenceScroll)
        sentenceList = findViewById(R.id.sentenceList)
        speedLabel = findViewById(R.id.speedLabel)
        speed = findViewById(R.id.speed)
        synthesizeButton = findViewById(R.id.synthesize)
        playButton = findViewById(R.id.play)
        characterCards = findViewById(R.id.characterCards)
        importCharacter = findViewById(R.id.importCharacter)
        prosodyStatus = findViewById(R.id.prosodyStatus)
        prosodyDetail = findViewById(R.id.prosodyDetail)
        downloadProsody = findViewById(R.id.downloadProsody)
        importProsody = findViewById(R.id.importProsody)
        deleteProsody = findViewById(R.id.deleteProsody)
        aboutText = findViewById(R.id.aboutText)
        characters.setPopupBackgroundResource(R.color.ink)
    }

    private fun bindActions() {
        menuButton.setOnClickListener {
            if (page == Page.Speak) drawer.openDrawer(drawerPanel)
            else showPage(Page.Speak)
        }
        findViewById<View>(R.id.openCharacters).setOnClickListener {
            drawer.closeDrawer(drawerPanel)
            showPage(Page.Characters)
        }
        findViewById<View>(R.id.openProsody).setOnClickListener {
            drawer.closeDrawer(drawerPanel)
            showPage(Page.Prosody)
        }
        findViewById<View>(R.id.openAbout).setOnClickListener {
            drawer.closeDrawer(drawerPanel)
            showPage(Page.About)
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    drawer.isDrawerOpen(GravityCompat.START) -> drawer.closeDrawer(GravityCompat.START)
                    page != Page.Speak -> showPage(Page.Speak)
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })
        sentence.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                draftFile().writeText(s?.toString().orEmpty())
                if (!suppress) invalidateAudio()
                updateControls()
            }
        })
        speed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                renderSpeed()
                speedFile().writeText(progress.coerceIn(0, 16).toString())
                if (fromUser && !suppress) invalidateAudio()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        characters.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppress) return
                val next = roster.getOrNull(position)?.id
                if (next == selectedId) return
                selectedId = next
                if (next == null) selectionFile().delete() else selectionFile().writeText(next)
                invalidateAudio()
                worker.execute { speech.dropEngine() }
                if (page == Page.About) aboutText.text = aboutLines()
                updateControls()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        synthesizeButton.setOnClickListener { onSynthesize() }
        playButton.setOnClickListener {
            if (playing) stopPlaying()
            else play(clips.indices.filter { clips[it]?.samples != null })
        }
        importCharacter.setOnClickListener {
            if (busy) return@setOnClickListener
            pickCharacter.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        }
        downloadProsody.setOnClickListener { downloadProsody() }
        importProsody.setOnClickListener {
            if (busy) return@setOnClickListener
            pickProsody.launch(arrayOf("*/*", "application/zip", "application/octet-stream"))
        }
        deleteProsody.setOnClickListener { deleteProsody() }
        findViewById<View>(R.id.copyAbout).setOnClickListener {
            val text = aboutLines()
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("about", text))
            toast("已复制")
        }
    }

    private fun showPage(next: Page) {
        page = next
        speakPage.visibility = if (next == Page.Speak) View.VISIBLE else View.GONE
        characterPage.visibility = if (next == Page.Characters) View.VISIBLE else View.GONE
        prosodyPage.visibility = if (next == Page.Prosody) View.VISIBLE else View.GONE
        aboutPage.visibility = if (next == Page.About) View.VISIBLE else View.GONE
        val onSpeak = next == Page.Speak
        pageTitle.setText(
            when (next) {
                Page.Speak -> R.string.app_name
                Page.Characters -> R.string.characters
                Page.Prosody -> R.string.prosody
                Page.About -> R.string.about
            },
        )
        menuButton.setImageResource(if (onSpeak) R.drawable.ic_menu else R.drawable.ic_back)
        menuButton.contentDescription = if (onSpeak) "菜单" else "返回"
        drawer.setDrawerLockMode(
            if (onSpeak) DrawerLayout.LOCK_MODE_UNLOCKED else DrawerLayout.LOCK_MODE_LOCKED_CLOSED,
            GravityCompat.START,
        )
        when (next) {
            Page.Characters -> renderCards()
            Page.Prosody -> renderProsody()
            Page.About -> aboutText.text = aboutLines()
            Page.Speak -> Unit
        }
    }

    private fun onSynthesize() {
        if (busy || playing) return
        val character = currentCharacter() ?: return
        val text = sentence.text.toString()
        if (text.isBlank()) return
        val pieces = splitter.split(text)
        if (pieces.isEmpty()) {
            toast("这句没有读出来")
            return
        }
        confirmProsody(preview = false) { synthesize(character, pieces) }
    }

    private fun synthesize(character: CharacterInfo, pieces: List<String>) {
        stopPlaying()
        clips = pieces.map { Clip(it, null, 32000, null) }.toMutableList()
        active = 0
        sentenceScroll.visibility = View.VISIBLE
        renderSentences()
        banner.visibility = View.GONE
        setBusy(true)
        val marker = markerFile()
        marker.writeText(versionLabel())
        val rate = speedOf(speed.progress)
        val threads = threads()
        worker.execute {
            try {
                speech.synthesize(character, pieces, rate, threads) { index, clip ->
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        if (index in clips.indices) clips[index] = clip
                        active = if (index + 1 < clips.size) index + 1 else -1
                        renderSentences()
                        updateControls()
                    }
                }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    active = -1
                    renderSentences()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    clearAudio()
                    alert(human(error))
                }
            } finally {
                marker.delete()
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    setBusy(false)
                }
            }
        }
    }

    private fun preview(character: CharacterInfo) {
        if (busy) return
        confirmProsody(preview = true) {
            stopPlaying()
            setBusy(true)
            val marker = markerFile()
            marker.writeText(versionLabel())
            val gen = playGen.incrementAndGet()
            worker.execute {
                try {
                    val clip = speech.preview(character, threads())
                    val samples = clip.samples
                    if (samples == null) {
                        runOnUiThread { if (!isDestroyed) alert(clip.error ?: "这句没有读出来") }
                        return@execute
                    }
                    runOnUiThread {
                        if (isDestroyed || gen != playGen.get()) return@runOnUiThread
                        playing = true
                        updateControls()
                    }
                    playSamples(samples, clip.sampleRate) { gen != playGen.get() }
                } catch (error: Exception) {
                    runOnUiThread { if (!isDestroyed) alert(human(error)) }
                } finally {
                    marker.delete()
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        if (gen == playGen.get()) playing = false
                        setBusy(false)
                    }
                }
            }
        }
    }

    private fun play(indexes: List<Int>) {
        if (busy || indexes.isEmpty()) return
        val gen = playGen.incrementAndGet()
        playing = true
        updateControls()
        worker.execute {
            try {
                for (index in indexes) {
                    if (gen != playGen.get()) return@execute
                    val clip = clips.getOrNull(index) ?: continue
                    val samples = clip.samples ?: continue
                    runOnUiThread {
                        if (isDestroyed || gen != playGen.get()) return@runOnUiThread
                        active = index
                        renderSentences()
                    }
                    playSamples(samples, clip.sampleRate) { gen != playGen.get() }
                }
            } finally {
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    if (gen == playGen.get()) {
                        playing = false
                        active = -1
                        renderSentences()
                        updateControls()
                    }
                }
            }
        }
    }

    private fun stopPlaying() {
        playGen.incrementAndGet()
        if (!playing && active < 0) return
        playing = false
        active = -1
        renderSentences()
        updateControls()
    }

    private fun confirmProsody(preview: Boolean, then: () -> Unit) {
        if (Prosody.isReady(filesDir)) {
            then()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("还没有韵律模型")
            .setMessage("可以朗读，但停顿和重音会弱一些。")
            .setNegativeButton(if (preview) R.string.continue_preview else R.string.continue_synth) { _, _ ->
                then()
            }
            .setPositiveButton(R.string.go_add) { _, _ -> showPage(Page.Prosody) }
            .show()
    }

    private fun onCharacterPicked(uri: Uri) {
        if (busy) return
        worker.execute {
            try {
                val peeked = contentResolver.openInputStream(uri)?.use { CharacterPack.peek(it) }
                    ?: error("打不开这个文件")
                val existing = CharacterPack.list(filesDir).find { it.id == peeked.first }?.name
                val prefill = existing ?: peeked.second
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    askName("给角色命名", prefill) { name -> importCharacter(uri, name) }
                }
            } catch (error: Exception) {
                runOnUiThread { if (!isDestroyed) alert(human(error)) }
            }
        }
    }

    private fun importCharacter(uri: Uri, name: String) {
        if (busy) return
        setBusy(true)
        worker.execute {
            try {
                contentResolver.openInputStream(uri)?.use { CharacterPack.importZip(filesDir, it, name) }
                    ?: error("打不开这个文件")
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    setBusy(false)
                    refreshCharacters()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    setBusy(false)
                    alert(human(error))
                }
            }
        }
    }

    private fun renameCharacter(info: CharacterInfo) {
        askName("给角色命名", info.name) { name ->
            if (busy) return@askName
            setBusy(true)
            worker.execute {
                try {
                    CharacterPack.rename(info, name)
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        setBusy(false)
                        refreshCharacters()
                    }
                } catch (error: Exception) {
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        setBusy(false)
                        alert(human(error))
                    }
                }
            }
        }
    }

    private fun deleteCharacter(info: CharacterInfo) {
        if (busy) return
        setBusy(true)
        val selected = info.id == selectedId
        worker.execute {
            try {
                if (selected) speech.dropEngine()
                info.root.deleteRecursively()
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    if (selected) {
                        selectedId = null
                        selectionFile().delete()
                        invalidateAudio()
                    }
                    setBusy(false)
                    refreshCharacters()
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    setBusy(false)
                    alert(human(error))
                }
            }
        }
    }

    private fun downloadProsody() {
        if (busy) return
        setBusy(true)
        downloading = true
        prosodyDetail.text = "正在下载"
        worker.execute {
            try {
                speech.dropProsody()
                ProsodyPack.download(filesDir) { done, total ->
                    val line = if (total > 0) {
                        "已下载 ${bytesLabel(done)} / ${bytesLabel(total)}"
                    } else {
                        "已下载 ${bytesLabel(done)}"
                    }
                    runOnUiThread { if (!isDestroyed) prosodyDetail.text = line }
                }
            } catch (error: Exception) {
                runOnUiThread { if (!isDestroyed) alert(human(error)) }
            } finally {
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    downloading = false
                    setBusy(false)
                    renderProsody()
                }
            }
        }
    }

    private fun onProsodyPicked(uri: Uri) {
        if (busy) return
        val name = displayName(uri)
        setBusy(true)
        worker.execute {
            try {
                speech.dropProsody()
                contentResolver.openInputStream(uri)?.use { ProsodyPack.importFile(filesDir, it, name) }
                    ?: error("打不开这个文件")
            } catch (error: Exception) {
                runOnUiThread { if (!isDestroyed) alert(human(error)) }
            } finally {
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    setBusy(false)
                    renderProsody()
                }
            }
        }
    }

    private fun deleteProsody() {
        if (busy) return
        setBusy(true)
        worker.execute {
            try {
                speech.dropProsody()
                ProsodyPack.delete(filesDir)
            } catch (error: Exception) {
                runOnUiThread { if (!isDestroyed) alert(human(error)) }
            } finally {
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    setBusy(false)
                    renderProsody()
                }
            }
        }
    }

    private fun refreshCharacters() {
        roster = CharacterPack.list(filesDir)
        val saved = selectionFile().takeIf { it.isFile }?.readText()?.trim()
        selectedId = roster.find { it.id == saved }?.id
        if (selectedId == null) selectionFile().delete()
        if (roster.isEmpty()) {
            placeholder.visibility = View.VISIBLE
            characters.visibility = View.GONE
        } else {
            placeholder.visibility = View.GONE
            characters.visibility = View.VISIBLE
            val labels = roster.map { it.name } + getString(R.string.unselected)
            val generation = ++spinnerGen
            suppress = true
            val adapter = ArrayAdapter(this, R.layout.spinner_item, labels)
            adapter.setDropDownViewResource(R.layout.spinner_item)
            characters.adapter = adapter
            val index = roster.indexOfFirst { it.id == selectedId }
            characters.setSelection(if (index >= 0) index else roster.size, false)
            characters.post {
                if (generation == spinnerGen) suppress = false
            }
        }
        renderCards()
        if (page == Page.About) aboutText.text = aboutLines()
        updateControls()
    }

    private fun renderCards() {
        characterCards.removeAllViews()
        roster.forEach { info ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(18), 0, dp(8))
            }
            card.addView(TextView(this).apply {
                text = info.name
                textSize = 18f
                setTextColor(color(R.color.paper))
            })
            val size = TextView(this).apply {
                text = "…"
                textSize = 15f
                setTextColor(color(R.color.muted))
                gravity = Gravity.END
            }
            val top = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, 0)
            }
            top.addView(tonedButton(getString(R.string.preview), filled = false).apply {
                setOnClickListener { preview(info) }
            })
            top.addView(size, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(top)
            val actions = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, 0)
            }
            actions.addView(
                tonedButton(getString(R.string.rename), filled = false),
                LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) },
            )
            actions.addView(
                tonedButton(getString(R.string.delete), filled = false),
                LinearLayout.LayoutParams(0, dp(48), 1f),
            )
            (actions.getChildAt(0) as Button).setOnClickListener { renameCharacter(info) }
            (actions.getChildAt(1) as Button).setOnClickListener { deleteCharacter(info) }
            card.addView(actions)
            characterCards.addView(card)
            worker.execute {
                val bytes = info.root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                runOnUiThread { if (!isDestroyed) size.text = bytesLabel(bytes) }
            }
        }
    }

    private fun renderSentences() {
        sentenceList.removeAllViews()
        clips.forEachIndexed { index, clip ->
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, dp(4))
            }
            val title = TextView(this).apply {
                text = clip?.text ?: ""
                textSize = 16f
                setTextColor(color(if (index == active) R.color.amber else R.color.paper))
            }
            column.addView(title)
            val error = clip?.error
            if (clip != null && clip.samples == null && !error.isNullOrBlank()) {
                column.addView(TextView(this).apply {
                    text = error
                    textSize = 14f
                    setTextColor(color(R.color.muted))
                    setPadding(0, dp(2), 0, 0)
                })
            }
            if (clip?.samples != null && !busy) {
                column.setOnClickListener {
                    if (!busy) play(listOf(index))
                }
            }
            sentenceList.addView(column)
        }
    }

    private fun renderProsody() {
        if (downloading) return
        val ready = Prosody.isReady(filesDir)
        prosodyStatus.text = if (ready) "已安装" else "未安装"
        prosodyDetail.text = if (ready) {
            "占用 ${bytesLabel(Prosody.modelFile(filesDir).length())}\n所有中文角色共用这一份。"
        } else {
            "所有中文角色共用这一份。"
        }
        deleteProsody.visibility = if (ready) View.VISIBLE else View.GONE
    }

    private fun renderSpeed() {
        speedLabel.text = "语速 ${"%.2f".format(Locale.US, speedOf(speed.progress))}"
    }

    private fun updateControls() {
        val canSpeak = !busy && !playing && currentCharacter() != null && sentence.text.toString().isNotBlank()
        synthesizeButton.isEnabled = canSpeak
        sentence.isEnabled = !busy
        speed.isEnabled = !busy
        characters.isEnabled = !busy
        importCharacter.isEnabled = !busy
        downloadProsody.isEnabled = !busy
        importProsody.isEnabled = !busy
        deleteProsody.isEnabled = !busy
        val ready = clips.any { it?.samples != null }
        when {
            playing -> {
                playButton.isEnabled = true
                playButton.setText(R.string.stop)
                playButton.backgroundTintList = colorState(R.color.amber)
                playButton.setTextColor(color(R.color.ink))
            }
            ready && !busy -> {
                playButton.isEnabled = true
                playButton.setText(R.string.play)
                playButton.backgroundTintList = colorState(R.color.amber)
                playButton.setTextColor(color(R.color.ink))
            }
            else -> {
                playButton.isEnabled = false
                playButton.setText(R.string.play)
                playButton.backgroundTintList = colorState(R.color.line)
                playButton.setTextColor(color(R.color.paper))
            }
        }
    }

    private fun invalidateAudio() {
        if (playing) stopPlaying()
        if (clips.isEmpty()) return
        clearAudio()
        updateControls()
    }

    private fun clearAudio() {
        clips.clear()
        active = -1
        sentenceScroll.visibility = View.GONE
        sentenceList.removeAllViews()
    }

    private fun restoreDraft() {
        suppress = true
        sentence.setText(draftFile().takeIf { it.isFile }?.readText().orEmpty())
        val saved = speedFile().takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
        if (saved != null) speed.progress = saved.coerceIn(0, 16)
        suppress = false
    }

    private fun showInterrupted() {
        val marker = markerFile()
        if (!marker.isFile) return
        val version = marker.readText().trim().ifBlank { versionLabel() }
        banner.text = "上次合成没有完成\n版本 $version"
        banner.visibility = View.VISIBLE
        marker.delete()
    }

    private fun askName(title: String, prefill: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(prefill)
            setSelection(text.length)
            hint = "名字"
            setTextColor(color(R.color.paper))
            setHintTextColor(color(R.color.muted))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.field_bg)
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val wrap = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(wrap)
            .setPositiveButton("确定", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isEmpty()) {
                    input.error = "名字不能是空的"
                    return@setOnClickListener
                }
                dialog.dismiss()
                onOk(name)
            }
        }
        dialog.show()
    }

    private fun human(error: Throwable): String {
        if (error is RefMismatch) {
            return error.message ?: "参考音素和角色包里的原文对不上，请重新导入这个角色"
        }
        val message = error.message?.trim().orEmpty()
        if (
            message.contains("缺少") ||
            message.contains("不完整") ||
            message.contains("太小") ||
            message.contains("Git LFS") ||
            message.contains("不是模型")
        ) {
            return "角色包不完整，请重新导入"
        }
        if (message.any { it.code > 0x3000 }) return message
        return "合成没有完成"
    }

    private fun alert(message: String) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("确定", null)
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun aboutLines(): String {
        val role = roster.find { it.id == selectedId }?.name ?: getString(R.string.unselected)
        return "版本 ${versionLabel()}\n" +
            "系统 Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）\n" +
            "机型 ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "当前角色 $role"
    }

    private fun versionLabel(): String = "${versionName()}（${versionCode()}）"

    private fun versionName(): String {
        return packageManager.getPackageInfo(packageName, 0).versionName ?: "0.0.1"
    }

    private fun versionCode(): Long {
        val info = packageManager.getPackageInfo(packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    private fun currentCharacter(): CharacterInfo? = roster.find { it.id == selectedId }

    private fun threads(): Int = min(6, max(1, Runtime.getRuntime().availableProcessors()))

    private fun setBusy(value: Boolean) {
        busy = value
        updateControls()
        if (sentenceScroll.visibility == View.VISIBLE) renderSentences()
    }

    private fun displayName(uri: Uri): String {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0).orEmpty()
        }
        return uri.lastPathSegment.orEmpty()
    }

    private fun tonedButton(label: String, filled: Boolean): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            backgroundTintList = colorState(if (filled) R.color.amber else R.color.line)
            setTextColor(color(if (filled) R.color.ink else R.color.paper))
        }
    }

    private fun bytesLabel(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return "${mb.roundToInt()} MB"
    }

    private fun color(id: Int) = ContextCompat.getColor(this, id)

    private fun colorState(id: Int) = android.content.res.ColorStateList.valueOf(color(id))

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun draftFile() = File(filesDir, "draft.txt")

    private fun speedFile() = File(filesDir, "speech-speed.txt")

    private fun selectionFile() = File(filesDir, "selected-character.txt")

    private fun markerFile() = File(filesDir, "last_step.txt")
}
