package com.genie.tts

import android.Manifest
import android.app.Dialog
import android.content.ActivityNotFoundException
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
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputFilter
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
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
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.text.HtmlCompat
import androidx.core.view.updatePadding
import androidx.drawerlayout.widget.DrawerLayout
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private enum class Page { Speak, Characters, Service, Prosody, About, Logs }

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
    private lateinit var prosodyBar: View
    private lateinit var aboutBar: View
    private lateinit var serviceBar: View
    private lateinit var logsBar: View
    private lateinit var otherBar: View
    private lateinit var roleRow: View
    private lateinit var menuButton: android.widget.ImageButton
    private lateinit var pageTitle: TextView
    private lateinit var banner: TextView
    private lateinit var drawerRoleCount: TextView
    private lateinit var drawerServiceState: TextView
    private lateinit var drawerProsodyState: TextView
    private lateinit var speakPage: View
    private lateinit var speakChrome: View
    private var imeBottom = 0
    private lateinit var characterPage: View
    private lateinit var servicePage: View
    private lateinit var prosodyPage: View
    private lateinit var aboutPage: View
    private lateinit var logsPage: View
    private lateinit var placeholder: TextView
    private lateinit var characters: android.widget.Spinner
    private lateinit var sentence: EditText
    private lateinit var sentenceCard: View
    private lateinit var sentenceScroll: View
    private lateinit var sentenceList: LinearLayout
    private lateinit var sentenceHeading: TextView
    private lateinit var sentenceBadge: TextView
    private lateinit var workDot: View
    private lateinit var workMetrics: TextView
    private lateinit var workNoteLine: TextView
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
    private lateinit var serviceUptime: View
    private lateinit var serviceUptimeValue: TextView
    private lateinit var serviceDot: View
    private val uptimeHandler = Handler(Looper.getMainLooper())
    private val uptimeTicker = Runnable {
        if (isDestroyed) return@Runnable
        paintUptime()
        scheduleUptime()
    }
    private lateinit var serviceNotice: TextView
    private lateinit var serviceToggle: TextView
    private lateinit var servicePort: EditText
    private lateinit var serviceLocalUrl: TextView
    private lateinit var serviceLanRow: View
    private lateinit var serviceLanUrl: TextView
    private lateinit var serviceIds: TextView
    private lateinit var serviceLog: TextView
    private lateinit var serviceLogChevron: View
    private var logOpen = false
    private var logLevelFilter: LogLevel? = null
    private var pendingLogText: String? = null
    private lateinit var prosodyStatus: TextView
    private lateinit var prosodyDetail: TextView
    private lateinit var prosodyProgress: ProgressBar
    private lateinit var prosodyBadge: TextView
    private lateinit var prosodyModel: TextView
    private lateinit var prosodyFile: TextView
    private lateinit var prosodySize: TextView
    private lateinit var prosodyPath: TextView
    private lateinit var prosodyUpdated: TextView
    private lateinit var prosodyDownloadSub: TextView
    private lateinit var prosodyCopy: ImageButton
    private lateinit var prosodyDeleteCard: View
    private lateinit var downloadProsody: TextView
    private lateinit var importProsody: TextView
    private lateinit var deleteProsody: TextView
    private lateinit var aboutBadge: TextView
    private lateinit var aboutSystem: TextView
    private lateinit var aboutModel: TextView
    private lateinit var aboutRole: TextView
    private lateinit var aboutInstalled: TextView
    private lateinit var aboutBuilt: TextView
    private lateinit var aboutVersion: TextView
    private lateinit var aboutPackage: TextView

    private var page = Page.Speak
    private var roster: List<CharacterInfo> = emptyList()
    private var selectedId: String? = null
    private var clips: MutableList<Clip?> = mutableListOf()
    private var workNote = ""
    private var synthStartedAt = 0L
    private var synthElapsedMs = 0L
    private var active = -1
    private var busy = false
    private var playing = false
    private var speedGestureKeepsAudio = false
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
    private var importSheet: Dialog? = null
    private var importInspecting = false
    private var importToken = 0
    private var importPlayer: MediaPlayer? = null
    private var importFocus: AudioFocusRequest? = null
    private var importAudioFile: File? = null
    private var importListen: TextView? = null
    private var onImportAudioStopped: (() -> Unit)? = null

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
    private val saveLog = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val text = pendingLogText
        pendingLogText = null
        if (uri == null || text.isNullOrEmpty()) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(text.toByteArray(Charsets.UTF_8))
            } ?: error("empty")
            toast("已导出")
        } catch (_: Exception) {
            toast("没有写出去")
        }
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
        findViewById<TextView>(R.id.logsVersion).text = version
        renderProsody()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openRequestedPage(intent)
    }

    override fun onStart() {
        super.onStart()
        TtsRuntime.listener = { if (!isDestroyed) renderService() }
        TtsLog.listener = {
            if (!isDestroyed) {
                serviceLog.text = TtsLog.recent()
                if (page == Page.Logs) renderLogs(logAutoScroll().isChecked)
            }
        }
        renderService()
        serviceLog.text = TtsLog.recent()
    }

    override fun onStop() {
        uptimeHandler.removeCallbacks(uptimeTicker)
        TtsRuntime.listener = null
        TtsLog.listener = null
        super.onStop()
    }

    override fun onDestroy() {
        importSheet?.setOnDismissListener(null)
        importSheet?.dismiss()
        importSheet = null
        stopImportAudio()
        importAudioFile?.delete()
        importAudioFile = null
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
        prosodyBar = findViewById(R.id.prosodyBar)
        aboutBar = findViewById(R.id.aboutBar)
        serviceBar = findViewById(R.id.serviceBar)
        logsBar = findViewById(R.id.logsBar)
        otherBar = findViewById(R.id.otherBar)
        roleRow = findViewById(R.id.roleRow)
        menuButton = findViewById(R.id.menuButton)
        pageTitle = findViewById(R.id.pageTitle)
        banner = findViewById(R.id.banner)
        drawerRoleCount = findViewById(R.id.drawerRoleCount)
        drawerServiceState = findViewById(R.id.drawerServiceState)
        drawerProsodyState = findViewById(R.id.drawerProsodyState)
        speakPage = findViewById(R.id.speakPage)
        speakChrome = findViewById(R.id.speakChrome)
        characterPage = findViewById(R.id.characterPage)
        servicePage = findViewById(R.id.servicePage)
        prosodyPage = findViewById(R.id.prosodyPage)
        aboutPage = findViewById(R.id.aboutPage)
        logsPage = findViewById(R.id.logsPage)
        placeholder = findViewById(R.id.characterPlaceholder)
        characters = findViewById(R.id.characters)
        sentence = findViewById(R.id.sentence)
        sentenceCard = findViewById(R.id.sentenceCard)
        sentenceScroll = findViewById(R.id.sentenceScroll)
        sentenceList = findViewById(R.id.sentenceList)
        sentenceHeading = findViewById(R.id.sentenceHeading)
        sentenceBadge = findViewById(R.id.sentenceBadge)
        workDot = findViewById(R.id.workDot)
        workMetrics = findViewById(R.id.workMetrics)
        workNoteLine = findViewById(R.id.workNote)
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
        serviceUptime = findViewById(R.id.serviceUptime)
        serviceUptimeValue = findViewById(R.id.serviceUptimeValue)
        serviceDot = findViewById(R.id.serviceDot)
        serviceNotice = findViewById(R.id.serviceNotice)
        serviceToggle = findViewById(R.id.serviceToggle)
        servicePort = findViewById(R.id.servicePort)
        serviceLocalUrl = findViewById(R.id.serviceLocalUrl)
        serviceLanRow = findViewById(R.id.serviceLanRow)
        serviceLanUrl = findViewById(R.id.serviceLanUrl)
        serviceIds = findViewById(R.id.serviceIds)
        serviceLog = findViewById(R.id.serviceLog)
        serviceLogChevron = findViewById(R.id.serviceLogChevron)
        prosodyStatus = findViewById(R.id.prosodyStatus)
        prosodyDetail = findViewById(R.id.prosodyDetail)
        prosodyProgress = findViewById(R.id.prosodyProgress)
        prosodyBadge = findViewById(R.id.prosodyBadge)
        prosodyModel = findViewById(R.id.prosodyModel)
        prosodyFile = findViewById(R.id.prosodyFile)
        prosodySize = findViewById(R.id.prosodySize)
        prosodyPath = findViewById(R.id.prosodyPath)
        prosodyUpdated = findViewById(R.id.prosodyUpdated)
        prosodyDownloadSub = findViewById(R.id.prosodyDownloadSub)
        prosodyCopy = findViewById(R.id.prosodyCopy)
        prosodyDeleteCard = findViewById(R.id.prosodyDeleteCard)
        downloadProsody = findViewById(R.id.downloadProsody)
        importProsody = findViewById(R.id.importProsody)
        deleteProsody = findViewById(R.id.deleteProsody)
        aboutBadge = findViewById(R.id.aboutBadge)
        aboutSystem = findViewById(R.id.aboutSystem)
        aboutModel = findViewById(R.id.aboutModel)
        aboutRole = findViewById(R.id.aboutRole)
        aboutInstalled = findViewById(R.id.aboutInstalled)
        aboutBuilt = findViewById(R.id.aboutBuilt)
        aboutVersion = findViewById(R.id.aboutVersion)
        aboutPackage = findViewById(R.id.aboutPackage)
        prosodyModel.text = Prosody.FILE_NAME
        prosodyFile.text = Prosody.FILE_NAME
        prosodyDownloadSub.text = "拉取 ${Release.TAG} 的 prosody.zip，大约 ${bytesLabel(Prosody.MODEL_BYTES)}，中断后可以接着下。"
        prosodyTipWhat()
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
            imeBottom = ime
            applyTypingSpace()
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

    private fun applyTypingSpace() {
        val typing = imeBottom > 0 && sentence.hasFocus()
        speakChrome.visibility = if (typing) View.GONE else View.VISIBLE
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
            if (batching) exitBatch() else drawer.openDrawer(drawerPanel)
        }
        findViewById<View>(R.id.prosodyBack).setOnClickListener { drawer.openDrawer(drawerPanel) }
        findViewById<View>(R.id.aboutBack).setOnClickListener { showPage(Page.Speak) }
        findViewById<View>(R.id.logsBack).setOnClickListener { showPage(Page.Speak) }
        findViewById<View>(R.id.logsRefresh).setOnClickListener {
            TtsLog.reload()
            serviceLog.text = TtsLog.recent()
            renderLogs(true)
        }
        findViewById<View>(R.id.logsHeaderExport).setOnClickListener { exportLogs() }
        findViewById<View>(R.id.logsMore).setOnClickListener { copyLogPath() }
        findViewById<View>(R.id.logFilterAll).setOnClickListener { pickLogLevel(null) }
        findViewById<View>(R.id.logFilterInfo).setOnClickListener { pickLogLevel(LogLevel.INFO) }
        findViewById<View>(R.id.logFilterWarn).setOnClickListener { pickLogLevel(LogLevel.WARN) }
        findViewById<View>(R.id.logFilterError).setOnClickListener { pickLogLevel(LogLevel.ERROR) }
        findViewById<View>(R.id.logFilterDebug).setOnClickListener { pickLogLevel(LogLevel.DEBUG) }
        val logQuery = findViewById<EditText>(R.id.logQuery)
        logQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                findViewById<View>(R.id.logQueryClear).visibility =
                    if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                if (page == Page.Logs) renderLogs(false)
            }
        })
        findViewById<View>(R.id.logQueryClear).setOnClickListener { logQuery.setText("") }
        val logSwitch = logAutoScroll()
        val switchStates = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        logSwitch.trackTintList = ColorStateList(switchStates, intArrayOf(0xFF0A4B56.toInt(), 0xFFCBD5E1.toInt()))
        logSwitch.thumbTintList = ColorStateList.valueOf(Color.WHITE)
        findViewById<View>(R.id.serviceBack).setOnClickListener { drawer.openDrawer(drawerPanel) }
        findViewById<View>(R.id.serviceLogJump).setOnClickListener { revealLog() }
        findViewById<View>(R.id.serviceLogHead).setOnClickListener { setLogOpen(!logOpen) }
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
        findViewById<View>(R.id.openHome).setOnClickListener {
            drawer.closeDrawer(drawerPanel)
            showPage(Page.Speak)
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
            showPage(Page.Logs)
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
        sentence.setOnFocusChangeListener { _, _ -> applyTypingSpace() }
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
                if (fromUser && !suppress && !playing && !speedGestureKeepsAudio) invalidateAudio()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                speedGestureKeepsAudio = playing
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                speedGestureKeepsAudio = false
            }
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
                if (page == Page.About) renderAbout()
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
        downloadProsody.setOnClickListener { confirmDownloadProsody() }
        importProsody.setOnClickListener {
            if (busy) return@setOnClickListener
            pickProsody.launch(arrayOf("*/*", "application/zip", "application/octet-stream"))
        }
        deleteProsody.setOnClickListener { confirmDeleteProsody() }
        prosodyCopy.setOnClickListener {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("prosody", Prosody.modelFile(filesDir).absolutePath))
            toast("已复制")
        }
        serviceToggle.setOnClickListener { toggleService() }
        findViewById<View>(R.id.copyLocal).setOnClickListener { copyPlain("tts", serviceLocalUrl.text) }
        findViewById<View>(R.id.copyLan).setOnClickListener { copyPlain("tts", serviceLanUrl.text) }
        findViewById<View>(R.id.copyLog).setOnClickListener {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("tts-log", TtsLog.recent()))
            toast("已复制")
        }
        findViewById<View>(R.id.logClear).setOnClickListener { confirmClearLogs() }
        findViewById<View>(R.id.logCopy).setOnClickListener { copyAllLogs() }
        findViewById<View>(R.id.logExport).setOnClickListener { exportLogs() }
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
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("about", aboutLines()))
            toast("已复制")
        }
        findViewById<View>(R.id.aboutOpen).setOnClickListener { openRepo() }
    }

    private fun showPage(next: Page) {
        page = next
        speakPage.visibility = if (next == Page.Speak) View.VISIBLE else View.GONE
        characterPage.visibility = if (next == Page.Characters) View.VISIBLE else View.GONE
        servicePage.visibility = if (next == Page.Service) View.VISIBLE else View.GONE
        prosodyPage.visibility = if (next == Page.Prosody) View.VISIBLE else View.GONE
        aboutPage.visibility = if (next == Page.About) View.VISIBLE else View.GONE
        logsPage.visibility = if (next == Page.Logs) View.VISIBLE else View.GONE
        applyTypingSpace()
        val onSpeak = next == Page.Speak
        val onRoles = next == Page.Characters
        val onProsody = next == Page.Prosody
        val onAbout = next == Page.About
        val onService = next == Page.Service
        val onLogs = next == Page.Logs
        if (!onRoles) {
            roleMenu?.dismiss()
            batching = false
            picked.clear()
        }
        homeBar.visibility = if (onSpeak) View.VISIBLE else View.GONE
        rolesBar.visibility = if (onRoles) View.VISIBLE else View.GONE
        prosodyBar.visibility = if (onProsody) View.VISIBLE else View.GONE
        aboutBar.visibility = if (onAbout) View.VISIBLE else View.GONE
        serviceBar.visibility = if (onService) View.VISIBLE else View.GONE
        logsBar.visibility = if (onLogs) View.VISIBLE else View.GONE
        val light = onSpeak || onRoles || onProsody || onAbout || onService || onLogs
        otherBar.visibility = if (light) View.GONE else View.VISIBLE
        val pageColor = color(if (light) R.color.bg_light else R.color.ink)
        val headerColor = color(if (light) R.color.header else R.color.ink)
        headerSlot.setBackgroundColor(headerColor)
        pageFrame.setBackgroundColor(pageColor)
        findViewById<View>(R.id.contentRoot).setBackgroundColor(pageColor)
        banner.setTextColor(color(if (light) R.color.primary else R.color.amber))
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = light
        pageTitle.setText(
            when (next) {
                Page.Speak -> R.string.app_name
                Page.Characters -> R.string.characters
                Page.Service -> R.string.service
                Page.Prosody -> R.string.prosody
                Page.About -> R.string.about
                Page.Logs -> R.string.view_log
            },
        )
        menuButton.setImageResource(if (onSpeak) R.drawable.ic_menu else R.drawable.ic_back)
        menuButton.contentDescription = if (onSpeak) "菜单" else "返回"
        val menuPages = onSpeak || onRoles || onService || onProsody
        drawer.setDrawerLockMode(
            if (menuPages) DrawerLayout.LOCK_MODE_UNLOCKED else DrawerLayout.LOCK_MODE_LOCKED_CLOSED,
            GravityCompat.START,
        )
        when (next) {
            Page.Characters -> renderCards()
            Page.Service -> renderService()
            Page.Prosody -> renderProsody()
            Page.About -> renderAbout()
            Page.Logs -> renderLogs(true)
            Page.Speak -> Unit
        }
        scheduleUptime()
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
        synthStartedAt = SystemClock.elapsedRealtime()
        synthElapsedMs = 0
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
                    if (synthStartedAt > 0L) synthElapsedMs = SystemClock.elapsedRealtime() - synthStartedAt
                    synthStartedAt = 0
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
        if (busy || importInspecting) return
        importInspecting = true
        worker.execute {
            try {
                val preview = contentResolver.openFileDescriptor(uri, "r")?.use { parcel ->
                    FileInputStream(parcel.fileDescriptor).use { input -> CharacterPack.peek(input.channel) }
                } ?: error("打不开这个文件")
                val name = displayName(uri).ifBlank { "角色包.zip" }
                val size = fileSize(uri)
                runOnUiThread {
                    importInspecting = false
                    if (!isDestroyed) showImportSheet(uri, preview, name, size)
                }
            } catch (error: Exception) {
                runOnUiThread {
                    importInspecting = false
                    if (!isDestroyed) alert(human(error))
                }
            }
        }
    }

    private fun showImportSheet(uri: Uri, preview: PackPreview, fileName: String, fileBytes: Long) {
        importSheet?.setOnDismissListener(null)
        importSheet?.dismiss()
        stopImportAudio()
        importAudioFile?.delete()
        importAudioFile = null
        val token = ++importToken
        val dialog = Dialog(this, R.style.Theme_Genie_Sheet)
        val root = layoutInflater.inflate(R.layout.dialog_import_role, null)
        dialog.setContentView(root)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        root.findViewById<TextView>(R.id.importFileName).text = fileName
        val size = if (fileBytes >= 0) sizeLabel(fileBytes) else ""
        root.findViewById<TextView>(R.id.importFileMeta).text =
            if (size.isEmpty()) getString(R.string.import_full_pack) else "$size · ${getString(R.string.import_full_pack)}"
        root.findViewById<TextView>(R.id.importRoleId).text = preview.id
        val nameField = root.findViewById<EditText>(R.id.importRoleName)
        val existing = roster.find { it.id == preview.id }?.name
        nameField.setText(existing ?: preview.name)
        nameField.setSelection(nameField.text.length)
        val audioLabel = root.findViewById<TextView>(R.id.importAudioLabel)
        val listen = root.findViewById<TextView>(R.id.importPreview)
        importListen = listen
        val audioName = preview.audioName
        if (audioName == null) {
            audioLabel.setText(R.string.import_no_audio)
            listen.alpha = 0.4f
            listen.setOnClickListener { toast(getString(R.string.import_no_audio)) }
        } else {
            audioLabel.text = "参考音频: ${audioName.substringAfterLast('/')}（正在读取）"
            listen.alpha = 0.4f
            listen.isEnabled = false
            worker.execute {
                try {
                    val bytes = contentResolver.openFileDescriptor(uri, "r")?.use { parcel ->
                        FileInputStream(parcel.fileDescriptor).use { input ->
                            CharacterPack.readNamed(input.channel, audioName)
                        }
                    } ?: error("打不开这个文件")
                    val ext = audioName.substringAfterLast('.', "bin").lowercase()
                    val file = File(cacheDir, "import-reference.$ext")
                    file.writeBytes(bytes)
                    val seconds = CharacterPack.wavSeconds(bytes) ?: mediaSeconds(file)
                    runOnUiThread {
                        if (isDestroyed || token != importToken) {
                            file.delete()
                            return@runOnUiThread
                        }
                        importAudioFile = file
                        audioLabel.text = audioCaption(audioName, seconds)
                        listen.alpha = 1f
                        listen.isEnabled = true
                        listen.setOnClickListener {
                            val playing = runCatching { importPlayer?.isPlaying == true }.getOrDefault(false)
                            if (playing) stopImportAudio() else playImportAudio(file, listen)
                        }
                    }
                } catch (error: Exception) {
                    runOnUiThread {
                        if (isDestroyed || token != importToken) return@runOnUiThread
                        audioLabel.setText(R.string.import_audio_failed)
                        listen.alpha = 0.4f
                        listen.setOnClickListener { toast(human(error)) }
                    }
                }
            }
        }
        root.findViewById<View>(R.id.importClose).setOnClickListener { dialog.dismiss() }
        root.findViewById<View>(R.id.importCancel).setOnClickListener { dialog.dismiss() }
        root.findViewById<View>(R.id.importReselect).setOnClickListener {
            pickCharacter.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
        }
        root.findViewById<View>(R.id.importConfirm).setOnClickListener {
            if (busy) return@setOnClickListener
            val typed = nameField.text.toString().trim()
            dialog.dismiss()
            importCharacter(uri, typed.ifEmpty { preview.id })
        }
        dialog.setOnDismissListener {
            if (importSheet === dialog) importSheet = null
            stopImportAudio()
            importAudioFile?.delete()
            importAudioFile = null
            if (token == importToken) importToken++
        }
        val scroll = root.findViewById<android.widget.ScrollView>(R.id.importScroll)
        scroll.post {
            val max = (resources.displayMetrics.heightPixels * 0.58f).toInt()
            if (scroll.height > max) scroll.layoutParams = scroll.layoutParams.apply { height = max }
        }
        importSheet = dialog
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun playImportAudio(file: File, button: TextView) {
        playHeldFile(
            file,
            onStarted = { button.text = "停止" },
            onStopped = { button.text = getString(R.string.preview) },
        )
    }

    private fun playHeldFile(file: File, onStarted: () -> Unit, onStopped: () -> Unit) {
        stopPlaying()
        stopImportAudio()
        onImportAudioStopped = onStopped
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .build()
        val granted = getSystemService(AudioManager::class.java)
            .requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        importFocus = focus
        val player = MediaPlayer()
        importPlayer = player
        try {
            player.setAudioAttributes(attributes)
            player.setDataSource(file.absolutePath)
            player.setOnCompletionListener { runOnUiThread { stopImportAudio() } }
            player.prepare()
            player.start()
            onStarted()
            if (!granted) toast("声音可能被其他应用占用")
        } catch (_: Exception) {
            stopImportAudio()
            toast(getString(R.string.import_audio_failed))
        }
    }

    private fun stopImportAudio() {
        val player = importPlayer
        importPlayer = null
        player?.runCatching { stop() }
        player?.runCatching { release() }
        importFocus?.let { focus ->
            getSystemService(AudioManager::class.java).abandonAudioFocusRequest(focus)
        }
        importFocus = null
        importListen?.text = getString(R.string.preview)
        val stopped = onImportAudioStopped
        onImportAudioStopped = null
        stopped?.invoke()
    }

    private fun mediaSeconds(file: File): Double? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val millis = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            if (millis == null || millis <= 0L) null else millis / 1000.0
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun audioCaption(name: String, seconds: Double?): String {
        val file = name.substringAfterLast('/')
        val clock = seconds?.let { " (${formatSec(it)}s)" }.orEmpty()
        return "参考音频: $file$clock"
    }

    private fun sizeLabel(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 0.1) "%.1f MB".format(Locale.US, mb) else "$bytes B"
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

    private fun confirmDownloadProsody() {
        if (busy) return
        if (!Prosody.isReady(filesDir)) {
            downloadProsody()
            return
        }
        showLightConfirm(
            getString(R.string.prosody_redownload_title),
            getString(R.string.prosody_redownload_message),
            getString(R.string.prosody_redownload),
        ) { downloadProsody() }
    }

    private fun downloadProsody() {
        if (busy) return
        setBusy(true)
        downloading = true
        downloadProsody.setText(R.string.prosody_download_busy)
        prosodyDetail.setText(R.string.prosody_downloading)
        showProsodyProgress(0, 0)
        worker.execute {
            try {
                speech.dropProsody()
                ProsodyPack.download(filesDir) { done, total ->
                    val line = if (total > 0) {
                        "已下载 ${bytesLabel(done)} / ${bytesLabel(total)}"
                    } else {
                        "已下载 ${bytesLabel(done)}"
                    }
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        prosodyDetail.text = line
                        showProsodyProgress(done, total)
                    }
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

    private fun showProsodyProgress(done: Long, total: Long) {
        prosodyDetail.visibility = View.VISIBLE
        prosodyProgress.visibility = View.VISIBLE
        if (total > 0) {
            prosodyProgress.isIndeterminate = false
            prosodyProgress.max = 1000
            prosodyProgress.progress = ((done.coerceIn(0, total) * 1000) / total).toInt()
        } else {
            prosodyProgress.isIndeterminate = true
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

    private fun confirmDeleteProsody() {
        if (busy) return
        showLightConfirm(
            getString(R.string.prosody_delete_title),
            getString(R.string.prosody_delete_sub),
            getString(R.string.prosody_remove),
        ) { deleteProsody() }
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
        if (page == Page.About) renderAbout()
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
                if (!batching) editCharacter(info)
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
        rolesBack.setImageResource(if (batch) R.drawable.ic_close else R.drawable.ic_menu)
        rolesBack.contentDescription = if (batch) "取消" else getString(R.string.menu)
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
        showLightConfirm(
            getString(R.string.batch_manage),
            "要删除这 $count 个角色。",
            getString(R.string.delete),
        ) {
            removeCharacters(roster.filter { it.id in picked })
        }
    }

    private fun confirmClearRoles() {
        if (busy) return
        if (roster.isEmpty()) {
            toast(getString(R.string.no_roles))
            return
        }
        showLightConfirm(
            getString(R.string.clear_roles),
            "已导入的角色都会删掉。首页正在用的角色会回到未选择。",
            "清空",
        ) {
            removeCharacters(roster.toList())
        }
    }

    private fun editCharacter(info: CharacterInfo) {
        val root = layoutInflater.inflate(R.layout.dialog_edit_role, null)
        val dialog = showLightCard(root)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val limit = maxOf(20, info.name.length)
        val nameField = root.findViewById<EditText>(R.id.editRoleName)
        val count = root.findViewById<TextView>(R.id.editRoleCount)
        val currentPill = root.findViewById<View>(R.id.editRoleCurrent)
        val switch = root.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.editRoleSwitch)
        root.findViewById<TextView>(R.id.editRoleBannerName).text = info.name
        root.findViewById<TextView>(R.id.editRoleBannerId).text = "ID: ${info.id}"
        root.findViewById<TextView>(R.id.editRoleId).text = info.id
        nameField.filters = arrayOf(InputFilter.LengthFilter(limit))
        nameField.setText(info.name)
        nameField.setSelection(nameField.text.length)
        fun paintCount() {
            count.text = "${nameField.text.length} / $limit"
        }
        paintCount()
        nameField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = paintCount()
        })
        val current = selectedId == info.id
        currentPill.visibility = if (current) View.VISIBLE else View.GONE
        switch.isChecked = current
        switch.thumbTintList = ColorStateList.valueOf(Color.WHITE)
        switch.trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(Color.parseColor("#0F766E"), Color.parseColor("#E2E8F0")),
        )
        switch.setOnCheckedChangeListener { _, checked ->
            currentPill.visibility = if (checked) View.VISIBLE else View.GONE
        }
        root.findViewById<View>(R.id.editRoleCopy).setOnClickListener {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("character-id", info.id))
            toast("已复制")
        }
        root.findViewById<View>(R.id.editRoleClear).setOnClickListener { nameField.setText("") }
        root.findViewById<View>(R.id.editRoleClose).setOnClickListener { dialog.dismiss() }
        root.findViewById<View>(R.id.editRoleCancel).setOnClickListener { dialog.dismiss() }
        bindRoleAudio(root, info, dialog)
        root.findViewById<View>(R.id.editRoleDelete).setOnClickListener {
            if (busy) return@setOnClickListener
            dialog.dismiss()
            showLightConfirm(
                getString(R.string.edit_role_delete),
                getString(R.string.edit_role_delete_message),
                getString(R.string.delete),
            ) { deleteCharacter(info) }
        }
        root.findViewById<View>(R.id.editRoleSave).setOnClickListener {
            saveRoleEdits(info, nameField.text.toString(), switch.isChecked, dialog)
        }
        dialog.setOnDismissListener { stopImportAudio() }
        root.post {
            val max = (resources.displayMetrics.heightPixels * 0.78f).toInt()
            if (root.height > max) root.layoutParams = root.layoutParams.apply { height = max }
        }
    }

    private fun bindRoleAudio(root: View, info: CharacterInfo, dialog: Dialog) {
        val play = root.findViewById<ImageButton>(R.id.editRolePlay)
        val label = root.findViewById<TextView>(R.id.editRoleAudio)
        val meta = root.findViewById<TextView>(R.id.editRoleAudioMeta)
        val audio = CharacterPack.referenceAudio(info.root)
        if (audio == null) {
            label.setText(R.string.import_no_audio)
            return
        }
        play.visibility = View.VISIBLE
        label.text = audio.name
        meta.visibility = View.VISIBLE
        meta.text = "正在读取"
        worker.execute {
            val seconds = mediaSeconds(audio)
            runOnUiThread {
                if (isDestroyed || !dialog.isShowing) return@runOnUiThread
                meta.text = seconds?.let { "${formatSec(it)}s" }.orEmpty()
                if (meta.text.isEmpty()) meta.visibility = View.GONE
            }
        }
        play.setOnClickListener {
            val playing = runCatching { importPlayer?.isPlaying == true }.getOrDefault(false)
            if (playing) {
                stopImportAudio()
                return@setOnClickListener
            }
            playHeldFile(
                audio,
                onStarted = {
                    play.setImageResource(R.drawable.ic_role_bars)
                    play.contentDescription = "停止"
                },
                onStopped = {
                    play.setImageResource(R.drawable.ic_role_play)
                    play.contentDescription = getString(R.string.preview)
                },
            )
        }
    }

    private fun saveRoleEdits(info: CharacterInfo, typed: String, makeCurrent: Boolean, dialog: Dialog) {
        val name = typed.trim()
        if (name.isEmpty()) {
            toast("名字不能是空的")
            return
        }
        if (busy) return
        val nameChanged = name != info.name
        val wasCurrent = selectedId == info.id
        val selectionChanged = makeCurrent != wasCurrent
        dialog.dismiss()
        if (!nameChanged && !selectionChanged) return
        setBusy(true)
        worker.execute {
            try {
                if (nameChanged) CharacterPack.rename(info, name)
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    if (selectionChanged) {
                        if (makeCurrent) {
                            selectedId = info.id
                            selectionFile().writeText(info.id)
                        } else if (wasCurrent) {
                            selectedId = null
                            selectionFile().delete()
                        }
                        invalidateAudio()
                    }
                    setBusy(false)
                    refreshCharacters()
                    if (selectionChanged) worker.execute { speech.dropEngine() }
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

    private fun showLightConfirm(title: String, message: String, confirm: String, onConfirm: () -> Unit) {
        val root = layoutInflater.inflate(R.layout.dialog_light_confirm, null)
        val dialog = showLightCard(root)
        root.findViewById<TextView>(R.id.confirmTitle).text = title
        root.findViewById<TextView>(R.id.confirmMessage).text = message
        root.findViewById<TextView>(R.id.confirmOk).text = confirm
        root.findViewById<View>(R.id.confirmCancel).setOnClickListener { dialog.dismiss() }
        root.findViewById<View>(R.id.confirmOk).setOnClickListener {
            dialog.dismiss()
            onConfirm()
        }
    }

    private fun showLightCard(content: View): Dialog {
        val dialog = Dialog(this, R.style.Theme_Genie_Sheet)
        dialog.setContentView(content)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        val width = (resources.displayMetrics.widthPixels * 0.86f).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setGravity(Gravity.CENTER)
        return dialog
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
        sentenceCard.visibility = View.VISIBLE
        sentenceHeading.text = "分句列表（共 ${clips.size} 句）"
        sentenceBadge.text = when {
            synthesizing && loadingModel -> "正在加载模型"
            synthesizing -> "正在合成"
            playing -> "正在播放"
            clips.any { it?.samples != null } -> "合成完毕 · 点击单句播放"
            else -> ""
        }
        sentenceBadge.visibility = if (sentenceBadge.text.isNullOrBlank()) View.GONE else View.VISIBLE
        val rowHeight = dp(40)
        val scrollHeight = rowHeight * 3
        if (sentenceScroll.layoutParams.height != scrollHeight) {
            sentenceScroll.layoutParams.height = scrollHeight
            sentenceScroll.requestLayout()
        }
        sentenceList.removeAllViews()
        clips.forEachIndexed { index, clip ->
            val hot = index == active && (playing || synthesizing)
            val samples = clip?.samples
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(8), dp(4), dp(8))
                if (hot) background = rounded(Color.parseColor("#F0FDFA"), 6f)
            }
            row.addView(TextView(this).apply {
                text = "%02d".format(Locale.US, index + 1)
                gravity = Gravity.CENTER
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextColor(if (hot) Color.WHITE else color(R.color.text_sub))
                background = rounded(if (hot) color(R.color.primary) else Color.parseColor("#F1F5F9"), 4f)
                minWidth = dp(20)
                minHeight = dp(20)
            })
            row.addView(TextView(this).apply {
                text = clip?.text.orEmpty()
                textSize = 12f
                typeface = Typeface.SERIF
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(color(if (hot) R.color.text_main else R.color.text_sub))
                setPadding(dp(8), 0, dp(8), 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val detail = when {
                samples != null -> "${formatSec(secondsOf(samples, clip.sampleRate))}s"
                !clip?.error.isNullOrBlank() -> clip?.error
                synthesizing && index == active -> "合成中"
                else -> ""
            }
            row.addView(TextView(this).apply {
                text = detail
                textSize = 11f
                typeface = Typeface.MONOSPACE
                setTextColor(
                    when {
                        !clip?.error.isNullOrBlank() -> color(R.color.amber)
                        hot -> color(R.color.primary)
                        else -> color(R.color.text_sub)
                    },
                )
            })
            if (samples != null) {
                val button = ImageButton(this).apply {
                    setImageResource(R.drawable.ic_role_play)
                    imageTintList = ColorStateList.valueOf(if (hot) Color.WHITE else color(R.color.text_sub))
                    background = rounded(if (hot) color(R.color.primary) else Color.parseColor("#F1F5F9"), 99f)
                    contentDescription = "播放这一句"
                    scaleType = ImageView.ScaleType.CENTER
                    minimumWidth = 0
                    minimumHeight = 0
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                    val side = dp(24)
                    layoutParams = LinearLayout.LayoutParams(side, side).apply { marginStart = dp(8) }
                }
                if (!busy) {
                    val playThis = {
                        if (!busy) play(listOf(index))
                    }
                    button.setOnClickListener { playThis() }
                    row.setOnClickListener { playThis() }
                }
                row.addView(button)
            }
            sentenceList.addView(
                row,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight),
            )
        }
        paintWork()
    }

    private fun renderProsody() {
        val file = Prosody.modelFile(filesDir)
        val ready = Prosody.isReady(filesDir)
        prosodyStatus.setText(if (ready) R.string.prosody_installed else R.string.prosody_missing)
        prosodyStatus.setBackgroundResource(if (ready) R.drawable.bg_prosody_ready else R.drawable.bg_prosody_idle)
        prosodyStatus.setTextColor(if (ready) 0xFF047857.toInt() else color(R.color.text_sub))
        prosodyBadge.setText(if (ready) R.string.prosody_ready else R.string.prosody_missing)
        prosodySize.text = when {
            ready -> bytesLabel(file.length())
            file.isFile -> getString(R.string.prosody_bad_size)
            else -> getString(R.string.prosody_missing)
        }
        prosodyPath.text = file.absolutePath
        prosodyUpdated.text = when {
            ready -> "上次写入：${writtenAt(file.lastModified())}"
            file.isFile -> "大小不是 ${"%,d".format(Locale.US, Prosody.MODEL_BYTES)} 字节"
            else -> getString(R.string.prosody_absent)
        }
        prosodyDeleteCard.visibility = if (file.isFile) View.VISIBLE else View.GONE
        downloadProsody.setText(
            when {
                downloading -> R.string.prosody_download_busy
                ready -> R.string.prosody_installed
                else -> R.string.download
            },
        )
        if (!downloading) {
            prosodyDetail.visibility = View.GONE
            prosodyProgress.visibility = View.GONE
            prosodyProgress.isIndeterminate = false
            prosodyProgress.progress = 0
        }
        drawerProsodyState.text = if (ready) "已就绪" else "未安装"
    }

    private fun prosodyTipWhat() {
        findViewById<TextView>(R.id.prosodyTipWhat).text = html(getString(R.string.prosody_tip_what))
        findViewById<TextView>(R.id.prosodyTipNet).text = html(getString(R.string.prosody_tip_net))
    }

    private fun html(source: String): CharSequence = HtmlCompat.fromHtml(source, HtmlCompat.FROM_HTML_MODE_COMPACT)

    private fun writtenAt(millis: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
        return format.format(Date(millis))
    }

    private fun renderService() {
        val running = TtsRuntime.running
        val problem = TtsRuntime.error
        val switching = TtsRuntime.switching
        serviceStatus.text = when {
            switching -> getString(R.string.service_switching)
            running -> getString(R.string.service_running)
            !problem.isNullOrBlank() -> problem
            else -> getString(R.string.service_stopped)
        }
        val open = running && !switching
        paintUptime()
        scheduleUptime()
        serviceDot.backgroundTintList = ColorStateList.valueOf(
            when {
                open -> Color.parseColor("#10B981")
                !running && problem.isNullOrBlank() && !switching -> Color.parseColor("#CBD5E1")
                else -> color(R.color.amber)
            },
        )
        val warn = running && !notificationsAllowed()
        serviceNotice.visibility = if (warn) View.VISIBLE else View.GONE
        serviceLocalUrl.text = serviceUrl("127.0.0.1")
        val lan = interfaceAddresses().filter { it != "127.0.0.1" }
        serviceLanRow.visibility = if (lan.isEmpty()) View.GONE else View.VISIBLE
        serviceLanUrl.text = lan.joinToString("\n") { serviceUrl(it) }
        serviceIds.text = roster.joinToString("、") { it.id }.ifBlank { getString(R.string.service_ids_empty) }
        drawerServiceState.text = if (running) "运行中" else getString(R.string.service_stopped)
        drawerServiceState.setTextColor(color(if (running) R.color.primary else R.color.text_sub))
        drawerServiceState.setBackgroundResource(if (running) R.drawable.bg_pill else R.drawable.bg_prosody_idle)
        serviceToggle.setText(if (running) R.string.service_stop else R.string.service_start)
        serviceToggle.setBackgroundResource(if (running) R.drawable.bg_service_stop else R.drawable.bg_service_start)
        serviceToggle.setTextColor(if (running) Color.parseColor("#BE123C") else Color.WHITE)
        serviceToggle.isEnabled = !switching
        serviceToggle.alpha = if (switching) 0.45f else 1f
        val shown = TtsRuntime.port.toString()
        if (!servicePort.hasFocus() && servicePort.text.toString() != shown) {
            servicePort.setText(shown)
        }
    }

    private fun serviceUrl(address: String): String = "http://$address:${TtsRuntime.port}/tts"

    private fun paintUptime() {
        val started = TtsRuntime.startedAt
        val show = TtsRuntime.running && started > 0L
        serviceUptime.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        val total = ((SystemClock.elapsedRealtime() - started) / 1000).coerceAtLeast(0)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        serviceUptimeValue.text = "%02d:%02d:%02d".format(Locale.US, hours, minutes, seconds)
    }

    private fun scheduleUptime() {
        uptimeHandler.removeCallbacks(uptimeTicker)
        val started = TtsRuntime.startedAt
        val show = TtsRuntime.running && started > 0L && page == Page.Service && !isDestroyed
        if (!show) return
        val elapsed = SystemClock.elapsedRealtime() - started
        val delay = (1000 - (elapsed % 1000)).coerceIn(32, 1000)
        uptimeHandler.postDelayed(uptimeTicker, delay)
    }

    private fun setLogOpen(open: Boolean) {
        logOpen = open
        serviceLog.visibility = if (open) View.VISIBLE else View.GONE
        serviceLogChevron.rotation = if (open) 180f else 0f
    }

    private fun logAutoScroll(): SwitchCompat = findViewById(R.id.logAutoScroll)

    private fun pickLogLevel(level: LogLevel?) {
        logLevelFilter = level
        if (page == Page.Logs) renderLogs(false)
    }

    private fun confirmClearLogs() {
        if (TtsLog.entries().isEmpty()) {
            toast("还没有日志")
            return
        }
        showLightConfirm("清空日志", "要清空已经记下的日志。", "清空") {
            TtsLog.clear()
        }
    }

    private fun copyAllLogs() {
        val text = TtsLog.text()
        if (text.isBlank()) {
            toast("还没有日志")
            return
        }
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("tts-log", text))
        toast("已复制")
    }

    private fun exportLogs() {
        val text = TtsLog.text()
        if (text.isBlank()) {
            toast("还没有日志")
            return
        }
        pendingLogText = text
        saveLog.launch("genie-log.txt")
    }

    private fun copyLogPath() {
        val path = TtsLog.path()
        if (path.isBlank()) {
            toast("还没有日志")
            return
        }
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("tts-log", path))
        toast("已复制日志路径")
    }

    private fun renderLogs(follow: Boolean) {
        val entries = TtsLog.entries()
        val query = findViewById<EditText>(R.id.logQuery).text.toString().trim()
        val shown = entries.mapIndexed { index, line -> (index + 1) to line }.filter { (_, line) ->
            val levelOk = logLevelFilter == null || logLevel(line.message) == logLevelFilter
            val queryOk = query.isEmpty() || line.message.contains(query, ignoreCase = true)
            levelOk && queryOk
        }
        paintLogChip(R.id.logFilterAll, null, entries.size)
        paintLogChip(R.id.logFilterInfo, LogLevel.INFO, entries.count { logLevel(it.message) == LogLevel.INFO })
        paintLogChip(R.id.logFilterWarn, LogLevel.WARN, entries.count { logLevel(it.message) == LogLevel.WARN })
        paintLogChip(R.id.logFilterError, LogLevel.ERROR, entries.count { logLevel(it.message) == LogLevel.ERROR })
        paintLogChip(R.id.logFilterDebug, LogLevel.DEBUG, entries.count { logLevel(it.message) == LogLevel.DEBUG })
        val running = TtsRuntime.running
        val tone = if (running) 0xFF0F766E.toInt() else 0xFF64748B.toInt()
        val bannerText = findViewById<TextView>(R.id.logBannerText)
        bannerText.text = if (running) "接口开着 · PID ${Process.myPid()}" else "接口未开启 · PID ${Process.myPid()}"
        bannerText.setTextColor(tone)
        val ram = findViewById<TextView>(R.id.logBannerRam)
        val memory = Debug.MemoryInfo()
        Debug.getMemoryInfo(memory)
        ram.text = "RAM %.1f MB".format(Locale.US, memory.totalPss / 1024.0)
        ram.setTextColor(tone)
        findViewById<View>(R.id.logBannerDot).backgroundTintList =
            ColorStateList.valueOf(if (running) 0xFF0D9488.toInt() else 0xFF94A3B8.toInt())
        val list = findViewById<LinearLayout>(R.id.logList)
        val scroll = findViewById<ScrollView>(R.id.logScroll)
        val scrollY = scroll.scrollY
        list.removeAllViews()
        val end = findViewById<TextView>(R.id.logEnd)
        if (shown.isEmpty()) {
            end.visibility = View.GONE
            list.addView(TextView(this).apply {
                text = if (entries.isEmpty()) "还没有日志" else "没有符合的日志"
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(28), dp(16), dp(28))
                setTextColor(color(R.color.text_sub))
                textSize = 13f
            })
        } else {
            end.visibility = View.VISIBLE
            end.text = if (logLevelFilter == null && query.isEmpty()) "已显示全部日志" else "已显示筛选结果"
            shown.forEachIndexed { position, (index, line) ->
                if (position > 0) list.addView(logDivider())
                list.addView(logRow(index, line))
            }
        }
        findViewById<TextView>(R.id.logCount).text = "共 ${entries.size} 条 · 占用 ${logBytes(TtsLog.bytes())}"
        scroll.post {
            scroll.post {
                val child = scroll.getChildAt(0)
                if (follow && child != null) scroll.scrollTo(0, child.bottom)
                else scroll.scrollTo(0, scrollY)
            }
        }
    }

    private fun paintLogChip(id: Int, level: LogLevel?, count: Int) {
        val view = findViewById<TextView>(id)
        val selected = logLevelFilter == level
        val label = level?.name ?: "全部"
        view.text = if (level == null || count > 0) "$label  $count" else label
        view.setTextColor(if (selected) Color.WHITE else 0xFF475569.toInt())
        view.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        view.background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(if (selected) 0xFF0A4B56.toInt() else 0xFFF1F5F9.toInt())
        }
        val dotColor = when (level) {
            LogLevel.INFO -> 0xFF06B6D4.toInt()
            LogLevel.WARN -> 0xFFF59E0B.toInt()
            LogLevel.ERROR -> 0xFFF43F5E.toInt()
            LogLevel.DEBUG -> 0xFFA855F7.toInt()
            null -> 0
        }
        if (dotColor == 0) {
            view.setCompoundDrawables(null, null, null, null)
        } else {
            val size = dp(6).coerceAtLeast(1)
            val dot = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(dotColor)
                setBounds(0, 0, size, size)
            }
            view.setCompoundDrawables(dot, null, null, null)
            view.compoundDrawablePadding = dp(4)
        }
    }

    private fun logRow(index: Int, line: LogLine): View {
        val level = logLevel(line.message)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setBackgroundColor(logRowColor(level))
        }
        val meta = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        meta.addView(TextView(this).apply {
            text = line.time.ifBlank { "--:--:--.---" }
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFF94A3B8.toInt())
        })
        meta.addView(
            logBadge(level),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(6)
            },
        )
        meta.addView(
            TextView(this).apply {
                text = "[${logTag(line.message)}]"
                textSize = 11f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFF334155.toInt())
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) },
        )
        meta.addView(TextView(this).apply {
            text = "#%03d".format(Locale.US, index)
            textSize = 10f
            setTextColor(0xFF94A3B8.toInt())
        })
        row.addView(meta)
        row.addView(TextView(this).apply {
            text = line.message
            textSize = 12f
            setTextColor(0xFF334155.toInt())
            setPadding(dp(2), dp(4), 0, 0)
            setTextIsSelectable(true)
        })
        return row
    }

    private fun logBadge(level: LogLevel): TextView {
        val (fill, ink, stroke) = when (level) {
            LogLevel.INFO -> Triple(0xFFECFEFF.toInt(), 0xFF0E7490.toInt(), 0xFFA5F3FC.toInt())
            LogLevel.WARN -> Triple(0xFFFFFBEB.toInt(), 0xFFB45309.toInt(), 0xFFFDE68A.toInt())
            LogLevel.ERROR -> Triple(0xFFFFF1F2.toInt(), 0xFFBE123C.toInt(), 0xFFFECDD3.toInt())
            LogLevel.DEBUG -> Triple(0xFFFAF5FF.toInt(), 0xFF7E22CE.toInt(), 0xFFE9D5FF.toInt())
        }
        return TextView(this).apply {
            text = level.name
            textSize = 10f
            setTextColor(ink)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(6), dp(1), dp(6), dp(1))
            background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setColor(fill)
                setStroke(dp(1).coerceAtLeast(1), stroke)
            }
        }
    }

    private fun logRowColor(level: LogLevel): Int = when (level) {
        LogLevel.INFO -> Color.WHITE
        LogLevel.WARN -> 0xFFFFFBEB.toInt()
        LogLevel.ERROR -> 0xFFFFF1F2.toInt()
        LogLevel.DEBUG -> 0xFFFAF5FF.toInt()
    }

    private fun logDivider(): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1).coerceAtLeast(1),
            )
            setBackgroundColor(0xFFF1F5F9.toInt())
        }
    }

    private fun logBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        return "${(bytes + 512) / 1024} KB"
    }

    private fun revealLog() {
        setLogOpen(true)
        val card = findViewById<View>(R.id.serviceLogCard)
        card.post { (servicePage as android.widget.ScrollView).smoothScrollTo(0, card.top) }
    }

    private fun copyPlain(label: String, text: CharSequence) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        toast("已复制")
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
        val prosodyAlpha = if (busy) 0.4f else 1f
        downloadProsody.alpha = prosodyAlpha
        importProsody.alpha = prosodyAlpha
        deleteProsody.alpha = prosodyAlpha
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
        synthStartedAt = 0
        synthElapsedMs = 0
        workNote = ""
        renderSentences()
    }

    private fun showWork(text: String) {
        workNote = text
        paintWork()
    }

    private fun paintWork() {
        val warn = workNote.contains("没有声音") || workNote.contains("占用") || workNote.contains("没有可以播放")
        workStatus.text = when {
            synthesizing && loadingModel -> "加载中"
            synthesizing -> "合成中"
            playing -> "播放中"
            clips.any { it?.samples != null } -> "完成"
            clips.isNotEmpty() -> "完成"
            else -> "未合成"
        }
        val metrics = metricsLine()
        val playingLine = workNote.startsWith("正在播放")
        workMetrics.text = when {
            playingLine -> workNote
            metrics.isNotBlank() -> metrics
            else -> if (isProgressNote(workNote)) "" else workNote
        }
        val extra = workNote.isNotBlank() && !isProgressNote(workNote) && workMetrics.text != workNote
        workNoteLine.text = workNote
        workNoteLine.visibility = if (extra) View.VISIBLE else View.GONE
        workDot.backgroundTintList = ColorStateList.valueOf(
            when {
                warn -> color(R.color.amber)
                playing || synthesizing -> color(R.color.primary)
                clips.any { it?.samples != null } -> Color.parseColor("#10B981")
                else -> Color.parseColor("#CBD5E1")
            },
        )
    }

    private fun isProgressNote(note: String): Boolean {
        return note.isBlank() ||
            note.startsWith("正在加载") ||
            note.startsWith("正在合成") ||
            note.startsWith("正在打开") ||
            note.startsWith("正在播放") ||
            note.startsWith("合成完成") ||
            note == "播放结束" ||
            note == "已停止"
    }

    private fun metricsLine(): String {
        if (clips.isEmpty()) return "分句: -/-  耗时: -  时长: -  实时率: -"
        val total = clips.size
        val finished = clips.count { it?.samples != null || !it?.error.isNullOrBlank() }
        val shown = if (synthesizing) (active + 1).coerceIn(0, total) else finished
        val parts = mutableListOf("分句: $shown/$total")
        val elapsed = currentElapsed()
        if (elapsed > 0) parts += "耗时: ${formatElapsed(elapsed)}"
        val audio = clips.sumOf { clip ->
            val samples = clip?.samples ?: return@sumOf 0.0
            secondsOf(samples, clip.sampleRate)
        }
        if (audio > 0) {
            parts += "时长: ${formatSec(audio)}s"
            if (elapsed > 0) parts += "实时率: ${"%.2f".format(Locale.US, (elapsed / 1000.0) / audio)}x"
        }
        return parts.joinToString("  ")
    }

    private fun currentElapsed(): Long {
        if (synthesizing && synthStartedAt > 0L) return SystemClock.elapsedRealtime() - synthStartedAt
        return synthElapsedMs
    }

    private fun formatElapsed(ms: Long): String {
        return if (ms < 1000) "${ms}ms" else "${"%.1f".format(Locale.US, ms / 1000.0)}s"
    }

    private fun rounded(fill: Int, radiusDp: Float): GradientDrawable {
        return GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radiusDp * resources.displayMetrics.density
        }
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

    private fun renderAbout() {
        val role = roster.find { it.id == selectedId }?.name ?: getString(R.string.unselected)
        aboutBadge.text = versionBadge()
        aboutSystem.text = "Android ${Build.VERSION.RELEASE}"
        aboutModel.text = "${Build.MANUFACTURER} ${Build.MODEL}"
        aboutRole.text = role
        aboutInstalled.text = writtenAt(packageManager.getPackageInfo(packageName, 0).lastUpdateTime)
        aboutBuilt.text = writtenAt(BuildConfig.BUILT_AT)
        aboutVersion.text = versionLabel()
        aboutPackage.text = packageName
    }

    private fun openRepo() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.about_repo_url)))
        try {
            startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            toast("打不开这个链接")
        }
    }

    private fun aboutLines(): String {
        val role = roster.find { it.id == selectedId }?.name ?: getString(R.string.unselected)
        val installed = writtenAt(packageManager.getPackageInfo(packageName, 0).lastUpdateTime)
        return "版本 ${versionLabel()}\n" +
            "构建时间 ${writtenAt(BuildConfig.BUILT_AT)}\n" +
            "包名 $packageName\n" +
            "系统 Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）\n" +
            "机型 ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "当前角色 $role\n" +
            "安装时间 $installed"
    }

    private fun versionLabel(): String = "${versionName()}（${versionCode()}）"

    private fun versionName(): String {
        return packageManager.getPackageInfo(packageName, 0).versionName ?: "0.0.16"
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
        if (sentenceCard.visibility == View.VISIBLE) renderSentences()
    }

    private fun fileSize(uri: Uri): Long {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0)
        }
        return -1L
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
