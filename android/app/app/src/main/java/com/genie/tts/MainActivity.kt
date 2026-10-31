package com.genie.tts

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.drawerlayout.widget.DrawerLayout
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private enum class Page { Speak, Characters, Service, Prosody, About }

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "genie").apply { isDaemon = true }
    }
    private val playGen = AtomicInteger(0)
    private val splitter = TextSplitter()

    private lateinit var speech: Speech
    private lateinit var drawer: DrawerLayout
    private lateinit var drawerPanel: View
    private lateinit var headerSlot: View
    private lateinit var pageFrame: View
    private lateinit var homeBar: View
    private lateinit var rolesBar: View
    private lateinit var otherBar: View
    private lateinit var roleRow: View
    private lateinit var menuButton: android.widget.ImageButton
    private lateinit var pageTitle: TextView
    private lateinit var banner: TextView
    private lateinit var speedChip: TextView
    private lateinit var volumeChip: TextView
    private lateinit var drawerRoleCount: TextView
    private lateinit var drawerServiceState: TextView
    private lateinit var drawerProsodyState: TextView
    private lateinit var speakPage: View
    private lateinit var characterPage: View
    private lateinit var servicePage: View
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
    private lateinit var workStatus: TextView
    private lateinit var characterCards: LinearLayout
    private lateinit var rolesTitle: TextView
    private lateinit var rolesBack: ImageButton
    private lateinit var roleCount: TextView
    private lateinit var roleQuery: EditText
    private lateinit var roleSearchButton: View
    private lateinit var roleMore: View
    private lateinit var roleEmpty: View
    private lateinit var roleFoot: TextView
    private lateinit var roleBatchBar: View
    private lateinit var roleSelectAll: TextView
    private lateinit var roleDeletePicked: TextView
    private lateinit var importCharacter: View
    private lateinit var serviceStatus: TextView
    private lateinit var serviceDetail: TextView
    private lateinit var serviceToggle: Button
    private lateinit var copyService: Button
    private lateinit var servicePort: EditText
    private lateinit var serviceLog: TextView
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
    private var synthesizing = false
    private var loadingModel = false
    private var playFocusGranted = true
    private var suppress = false
    private var spinnerGen = 0
    private var downloading = false
    private var roleMenu: PopupWindow? = null
    private var previewId: String? = null
    private var batching = false
    private val picked = mutableSetOf<String>()

    private val avatarIcons = intArrayOf(
        R.drawable.ic_avatar_wave,
        R.drawable.ic_avatar_plus,
        R.drawable.ic_avatar_mic,
        R.drawable.ic_avatar_sparkles,
        R.drawable.ic_avatar_note,
        R.drawable.ic_avatar_microphone,
        R.drawable.ic_avatar_heart,
        R.drawable.ic_avatar_book,
    )
    private val avatarColors = arrayOf(
        intArrayOf(0xFF005461.toInt(), 0xFF14B8A6.toInt()),
        intArrayOf(0xFF059669.toInt(), 0xFF2DD4BF.toInt()),
        intArrayOf(0xFFD97706.toInt(), 0xFF0F766E.toInt()),
        intArrayOf(0xFF14B8A6.toInt(), 0xFF22D3EE.toInt()),
        intArrayOf(0xFF0EA5E9.toInt(), 0xFF818CF8.toInt()),
        intArrayOf(0xFF475569.toInt(), 0xFF005461.toInt()),
        intArrayOf(0xFFFB7185.toInt(), 0xFF14B8A6.toInt()),
        intArrayOf(0xFF115E59.toInt(), 0xFF10B981.toInt()),
    )

    private val pickCharacter = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onCharacterPicked(uri)
    }
    private val pickProsody = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onProsodyPicked(uri)
    }
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        startTtsService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TtsRuntime.port = TtsPorts.read(filesDir)
        TtsLog.attach(filesDir)
        setContentView(R.layout.activity_main)
        Engines.activityOpen = true
        speech = Engines.get(filesDir) { assets.open(it) }
        CharacterPack.migrateLegacy(filesDir)
        bindViews()
        restoreDraft()
        showInterrupted()
        bindActions()
        refreshCharacters()
        renderSpeed()
        showPage(Page.Speak)
        openRequestedPage(intent)
        val version = versionBadge()
        findViewById<TextView>(R.id.drawerVersion).text = version
        findViewById<TextView>(R.id.homeVersion).text = version
        renderProsody()
        renderMeters()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openRequestedPage(intent)
    }

    override fun onStart() {
        super.onStart()
        TtsRuntime.listener = { if (!isDestroyed) renderService() }
        TtsLog.listener = { if (!isDestroyed) serviceLog.text = TtsLog.recent() }
        renderService()
        serviceLog.text = TtsLog.recent()
    }

    override fun onStop() {
        TtsRuntime.listener = null
        TtsLog.listener = null
        super.onStop()
    }

    override fun onDestroy() {
        playGen.incrementAndGet()
        if (isFinishing) {
            Engines.activityOpen = false
            worker.execute { Engines.closeIfIdle() }
        }
        super.onDestroy()
    }

    private fun bindViews() {
        drawer = findViewById(R.id.drawer)
        drawerPanel = findViewById(R.id.drawerPanel)
        headerSlot = findViewById(R.id.headerSlot)
        pageFrame = findViewById(R.id.pageFrame)
        homeBar = findViewById(R.id.homeBar)
        rolesBar = findViewById(R.id.rolesBar)
        otherBar = findViewById(R.id.otherBar)
        roleRow = findViewById(R.id.roleRow)
        menuButton = findViewById(R.id.menuButton)
        pageTitle = findViewById(R.id.pageTitle)
        banner = findViewById(R.id.banner)
        speedChip = findViewById(R.id.speedChip)
        volumeChip = findViewById(R.id.volumeChip)
        drawerRoleCount = findViewById(R.id.drawerRoleCount)
        drawerServiceState = findViewById(R.id.drawerServiceState)
        drawerProsodyState = findViewById(R.id.drawerProsodyState)
        speakPage = findViewById(R.id.speakPage)
        characterPage = findViewById(R.id.characterPage)
        servicePage = findViewById(R.id.servicePage)
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
        workStatus = findViewById(R.id.workStatus)
        characterCards = findViewById(R.id.characterCards)
        rolesTitle = findViewById(R.id.rolesTitle)
        rolesBack = findViewById(R.id.rolesBack)
        roleCount = findViewById(R.id.roleCount)
        roleQuery = findViewById(R.id.roleQuery)
        roleSearchButton = findViewById(R.id.roleSearchButton)
        roleMore = findViewById(R.id.roleMore)
        roleEmpty = findViewById(R.id.roleEmpty)
        roleFoot = findViewById(R.id.roleFoot)
        roleBatchBar = findViewById(R.id.roleBatchBar)
        roleSelectAll = findViewById(R.id.roleSelectAll)
        roleDeletePicked = findViewById(R.id.roleDeletePicked)
        importCharacter = findViewById(R.id.importCharacter)
        serviceStatus = findViewById(R.id.serviceStatus)
        serviceDetail = findViewById(R.id.serviceDetail)
        serviceToggle = findViewById(R.id.serviceToggle)
        copyService = findViewById(R.id.copyService)
        servicePort = findViewById(R.id.servicePort)
        serviceLog = findViewById(R.id.serviceLog)
        prosodyStatus = findViewById(R.id.prosodyStatus)
        prosodyDetail = findViewById(R.id.prosodyDetail)
        downloadProsody = findViewById(R.id.downloadProsody)
        importProsody = findViewById(R.id.importProsody)
        deleteProsody = findViewById(R.id.deleteProsody)
        aboutText = findViewById(R.id.aboutText)
        characters.setPopupBackgroundResource(R.color.card)
        padForScreen()
    }

    private fun padForScreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        val content = findViewById<View>(R.id.contentRoot)
        val drawerHeader = findViewById<View>(R.id.drawerHeader)
        val drawerNav = findViewById<View>(R.id.drawerNav)
        val contentLeft = content.paddingLeft
        val contentRight = content.paddingRight
        val headerTop = headerSlot.paddingTop
        val pageBottom = pageFrame.paddingBottom
        val drawerLeft = drawerPanel.paddingLeft
        val drawerHeaderTop = drawerHeader.paddingTop
        val drawerNavBottom = drawerNav.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(drawer) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            content.updatePadding(
                left = contentLeft + bars.left,
                right = contentRight + bars.right,
            )
            headerSlot.updatePadding(top = headerTop + bars.top)
            pageFrame.updatePadding(bottom = pageBottom + max(bars.bottom, ime))
            drawerPanel.updatePadding(left = drawerLeft + bars.left)
            drawerHeader.updatePadding(top = drawerHeaderTop + bars.top)
            drawerNav.updatePadding(bottom = drawerNavBottom + bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun bindActions() {
        menuButton.setOnClickListener {
            if (page == Page.Speak) drawer.openDrawer(drawerPanel)
            else showPage(Page.Speak)
        }
        findViewById<View>(R.id.homeMenu).setOnClickListener {
            drawer.openDrawer(drawerPanel)
        }
        findViewById<View>(R.id.homeSearch).setOnClickListener {
            sentence.requestFocus()
        }
        findViewById<View>(R.id.homeMore).setOnClickListener {
            toast(getString(R.string.not_ready))
        }
        rolesBack.setOnClickListener {
            if (batching) exitBatch() else showPage(Page.Speak)
        }
        findViewById<View>(R.id.roleSearchButton).setOnClickListener { toggleRoleSearch() }
        findViewById<View>(R.id.roleMore).setOnClickListener { showRoleMenu(it) }
        roleQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (page == Page.Characters) renderCards()
            }
        })
        roleSelectAll.setOnClickListener { toggleSelectShown() }
        roleDeletePicked.setOnClickListener { confirmDeletePicked() }
        findViewById<View>(R.id.drawerClose).setOnClickListener {
            drawer.closeDrawer(drawerPanel)
        }
        roleRow.setOnClickListener {
            if (roster.isEmpty()) showPage(Page.Characters)
            else characters.performClick()
        }
        findViewById<View>(R.id.openSettings).setOnClickListener {
            drawer.closeDrawer(drawerPanel)
            toast(getString(R.string.not_ready))
        }
        findViewById<View>(R.id.openHelp).setOnClickListener {
            drawer.closeDrawer(drawerPanel)
            toast(getString(R.string.not_ready))
        }
        findViewById<View>(R.id.openLogs).setOnClickListener {
            drawer.closeDrawer(drawerPanel)
            showPage(Page.Service)
        }
        findViewById<View>(R.id.openCharacters).setOnClickListener {
            drawer.closeDrawer(drawerPanel)
            showPage(Page.Characters)
        }
        findViewById<View>(R.id.openService).setOnClickListener {
            drawer.closeDrawer(drawerPanel)
            showPage(Page.Service)
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
                    page == Page.Characters && batching -> exitBatch()
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
        synthesizeButton.setOnClickListener {
            if (!synthesizing && !busy) onSynthesize()
        }
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
        serviceToggle.setOnClickListener { toggleService() }
        copyService.setOnClickListener {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("tts", serviceAddresses()))
            toast("已复制")
        }
        findViewById<View>(R.id.copyLog).setOnClickListener {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("tts-log", TtsLog.recent()))
            toast("已复制")
        }
        servicePort.setOnEditorActionListener { view, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                view.clearFocus()
                true
            } else {
                false
            }
        }
        servicePort.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) commitPort()
        }
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
        servicePage.visibility = if (next == Page.Service) View.VISIBLE else View.GONE
        prosodyPage.visibility = if (next == Page.Prosody) View.VISIBLE else View.GONE
        aboutPage.visibility = if (next == Page.About) View.VISIBLE else View.GONE
        val onSpeak = next == Page.Speak
        val onRoles = next == Page.Characters
        if (!onRoles) {
            roleMenu?.dismiss()
            batching = false
            picked.clear()
        }
        homeBar.visibility = if (onSpeak) View.VISIBLE else View.GONE
        rolesBar.visibility = if (onRoles) View.VISIBLE else View.GONE
        otherBar.visibility = if (onSpeak || onRoles) View.GONE else View.VISIBLE
        val light = onSpeak || onRoles
        val pageColor = color(if (light) R.color.bg_light else R.color.ink)
        val headerColor = color(if (light) R.color.header else R.color.ink)
        headerSlot.setBackgroundColor(headerColor)
        pageFrame.setBackgroundColor(pageColor)
        findViewById<View>(R.id.contentRoot).setBackgroundColor(pageColor)
        banner.setTextColor(color(if (light) R.color.primary else R.color.amber))
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = light
        if (onSpeak) renderMeters()
        pageTitle.setText(
            when (next) {
                Page.Speak -> R.string.app_name
                Page.Characters -> R.string.characters
                Page.Service -> R.string.service
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
            Page.Service -> renderService()
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
        synthesizing = true
        loadingModel = true
        showWork("正在加载模型，共 ${pieces.size} 句")
        renderSentences()
        banner.visibility = View.GONE
        setBusy(true)
        val marker = markerFile()
        marker.writeText(versionLabel())
        val rate = speedOf(speed.progress)
        val threads = threads()
        worker.execute {
            try {
                speech.synthesize(
                    character,
                    pieces,
                    rate,
                    threads,
                    onMark = { loading, index ->
                        runOnUiThread {
                            if (isDestroyed) return@runOnUiThread
                            loadingModel = loading
                            if (!loading && index in clips.indices) active = index
                            showWork(
                                if (loading) {
                                    "正在加载模型，共 ${pieces.size} 句"
                                } else {
                                    "正在合成第 ${index + 1} 句，共 ${pieces.size} 句"
                                },
                            )
                            renderSentences()
                            updateControls()
                            scrollToActive()
                        }
                    },
                    onPiece = { index, clip ->
                        runOnUiThread {
                            if (isDestroyed) return@runOnUiThread
                            if (index in clips.indices) clips[index] = clip
                            renderSentences()
                            updateControls()
                        }
                    },
                )
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    synthesizing = false
                    loadingModel = false
                    active = -1
                    renderSentences()
                    showWork(synthSummary())
                    updateControls()
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
                    synthesizing = false
                    loadingModel = false
                    setBusy(false)
                }
            }
        }
    }

    private fun preview(character: CharacterInfo) {
        if (busy) return
        confirmProsody(preview = true) {
            stopPlaying()
            previewId = character.id
            if (page == Page.Characters) renderCards()
            setBusy(true)
            banner.text = "正在加载模型"
            banner.visibility = View.VISIBLE
            val marker = markerFile()
            marker.writeText(versionLabel())
            val gen = playGen.incrementAndGet()
            worker.execute {
                try {
                    val clip = speech.preview(character, threads()) { loading, _ ->
                        runOnUiThread {
                            if (isDestroyed) return@runOnUiThread
                            banner.text = if (loading) "正在加载模型" else "正在生成试听"
                            banner.visibility = View.VISIBLE
                        }
                    }
                    val samples = clip.samples
                    if (samples == null) {
                        runOnUiThread { if (!isDestroyed) alert(clip.error ?: "这句没有读出来") }
                        return@execute
                    }
                    val quiet = isQuiet(samples)
                    runOnUiThread {
                        if (isDestroyed || gen != playGen.get()) return@runOnUiThread
                        playing = true
                        banner.text = if (quiet) "这句几乎没有声音" else "正在打开试听"
                        banner.visibility = View.VISIBLE
                        updateControls()
                    }
                    playSamples(
                        this,
                        samples,
                        clip.sampleRate,
                        { gen != playGen.get() },
                        onStarted = { granted ->
                            runOnUiThread {
                                if (isDestroyed || gen != playGen.get()) return@runOnUiThread
                                playFocusGranted = granted
                                banner.text = previewLine(0, samples.size, clip.sampleRate, quiet, granted)
                            }
                        },
                        onProgress = { played, total ->
                            runOnUiThread {
                                if (isDestroyed || gen != playGen.get()) return@runOnUiThread
                                banner.text = previewLine(played, total, clip.sampleRate, quiet, playFocusGranted)
                            }
                        },
                    )
                } catch (error: Exception) {
                    runOnUiThread { if (!isDestroyed) alert(human(error)) }
                } finally {
                    marker.delete()
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        if (gen == playGen.get()) {
                            playing = false
                            previewId = null
                        }
                        banner.visibility = View.GONE
                        setBusy(false)
                        if (page == Page.Characters) renderCards()
                    }
                }
            }
        }
    }

    private fun play(indexes: List<Int>) {
        if (busy || indexes.isEmpty()) return
        val gen = playGen.incrementAndGet()
        playing = true
        playFocusGranted = true
        showWork("正在打开播放")
        updateControls()
        worker.execute {
            try {
                for (index in indexes) {
                    if (gen != playGen.get()) return@execute
                    val clip = clips.getOrNull(index) ?: continue
                    val samples = clip.samples ?: continue
                    val quiet = isQuiet(samples)
                    runOnUiThread {
                        if (isDestroyed || gen != playGen.get()) return@runOnUiThread
                        active = index
                        renderSentences()
                        scrollToActive()
                    }
                    playSamples(
                        this,
                        samples,
                        clip.sampleRate,
                        { gen != playGen.get() },
                        onStarted = { granted ->
                            runOnUiThread {
                                if (isDestroyed || gen != playGen.get()) return@runOnUiThread
                                playFocusGranted = granted
                                showWork(playLine(index, 0, samples.size, clip.sampleRate, quiet))
                            }
                        },
                        onProgress = { played, total ->
                            runOnUiThread {
                                if (isDestroyed || gen != playGen.get()) return@runOnUiThread
                                showWork(playLine(index, played, total, clip.sampleRate, quiet))
                            }
                        },
                    )
                }
                runOnUiThread {
                    if (!isDestroyed && gen == playGen.get()) showWork("播放结束")
                }
            } catch (error: Exception) {
                runOnUiThread {
                    if (!isDestroyed && gen == playGen.get()) showWork(human(error))
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
        val wasPlaying = playing
        playGen.incrementAndGet()
        if (!wasPlaying && active < 0) return
        playing = false
        active = -1
        renderSentences()
        updateControls()
        if (wasPlaying) showWork("已停止")
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
        removeCharacters(listOf(info))
    }

    private fun removeCharacters(targets: List<CharacterInfo>) {
        if (busy || targets.isEmpty()) return
        setBusy(true)
        val ids = targets.map { it.id }.toSet()
        val dropsSelection = selectedId in ids
        if (previewId in ids) {
            previewId = null
            stopPlaying()
        }
        worker.execute {
            try {
                if (dropsSelection) speech.dropEngine()
                targets.forEach { it.root.deleteRecursively() }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    if (dropsSelection) {
                        selectedId = null
                        selectionFile().delete()
                        invalidateAudio()
                    }
                    picked.removeAll(ids)
                    setBusy(false)
                    refreshCharacters()
                    if (batching && roster.isEmpty()) exitBatch()
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
            val adapter = ArrayAdapter(this, R.layout.spinner_selected, labels)
            adapter.setDropDownViewResource(R.layout.spinner_item)
            characters.adapter = adapter
            val index = roster.indexOfFirst { it.id == selectedId }
            characters.setSelection(if (index >= 0) index else roster.size, false)
            characters.post {
                if (generation == spinnerGen) suppress = false
            }
        }
        renderCards()
        drawerRoleCount.text = "${roster.size}个角色"
        if (page == Page.About) aboutText.text = aboutLines()
        updateControls()
    }

    private fun renderCards() {
        val query = roleQuery.text?.toString()?.trim().orEmpty()
        val shown = roster.filter { info ->
            query.isEmpty() ||
                info.name.contains(query, ignoreCase = true) ||
                info.id.contains(query, ignoreCase = true)
        }
        roleFoot.text = roleFootText()
        roleEmpty.visibility = if (query.isNotEmpty() && shown.isEmpty()) View.VISIBLE else View.GONE
        val shownIds = shown.map { it.id }
        val allShown = shownIds.isNotEmpty() && shownIds.all { it in picked }
        roleSelectAll.setText(if (allShown) R.string.select_none else R.string.select_all)
        roleSelectAll.tag = shownIds
        characterCards.removeAllViews()
        shown.forEach { info -> characterCards.addView(roleCard(info)) }
        applyBatchChrome()
    }

    private fun roleFootText(): CharSequence {
        val total = "共 ${roster.size} 个模型"
        val current = roster.find { it.id == selectedId } ?: return total
        val text = "$total  |  当前：${current.name}"
        val start = text.length - current.name.length
        return SpannableString(text).apply {
            setSpan(ForegroundColorSpan(color(R.color.header)), start, text.length, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), start, text.length, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun roleCard(info: CharacterInfo): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_role_card)
            elevation = dp(1).toFloat()
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setOnClickListener { if (batching) togglePicked(info.id) }
            setOnLongClickListener {
                if (!batching) roleActions(info)
                true
            }
        }
        if (batching && info.id in picked) {
            card.background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#E6F4F6"))
            }
        }
        card.addView(avatarView(info))
        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            }
        }
        text.addView(TextView(this).apply {
            this.text = info.name
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(color(R.color.text_main))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        text.addView(TextView(this).apply {
            this.text = "ID: ${info.id}"
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, dp(2), 0, 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        card.addView(text)
        if (batching) {
            card.addView(batchMark(info.id in picked))
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.topMargin = dp(10)
            params.marginStart = dp(12)
            params.marginEnd = dp(12)
            card.layoutParams = params
            return card
        }
        val playingThis = previewId == info.id
        card.addView(ImageButton(this).apply {
            val box = dp(40)
            layoutParams = LinearLayout.LayoutParams(box, box).apply { marginStart = dp(8) }
            background = ContextCompat.getDrawable(
                this@MainActivity,
                if (playingThis) R.drawable.bg_role_play_on else R.drawable.bg_role_play,
            )
            setImageResource(if (playingThis) R.drawable.ic_role_bars else R.drawable.ic_role_play)
            imageTintList = ColorStateList.valueOf(color(if (playingThis) R.color.header else R.color.primary))
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = if (playingThis) "正在试听${info.name}" else "试听${info.name}"
            isEnabled = !busy || playingThis
            alpha = if (isEnabled) 1f else 0.4f
            setOnClickListener { preview(info) }
        })
        val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        params.topMargin = dp(10)
        params.marginStart = dp(12)
        params.marginEnd = dp(12)
        card.layoutParams = params
        return card
    }

    private fun avatarView(info: CharacterInfo): View {
        val size = dp(44)
        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, size, size, dp(12).toFloat())
                }
            }
            clipToOutline = true
        }
        val image = CharacterPack.avatarImage(info.root)
        val bitmap = image?.let { decodeAvatar(it) }
        if (bitmap != null) {
            frame.addView(ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(size, size)
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(bitmap)
            })
        } else {
            val index = avatarIndex(info)
            frame.background = GradientDrawable(GradientDrawable.Orientation.BL_TR, avatarColors[index]).apply {
                cornerRadius = dp(12).toFloat()
            }
            frame.addView(ImageView(this).apply {
                val icon = dp(24)
                layoutParams = FrameLayout.LayoutParams(icon, icon, Gravity.CENTER)
                setImageResource(avatarIcons[index])
                imageTintList = ColorStateList.valueOf(Color.WHITE)
            })
        }
        return frame
    }

    private fun avatarIndex(info: CharacterInfo): Int {
        val file = File(info.root, CharacterPack.AVATAR_ICON)
        val saved = file.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
        if (saved != null && saved in avatarIcons.indices) return saved
        val pick = kotlin.random.Random.nextInt(avatarIcons.size)
        runCatching { file.writeText(pick.toString()) }
        return pick
    }

    private fun decodeAvatar(file: File): android.graphics.Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) sample *= 2
        return BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun batchMark(on: Boolean): View {
        return ImageView(this).apply {
            val box = dp(24)
            layoutParams = LinearLayout.LayoutParams(box, box).apply { marginStart = dp(8) }
            background = ContextCompat.getDrawable(
                this@MainActivity,
                if (on) R.drawable.bg_role_fab else R.drawable.bg_role_check_off,
            )
            if (on) {
                setImageResource(R.drawable.ic_role_check)
                imageTintList = ColorStateList.valueOf(Color.WHITE)
            }
            scaleType = ImageView.ScaleType.CENTER
            isClickable = false
        }
    }

    private fun applyBatchChrome() {
        val batch = batching && page == Page.Characters
        rolesTitle.setText(if (batch) R.string.batch_manage else R.string.characters)
        rolesBack.setImageResource(if (batch) R.drawable.ic_close else R.drawable.ic_back)
        rolesBack.contentDescription = if (batch) "取消" else "返回"
        roleCount.text = if (batch) "已选 ${picked.size}" else "${roster.size}个角色"
        roleMore.visibility = if (batch) View.GONE else View.VISIBLE
        roleFoot.visibility = if (batch) View.GONE else View.VISIBLE
        importCharacter.visibility = if (batch) View.GONE else View.VISIBLE
        roleBatchBar.visibility = if (batch) View.VISIBLE else View.GONE
        roleDeletePicked.alpha = if (picked.isEmpty()) 0.4f else 1f
    }

    private fun enterBatch() {
        if (busy) return
        if (roster.isEmpty()) {
            toast(getString(R.string.no_roles))
            return
        }
        batching = true
        picked.clear()
        renderCards()
    }

    private fun exitBatch() {
        if (!batching) return
        batching = false
        picked.clear()
        if (page == Page.Characters) renderCards()
    }

    private fun togglePicked(id: String) {
        if (!picked.add(id)) picked.remove(id)
        renderCards()
    }

    private fun toggleSelectShown() {
        @Suppress("UNCHECKED_CAST")
        val shownIds = roleSelectAll.tag as? List<String> ?: return
        if (shownIds.isNotEmpty() && shownIds.all { it in picked }) picked.removeAll(shownIds.toSet())
        else picked.addAll(shownIds)
        renderCards()
    }

    private fun confirmDeletePicked() {
        if (picked.isEmpty()) {
            toast(getString(R.string.pick_first))
            return
        }
        val count = picked.size
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.batch_manage))
            .setMessage("要删除这 $count 个角色。")
            .setNegativeButton("取消", null)
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                removeCharacters(roster.filter { it.id in picked })
            }
            .show()
    }

    private fun confirmClearRoles() {
        if (busy) return
        if (roster.isEmpty()) {
            toast(getString(R.string.no_roles))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.clear_roles))
            .setMessage("已导入的角色都会删掉。首页正在用的角色会回到未选择。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                removeCharacters(roster.toList())
            }
            .show()
    }

    private fun roleActions(info: CharacterInfo) {
        AlertDialog.Builder(this)
            .setTitle(info.name)
            .setItems(arrayOf(getString(R.string.rename), getString(R.string.delete))) { _, which ->
                if (which == 0) renameCharacter(info) else deleteCharacter(info)
            }
            .show()
    }

    private fun toggleRoleSearch() {
        val open = roleQuery.visibility != View.VISIBLE
        roleQuery.visibility = if (open) View.VISIBLE else View.GONE
        if (open) {
            roleQuery.requestFocus()
        } else {
            roleQuery.setText("")
        }
    }

    private fun showRoleMenu(anchor: View) {
        roleMenu?.dismiss()
        val menu = layoutInflater.inflate(R.layout.menu_roles, null)
        val popup = PopupWindow(menu, dp(176), ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.isOutsideTouchable = true
        popup.elevation = dp(12).toFloat()
        menu.findViewById<View>(R.id.menuImport).setOnClickListener {
            popup.dismiss()
            importCharacter.performClick()
        }
        menu.findViewById<View>(R.id.menuBatch).setOnClickListener {
            popup.dismiss()
            enterBatch()
        }
        menu.findViewById<View>(R.id.menuExport).setOnClickListener {
            popup.dismiss()
            toast(getString(R.string.not_ready))
        }
        menu.findViewById<View>(R.id.menuClear).setOnClickListener {
            popup.dismiss()
            confirmClearRoles()
        }
        popup.showAsDropDown(anchor, 0, 0, Gravity.END)
        roleMenu = popup
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
                setTextColor(color(if (index == active) R.color.primary else R.color.text_main))
            }
            column.addView(title)
            val samples = clip?.samples
            val detail = when {
                samples != null && isQuiet(samples) -> "几乎没有声音"
                samples != null -> "${formatSec(secondsOf(samples, clip?.sampleRate ?: 32000))} 秒"
                clip != null && !clip.error.isNullOrBlank() -> clip.error
                synthesizing && index == active && loadingModel -> "等待模型"
                synthesizing && index == active -> "正在合成"
                else -> null
            }
            if (!detail.isNullOrBlank()) {
                column.addView(TextView(this).apply {
                    text = detail
                    textSize = 14f
                    setTextColor(color(if (samples != null && isQuiet(samples)) R.color.amber else R.color.text_sub))
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
        drawerProsodyState.text = if (ready) "已就绪" else "未安装"
    }

    private fun renderService() {
        val running = TtsRuntime.running
        val problem = TtsRuntime.error
        serviceStatus.text = when {
            TtsRuntime.switching -> "正在切换端口"
            running -> "已开启"
            !problem.isNullOrBlank() -> problem
            else -> "未开启"
        }
        serviceStatus.setTextColor(color(if (!running && !problem.isNullOrBlank()) R.color.amber else R.color.paper))
        val ids = roster.joinToString("、") { it.id }.ifBlank { "还没有。先在角色管理里导入" }
        val notice = if (running && !notificationsAllowed()) "通知没打开，系统可能会把接口收掉。\n" else ""
        serviceDetail.text = serviceAddresses() + "\n\n角色 id\n$ids\n\n" +
            notice +
            "POST /tts，JSON 里是 character_name、text、split_sentence。语速固定 1.00。切走或锁屏后，通知会留着。"
        drawerServiceState.text = if (running) "运行中" else "未开启"
        serviceToggle.setText(if (running) R.string.service_stop else R.string.service_start)
        serviceToggle.backgroundTintList = colorState(if (running) R.color.line else R.color.amber)
        serviceToggle.setTextColor(color(if (running) R.color.paper else R.color.ink))
        val shown = TtsRuntime.port.toString()
        if (!servicePort.hasFocus() && servicePort.text.toString() != shown) {
            servicePort.setText(shown)
        }
    }

    private fun commitPort() {
        val value = TtsPorts.parse(servicePort.text.toString())
        if (value == null) {
            toast("端口要在 1 到 65535 之间")
            servicePort.setText(TtsRuntime.port.toString())
            return
        }
        if (value == TtsRuntime.port) return
        TtsPorts.write(filesDir, value)
        TtsRuntime.port = value
        if (TtsRuntime.running) {
            TtsRuntime.switching = true
            ContextCompat.startForegroundService(this, Intent(this, TtsService::class.java))
        }
        renderService()
    }

    private fun serviceAddresses(): String {
        val lines = interfaceAddresses().map { address ->
            val label = if (address == "127.0.0.1") "本机" else "局域网"
            "$label\nhttp://$address:${TtsRuntime.port}/tts"
        }
        return lines.joinToString("\n")
    }

    private fun toggleService() {
        if (TtsRuntime.running) {
            stopService(Intent(this, TtsService::class.java))
            return
        }
        if (
            Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startTtsService()
    }

    private fun startTtsService() {
        TtsRuntime.error = null
        ContextCompat.startForegroundService(this, Intent(this, TtsService::class.java))
    }

    private fun notificationsAllowed(): Boolean {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun openRequestedPage(source: Intent?) {
        if (source?.getStringExtra(TtsRuntime.EXTRA_PAGE) == TtsRuntime.PAGE_SERVICE) showPage(Page.Service)
    }

    private fun renderSpeed() {
        val rate = "%.2f".format(Locale.US, speedOf(speed.progress))
        speedLabel.text = labeled(getString(R.string.speed_adjust), rate, "  ")
        speedChip.text = labeled("语速", rate, ": ")
    }

    private fun renderMeters() {
        val rate = "%.2f".format(Locale.US, speedOf(speed.progress))
        speedChip.text = labeled("语速", rate, ": ")
        volumeChip.text = labeled(getString(R.string.volume_label), volumePercent().toString(), ": ")
    }

    private fun labeled(label: String, value: String, gap: String): CharSequence {
        val text = "$label$gap$value"
        val start = text.length - value.length
        return android.text.SpannableString(text).apply {
            setSpan(
                android.text.style.ForegroundColorSpan(color(R.color.primary)),
                start,
                text.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            setSpan(
                android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                start,
                text.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    private fun volumePercent(): Int {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        val maxVolume = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val now = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        return (now * 100f / maxVolume).roundToInt()
    }

    private fun versionBadge(): String = "v${versionName()} (${versionCode()})"

    private fun updateControls() {
        val canSpeak = !busy && !playing && !synthesizing && currentCharacter() != null && sentence.text.toString().isNotBlank()
        sentence.isEnabled = !busy
        speed.isEnabled = !busy
        characters.isEnabled = !busy
        importCharacter.isEnabled = !busy
        importCharacter.alpha = if (importCharacter.isEnabled) 1f else 0.4f
        downloadProsody.isEnabled = !busy
        importProsody.isEnabled = !busy
        deleteProsody.isEnabled = !busy
        when {
            synthesizing && loadingModel -> {
                synthesizeButton.isEnabled = true
                synthesizeButton.text = "加载中"
            }
            synthesizing -> {
                synthesizeButton.isEnabled = true
                val shown = (active + 1).coerceAtLeast(1)
                synthesizeButton.text = "$shown/${clips.size.coerceAtLeast(1)}"
            }
            else -> {
                synthesizeButton.isEnabled = canSpeak
                synthesizeButton.setText(R.string.synthesize)
            }
        }
        synthesizeButton.backgroundTintList = null
        synthesizeButton.setTextColor(color(android.R.color.white))
        synthesizeButton.alpha = if (synthesizeButton.isEnabled) 1f else 0.4f
        val ready = clips.any { it?.samples != null }
        when {
            playing -> {
                playButton.isEnabled = true
                playButton.setText(R.string.stop)
            }
            ready && !busy && !synthesizing -> {
                playButton.isEnabled = true
                playButton.setText(R.string.play)
            }
            else -> {
                playButton.isEnabled = false
                playButton.setText(R.string.play)
            }
        }
        playButton.backgroundTintList = null
        playButton.setTextColor(color(R.color.primary))
        playButton.alpha = if (playButton.isEnabled) 1f else 0.4f
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
        showWork("")
    }

    private fun showWork(text: String) {
        workStatus.text = text
        workStatus.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun scrollToActive() {
        if (active < 0) return
        val child = sentenceList.getChildAt(active) ?: return
        sentenceScroll.post {
            (sentenceScroll as android.widget.ScrollView).smoothScrollTo(0, child.top)
        }
    }

    private fun synthSummary(): String {
        val playable = clips.count { it?.samples != null }
        val quiet = clips.count { clip -> clip?.samples?.let(::isQuiet) == true }
        return when {
            playable == 0 -> "没有可以播放的句子"
            quiet == playable -> "合成完成，但是几乎没有声音"
            else -> "合成完成，$playable 句可播放"
        }
    }

    private fun playLine(index: Int, played: Int, total: Int, sampleRate: Int, quiet: Boolean): String {
        val clock = "${formatCount(played, sampleRate)}/${formatCount(total, sampleRate)} 秒"
        val head = "正在播放第 ${index + 1} 句 · $clock"
        return when {
            quiet -> "$head，这句几乎没有声音"
            !playFocusGranted -> "声音可能被其他应用占用。$head"
            else -> head
        }
    }

    private fun previewLine(played: Int, total: Int, sampleRate: Int, quiet: Boolean, granted: Boolean): String {
        val clock = "${formatCount(played, sampleRate)}/${formatCount(total, sampleRate)} 秒"
        return when {
            quiet -> "正在试听 $clock，这句几乎没有声音"
            !granted -> "声音可能被其他应用占用。正在试听 $clock"
            else -> "正在试听 $clock"
        }
    }

    private fun formatCount(samples: Int, sampleRate: Int): String {
        val seconds = if (sampleRate <= 0) 0.0 else samples.toDouble() / sampleRate
        return formatSec(seconds)
    }

    private fun formatSec(seconds: Double): String = "%.1f".format(Locale.US, seconds)

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
        return packageManager.getPackageInfo(packageName, 0).versionName ?: "0.0.7"
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
