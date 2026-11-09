package com.genie.tts

import com.genie.tts.databinding.ActivityMainBinding
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
import com.genie.tts.ui.AppPage
import com.genie.tts.ui.EditRoleDialog
import com.genie.tts.ui.HeldAudio
import com.genie.tts.ui.ImportSheet
import com.genie.tts.ui.LightDialogs
import com.genie.tts.ui.CharacterController
import com.genie.tts.ui.CharacterSnapshot
import com.genie.tts.ui.PageShell
import com.genie.tts.ui.RoleSpinner
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
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.View
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
import java.io.File
import java.io.FileInputStream
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val session: AppViewModel by viewModels()
    private val splitter = TextSplitter()
    private lateinit var shell: PageShell
    private lateinit var roles: RoleSpinner
    private lateinit var heldAudio: HeldAudio
    private lateinit var light: LightDialogs
    private lateinit var importSheet: ImportSheet
    private lateinit var editRole: EditRoleDialog
    private lateinit var logController: LogPageController
    private lateinit var helpController: HelpPageController
    private lateinit var aboutController: AboutPageController
    private lateinit var serviceController: ServicePageController
    private lateinit var speakController: SpeakController
    private lateinit var characterController: CharacterController
    private lateinit var prosodyController: ProsodyController

    private var page = AppPage.Speak
    private var restoring = false
    private var speedGestureKeepsAudio = false
    private var pinnedBanner: String? = null
    private var seenNotice = 0L
    private var stopCharacterWatch: (() -> Unit)? = null
    private var stopProsodyWatch: (() -> Unit)? = null
    private var stopNoticeWatch: (() -> Unit)? = null
    private var drawnPreview: String? = null
    private var drawnPlaying = false
    private var drawnBusy = false
    private var stopSpeechWatch: (() -> Unit)? = null
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
        if (uri == null) return@registerForActivityResult
        Thread({
            val saved = try {
                contentResolver.openOutputStream(uri)?.use(TtsLog::exportTo) ?: error("empty")
                true
            } catch (_: Exception) {
                false
            }
            runOnUiThread {
                if (!isDestroyed) toast(getString(if (saved) R.string.exported else R.string.export_failed))
            }
        }, "genie-log-export").start()
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
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        heldAudio = HeldAudio(this)
        light = LightDialogs(this)
        importSheet = ImportSheet(this, heldAudio) { session.playback.stop() }
        editRole = EditRoleDialog(this, light, heldAudio) { session.playback.stop() }
        aboutController = AboutPageController(this, binding)
        logController = LogPageController(this, binding) { page == AppPage.Logs }
        helpController = HelpPageController(this, binding, { page == AppPage.Help }) { aboutController.versionName() }
        serviceController = ServicePageController(this, binding, { page == AppPage.Service }, ::notificationsAllowed)
        speakController = SpeakController(this, binding)
        characterController = CharacterController(this, binding) { id, choices -> session.characters.avatarIndex(id, choices) }
        prosodyController = ProsodyController(this, binding)
        shell = PageShell(this, binding) { speakController.sentence.hasFocus() }
        roles = RoleSpinner(this, binding)
        shell.padForScreen()
        restoreDraft()
        showInterrupted()
        bindActions()
        session.characters.refresh()
        renderCharacters()
        renderSpeed()
        showPage(AppPage.Speak)
        openRequestedPage(intent)
        val version = aboutController.versionBadge()
        binding.drawerVersion.text = version
        binding.homeVersion.text = version
        binding.logsVersion.text = version
        binding.logsBuild.text = appBuildBanner(installed)
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
            if (!isDestroyed && page == AppPage.Logs) logController.onNewLines()
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

    private fun bindActions() {
        val sentence = speakController.sentence
        val speed = speakController.speed
        shell.bindMenu {
            if (page == AppPage.Speak) shell.openDrawer() else showPage(AppPage.Speak)
        }
        binding.homeMenu.setOnClickListener { shell.openDrawer() }
        binding.homeSearch.setOnClickListener { sentence.requestFocus() }
        binding.homeMore.setOnClickListener { toast(getString(R.string.not_ready)) }
        binding.prosodyBack.setOnClickListener { shell.openDrawer() }
        binding.aboutBack.setOnClickListener { showPage(AppPage.Speak) }
        helpController.bind(
            onBack = { showPage(AppPage.Speak) },
            onRefresh = { toast(getString(R.string.up_to_date, aboutController.versionBadge())) },
            onDocs = ::openRepo,
            onCopyEndpoint = ::copyHelpEndpoint,
            onRepo = ::openRepo,
            onProsody = { openLink(Release.PROSODY_URL) },
            onOpenLogs = { showPage(AppPage.Logs) },
        )
        logController.bind(
            onBack = { showPage(AppPage.Speak) },
            onExport = ::exportLogs,
            onClear = ::confirmClearLogs,
        )
        binding.serviceBack.setOnClickListener { shell.openDrawer() }
        binding.serviceLogJump.setOnClickListener { showPage(AppPage.Logs) }
        characterController.bind(
            onQuery = { if (page == AppPage.Characters) renderCards() },
            onBack = {
                if (session.characters.current().batching) exitBatch() else shell.openDrawer()
            },
            onMore = { showRoleMenu(it) },
            onSelectAll = { toggleSelectShown() },
            onDeletePicked = { confirmDeletePicked() },
            onImport = {
                if (isBusy()) return@bind
                pickCharacter.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
            },
        )
        shell.bindDrawer(onPage = ::showPage, onSettings = { toast(getString(R.string.not_ready)) })
        shell.bindRoleRow {
            if (session.characters.current().roster.isEmpty()) showPage(AppPage.Characters) else roles.click()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    shell.isDrawerOpen() -> shell.closeDrawer()
                    page == AppPage.Characters && session.characters.current().batching -> exitBatch()
                    page != AppPage.Speak -> showPage(AppPage.Speak)
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })
        sentence.setOnFocusChangeListener { _, _ -> shell.applyTyping() }
        sentence.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                draftFile().writeText(s?.toString().orEmpty())
                if (!restoring) invalidateAudio()
                updateControls()
            }
        })
        speed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                renderSpeed()
                speedFile().writeText(progress.coerceIn(0, 16).toString())
                if (fromUser && !session.playback.current().playing && !speedGestureKeepsAudio) invalidateAudio()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                speedGestureKeepsAudio = session.playback.current().playing
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                speedGestureKeepsAudio = false
            }
        })
        roles.bind { position ->
            val next = session.characters.current().roster.getOrNull(position)?.id
            session.characters.select(next)
            if (page == AppPage.About) aboutController.render(currentRoleName())
            updateControls()
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
        binding.servicePage.copyLocal.setOnClickListener {
            copyPlain("tts", serviceController.localUrl.text)
        }
        binding.servicePage.copyLan.setOnClickListener {
            copyPlain("tts", serviceController.lanUrl.text)
        }
        binding.aboutPage.copyAbout.setOnClickListener {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("about", aboutController.lines(currentRoleName())))
            toast(getString(R.string.copied))
        }
        binding.aboutPage.aboutOpen.setOnClickListener { openRepo() }
    }

    private fun showPage(next: AppPage) {
        page = next
        shell.show(next)
        if (next != AppPage.Characters) {
            characterController.dismissMenu()
            session.characters.leaveBatch()
        }
        when (next) {
            AppPage.Characters -> renderCards()
            AppPage.Service -> renderService()
            AppPage.Prosody -> renderProsody()
            AppPage.About -> aboutController.render(currentRoleName())
            AppPage.Logs -> logController.render(true)
            AppPage.Help -> helpController.render(true)
            AppPage.Speak -> Unit
        }
        serviceController.scheduleUptime()
    }

    private fun onSynthesize() {
        if (isBusy()) return
        session.playback.quietStop()
        val character = currentCharacter() ?: return
        val text = speakController.sentence.text.toString()
        if (text.isBlank()) return
        val pieces = splitter.split(text)
        if (pieces.isEmpty()) {
            toast(getString(R.string.sentence_unread))
            return
        }
        confirmProsody(preview = false) {
            session.synthesis.synthesize(
                character,
                pieces,
                speedOf(speakController.speed.progress),
                aboutController.versionLabel(),
            )
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
            .setTitle(R.string.prosody_missing_title)
            .setMessage(R.string.prosody_missing_message)
            .setNegativeButton(if (preview) R.string.continue_preview else R.string.continue_synth) { _, _ ->
                then()
            }
            .setPositiveButton(R.string.go_add) { _, _ -> showPage(AppPage.Prosody) }
            .show()
    }

    private fun onCharacterPicked(uri: Uri) {
        val name = displayName(uri).ifBlank { getString(R.string.character_pack_name) }
        val size = fileSize(uri)
        session.characters.inspect(uri, name, size) {
            contentResolver.openFileDescriptor(uri, "r")?.use { parcel ->
                FileInputStream(parcel.fileDescriptor).use { input -> CharacterPack.peek(input.channel) }
            } ?: error(getString(R.string.file_unreadable))
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
                    contentResolver.openInputStream(uri) ?: error(getString(R.string.file_unreadable))
                }
            },
            onReselect = {
                pickCharacter.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
            },
        )
    }

    private fun renderCharacters() {
        if (isDestroyed || !::roles.isInitialized) return
        val state = session.characters.current()
        session.characters.consumeOffer()?.let(::showImportOffer)
        roles.render(
            state.roster.map { it.name },
            state.roster.map { it.id },
            state.selectedId,
            getString(R.string.unselected),
        )
        renderCards()
        shell.setRoleCount(state.roster.size)
        if (page == AppPage.About) aboutController.render(currentRoleName())
        updateControls()
    }

    private fun characterState(): CharacterSnapshot {
        val state = session.characters.current()
        val playback = session.playback.current()
        return CharacterSnapshot(
            roster = state.roster,
            selectedId = state.selectedId,
            batching = state.batching,
            onPage = page == AppPage.Characters,
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
            getString(R.string.delete_roles, picked.size),
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
            getString(R.string.clear_roles_message),
            getString(R.string.clear),
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
            contentResolver.openInputStream(uri) ?: error(getString(R.string.file_unreadable))
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
        characterController.showMenu(
            anchor,
            onImport = { characterController.clickImport() },
            onBatch = ::enterBatch,
            onExport = { toast(getString(R.string.not_ready)) },
            onClear = ::confirmClearRoles,
        )
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
            canSpeak = !busy && !synth.synthesizing && currentCharacter() != null &&
                speakController.sentence.text.toString().isNotBlank(),
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
        val controlsChanged = speakController.apply(snap) { index -> if (!isBusy()) play(listOf(index)) }
        if (controlsChanged) {
            roles.setEnabled(!snap.busy)
            characterController.setImportEnabled(!snap.busy)
            renderProsody()
        }
        val play = session.playback.current()
        val cardsChanged = play.previewId != drawnPreview || play.playing != drawnPlaying || snap.busy != drawnBusy
        drawnBusy = snap.busy
        drawnPreview = play.previewId
        drawnPlaying = play.playing
        if (cardsChanged && page == AppPage.Characters) renderCards()
        applyBanner()
    }

    private fun applyBanner() {
        val banner = shell.banner
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
        clipboard.setPrimaryClip(
            ClipData.newPlainText("tts", helpCopyText(this, ServiceStates.store.current().port)),
        )
        toast(getString(R.string.endpoint_copied))
    }

    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            toast(getString(R.string.link_failed))
        }
    }

    private fun confirmClearLogs() {
        if (TtsLog.isEmpty()) {
            toast(getString(R.string.log_empty))
            return
        }
        showLightConfirm(
            getString(R.string.log_clear),
            getString(R.string.clear_logs_message),
            getString(R.string.clear),
        ) {
            TtsLog.clear()
        }
    }

    private fun exportLogs() {
        if (TtsLog.isEmpty()) {
            toast(getString(R.string.log_empty))
            return
        }
        saveLog.launch("genie-log.txt")
    }

    private fun copyPlain(label: String, text: CharSequence) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        toast(getString(R.string.copied))
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
        if (source?.getStringExtra(TtsService.EXTRA_PAGE) == TtsService.PAGE_SERVICE) showPage(AppPage.Service)
    }

    private fun renderSpeed() {
        speakController.renderSpeed("%.2f".format(Locale.US, speedOf(speakController.speed.progress)))
    }

    private fun updateControls() {
        speakController.updateControls(speakState())
        roles.setEnabled(!isBusy())
        characterController.setImportEnabled(!isBusy())
        renderProsody()
    }

    private fun invalidateAudio() {
        invalidateSpokenAudio(session.playback, session.synthesis)
    }

    private fun restoreDraft() {
        restoring = true
        speakController.sentence.setText(draftFile().takeIf { it.isFile }?.readText().orEmpty())
        val saved = speedFile().takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
        if (saved != null) speakController.speed.progress = saved.coerceIn(0, 16)
        restoring = false
    }

    private fun showInterrupted() {
        if (session.playback.current().bannerOwned) return
        val raw = session.synthesis.consumeMarker() ?: return
        pinnedBanner = getString(
            R.string.last_synth_banner,
            raw.ifBlank { aboutController.versionLabel() },
        )
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
            .setPositiveButton(R.string.ok, null)
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
            toast(getString(R.string.link_failed))
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
            info.versionName ?: "0.0.20",
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

    private fun draftFile() = File(filesDir, "draft.txt")

    private fun speedFile() = File(filesDir, "speech-speed.txt")

}
