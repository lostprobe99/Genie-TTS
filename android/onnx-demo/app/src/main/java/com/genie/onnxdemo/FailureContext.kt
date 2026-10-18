package com.genie.onnxdemo

internal data class RuntimeFacts(
    val versionName: String,
    val versionCode: Long,
    val androidRelease: String,
    val api: Int,
    val manufacturer: String,
    val model: String,
    val abi: String,
    val cores: Int,
    val maxMemoryMb: Long,
    val characterId: String?,
    val prosodyReady: Boolean,
)

internal fun formatFailureContext(facts: RuntimeFacts): String {
    val role = facts.characterId?.takeIf { it.isNotBlank() } ?: "未选"
    val prosody = if (facts.prosodyReady) "韵律已在" else "韵律未下"
    return "版本 ${facts.versionName}（${facts.versionCode}）\n" +
        "环境 Android ${facts.androidRelease}（API ${facts.api}），" +
        "${facts.manufacturer} ${facts.model}，${facts.abi}，核心 ${facts.cores}，内存上限 ${facts.maxMemoryMb} MB\n" +
        "角色 $role，$prosody"
}
