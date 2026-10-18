package com.genie.onnxdemo

import org.junit.Assert.assertEquals
import org.junit.Test

class TextSplitterTest {
    private val splitter = TextSplitter()

    @Test
    fun matchesDesktopSplits() {
        val cases = linkedMapOf(
            "今天天气不错，我们出去走走吧。" to listOf("今天天气不错，我们出去走走吧。"),
            "你怎么现在才来，不是说好了三点吗？太好了，我们明天就能出发了！" to listOf(
                "你怎么现在才来，不是说好了三点吗？",
                "太好了，我们明天就能出发了！",
            ),
            "好。" to listOf("好。"),
            "好。我们明天出发。" to listOf("好。我们明天出发。"),
            "把窗户关上，外面风很大，你先睡吧。快一点，车要开了！" to listOf(
                "把窗户关上，外面风很大，你先睡吧。",
                "快一点，车要开了！",
            ),
            "无论是盐引，还是大明宝钞，只要是朝廷发行的货币，在地方上就渐渐失去了信用。商人们拒绝收纳，百姓们也纷纷囤积实物。到了后来，连官府自己的税关都不要这些纸币，财政就此彻底败坏。" to listOf(
                "无论是盐引，还是大明宝钞，只要是朝廷发行的货币，",
                "在地方上就渐渐失去了信用。",
                "商人们拒绝收纳，百姓们也纷纷囤积实物。",
                "到了后来，连官府自己的税关都不要这些纸币，财政就此彻底败坏。",
            ),
            "第一句。\n第二句！" to listOf("第一句。", "第二句！"),
            "他说：“走吧。”然后离开了。" to listOf("他说：“走吧。”", "然后离开了。"),
            "一二三四五六七八九十一二三四五六七八九十，后面还有十个字要说完。" to listOf(
                "一二三四五六七八九十一二三四五六七八九十，",
                "后面还有十个字要说完。",
            ),
            "他说——然后离开了。" to listOf("他说——然后离开了。"),
            "一二三四五六七八九十一二三四五六七八九十——后面还有字。" to listOf(
                "一二三四五六七八九十一二三四五六七八九十——后面还有字。",
            ),
            "快一点，车要开了，别再磨蹭了！" to listOf("快一点，车要开了，别再磨蹭了！"),
            "我说了多少遍，这件事不能再拖了。" to listOf("我说了多少遍，这件事不能再拖了。"),
            "朝辞白帝彩云间，千里江陵一日还。" to listOf("朝辞白帝彩云间，千里江陵一日还。"),
            "远而望之，皎若太阳升朝霞。迫而察之，灼若芙蕖出渌波。" to listOf(
                "远而望之，皎若太阳升朝霞。",
                "迫而察之，灼若芙蕖出渌波。",
            ),
            "战争的伟力之最深厚的根源，存在于民众之中。" to listOf("战争的伟力之最深厚的根源，存在于民众之中。"),
            "" to emptyList(),
            "\n\n" to emptyList(),
            "   " to emptyList(),
            "Hi，你好。" to listOf("Hi，你好。"),
        )
        for ((input, expected) in cases) {
            assertEquals(input, expected, splitter.split(input))
        }
    }
}
