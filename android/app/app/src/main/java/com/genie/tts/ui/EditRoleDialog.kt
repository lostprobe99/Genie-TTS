package com.genie.tts.ui

import com.genie.tts.R
import com.genie.tts.character.CharacterInfo
import com.genie.tts.character.CharacterPack
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors
import kotlin.math.max

/** 长按角色后的改名页。保存和删除由外面做。 */
internal class EditRoleDialog(
    private val activity: AppCompatActivity,
    private val light: LightDialogs,
    private val audio: HeldAudio,
    private val stopSpeech: () -> Unit,
) {
    private var dialog: Dialog? = null
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "genie-role-audio").apply { isDaemon = true }
    }

    fun dismiss() {
        dialog?.dismiss()
        dialog = null
    }

    fun show(
        info: CharacterInfo,
        selected: Boolean,
        busy: () -> Boolean,
        onSave: (String, Boolean) -> Unit,
        onDelete: () -> Unit,
    ) {
        val root = activity.layoutInflater.inflate(R.layout.dialog_edit_role, null)
        val sheet = light.card(root)
        dialog = sheet
        sheet.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val limit = max(20, info.name.length)
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
        currentPill.visibility = if (selected) View.VISIBLE else View.GONE
        switch.isChecked = selected
        switch.thumbTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
        switch.trackTintList = android.content.res.ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(Color.parseColor("#0F766E"), Color.parseColor("#E2E8F0")),
        )
        switch.setOnCheckedChangeListener { _, checked ->
            currentPill.visibility = if (checked) View.VISIBLE else View.GONE
        }
        root.findViewById<View>(R.id.editRoleCopy).setOnClickListener {
            val clipboard = activity.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("character-id", info.id))
            toast("已复制")
        }
        root.findViewById<View>(R.id.editRoleClear).setOnClickListener { nameField.setText("") }
        root.findViewById<View>(R.id.editRoleClose).setOnClickListener { sheet.dismiss() }
        root.findViewById<View>(R.id.editRoleCancel).setOnClickListener { sheet.dismiss() }
        bindAudio(root, info, sheet)
        root.findViewById<View>(R.id.editRoleDelete).setOnClickListener {
            if (busy()) return@setOnClickListener
            sheet.dismiss()
            light.confirm(
                activity.getString(R.string.edit_role_delete),
                activity.getString(R.string.edit_role_delete_message),
                activity.getString(R.string.delete),
            ) { onDelete() }
        }
        root.findViewById<View>(R.id.editRoleSave).setOnClickListener {
            val typed = nameField.text.toString()
            if (typed.trim().isEmpty()) {
                toast("名字不能是空的")
                return@setOnClickListener
            }
            if (busy()) return@setOnClickListener
            sheet.dismiss()
            onSave(typed, switch.isChecked)
        }
        sheet.setOnDismissListener { audio.stop() }
        root.post {
            val height = (activity.resources.displayMetrics.heightPixels * 0.78f).toInt()
            if (root.height > height) root.layoutParams = root.layoutParams.apply { this.height = height }
        }
    }

    private fun bindAudio(root: View, info: CharacterInfo, sheet: Dialog) {
        val play = root.findViewById<ImageButton>(R.id.editRolePlay)
        val label = root.findViewById<TextView>(R.id.editRoleAudio)
        val meta = root.findViewById<TextView>(R.id.editRoleAudioMeta)
        val file = CharacterPack.referenceAudio(info.root)
        if (file == null) {
            label.setText(R.string.import_no_audio)
            return
        }
        play.visibility = View.VISIBLE
        label.text = file.name
        meta.visibility = View.VISIBLE
        meta.text = "正在读取"
        worker.execute {
            val seconds = mediaSeconds(file)
            activity.runOnUiThread {
                if (activity.isDestroyed || !sheet.isShowing) return@runOnUiThread
                meta.text = seconds?.let { "${formatSeconds(it)}s" }.orEmpty()
                if (meta.text.isEmpty()) meta.visibility = View.GONE
            }
        }
        play.setOnClickListener {
            if (audio.isPlaying()) {
                audio.stop()
                return@setOnClickListener
            }
            stopSpeech()
            audio.play(
                file,
                onStarted = {
                    play.setImageResource(R.drawable.ic_role_bars)
                    play.contentDescription = "停止"
                },
                onStopped = {
                    play.setImageResource(R.drawable.ic_role_play)
                    play.contentDescription = activity.getString(R.string.preview)
                },
            )
        }
    }

    private fun toast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
    }
}
