package com.genie.tts.ui

import com.genie.tts.databinding.DialogImportRoleBinding
import com.genie.tts.R
import com.genie.tts.character.CharacterPack
import com.genie.tts.character.PackPreview
import com.genie.tts.core.uiText
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Executors

/** 导入前的确认页。确认之后做什么，由外面决定。 */
internal class ImportSheet(
    private val activity: AppCompatActivity,
    private val audio: HeldAudio,
    private val stopSpeech: () -> Unit,
) {
    private var dialog: Dialog? = null
    private var token = 0
    private var audioFile: File? = null
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "genie-import-audio").apply { isDaemon = true }
    }

    fun dismiss() {
        dialog?.setOnDismissListener(null)
        dialog?.dismiss()
        dialog = null
        releaseAudio()
    }

    fun show(
        uri: Uri,
        preview: PackPreview,
        fileName: String,
        fileBytes: Long,
        existingName: String?,
        busy: () -> Boolean,
        onConfirm: (String) -> Unit,
        onReselect: () -> Unit,
    ) {
        dismiss()
        val ticket = ++token
        val sheet = Dialog(activity, R.style.Theme_Genie_Sheet)
        val form = DialogImportRoleBinding.inflate(activity.layoutInflater)
        val root = form.root
        sheet.setContentView(root)
        sheet.setCanceledOnTouchOutside(true)
        sheet.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        form.importFileName.text = fileName
        val size = if (fileBytes >= 0) sizeLabel(fileBytes) else ""
        form.importFileMeta.text =
            if (size.isEmpty()) activity.getString(R.string.import_full_pack)
            else "$size · ${activity.getString(R.string.import_full_pack)}"
        form.importRoleId.text = preview.id
        val nameField = form.importRoleName
        if (existingName != null) {
            form.importTitle.setText(R.string.import_update)
            form.importNameNote.setText(R.string.import_name_note_update)
            form.importConfirmLabel.setText(R.string.import_confirm_update)
        }
        nameField.setText(existingName ?: preview.name)
        nameField.setSelection(nameField.text.length)
        val audioLabel = form.importAudioLabel
        val listen = form.importPreview
        audio.bindLabel(listen)
        val audioName = preview.audioName
        if (audioName == null) {
            audioLabel.setText(R.string.import_no_audio)
            listen.alpha = 0.4f
            listen.setOnClickListener { toast(activity.getString(R.string.import_no_audio)) }
        } else {
            audioLabel.text = activity.getString(
                R.string.import_audio_reading,
                audioName.substringAfterLast('/'),
            )
            listen.alpha = 0.4f
            listen.isEnabled = false
            worker.execute {
                try {
                    val bytes = activity.contentResolver.openFileDescriptor(uri, "r")?.use { parcel ->
                        FileInputStream(parcel.fileDescriptor).use { input ->
                            CharacterPack.readNamed(input.channel, audioName)
                        }
                    } ?: error(activity.getString(R.string.file_unreadable))
                    val ext = audioName.substringAfterLast('.', "bin").lowercase()
                    val file = File(activity.cacheDir, "import-reference.$ext")
                    file.writeBytes(bytes)
                    val seconds = CharacterPack.wavSeconds(bytes) ?: mediaSeconds(file)
                    activity.runOnUiThread {
                        if (activity.isDestroyed || ticket != token) {
                            file.delete()
                            return@runOnUiThread
                        }
                        audioFile = file
                        audioLabel.text = audioCaption(audioName, seconds)
                        listen.alpha = 1f
                        listen.isEnabled = true
                        listen.setOnClickListener {
                            if (audio.isPlaying()) audio.stop() else play(file, listen)
                        }
                    }
                } catch (error: Exception) {
                    activity.runOnUiThread {
                        if (activity.isDestroyed || ticket != token) return@runOnUiThread
                        audioLabel.setText(R.string.import_audio_failed)
                        listen.alpha = 0.4f
                        listen.setOnClickListener { toast(uiText(error)) }
                    }
                }
            }
        }
        form.importClose.setOnClickListener { sheet.dismiss() }
        form.importCancel.setOnClickListener { sheet.dismiss() }
        form.importReselect.setOnClickListener { onReselect() }
        form.importConfirm.setOnClickListener {
            if (busy()) return@setOnClickListener
            val typed = nameField.text.toString().trim()
            sheet.dismiss()
            onConfirm(typed.ifEmpty { preview.id })
        }
        sheet.setOnDismissListener {
            if (dialog === sheet) dialog = null
            releaseAudio()
            if (ticket == token) token++
        }
        val scroll = form.importScroll
        scroll.post {
            val max = (activity.resources.displayMetrics.heightPixels * 0.58f).toInt()
            if (scroll.height > max) scroll.layoutParams = scroll.layoutParams.apply { height = max }
        }
        dialog = sheet
        sheet.show()
        sheet.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun play(file: File, button: TextView) {
        stopSpeech()
        audio.play(
            file,
            onStarted = { button.text = activity.getString(R.string.stop) },
            onStopped = { button.text = activity.getString(R.string.preview) },
        )
    }

    private fun releaseAudio() {
        audio.stop()
        audio.bindLabel(null)
        audioFile?.delete()
        audioFile = null
    }

    private fun audioCaption(name: String, seconds: Double?): String {
        val file = name.substringAfterLast('/')
        val clock = seconds?.let { " (${formatSeconds(it)}s)" }.orEmpty()
        return activity.getString(R.string.import_audio_line, file, clock)
    }

    private fun sizeLabel(bytes: Long): String {
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 0.1) "%.1f MB".format(java.util.Locale.US, mb) else "$bytes B"
    }

    private fun toast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }
}
