package com.genie.tts.ui

import com.genie.tts.BuildConfig
import com.genie.tts.R
import android.os.Build
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Builds the about page and its copyable plain-text summary. */
internal class AboutPageController(
    private val activity: AppCompatActivity,
) {
    private val badge: TextView = activity.findViewById(R.id.aboutBadge)
    private val system: TextView = activity.findViewById(R.id.aboutSystem)
    private val model: TextView = activity.findViewById(R.id.aboutModel)
    private val role: TextView = activity.findViewById(R.id.aboutRole)
    private val installed: TextView = activity.findViewById(R.id.aboutInstalled)
    private val built: TextView = activity.findViewById(R.id.aboutBuilt)
    private val version: TextView = activity.findViewById(R.id.aboutVersion)
    private val packageName: TextView = activity.findViewById(R.id.aboutPackage)

    fun render(currentRole: String) {
        badge.text = versionBadge()
        system.text = activity.getString(R.string.about_system_value, Build.VERSION.RELEASE)
        model.text = "${Build.MANUFACTURER} ${Build.MODEL}"
        role.text = currentRole
        installed.text = writtenAt(packageInfo().lastUpdateTime)
        built.text = writtenAt(BuildConfig.BUILT_AT)
        version.text = versionLabel()
        packageName.text = activity.packageName
    }

    fun lines(currentRole: String): String {
        return activity.getString(
            R.string.about_lines,
            versionLabel(),
            writtenAt(BuildConfig.BUILT_AT),
            activity.packageName,
            Build.VERSION.RELEASE,
            Build.VERSION.SDK_INT,
            "${Build.MANUFACTURER} ${Build.MODEL}",
            currentRole,
            writtenAt(packageInfo().lastUpdateTime),
        )
    }

    fun versionBadge(): String = "v${versionName()} (${versionCode()})"
    fun versionLabel(): String = "${versionName()}（${versionCode()}）"
    fun versionName(): String = packageInfo().versionName ?: "0.0.19"

    private fun versionCode(): Long {
        val info = packageInfo()
        return if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    private fun packageInfo() = activity.packageManager.getPackageInfo(activity.packageName, 0)

    private fun writtenAt(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(millis))
}
