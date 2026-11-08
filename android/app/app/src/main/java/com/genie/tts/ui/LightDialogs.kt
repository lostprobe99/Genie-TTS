package com.genie.tts.ui

import com.genie.tts.R
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** 浅色确认框和居中卡片。角色、韵律和日志共用。 */
internal class LightDialogs(private val activity: AppCompatActivity) {
    fun confirm(title: String, message: String, confirm: String, onConfirm: () -> Unit) {
        val root = activity.layoutInflater.inflate(R.layout.dialog_light_confirm, null)
        val dialog = card(root)
        root.findViewById<TextView>(R.id.confirmTitle).text = title
        root.findViewById<TextView>(R.id.confirmMessage).text = message
        root.findViewById<TextView>(R.id.confirmOk).text = confirm
        root.findViewById<View>(R.id.confirmCancel).setOnClickListener { dialog.dismiss() }
        root.findViewById<View>(R.id.confirmOk).setOnClickListener {
            dialog.dismiss()
            onConfirm()
        }
    }

    fun card(content: View): Dialog {
        val dialog = Dialog(activity, R.style.Theme_Genie_Sheet)
        dialog.setContentView(content)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        val width = (activity.resources.displayMetrics.widthPixels * 0.86f).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setGravity(Gravity.CENTER)
        return dialog
    }
}
