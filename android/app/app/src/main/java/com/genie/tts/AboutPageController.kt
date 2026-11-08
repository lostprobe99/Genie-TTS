package com.genie.tts

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
        system.text = "Android ${Build.VERSION.RELEASE}"
        model.text = "${Build.MANUFACTURER} ${Build.MODEL}"
        role.text = currentRole
        installed.text = writtenAt(packageInfo().lastUpdateTime)
        built.text = writtenAt(BuildConfig.BUILT_AT)
        version.text = versionLabel()
        packageName.text = activity.packageName
    }

    fun lines(currentRole: String): String {
        return "版本 ${versionLabel()}\n" +
            "构建时间 ${writtenAt(BuildConfig.BUILT_AT)}\n" +
            "包名 ${activity.packageName}\n" +
            "系统 Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）\n" +
            "机型 ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "当前角色 $currentRole\n" +
            "安装时间 ${writtenAt(packageInfo().lastUpdateTime)}"
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
