package com.genie.tts.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppFailureTest {
    @Test
    fun eachKindIsItsOwnType() {
        val failures = listOf<AppFailure>(
            AppFailure.Cancelled(),
            AppFailure.Busy(),
            AppFailure.TimedOut(),
            AppFailure.ClientDisconnected(),
            AppFailure.CharacterNotFound("feibi"),
            AppFailure.PackIncomplete("缺少 vits_fp32.onnx"),
            AppFailure.ReferenceMismatch(),
            AppFailure.ModelLoadFailed("加载失败"),
        )
        assertEquals(failures.size, failures.map { it::class }.toSet().size)
        assertTrue(failures.all { it is Exception })
    }

    @Test
    fun notFoundAndIncompleteKeepWhatTheLogWillNeed() {
        assertEquals("feibi", AppFailure.CharacterNotFound("feibi").characterId)
        val incomplete = AppFailure.PackIncomplete("vits_fp32.onnx 太小，不是模型")
        assertEquals("vits_fp32.onnx 太小，不是模型", incomplete.detail)
        val cause = IllegalStateException("会话打不开")
        val failed = AppFailure.ModelLoadFailed("vits_fp32.onnx", cause)
        assertEquals("vits_fp32.onnx", failed.detail)
        assertEquals(cause, failed.cause)
    }
}
