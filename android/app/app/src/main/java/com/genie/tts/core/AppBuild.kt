package com.genie.tts.core

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal data class AppBuild(
    val versionName: String,
    val versionCode: Long,
    val builtAt: Long,
    val installedAt: Long,
)

internal fun appBuildMarker(build: AppBuild): String =
    "${build.versionName}|${build.versionCode}|${build.builtAt}|${build.installedAt}"

internal fun appBuildChanged(saved: String?, build: AppBuild): Boolean =
    saved?.trim() != appBuildMarker(build)

internal fun appBuildClock(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(millis))

internal fun appBuildLogLine(build: AppBuild): String =
    "当前版本 ${build.versionName}（${build.versionCode}），编译 ${appBuildClock(build.builtAt)}，安装 ${appBuildClock(build.installedAt)}"

internal fun appBuildBanner(build: AppBuild): String =
    "${build.versionName}（${build.versionCode}）  编译 ${appBuildClock(build.builtAt)}  安装 ${appBuildClock(build.installedAt)}"

/** 安装包变了才记一条。版本号相同但编译时间或安装时间变了也算。 */
internal fun noteInstalledBuild(dir: File, build: AppBuild, log: (String) -> Unit) {
    val file = File(dir, "app-build.txt")
    val saved = file.takeIf { it.isFile }?.readText()
    if (!appBuildChanged(saved, build)) return
    log(appBuildLogLine(build))
    file.writeText(appBuildMarker(build))
}
