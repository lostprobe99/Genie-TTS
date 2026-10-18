package com.genie.tts.g2p

/** Tone sandhi and erhua, following ToneSandhi.py and Erhua.py. */
class ToneSandhi(files: G2pFiles, private val jieba: Jieba, private val pinyin: Pinyin) {
    private val mustNeural = files.lines("must_neural.txt").toHashSet()
    private val mustNotNeural = files.lines("must_not_neural.txt").toHashSet()
    private val mustErhua = files.lines("must_erhua.txt").toHashSet()
    private val notErhua = files.lines("not_erhua.txt").toHashSet()

    fun preMerge(tokens: List<PosToken>): List<PosToken> {
        val merged = mergeContinuous(mergeReduplication(mergeYi(mergeBu(tokens))), second = false)
        return mergeEr(mergeContinuous(merged, second = true))
    }

    fun modify(word: String, pos: String, finals: MutableList<String>): List<String> {
        bu(word, finals)
        yi(word, finals)
        neural(word, pos, finals)
        three(word, finals)
        return finals
    }

    fun erhua(initials: List<String>, finals: List<String>, word: String, pos: String): Pair<List<String>, List<String>> {
        val nextFinals = finals.toMutableList()
        for (index in nextFinals.indices) {
            if (index == nextFinals.lastIndex && word.getOrNull(index) == '儿' && nextFinals[index] == "er1") {
                nextFinals[index] = "er2"
            }
        }
        if (word !in mustErhua && (word in notErhua || pos in setOf("a", "j", "nr"))) {
            return initials to nextFinals
        }
        if (nextFinals.size != word.length) return initials to nextFinals
        val newInitials = ArrayList<String>()
        val newFinals = ArrayList<String>()
        for (index in nextFinals.indices) {
            var phone = nextFinals[index]
            if (
                index == nextFinals.lastIndex &&
                word[index] == '儿' &&
                phone in setOf("er2", "er5") &&
                word.takeLast(2) !in notErhua &&
                newFinals.isNotEmpty()
            ) {
                phone = "er" + newFinals.last().last()
            }
            newInitials.add(initials[index])
            newFinals.add(phone)
        }
        return newInitials to newFinals
    }

    private fun mergeBu(tokens: List<PosToken>): List<PosToken> {
        val out = ArrayList<PosToken>()
        var last = ""
        for (token in tokens) {
            var word = token.word
            if (last == "不") word = last + word
            if (word != "不") out.add(PosToken(word, token.pos))
            last = word
        }
        if (last == "不") out.add(PosToken(last, "d"))
        return out
    }

    private fun mergeYi(tokens: List<PosToken>): List<PosToken> {
        val first = ArrayList<PosToken>()
        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]
            var merged = false
            if (index - 1 >= 0 && token.word == "一" && index + 1 < tokens.size) {
                val previous = first.lastOrNull() ?: tokens[index - 1]
                val next = tokens[index + 1]
                if (previous.word == next.word && previous.pos == "v" && next.pos == "v") {
                    first[first.lastIndex] = PosToken(previous.word + "一" + next.word, previous.pos)
                    index += 2
                    merged = true
                }
            }
            if (!merged) {
                first.add(PosToken(token.word, token.pos))
                index += 1
            }
        }
        val out = ArrayList<PosToken>()
        for (token in first) {
            if (out.isNotEmpty() && out.last().word == "一") out.last().word += token.word
            else out.add(PosToken(token.word, token.pos))
        }
        return out
    }

    private fun mergeReduplication(tokens: List<PosToken>): List<PosToken> {
        val out = ArrayList<PosToken>()
        for (token in tokens) {
            if (out.isNotEmpty() && token.word == out.last().word) out.last().word += token.word
            else out.add(PosToken(token.word, token.pos))
        }
        return out
    }

    private fun mergeContinuous(tokens: List<PosToken>, second: Boolean): List<PosToken> {
        val finals = tokens.map { pinyin.finalsTone3(it.word) }
        val out = ArrayList<PosToken>()
        val merged = BooleanArray(tokens.size)
        for (index in tokens.indices) {
            val token = tokens[index]
            val toneMatch = index > 0 && if (!second) {
                allToneThree(finals[index - 1]) && allToneThree(finals[index])
            } else {
                finals[index - 1].lastOrNull()?.lastOrNull() == '3' &&
                    finals[index].firstOrNull()?.lastOrNull() == '3'
            }
            val canMerge = toneMatch && !merged[index - 1] && !reduplication(tokens[index - 1].word) &&
                tokens[index - 1].word.length + token.word.length <= 3
            if (canMerge) {
                out.last().word += token.word
                merged[index] = true
            } else out.add(PosToken(token.word, token.pos))
        }
        return out
    }

    /** Python `str.isnumeric()`. Java does not treat Unihan numerals such as 百 as digits. */
    private fun Char.countsAsNumber(): Boolean = Character.isDigit(this) || this in NUMERIC_EXTRA

    private fun mergeEr(tokens: List<PosToken>): List<PosToken> {
        val out = ArrayList<PosToken>()
        for ((index, token) in tokens.withIndex()) {
            if (index > 0 && token.word == "儿" && tokens[index - 1].word != "#") out.last().word += token.word
            else out.add(PosToken(token.word, token.pos))
        }
        return out
    }

    private fun bu(word: String, finals: MutableList<String>) {
        if (word.length == 3 && word[1] == '不') finals[1] = tone(finals[1], '5')
        else {
            for (index in word.indices) {
                if (word[index] == '不' && index + 1 < word.length && finals[index + 1].lastOrNull() == '4') {
                    finals[index] = tone(finals[index], '2')
                }
            }
        }
    }

    private fun yi(word: String, finals: MutableList<String>) {
        if (word.contains("一") && word.all { it == '一' || it.countsAsNumber() }) return
        if (word.length == 3 && word[1] == '一' && word[0] == word[2]) finals[1] = tone(finals[1], '5')
        else if (word.startsWith("第一")) finals[1] = tone(finals[1], '1')
        else {
            for (index in word.indices) {
                if (word[index] == '一' && index + 1 < word.length && word[index + 1] !in PUNCTUATION) {
                    finals[index] = tone(finals[index], if (finals[index + 1].lastOrNull() == '4') '2' else '4')
                }
            }
        }
    }

    private fun neural(word: String, pos: String, finals: MutableList<String>) {
        if (finals.isEmpty() || word.isEmpty()) return
        for (index in word.indices) {
            if (
                index > 0 &&
                word[index] == word[index - 1] &&
                pos.first() in setOf('n', 'v', 'a') &&
                word !in mustNotNeural
            ) {
                finals[index] = tone(finals[index], '5')
            }
        }
        val ge = word.indexOf('个')
        when {
            word.last() in "吧呢哈啊呐噻嘛吖嗨呐哦哒额滴哩哟喽啰耶喔诶" -> finals[finals.lastIndex] = tone(finals.last(), '5')
            word.last() in "的地得" -> finals[finals.lastIndex] = tone(finals.last(), '5')
            word.length == 1 && word in "了着过" && pos in setOf("ul", "uz", "ug") -> {
                finals[finals.lastIndex] = tone(finals.last(), '5')
            }
            word.length > 1 && word.last() in "们子" && pos in setOf("r", "n") && word !in mustNotNeural -> {
                finals[finals.lastIndex] = tone(finals.last(), '5')
            }
            word.length > 1 && word.last() in "上下里" && pos in setOf("s", "l", "f") -> {
                finals[finals.lastIndex] = tone(finals.last(), '5')
            }
            word.length > 1 && word.last() in "来去" && word[word.length - 2] in "上下进出回过起开" -> {
                finals[finals.lastIndex] = tone(finals.last(), '5')
            }
            (ge >= 1 && (word[ge - 1].countsAsNumber() || word[ge - 1] in "几有两半多各整每做是")) || word == "个" -> {
                finals[ge] = tone(finals[ge], '5')
            }
            word in mustNeural || word.takeLast(2) in mustNeural -> finals[finals.lastIndex] = tone(finals.last(), '5')
        }
        val parts = splitWord(word)
        val lists = listOf(
            finals.take(parts[0].length).toMutableList(),
            finals.drop(parts[0].length).toMutableList(),
        )
        for (index in parts.indices) {
            val part = parts[index]
            if (part in mustNeural || part.takeLast(2) in mustNeural) {
                lists[index][lists[index].lastIndex] = tone(lists[index].last(), '5')
            }
        }
        finals.clear()
        lists.forEach { finals.addAll(it) }
    }

    private fun three(word: String, finals: MutableList<String>) {
        if (word.length == 2 && allToneThree(finals)) finals[0] = tone(finals[0], '2')
        else if (word.length == 3) {
            val parts = splitWord(word)
            if (allToneThree(finals)) {
                if (parts[0].length == 2) {
                    finals[0] = tone(finals[0], '2')
                    finals[1] = tone(finals[1], '2')
                } else if (parts[0].length == 1) finals[1] = tone(finals[1], '2')
            } else {
                val lists = listOf(
                    finals.take(parts[0].length).toMutableList(),
                    finals.drop(parts[0].length).toMutableList(),
                )
                for (index in lists.indices) {
                    val sub = lists[index]
                    if (allToneThree(sub) && sub.size == 2) sub[0] = tone(sub[0], '2')
                    else if (
                        index == 1 &&
                        !allToneThree(sub) &&
                        sub.first().last() == '3' &&
                        lists[0].last().last() == '3'
                    ) {
                        lists[0][lists[0].lastIndex] = tone(lists[0].last(), '2')
                    }
                }
                finals.clear()
                lists.forEach { finals.addAll(it) }
            }
        } else if (word.length == 4) {
            val lists = listOf(finals.take(2).toMutableList(), finals.drop(2).toMutableList())
            finals.clear()
            for (sub in lists) {
                if (allToneThree(sub)) sub[0] = tone(sub[0], '2')
                finals.addAll(sub)
            }
        }
    }

    private fun splitWord(word: String): List<String> {
        val found = jieba.cutForSearch(word).sortedBy { it.length }
        val first = found.first()
        return if (word.startsWith(first)) listOf(first, word.substring(first.length))
        else listOf(word.dropLast(first.length), first)
    }

    private fun allToneThree(finals: List<String>): Boolean = finals.all { it.isNotEmpty() && it.last() == '3' }

    private fun reduplication(word: String): Boolean = word.length == 2 && word[0] == word[1]

    private fun tone(final: String, number: Char): String = final.dropLast(1) + number

    companion object {
        private const val PUNCTUATION = "：，；。？！“”‘’':,;.?!"

        /** BMP characters where Python `isnumeric()` is true and `isdigit()` is false. */
        private val NUMERIC_EXTRA: Set<Char> = (
            "\u00BC\u00BD\u00BE\u09F4\u09F5\u09F6\u09F7\u09F8\u09F9\u0B72\u0B73\u0B74\u0B75\u0B76\u0B77" +
                "\u0BF0\u0BF1\u0BF2\u0C78\u0C79\u0C7A\u0C7B\u0C7C\u0C7D\u0C7E\u0D58\u0D59\u0D5A\u0D5B\u0D5C" +
                "\u0D5D\u0D5E\u0D70\u0D71\u0D72\u0D73\u0D74\u0D75\u0D76\u0D77\u0D78\u0F2A\u0F2B\u0F2C\u0F2D" +
                "\u0F2E\u0F2F\u0F30\u0F31\u0F32\u0F33\u1372\u1373\u1374\u1375\u1376\u1377\u1378\u1379\u137A" +
                "\u137B\u137C\u16EE\u16EF\u16F0\u17F0\u17F1\u17F2\u17F3\u17F4\u17F5\u17F6\u17F7\u17F8\u17F9" +
                "\u2150\u2151\u2152\u2153\u2154\u2155\u2156\u2157\u2158\u2159\u215A\u215B\u215C\u215D\u215E" +
                "\u215F\u2160\u2161\u2162\u2163\u2164\u2165\u2166\u2167\u2168\u2169\u216A\u216B\u216C\u216D" +
                "\u216E\u216F\u2170\u2171\u2172\u2173\u2174\u2175\u2176\u2177\u2178\u2179\u217A\u217B\u217C" +
                "\u217D\u217E\u217F\u2180\u2181\u2182\u2185\u2186\u2187\u2188\u2189\u2469\u246A\u246B\u246C" +
                "\u246D\u246E\u246F\u2470\u2471\u2472\u2473\u247D\u247E\u247F\u2480\u2481\u2482\u2483\u2484" +
                "\u2485\u2486\u2487\u2491\u2492\u2493\u2494\u2495\u2496\u2497\u2498\u2499\u249A\u249B\u24EB" +
                "\u24EC\u24ED\u24EE\u24EF\u24F0\u24F1\u24F2\u24F3\u24F4\u24FE\u277F\u2789\u2793\u2CFD\u3007" +
                "\u3021\u3022\u3023\u3024\u3025\u3026\u3027\u3028\u3029\u3038\u3039\u303A\u3192\u3193\u3194" +
                "\u3195\u3220\u3221\u3222\u3223\u3224\u3225\u3226\u3227\u3228\u3229\u3248\u3249\u324A\u324B" +
                "\u324C\u324D\u324E\u324F\u3251\u3252\u3253\u3254\u3255\u3256\u3257\u3258\u3259\u325A\u325B" +
                "\u325C\u325D\u325E\u325F\u3280\u3281\u3282\u3283\u3284\u3285\u3286\u3287\u3288\u3289\u32B1" +
                "\u32B2\u32B3\u32B4\u32B5\u32B6\u32B7\u32B8\u32B9\u32BA\u32BB\u32BC\u32BD\u32BE\u32BF\u3405" +
                "\u3483\u382A\u3B4D\u4E00\u4E03\u4E07\u4E09\u4E5D\u4E8C\u4E94\u4E96\u4EBF\u4EC0\u4EDF\u4EE8" +
                "\u4F0D\u4F70\u5104\u5146\u5169\u516B\u516D\u5341\u5343\u5344\u5345\u534C\u53C1\u53C2\u53C3" +
                "\u53C4\u56DB\u58F1\u58F9\u5E7A\u5EFE\u5EFF\u5F0C\u5F0D\u5F0E\u5F10\u62FE\u634C\u67D2\u6F06" +
                "\u7396\u767E\u8086\u842C\u8CAE\u8CB3\u8D30\u9621\u9646\u964C\u9678\u96F6\uA6E6\uA6E7\uA6E8" +
                "\uA6E9\uA6EA\uA6EB\uA6EC\uA6ED\uA6EE\uA6EF\uA830\uA831\uA832\uA833\uA834\uA835\uF96B\uF973" +
                "\uF978\uF9B2\uF9D1\uF9D3\uF9FD"
            ).toHashSet()
    }
}
