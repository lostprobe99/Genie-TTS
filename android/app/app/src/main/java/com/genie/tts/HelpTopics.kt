package com.genie.tts

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale

internal sealed class HelpPiece {
    data class Text(val value: String) : HelpPiece()
    data class Steps(val lines: List<Pair<String, String>>) : HelpPiece()
    data class Bullets(val lines: List<String>) : HelpPiece()
    data class Files(val names: List<String>) : HelpPiece()
    data class Tips(val lines: List<Pair<String, String>>) : HelpPiece()
    data object Copy : HelpPiece()
}

internal data class HelpQuestion(
    val title: String,
    val pieces: List<HelpPiece>,
    val startOpen: Boolean,
)

internal data class HelpTopic(
    val title: String,
    val badge: String?,
    val badgeInk: Int,
    val badgeFill: Int,
    val dot: Int,
    val tags: Set<String>,
    val questions: List<HelpQuestion>,
)

internal fun localTts(port: Int): String = "http://127.0.0.1:$port/tts"

internal fun helpCopyText(port: Int): String {
    return "POST ${localTts(port)}\n" +
        "{\"character_name\":\"feibi\",\"text\":\"要读的文字\",\"split_sentence\":true}"
}

internal fun helpTopics(port: Int): List<HelpTopic> {
    val endpoint = localTts(port)
    val bytes = "%,d".format(Locale.US, Prosody.MODEL_BYTES)
    return listOf(
        HelpTopic(
            title = "第三方阅读软件对接",
            badge = "高频推荐",
            badgeInk = 0xFF0F766E.toInt(),
            badgeFill = 0xFFF0FDFA.toInt(),
            dot = 0xFF0D9488.toInt(),
            tags = setOf("legado", "starter"),
            questions = listOf(
                HelpQuestion(
                    title = "如何与「阅读 3.0 (Legado)」或小说软件联动？",
                    startOpen = true,
                    pieces = listOf(
                        HelpPiece.Text("朗读接口是 POST /tts，请求体是 JSON。同一台手机上的软件用本机地址："),
                        HelpPiece.Steps(
                            listOf(
                                "启动服务：" to "在服务管理里点「开启」，状态变成「已开启」。",
                                "核对接口：" to "本机地址是 $endpoint。默认端口是 ${TtsPorts.DEFAULT}，改过就用服务管理页上显示的那一条。",
                                "填写请求：" to "character_name 用角色 id（例如 feibi），不用显示名称。text 是要读的中文。split_sentence 为 true 时按主屏的规则拆句。响应是 audio/wav，16 位单声道，正式角色采样率 32000。",
                            ),
                        ),
                        HelpPiece.Copy,
                    ),
                ),
                HelpQuestion(
                    title = "局域网内其他设备如何使用本机的朗读接口？",
                    startOpen = false,
                    pieces = listOf(
                        HelpPiece.Text("接口开着时会听本机所有网卡，没有单独的监听地址要改。两台设备连同一个 Wi-Fi，用服务管理页上的局域网地址："),
                        HelpPiece.Bullets(
                            listOf(
                                "本机软件用 $endpoint。",
                                "同一网络里的设备用页面列出的局域网地址，例如 http://192.168.1.8:$port/tts。没连上局域网时不显示这一行。",
                                "改过端口后，地址里的端口换成服务管理页上的数字。",
                            ),
                        ),
                    ),
                ),
            ),
        ),
        HelpTopic(
            title = "后台常驻与防杀进程",
            badge = "核心痛点",
            badgeInk = 0xFF047857.toInt(),
            badgeFill = 0xFFECFDF5.toInt(),
            dot = 0xFF059669.toInt(),
            tags = setOf("battery", "starter"),
            questions = listOf(
                HelpQuestion(
                    title = "息屏后朗读中断、听几分钟就暂停怎么办？",
                    startOpen = true,
                    pieces = listOf(
                        HelpPiece.Text("系统对后台算力限制较严。想让接口一直开着，可以做这三步："),
                        HelpPiece.Tips(
                            listOf(
                                "1. 电池优化设置为「无限制」" to "设置 → 应用管理 → Genie → 省电策略 → 选择「无限制 / 允许后台高耗电」。",
                                "2. 多任务卡片上锁" to "呼出多任务界面，长按或下拉 Genie 卡片，点小锁，避免一键清理。",
                                "3. 保持前台通知" to "开启后通知栏留着「朗读接口开着」。不要关掉这个应用的通知，否则系统可能会把接口收掉。",
                            ),
                        ),
                    ),
                ),
                HelpQuestion(
                    title = "通知栏常驻通知的作用是什么？可以隐藏吗？",
                    startOpen = false,
                    pieces = listOf(
                        HelpPiece.Text("通知栏的「朗读接口开着」用来把本机接口留在前台。通知上的「停止」会关掉接口。通知没打开时，服务管理页会写「通知没打开，系统可能会把接口收掉」。觉得打扰时，可以在系统通知里把它改成静默，不要关掉通知权限。从最近任务划掉应用时，部分手机会把接口一起停掉。"),
                    ),
                ),
            ),
        ),
        HelpTopic(
            title = "角色模型与声音合成",
            badge = null,
            badgeInk = 0,
            badgeFill = 0,
            dot = 0xFF0891B2.toInt(),
            tags = setOf("model", "starter"),
            questions = listOf(
                HelpQuestion(
                    title = "为什么导入角色后没有声音，或提示模型损坏？",
                    startOpen = false,
                    pieces = listOf(
                        HelpPiece.Text("角色从 zip 导入。压缩包里要有 manifest.json（含角色 id、参考原文和采样率），以及 models 目录下的四个模型："),
                        HelpPiece.Files(
                            listOf(
                                "t2s_encoder_fp32.onnx",
                                "t2s_first_stage_decoder_fp32.onnx",
                                "t2s_stage_decoder_fp32.onnx",
                                "vits_fp32.onnx",
                            ),
                        ),
                        HelpPiece.Text("正式角色采样率是 32000。参考音频不是必须的；包里如果有 wav、mp3 等，导入前可以试听。已发布的角色包是 feibi.zip 和 sanyueqi.zip。"),
                    ),
                ),
                HelpQuestion(
                    title = "如何修改发音人名字或试听参考音频？",
                    startOpen = false,
                    pieces = listOf(
                        HelpPiece.Text("在角色管理里长按这张卡。显示名称可以改，最多 20 个字；角色 ID 可以复制，不能改。包里如果留着参考音频，这里写出文件名和时长，可以试听。接口里的 character_name 用角色 id，不用这个显示名称。"),
                    ),
                ),
            ),
        ),
        HelpTopic(
            title = "韵律模型与服务端口",
            badge = null,
            badgeInk = 0,
            badgeFill = 0,
            dot = 0xFF115E59.toInt(),
            tags = setOf("service", "model"),
            questions = listOf(
                HelpQuestion(
                    title = "韵律模型是必需的吗？GitHub 下载失败怎么办？",
                    startOpen = false,
                    pieces = listOf(
                        HelpPiece.Text("不是必须的。没装也能出声，停顿和重音会弱一些。应用里的下载地址是 ${Release.PROSODY_URL}。连不上时，在电脑上下好这个 zip，再到韵律模型页从本地文件导入。也可以直接选 model_fp16.onnx。模型文件必须是 $bytes 字节。"),
                    ),
                ),
                HelpQuestion(
                    title = "端口 ${TtsPorts.DEFAULT} 被占用如何解决？",
                    startOpen = false,
                    pieces = listOf(
                        HelpPiece.Text("在服务管理的「网络与端口」里把默认端口 ${TtsPorts.DEFAULT} 改成 1 到 65535 之间别的空闲端口。接口开着时改端口，会先停掉原来的端口，再在新端口打开。阅读软件里的地址要改成新的 $endpoint。"),
                    ),
                ),
            ),
        ),
    )
}

internal fun fillHelpList(
    list: LinearLayout,
    port: Int,
    query: String,
    category: String,
    open: MutableMap<String, Boolean>,
    onToggle: (String) -> Unit,
    onCopy: () -> Unit,
): Boolean {
    val needle = query.trim()
    list.removeAllViews()
    var shown = false
    helpTopics(port).forEach { topic ->
        if (category != "all" && category !in topic.tags) return@forEach
        val questions = topic.questions.filter { question ->
            needle.isEmpty() || question.plain(port).contains(needle, ignoreCase = true)
        }
        if (questions.isEmpty()) return@forEach
        shown = true
        list.addView(topicHead(list, topic))
        questions.forEach { question ->
            val expanded = open.getOrPut(question.title) { question.startOpen }
            list.addView(questionCard(list, topic, question, expanded, port, onToggle, onCopy))
        }
    }
    return shown
}

private fun HelpQuestion.plain(port: Int): String {
    return buildString {
        append(title)
        pieces.forEach { piece ->
            when (piece) {
                is HelpPiece.Text -> append(piece.value)
                is HelpPiece.Steps -> piece.lines.forEach { append(it.first).append(it.second) }
                is HelpPiece.Bullets -> piece.lines.forEach { append(it) }
                is HelpPiece.Files -> piece.names.forEach { append(it) }
                is HelpPiece.Tips -> piece.lines.forEach { append(it.first).append(it.second) }
                HelpPiece.Copy -> append(localTts(port))
            }
        }
    }
}

private fun topicHead(list: LinearLayout, topic: HelpTopic): View {
    val row = LinearLayout(list.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(list, 4), dp(list, 8), dp(list, 4), dp(list, 6))
    }
    row.addView(TextView(list.context).apply {
        text = topic.title
        textSize = 12f
        setTextColor(0xFF334155.toInt())
        setTypeface(typeface, Typeface.BOLD)
    })
    if (topic.badge != null) {
        row.addView(
            TextView(list.context).apply {
                text = topic.badge
                textSize = 10f
                setTextColor(topic.badgeInk)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(list, 6), dp(list, 2), dp(list, 6), dp(list, 2))
                background = GradientDrawable().apply {
                    cornerRadius = dp(list, 4).toFloat()
                    setColor(topic.badgeFill)
                }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(list, 6) },
        )
    }
    return row
}

private fun questionCard(
    list: LinearLayout,
    topic: HelpTopic,
    question: HelpQuestion,
    expanded: Boolean,
    port: Int,
    onToggle: (String) -> Unit,
    onCopy: () -> Unit,
): View {
    val card = LinearLayout(list.context).apply {
        orientation = LinearLayout.VERTICAL
        background = list.context.getDrawable(R.drawable.bg_prosody_card)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(list, 8) }
    }
    val head = LinearLayout(list.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(list, 12), dp(list, 12), dp(list, 12), dp(list, 12))
        setOnClickListener { onToggle(question.title) }
    }
    head.addView(View(list.context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (expanded) topic.dot else 0xFF94A3B8.toInt())
        }
        layoutParams = LinearLayout.LayoutParams(dp(list, 6), dp(list, 6))
    })
    head.addView(
        TextView(list.context).apply {
            text = question.title
            textSize = 13f
            setTextColor(0xFF1E293B.toInt())
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        },
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(list, 8)
            marginEnd = dp(list, 8)
        },
    )
    head.addView(ImageView(list.context).apply {
        setImageResource(R.drawable.ic_chevron_down)
        imageTintList = android.content.res.ColorStateList.valueOf(0xFF94A3B8.toInt())
        rotation = if (expanded) 180f else 0f
        layoutParams = LinearLayout.LayoutParams(dp(list, 16), dp(list, 16))
    })
    card.addView(head)
    if (!expanded) return card
    val body = LinearLayout(list.context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(list, 12), 0, dp(list, 12), dp(list, 12))
    }
    question.pieces.forEach { piece ->
        when (piece) {
            is HelpPiece.Text -> body.addView(bodyText(list, piece.value))
            is HelpPiece.Steps -> body.addView(stepsBlock(list, piece.lines))
            is HelpPiece.Bullets -> body.addView(bulletsBlock(list, piece.lines))
            is HelpPiece.Files -> body.addView(filesBlock(list, piece.names))
            is HelpPiece.Tips -> body.addView(tipsBlock(list, piece.lines))
            HelpPiece.Copy -> body.addView(copyBlock(list, port, onCopy))
        }
    }
    card.addView(body)
    return card
}

private fun bodyText(list: LinearLayout, value: String): TextView {
    return TextView(list.context).apply {
        text = value
        textSize = 12f
        setTextColor(0xFF475569.toInt())
        setLineSpacing(dp(list, 2).toFloat(), 1f)
        setPadding(0, dp(list, 2), 0, dp(list, 6))
    }
}

private fun stepsBlock(list: LinearLayout, lines: List<Pair<String, String>>): View {
    val box = stack(list, 0xFFF8FAFC.toInt())
    lines.forEachIndexed { index, (label, detail) ->
        val row = LinearLayout(list.context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(list, 4), 0, dp(list, 4))
        }
        row.addView(TextView(list.context).apply {
            text = "${index + 1}"
            gravity = Gravity.CENTER
            textSize = 10f
            setTextColor(android.graphics.Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF0D9488.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(dp(list, 16), dp(list, 16)).apply {
                topMargin = dp(list, 1)
            }
        })
        val span = SpannableString("$label$detail")
        span.setSpan(StyleSpan(Typeface.BOLD), 0, label.length, 0)
        span.setSpan(ForegroundColorSpan(0xFF1E293B.toInt()), 0, label.length, 0)
        row.addView(
            TextView(list.context).apply {
                text = span
                textSize = 12f
                setTextColor(0xFF475569.toInt())
                setLineSpacing(dp(list, 2).toFloat(), 1f)
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(list, 8)
            },
        )
        box.addView(row)
    }
    return box
}

private fun bulletsBlock(list: LinearLayout, lines: List<String>): View {
    val box = LinearLayout(list.context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, dp(list, 4))
    }
    lines.forEach { line ->
        box.addView(TextView(list.context).apply {
            text = "• $line"
            textSize = 12f
            setTextColor(0xFF475569.toInt())
            setLineSpacing(dp(list, 2).toFloat(), 1f)
            setPadding(dp(list, 4), dp(list, 2), 0, dp(list, 2))
        })
    }
    return box
}

private fun filesBlock(list: LinearLayout, names: List<String>): View {
    val box = stack(list, 0xFFF8FAFC.toInt())
    names.forEach { name ->
        box.addView(TextView(list.context).apply {
            text = name
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFF0F766E.toInt())
            setPadding(0, dp(list, 2), 0, dp(list, 2))
        })
    }
    return box
}

private fun tipsBlock(list: LinearLayout, lines: List<Pair<String, String>>): View {
    val box = LinearLayout(list.context).apply { orientation = LinearLayout.VERTICAL }
    lines.forEach { (title, detail) ->
        val card = stack(list, 0xFFF8FAFC.toInt())
        card.addView(TextView(list.context).apply {
            text = title
            textSize = 12f
            setTextColor(0xFF1E293B.toInt())
            setTypeface(typeface, Typeface.BOLD)
        })
        card.addView(TextView(list.context).apply {
            text = detail
            textSize = 11f
            setTextColor(0xFF64748B.toInt())
            setPadding(0, dp(list, 2), 0, 0)
        })
        box.addView(card)
    }
    return box
}

private fun copyBlock(list: LinearLayout, port: Int, onCopy: () -> Unit): View {
    val row = LinearLayout(list.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(list, 10), dp(list, 8), dp(list, 8), dp(list, 8))
        background = GradientDrawable().apply {
            cornerRadius = dp(list, 12).toFloat()
            setColor(0xFFF0FDFA.toInt())
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(list, 4) }
    }
    row.addView(
        TextView(list.context).apply {
            text = localTts(port)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFF115E59.toInt())
            maxLines = 2
        },
        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
    )
    row.addView(TextView(list.context).apply {
        text = "复制配置"
        textSize = 11f
        setTextColor(android.graphics.Color.WHITE)
        setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER
        setPadding(dp(list, 10), dp(list, 6), dp(list, 10), dp(list, 6))
        background = GradientDrawable().apply {
            cornerRadius = dp(list, 8).toFloat()
            setColor(0xFF0D9488.toInt())
        }
        setOnClickListener { onCopy() }
    })
    return row
}

private fun stack(list: LinearLayout, fill: Int): LinearLayout {
    return LinearLayout(list.context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(list, 10), dp(list, 8), dp(list, 10), dp(list, 8))
        background = GradientDrawable().apply {
            cornerRadius = dp(list, 12).toFloat()
            setColor(fill)
        }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(list, 6) }
    }
}

private fun dp(list: LinearLayout, value: Int): Int {
    return (value * list.resources.displayMetrics.density).toInt().coerceAtLeast(if (value == 0) 0 else 1)
}
