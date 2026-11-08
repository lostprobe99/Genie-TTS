package com.genie.tts.core

import java.io.File

/** 合成或试听开始时写下版本，结束时删掉。进程中途没了，下次打开还能读到。 */
internal class RunMarker(private val file: File) : StepMark {
    override fun arm(label: String) {
        file.parentFile?.mkdirs()
        file.writeText(label)
    }

    override fun disarm() {
        file.delete()
    }

    override fun consume(): String? {
        if (!file.isFile) return null
        val text = file.readText().trim()
        file.delete()
        return text
    }
}

internal interface StepMark {
    fun arm(label: String)
    fun disarm()
    fun consume(): String?
}
