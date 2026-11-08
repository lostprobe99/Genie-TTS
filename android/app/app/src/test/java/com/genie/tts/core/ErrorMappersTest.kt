package com.genie.tts.core

import com.genie.tts.speech.RefMismatch
import org.junit.Assert.assertEquals
import org.junit.Test

class ErrorMappersTest {
    @Test
    fun packProblemsShareOneSentence() {
        val error = AppFailure.PackIncomplete("角色包缺少 vits_fp32.onnx")
        assertEquals("角色包不完整，请重新导入", uiText(error))
        assertEquals("这句没有读出来", sentenceUiText(IllegalStateException("打不开")))
        assertEquals("角色包不完整，请重新导入", sentenceUiText(error))
        assertEquals(503, httpFailure(error).status)
        assertEquals("character pack is incomplete", httpFailure(error).message)
        assertEquals("角色包不完整 角色包缺少 vits_fp32.onnx", failureLog(error))
    }

    @Test
    fun chineseMessagesStayVisible() {
        val error = IllegalStateException("名字不能是空的")
        assertEquals("名字不能是空的", uiText(error))
        assertEquals("名字不能是空的", failureLog(error))
    }

    @Test
    fun referenceMismatchHasItsOwnSentence() {
        assertEquals(
            "参考音素和角色包里的原文对不上，请重新导入这个角色",
            uiText(RefMismatch()),
        )
    }
}
