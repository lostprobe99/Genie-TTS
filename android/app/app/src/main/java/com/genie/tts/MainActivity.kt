package com.genie.tts

import com.genie.tts.character.BatchStart
import com.genie.tts.character.CharacterInfo
import com.genie.tts.character.CharacterPack
import com.genie.tts.character.ImportOffer
import com.genie.tts.core.LogLevel
import com.genie.tts.core.Notice
import com.genie.tts.core.NoticeKind
import com.genie.tts.core.LogModule
import com.genie.tts.core.TtsLog
import com.genie.tts.core.appBuildBanner
import com.genie.tts.core.noteInstalledBuild
import com.genie.tts.service.PortCommit
import com.genie.tts.service.ServiceStart
import com.genie.tts.service.ServiceStates
import com.genie.tts.service.TtsService
import com.genie.tts.speech.Engines
import com.genie.tts.speech.PlaybackKind
import com.genie.tts.speech.Prosody
import com.genie.tts.speech.invalidateSpokenAudio
import com.genie.tts.speech.Release
import com.genie.tts.speech.TextSplitter
import com.genie.tts.speech.speedOf
import com.genie.tts.ui.AboutPageController
import com.genie.tts.ui.EditRoleDialog
import com.genie.tts.ui.HeldAudio
import com.genie.tts.ui.ImportSheet
import com.genie.tts.ui.LightDialogs
import com.genie.tts.ui.CharacterController
import com.genie.tts.ui.CharacterSnapshot
import com.genie.tts.ui.ProsodyController
import com.genie.tts.ui.ProsodySnapshot
import com.genie.tts.ui.SpeakController
import com.genie.tts.ui.SpeakSnapshot
import com.genie.tts.ui.HelpPageController
import com.genie.tts.ui.LogPageController
import com.genie.tts.ui.ServicePageController
import com.genie.tts.ui.helpCopyText
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
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
import java.io.FileInputStream
import java.util.Locale
import kotlin.math.max

class MainActivity : AppCompatActivity() {
    private enum class Page { Speak, Characters, Service, Prosody, About, Logs, Help }

    private val session: AppViewModel by viewModels()
    private val splitter = TextSplitter()
    private lateinit var heldAudio: HeldAudio
    private lateinit var light: LightDialogs
    private lateinit var importSheet: ImportSheet
    private lateinit var editRole: EditRoleDialog
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
    private lateinit var helpBar: View
    private lateinit var otherBar: View
    private lateinit var roleRow: View
    private lateinit var menuButton: android.widget.ImageButton
    private lateinit var pageTitle: TextView
    private lateinit var banner: TextView
    private lateinit var drawerRoleCount: TextView
    private lateinit var speakPage: View
    private lateinit var speakChrome: View
    private var imeBottom = 0
    private lateinit var characterPage: View
    private lateinit var servicePage: View
    private lateinit var prosodyPage: View
    private lateinit var aboutPage: View
    private lateinit var logsPage: View
    private lateinit var helpPage: View
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
    private var pendingLogText: String? = null
    private lateinit var logController: LogPageController
    private lateinit var helpController: HelpPageController
    private lateinit var aboutController: AboutPageController
    private lateinit var serviceController: ServicePageController
    private lateinit var speakController: SpeakController
    private lateinit var characterController: CharacterController
    private lateinit var prosodyController: ProsodyController

    private var page = Page.Speak
    private var speedGestureKeepsAudio = false
    private var suppress = false
    private var pinnedBanner: String? = null
    private var seenSynthAlert = 0L
    private var seenPlayAlert = 0L
    private var seenCharacterAlert = 0L
    private var seenNotice = 0L
    private var stopCharacterWatch: (() -> Unit)? = null
    private var stopProsodyWatch: (() -> Unit)? = null
    private var stopNoticeWatch: (() -> Unit)? = null
    private var drawnSpeak = SpeakSnapshot(
        clips = emptyList(),
        synthesizing = false,
        loadingModel = false,
        playing = false,
        busy = false,
        active = -1,
        workNote = "",
        elapsedMs = 0L,
        canSpeak = false,
    )
    private var drawnPreview: String? = null
    private var drawnPlaying = false
    private var stopSpeechWatch: (() -> Unit)? = null
    private var spinnerGen = 0
    private var roleMenu: PopupWindow? = null
    private var stopWatchingService: (() -> Unit)? = null

    private val pickCharacter = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onCharacterPicked(uri)
    }
    private val pickProsody = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onProsodyPicked(uri)
    }
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        session.service.start()
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
        Engines.activityOpen = true
        session.service.restorePort()
        TtsLog.attach(filesDir)
        val installed = currentAppBuild()
        noteInstalledBuild(filesDir, installed) { line ->
            TtsLog.add(LogLevel.INFO, LogModule.APP, line)
        }
        setContentView(R.layout.activity_main)
        heldAudio = HeldAudio(this)
        light = LightDialogs(this)
        importSheet = ImportSheet(this, heldAudio) { session.playback.stop() }
        editRole = EditRoleDialog(this, light, heldAudio) { session.playback.stop() }
        aboutController = AboutPageController(this)
        logController = LogPageController(this) { page == Page.Logs }
        helpController = HelpPageController(this, { page == Page.Help }) { aboutController.versionName() }
        serviceController = ServicePageController(this, { page == Page.Service }, ::notificationsAllowed)
        speakController = SpeakController(this)
        characterController = CharacterController(this) { id, choices -> session.characters.avatarIndex(id, choices) }
        prosodyController = ProsodyController(this)
        bindViews()
        restoreDraft()
        showInterrupted()
        bindActions()
        session.characters.refresh()
        renderCharacters()
        renderSpeed()
        showPage(Page.Speak)
        openRequestedPage(intent)
        val version = aboutController.versionBadge()
        findViewById<TextView>(R.id.drawerVersion).text = version
        findViewById<TextView>(R.id.homeVersion).text = version
        findViewById<TextView>(R.id.logsVersion).text = version
        findViewById<TextView>(R.id.logsBuild).text = appBuildBanner(installed)
        renderProsody()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openRequestedPage(intent)
    }

    override fun onStart() {
        super.onStart()
        stopWatchingService?.invoke()
        stopWatchingService = ServiceStates.store.listen { if (!isDestroyed) renderService() }
        watchSpeech()
        stopCharacterWatch?.invoke()
        stopCharacterWatch = session.characters.listen { if (!isDestroyed) renderCharacters() }
        renderCharacters()
        stopProsodyWatch?.invoke()
        stopProsodyWatch = session.prosody.listen { if (!isDestroyed) renderProsody() }
        stopNoticeWatch?.invoke()
        stopNoticeWatch = session.notices.listen(::showNotice)
        showNotice(session.notices.current())
        renderProsody()
        TtsLog.listener = {
            if (!isDestroyed && page == Page.Logs) logController.render(logController.autoScroll.isChecked)
        }
        renderService()
    }

    override fun onStop() {
        serviceController.stop()
        stopSpeechWatch?.invoke()
        stopSpeechWatch = null
        stopCharacterWatch?.invoke()
        stopCharacterWatch = null
        stopProsodyWatch?.invoke()
        stopProsodyWatch = null
        stopNoticeWatch?.invoke()
        stopNoticeWatch = null
        stopWatchingService?.invoke()
        stopWatchingService = null
        TtsLog.listener = null
        super.onStop()
    }

    override fun onDestroy() {
        if (::importSheet.isInitialized) importSheet.dismiss()
        if (::editRole.isInitialized) editRole.dismiss()
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
        helpBar = findViewById(R.id.helpBar)
        otherBar = findViewById(R.id.otherBar)
        roleRow = findViewById(R.id.roleRow)
        menuButton = findViewById(R.id.menuButton)
        pageTitle = findViewById(R.id.pageTitle)
        banner = findViewById(R.id.banner)
        drawerRoleCount = findViewById(R.id.drawerRoleCount)
        speakPage = findViewById(R.id.speakPage)
        speakChrome = findViewById(R.id.speakChrome)
        characterPage = findViewById(R.id.characterPage)
        servicePage = findViewById(R.id.servicePage)
        prosodyPage = findViewById(R.id.prosodyPage)
        aboutPage = findViewById(R.id.aboutPage)
        logsPage = findViewById(R.id.logsPage)
        helpPage = findViewById(R.id.helpPage)
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
        findViewById<View>(R.id.prosodyBack).setOnClickListener { drawer.openDrawer(drawerPanel) }
        findViewById<View>(R.id.aboutBack).setOnClickListener { showPage(Page.Speak) }
        helpController.bind(
            onBack = { showPage(Page.Speak) },
            onRefresh = { toast("当前是 ${aboutController.versionBadge()}。应用不在线检查更新。") },
            onDocs = ::openRepo,
            onCopyEndpoint = ::copyHelpEndpoint,
            onRepo = ::openRepo,
            onProsody = { openLink(Release.PROSODY_URL) },
            onOpenLogs = { showPage(Page.Logs) },
        )
        logController.bind(
            onBack = { showPage(Page.Speak) },
            onExport = ::exportLogs,
            onCopyPath = ::copyLogPath,
            onClear = ::confirmClearLogs,
            onCopyAll = ::copyAllLogs,
        )
        findViewById<View>(R.id.serviceBack).setOnClickListener { drawer.openDrawer(drawerPanel) }
        findViewById<View>(R.id.serviceLogJump).setOnClickListener { showPage(Page.Logs) }
        characterController.bind(
            onQuery = { if (page == Page.Characters) renderCards() },
            onBack = {
                if (session.characters.current().batching) exitBatch() else drawer.openDrawer(drawerPanel)
            },
            onMore = { showRoleMenu(it) },
            onSelectAll = { toggleSelectShown() },
            onDeletePicked = { confirmDeletePicked() },
            onImport = {
                if (isBusy()) return@bind
                pickCharacter.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
            },
        )
        findViewById<View>(R.id.drawerClose).setOnClickListener {
            drawer.closeDrawer(drawerPanel)
        }
        findViewById<View>(R.id.openHome).setOnClickListener {
            drawer.closeDrawer(drawerPanel)
            showPage(Page.Speak)
        }
        roleRow.setOnClickListener {
            if (session.characters.current().roster.isEmpty()) showPage(Page.Characters)
            else characters.performClick()
        }
        findViewById<View>(R.id.openSettings).setOnClickListener {
            drawer.closeDrawer(drawerPanel)
            toast(getString(R.string.not_ready))
        }
        findViewById<View>(R.id.openHelp).setOnClickListener {
            drawer.closeDrawer(drawerPanel)
            showPage(Page.Help)
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
                    page == Page.Characters && session.characters.current().batching -> exitBatch()
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
                if (fromUser && !suppress && !session.playback.current().playing && !speedGestureKeepsAudio) invalidateAudio()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                speedGestureKeepsAudio = session.playback.current().playing
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                speedGestureKeepsAudio = false
            }
        })
        characters.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppress) return
                val next = session.characters.current().roster.getOrNull(position)?.id
                session.characters.select(next)
                if (page == Page.About) aboutController.render(currentRoleName())
                updateControls()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        speakController.bind(
            onSynthesize = { if (!session.synthesis.current().synthesizing && !isBusy()) onSynthesize() },
            onTogglePlay = {
                val playback = session.playback.current()
                if (playback.playing) session.playback.stop()
                else {
                    val clips = session.synthesis.current().clips
                    play(clips.indices.filter { clips[it]?.samples != null })
                }
            },
        )
        prosodyController.bind(
            onDownload = ::confirmDownloadProsody,
            onImport = {
                if (isBusy()) return@bind
                pickProsody.launch(arrayOf("*/*", "application/zip", "application/octet-stream"))
            },
            onDelete = ::confirmDeleteProsody,
            onCopyPath = { copyPlain("prosody", Prosody.modelFile(filesDir).absolutePath) },
        )
        serviceController.bind(::toggleService, ::commitPort)
        findViewById<View>(R.id.copyLocal).setOnClickListener {
            copyPlain("tts", serviceController.localUrl.text)
        }
        findViewById<View>(R.id.copyLan).setOnClickListener {
            copyPlain("tts", serviceController.lanUrl.text)
        }
        findViewById<View>(R.id.copyAbout).setOnClickListener {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("about", aboutController.lines(currentRoleName())))
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
        helpPage.visibility = if (next == Page.Help) View.VISIBLE else View.GONE
        applyTypingSpace()
        val onSpeak = next == Page.Speak
        val onRoles = next == Page.Characters
        val onProsody = next == Page.Prosody
        val onAbout = next == Page.About
        val onService = next == Page.Service
        val onLogs = next == Page.Logs
        val onHelp = next == Page.Help
        if (!onRoles) {
            roleMenu?.dismiss()
            session.characters.leaveBatch()
        }
        homeBar.visibility = if (onSpeak) View.VISIBLE else View.GONE
        rolesBar.visibility = if (onRoles) View.VISIBLE else View.GONE
        prosodyBar.visibility = if (onProsody) View.VISIBLE else View.GONE
        aboutBar.visibility = if (onAbout) View.VISIBLE else View.GONE
        serviceBar.visibility = if (onService) View.VISIBLE else View.GONE
        logsBar.visibility = if (onLogs) View.VISIBLE else View.GONE
        helpBar.visibility = if (onHelp) View.VISIBLE else View.GONE
        val light = onSpeak || onRoles || onProsody || onAbout || onService || onLogs || onHelp
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
                Page.Help -> R.string.help
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
            Page.About -> aboutController.render(currentRoleName())
            Page.Logs -> logController.render(true)
            Page.Help -> helpController.render(true)
            Page.Speak -> Unit
        }
        serviceController.scheduleUptime()
    }

    private fun onSynthesize() {
        if (isBusy()) return
        session.playback.quietStop()
        val character = currentCharacter() ?: return
        val text = sentence.text.toString()
        if (text.isBlank()) return
        val pieces = splitter.split(text)
        if (pieces.isEmpty()) {
            toast("这句没有读出来")
            return
        }
        confirmProsody(preview = false) {
            session.synthesis.synthesize(character, pieces, speedOf(speed.progress), aboutController.versionLabel())
        }
    }

    private fun preview(character: CharacterInfo) {
        if (isBusy()) return
        confirmProsody(preview = true) {
            session.synthesis.preview(character, aboutController.versionLabel())
        }
    }

    private fun play(indexes: List<Int>) {
        if (isBusy() || indexes.isEmpty()) return
        session.playback.play(indexes, session.synthesis.current().clips)
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
        val name = displayName(uri).ifBlank { "角色包.zip" }
        val size = fileSize(uri)
        session.characters.inspect(uri, name, size) {
            contentResolver.openFileDescriptor(uri, "r")?.use { parcel ->
                FileInputStream(parcel.fileDescriptor).use { input -> CharacterPack.peek(input.channel) }
            } ?: error("打不开这个文件")
        }
    }

    private fun showImportOffer(offer: ImportOffer) {
        val uri = offer.key as? Uri ?: return
        val existing = session.characters.current().roster.find { it.id == offer.preview.id }?.name
        importSheet.show(
            uri,
            offer.preview,
            offer.fileName,
            offer.fileBytes,
            existing,
            ::isBusy,
            onConfirm = { typed ->
                session.characters.import(typed) {
                    contentResolver.openInputStream(uri) ?: error("打不开这个文件")
                }
            },
            onReselect = {
                pickCharacter.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
            },
        )
    }

    private fun renderCharacters() {
        if (isDestroyed || !::characters.isInitialized) return
        val state = session.characters.current()
        val offer = session.characters.consumeOffer()
        if (offer != null) showImportOffer(offer)
        if (state.alert != null && state.alertId != seenCharacterAlert && !isFinishing) {
            seenCharacterAlert = state.alertId
            if (state.alert.isNotEmpty()) alert(state.alert)
        }
        if (state.roster.isEmpty()) {
            placeholder.visibility = View.VISIBLE
            characters.visibility = View.GONE
        } else {
            placeholder.visibility = View.GONE
            characters.visibility = View.VISIBLE
            val labels = state.roster.map { it.name } + getString(R.string.unselected)
            val generation = ++spinnerGen
            suppress = true
            val adapter = ArrayAdapter(this, R.layout.spinner_selected, labels)
            adapter.setDropDownViewResource(R.layout.spinner_item)
            characters.adapter = adapter
            val index = state.roster.indexOfFirst { it.id == state.selectedId }
            characters.setSelection(if (index >= 0) index else state.roster.size, false)
            characters.post { if (generation == spinnerGen) suppress = false }
        }
        renderCards()
        drawerRoleCount.text = "${state.roster.size}个角色"
        if (page == Page.About) aboutController.render(currentRoleName())
        updateControls()
    }

    private fun characterState(): CharacterSnapshot {
        val state = session.characters.current()
        val playback = session.playback.current()
        return CharacterSnapshot(
            roster = state.roster,
            selectedId = state.selectedId,
            batching = state.batching,
            onPage = page == Page.Characters,
            picked = state.picked,
            busy = isBusy(),
            playing = playback.playing,
            previewId = playback.previewId,
        )
    }

    private fun renderCards() {
        characterController.render(
            characterState(),
            onToggle = { id -> session.characters.togglePicked(id) },
            onEdit = ::editCharacter,
            onPreview = { info ->
                val playback = session.playback.current()
                if (playback.playing && playback.previewId == info.id) session.playback.stop() else preview(info)
            },
        )
    }

    private fun enterBatch() {
        when (session.characters.enterBatch()) {
            BatchStart.Busy -> Unit
            BatchStart.Empty -> toast(getString(R.string.no_roles))
            BatchStart.Started -> Unit
        }
    }

    private fun exitBatch() {
        session.characters.exitBatch()
    }

    private fun toggleSelectShown() {
        session.characters.toggleShown(characterController.shownIds())
    }

    private fun confirmDeletePicked() {
        val picked = session.characters.current().picked
        if (picked.isEmpty()) {
            toast(getString(R.string.pick_first))
            return
        }
        showLightConfirm(
            getString(R.string.batch_manage),
            "要删除这 ${picked.size} 个角色。",
            getString(R.string.delete),
        ) {
            val roster = session.characters.current().roster
            session.characters.remove(roster.filter { it.id in picked })
        }
    }

    private fun confirmClearRoles() {
        val roster = session.characters.current().roster
        if (isBusy()) return
        if (roster.isEmpty()) {
            toast(getString(R.string.no_roles))
            return
        }
        showLightConfirm(
            getString(R.string.clear_roles),
            "已导入的角色都会删掉。首页正在用的角色会回到未选择。",
            "清空",
        ) {
            session.characters.remove(session.characters.current().roster.toList())
        }
    }

    private fun editCharacter(info: CharacterInfo) {
        val selected = session.characters.current().selectedId == info.id
        editRole.show(
            info,
            selected,
            ::isBusy,
            onSave = { typed, makeCurrent -> session.characters.save(info, typed, makeCurrent) },
            onDelete = { session.characters.remove(listOf(info)) },
        )
    }

    private fun showLightConfirm(title: String, message: String, confirm: String, onConfirm: () -> Unit) {
        light.confirm(title, message, confirm, onConfirm)
    }

    private fun confirmDownloadProsody() {
        if (isBusy()) return
        if (!session.prosody.ready()) {
            session.prosody.download()
            return
        }
        showLightConfirm(
            getString(R.string.prosody_redownload_title),
            getString(R.string.prosody_redownload_message),
            getString(R.string.prosody_redownload),
        ) { session.prosody.download() }
    }

    private fun onProsodyPicked(uri: Uri) {
        val name = displayName(uri)
        session.prosody.import(name) {
            contentResolver.openInputStream(uri) ?: error("打不开这个文件")
        }
    }

    private fun confirmDeleteProsody() {
        if (isBusy()) return
        showLightConfirm(
            getString(R.string.prosody_delete_title),
            getString(R.string.prosody_delete_sub),
            getString(R.string.prosody_remove),
        ) { session.prosody.delete() }
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
            characterController.clickImport()
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

    private fun speakState(): SpeakSnapshot {
        val synth = session.synthesis.current()
        val play = session.playback.current()
        val busy = isBusy()
        val sentencePlaying = play.kind == PlaybackKind.Sentences && (play.playing || play.active >= 0)
        val elapsed = if (synth.synthesizing && synth.startedAt > 0L) {
            SystemClock.elapsedRealtime() - synth.startedAt
        } else {
            synth.elapsedMs
        }
        return SpeakSnapshot(
            clips = synth.clips,
            synthesizing = synth.synthesizing,
            loadingModel = synth.loadingModel,
            playing = play.playing,
            busy = busy,
            active = if (sentencePlaying) play.active else synth.active,
            workNote = play.note.ifEmpty { synth.note },
            elapsedMs = elapsed,
            canSpeak = !busy && !synth.synthesizing && currentCharacter() != null && sentence.text.toString().isNotBlank(),
        )
    }

    private fun watchSpeech() {
        val render = { if (!isDestroyed) renderSpeech() }
        val stops = listOf(
            session.synthesis.listen { render() },
            session.playback.listen { render() },
            session.lane.listen { render() },
        )
        stopSpeechWatch = { stops.forEach { it() } }
        renderSpeech()
    }

    private fun renderSpeech() {
        if (isDestroyed || !::speakController.isInitialized) return
        val snap = speakState()
        val listChanged = snap.clips != drawnSpeak.clips ||
            snap.synthesizing != drawnSpeak.synthesizing ||
            snap.loadingModel != drawnSpeak.loadingModel ||
            snap.active != drawnSpeak.active ||
            snap.busy != drawnSpeak.busy ||
            snap.playing != drawnSpeak.playing
        if (listChanged) {
            speakController.renderSentences(snap) { index -> if (!isBusy()) play(listOf(index)) }
        } else {
            speakController.paint(snap)
        }
        val controlsChanged = listChanged ||
            snap.workNote != drawnSpeak.workNote ||
            snap.canSpeak != drawnSpeak.canSpeak ||
            snap.elapsedMs != drawnSpeak.elapsedMs
        if (controlsChanged) {
            speakController.updateControls(snap)
            characters.isEnabled = !snap.busy
            characterController.setImportEnabled(!snap.busy)
            renderProsody()
        }
        if (snap.active != drawnSpeak.active && snap.active >= 0) speakController.scrollToActive(snap.active)
        val play = session.playback.current()
        val cardsChanged = play.previewId != drawnPreview || play.playing != drawnPlaying || snap.busy != drawnSpeak.busy
        drawnPreview = play.previewId
        drawnPlaying = play.playing
        if (cardsChanged && page == Page.Characters) renderCards()
        applyBanner()
        drawnSpeak = snap
        consumeAlerts()
    }

    private fun applyBanner() {
        val play = session.playback.current()
        if (play.bannerOwned) {
            val text = play.banner
            if (text.isNullOrEmpty()) {
                banner.visibility = View.GONE
            } else {
                banner.text = text
                banner.visibility = View.VISIBLE
            }
            return
        }
        val pinned = pinnedBanner
        if (pinned != null) {
            banner.text = pinned
            banner.visibility = View.VISIBLE
        }
    }

    private fun consumeAlerts() {
        if (isFinishing || isDestroyed) return
        val synth = session.synthesis.current()
        if (synth.alert != null && synth.alertId != seenSynthAlert) {
            seenSynthAlert = synth.alertId
            if (synth.alert.isNotEmpty()) alert(synth.alert)
        }
        val play = session.playback.current()
        if (play.alert != null && play.alertId != seenPlayAlert) {
            seenPlayAlert = play.alertId
            if (play.alert.isNotEmpty()) alert(play.alert)
        }
    }

    private fun isBusy(): Boolean = session.lane.isBusy()


    private fun prosodyState(): ProsodySnapshot {
        val file = Prosody.modelFile(filesDir)
        val job = session.prosody.current()
        return ProsodySnapshot(
            ready = Prosody.isReady(filesDir),
            fileExists = file.isFile,
            fileBytes = file.length(),
            filePath = file.absolutePath,
            lastModified = file.lastModified(),
            downloading = job.downloading,
            busy = isBusy(),
            downloadDone = job.downloadDone,
            downloadTotal = job.downloadTotal,
        )
    }

    private fun renderProsody() {
        prosodyController.render(prosodyState())
    }

    private fun renderService() {
        serviceController.render(session.characters.current().roster)
    }

    private fun copyHelpEndpoint() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("tts", helpCopyText(ServiceStates.store.current().port)))
        toast("已复制本机朗读接口")
    }

    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            toast("打不开这个链接")
        }
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

    private fun copyPlain(label: String, text: CharSequence) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        toast("已复制")
    }

    private fun commitPort() {
        val outcome = session.service.commitPort(serviceController.port.text.toString())
        if (outcome == PortCommit.Invalid) {
            serviceController.port.setText(ServiceStates.store.current().port.toString())
        }
    }

    private fun toggleService() {
        val granted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (session.service.toggle(granted) == ServiceStart.NeedPermission) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun notificationsAllowed(): Boolean {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun openRequestedPage(source: Intent?) {
        if (source?.getStringExtra(TtsService.EXTRA_PAGE) == TtsService.PAGE_SERVICE) showPage(Page.Service)
    }

    private fun renderSpeed() {
        speakController.renderSpeed("%.2f".format(Locale.US, speedOf(speed.progress)))
    }

    private fun updateControls() {
        speakController.updateControls(speakState())
        characters.isEnabled = !isBusy()
        characterController.setImportEnabled(!isBusy())
        renderProsody()
    }

    private fun invalidateAudio() {
        invalidateSpokenAudio(session.playback, session.synthesis)
    }

    private fun restoreDraft() {
        suppress = true
        sentence.setText(draftFile().takeIf { it.isFile }?.readText().orEmpty())
        val saved = speedFile().takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
        if (saved != null) speed.progress = saved.coerceIn(0, 16)
        suppress = false
    }

    private fun showInterrupted() {
        if (session.playback.current().bannerOwned) return
        val raw = session.synthesis.consumeMarker() ?: return
        pinnedBanner = "上次合成没有完成\n版本 ${raw.ifBlank { aboutController.versionLabel() }}"
        applyBanner()
    }

    private fun showNotice(notice: Notice?) {
        if (notice == null || notice.id == seenNotice || isFinishing || isDestroyed) return
        seenNotice = notice.id
        when (notice.kind) {
            NoticeKind.Dialog -> if (notice.text.isNotEmpty()) alert(notice.text)
            NoticeKind.Toast -> if (notice.text.isNotEmpty()) toast(notice.text)
        }
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

    private fun openRepo() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.about_repo_url)))
        try {
            startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            toast("打不开这个链接")
        }
    }

    private fun currentCharacter(): CharacterInfo? {
        val state = session.characters.current()
        return state.roster.find { it.id == state.selectedId }
    }

    private fun currentRoleName(): String = currentCharacter()?.name ?: getString(R.string.unselected)

    private fun currentAppBuild(): com.genie.tts.core.AppBuild {
        val info = packageManager.getPackageInfo(packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
        return com.genie.tts.core.AppBuild(
            info.versionName ?: "0.0.19",
            code,
            BuildConfig.BUILT_AT,
            info.lastUpdateTime,
        )
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

    private fun color(id: Int) = ContextCompat.getColor(this, id)

    private fun colorState(id: Int) = android.content.res.ColorStateList.valueOf(color(id))

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun draftFile() = File(filesDir, "draft.txt")

    private fun speedFile() = File(filesDir, "speech-speed.txt")

}
