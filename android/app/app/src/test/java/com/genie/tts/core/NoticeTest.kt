package com.genie.tts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NoticeTest {
    @Test
    fun eachNoticeGetsANewIdAndReplacesThePreviousOne() {
        val notices = Notices { it() }
        assertNull(notices.current())
        val seen = mutableListOf<Notice>()
        notices.listen { notice -> if (notice != null) seen += notice }
        notices.toast("端口要在 1 到 65535 之间")
        notices.dialog("打不开这个文件")
        assertEquals(NoticeKind.Dialog, notices.current()?.kind)
        assertEquals("打不开这个文件", notices.current()?.text)
        assertEquals(listOf(1L, 2L), seen.map { it.id })
        assertEquals(NoticeKind.Toast, seen.first().kind)
    }

    @Test
    fun anAlertIsTakenOnceAndBlankTextIsSkipped() {
        assertEquals(0L to null, takeAlert(0L, null, 0L))
        assertEquals(3L to "合成没有完成", takeAlert(0L, "合成没有完成", 3L))
        assertEquals(3L to null, takeAlert(3L, "合成没有完成", 3L))
        assertEquals(4L to null, takeAlert(3L, "", 4L))
    }
}
