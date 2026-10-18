package com.genie.onnxdemo

import org.junit.Assert.assertEquals
import org.junit.Test

class FailureContextTest {
    @Test
    fun failureContextIncludesVersionAndDevice() {
        val text = formatFailureContext(
            RuntimeFacts(
                versionName = "1.16",
                versionCode = 17,
                androidRelease = "14",
                api = 34,
                manufacturer = "Xiaomi",
                model = "23127PN0CC",
                abi = "arm64-v8a",
                cores = 8,
                maxMemoryMb = 512,
                characterId = "sanyueqi",
                prosodyReady = true,
            )
        )
        assertEquals(
            "版本 1.16（17）\n" +
                "环境 Android 14（API 34），Xiaomi 23127PN0CC，arm64-v8a，核心 8，内存上限 512 MB\n" +
                "角色 sanyueqi，韵律已在",
            text,
        )
    }

    @Test
    fun failureContextMarksMissingCharacterAndProsody() {
        val text = formatFailureContext(
            RuntimeFacts(
                versionName = "1.16",
                versionCode = 17,
                androidRelease = "15",
                api = 35,
                manufacturer = "Google",
                model = "Pixel",
                abi = "arm64-v8a",
                cores = 6,
                maxMemoryMb = 256,
                characterId = null,
                prosodyReady = false,
            )
        )
        assertEquals(
            "版本 1.16（17）\n" +
                "环境 Android 15（API 35），Google Pixel，arm64-v8a，核心 6，内存上限 256 MB\n" +
                "角色 未选，韵律未下",
            text,
        )
    }
}
