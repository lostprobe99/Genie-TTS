package com.genie.tts.g2p

/** Verbalizes numbers, dates and phones the same way as Normalization/text_normlization.py. */
class TextNormalizer(files: G2pFiles) {
    private val traditional = HashMap<String, String>()

    init {
        for (line in files.lines("t2s.txt")) {
            val tab = line.indexOf('\t')
            traditional[line.substring(0, tab)] = line.substring(tab + 1)
        }
    }

    fun normalize(text: String): List<String> {
        var cleaned = text.replace(" ", "")
        cleaned = DROP_SPLIT.replace(cleaned, "")
        val marked = SPLIT.replace(cleaned) { it.groupValues[1] + "\n" }.trim()
        if (marked.isEmpty()) return emptyList()
        return marked.split(Regex("\n+")).map { normalizeSentence(it.trim()) }
    }

    private fun normalizeSentence(input: String): String {
        var sentence = buildString {
            for (ch in input) append(traditional[ch.toString()] ?: ch.toString())
        }
        sentence = halfWidth(sentence)
        sentence = DATE.sub(sentence) { match ->
            val year = match.groupValues[1]
            val month = match.groupValues[3]
            val day = match.groupValues[5]
            buildString {
                if (year.isNotEmpty()) append(verbalizeDigit(year)).append("年")
                if (month.isNotEmpty()) append(verbalizeCardinal(month)).append("月")
                if (day.isNotEmpty()) append(verbalizeCardinal(day)).append(match.groupValues[9])
            }
        }
        sentence = DATE2.sub(sentence) { match ->
            buildString {
                append(verbalizeDigit(match.groupValues[1])).append("年")
                append(verbalizeCardinal(match.groupValues[3])).append("月")
                append(verbalizeCardinal(match.groupValues[4])).append("日")
            }
        }
        sentence = TIME_RANGE.sub(sentence, ::verbalizeTime)
        sentence = TIME.sub(sentence, ::verbalizeTime)
        sentence = TO_RANGE.sub(sentence) { it.value.replace("~", "至") }
        sentence = TEMPERATURE.sub(sentence) { match ->
            val sign = if (match.groupValues[1].isNotEmpty()) "零下" else ""
            val unit = if (match.groupValues[3] == "摄氏度") "摄氏度" else "度"
            sign + num2str(match.groupValues[2]) + unit
        }
        sentence = replaceMeasure(sentence)
        while (ASMD.containsMatchIn(sentence)) {
            sentence = ASMD.sub(sentence) { match ->
                match.groupValues[1] + ASMD_MAP.getValue(match.groupValues[8]) + match.groupValues[9]
            }
        }
        sentence = POWER.sub(sentence) { match ->
            val digits = match.value.map { POWER_MAP.getValue(it.toString()) }.joinToString("")
            "的${digits}次方"
        }
        sentence = FRAC.sub(sentence) { match ->
            val sign = if (match.groupValues[1].isNotEmpty()) "负" else ""
            sign + num2str(match.groupValues[3]) + "分之" + num2str(match.groupValues[2])
        }
        sentence = PERCENT.sub(sentence) { match ->
            val sign = if (match.groupValues[1].isNotEmpty()) "负" else ""
            sign + "百分之" + num2str(match.groupValues[2])
        }
        sentence = MOBILE.sub(sentence) { phoneToStr(it.value, mobile = true) }
        sentence = TELEPHONE.sub(sentence) { phoneToStr(it.value, mobile = false) }
        sentence = NATIONAL.sub(sentence) { phoneToStr(it.value, mobile = false) }
        sentence = RANGE.sub(sentence) { match ->
            num2str(match.groupValues[1]) + "到" + num2str(match.groupValues[6])
        }
        sentence = INTEGER.sub(sentence) { match ->
            "负" + num2str(match.groupValues[2])
        }
        sentence = VERSION.sub(sentence) { match ->
            match.groupValues[1].map { if (it == '.') "点" else num2str(it.toString()) }.joinToString("")
        }
        sentence = DECIMAL.sub(sentence, ::verbalizeNumber)
        sentence = QUANTIFIER.sub(sentence) { match ->
            var extra = match.groupValues[2]
            if (extra == "+") extra = "多"
            var number = num2str(match.groupValues[1])
            if (number == "二") number = "两"
            number + extra + match.groupValues[3]
        }
        sentence = DEFAULT_NUM.sub(sentence) { verbalizeDigit(it.value, altOne = true) }
        sentence = NUMBER.sub(sentence, ::verbalizeNumber)
        return postReplace(sentence)
    }

    private fun verbalizeTime(match: MatchResult): String {
        val hour = match.groupValues[1]
        val minute = match.groupValues[2]
        val second = match.groupValues[4]
        val ranged = match.groupValues.size > 6 && match.groupValues.drop(5).any { it.isNotEmpty() }
        val out = StringBuilder()
        out.append(num2str(hour)).append("点")
        if (minute.trimStart('0').isNotEmpty()) {
            out.append(if (minute.toInt() == 30) "半" else timeNum(minute) + "分")
        }
        if (second.isNotEmpty() && second.trimStart('0').isNotEmpty()) out.append(timeNum(second)).append("秒")
        if (ranged) {
            val hour2 = match.groupValues[6]
            val minute2 = match.groupValues[7]
            val second2 = match.groupValues[9]
            out.append("至").append(num2str(hour2)).append("点")
            if (minute2.trimStart('0').isNotEmpty()) {
                out.append(if (minute.toInt() == 30) "半" else timeNum(minute2) + "分")
            }
            if (second2.isNotEmpty() && second2.trimStart('0').isNotEmpty()) out.append(timeNum(second2)).append("秒")
        }
        return out.toString()
    }

    private fun verbalizeNumber(match: MatchResult): String {
        val pure = match.groupValues[5]
        if (pure.isNotEmpty()) return num2str(pure)
        val sign = if (match.groupValues[1].isNotEmpty()) "负" else ""
        return sign + num2str(match.groupValues[2])
    }

    private fun timeNum(value: String): String {
        val body = num2str(value.trimStart('0'))
        return if (value.startsWith("0")) "零$body" else body
    }

    private fun phoneToStr(phone: String, mobile: Boolean): String {
        val parts = if (mobile) phone.trimStart('+').split(' ') else phone.split('-')
        return parts.joinToString("，") { verbalizeDigit(it, altOne = true) }
    }

    private fun replaceMeasure(sentence: String): String {
        var value = sentence
        for ((notation, spoken) in MEASURES) {
            if (notation in value) value = value.replace(notation, spoken)
        }
        return value
    }

    private fun postReplace(input: String): String {
        var sentence = input
        val words = listOf(
            "/" to "每", "①" to "一", "②" to "二", "③" to "三", "④" to "四", "⑤" to "五",
            "⑥" to "六", "⑦" to "七", "⑧" to "八", "⑨" to "九", "⑩" to "十",
            "α" to "阿尔法", "β" to "贝塔", "γ" to "伽玛", "Γ" to "伽玛",
            "δ" to "德尔塔", "Δ" to "德尔塔", "ε" to "艾普西龙", "ζ" to "捷塔",
            "η" to "依塔", "θ" to "西塔", "Θ" to "西塔", "ι" to "艾欧塔", "κ" to "喀帕",
            "λ" to "拉姆达", "Λ" to "拉姆达", "μ" to "缪", "ν" to "拗", "ξ" to "克西",
            "Ξ" to "克西", "ο" to "欧米克伦", "π" to "派", "Π" to "派", "ρ" to "肉",
            "ς" to "西格玛", "Σ" to "西格玛", "σ" to "西格玛", "τ" to "套",
            "υ" to "宇普西龙", "φ" to "服艾", "Φ" to "服艾", "χ" to "器",
            "ψ" to "普赛", "Ψ" to "普赛", "ω" to "欧米伽", "Ω" to "欧米伽",
            "+" to "加", "-" to "减", "×" to "乘", "÷" to "除", "=" to "等",
        )
        for ((from, to) in words) sentence = sentence.replace(from, to)
        return DROP_POST.replace(sentence, "")
    }

    private fun halfWidth(text: String): String = buildString {
        for (ch in text) {
            val code = ch.code
            if ((code in 0xFF21..0xFF3A) || (code in 0xFF41..0xFF5A) || (code in 0xFF10..0xFF19)) {
                append((code - 65248).toChar())
            } else append(ch)
        }
    }

    companion object {
        private val DIGITS = mapOf(
            "0" to "零", "1" to "一", "2" to "二", "3" to "三", "4" to "四",
            "5" to "五", "6" to "六", "7" to "七", "8" to "八", "9" to "九",
        )
        private val UNITS = linkedMapOf(1 to "十", 2 to "百", 3 to "千", 4 to "万", 8 to "亿")
        private val MEASURES = listOf(
            "cm2" to "平方厘米", "cm²" to "平方厘米", "cm3" to "立方厘米", "cm³" to "立方厘米",
            "cm" to "厘米", "db" to "分贝", "ds" to "毫秒", "kg" to "千克", "km" to "千米",
            "m2" to "平方米", "m²" to "平方米", "m³" to "立方米", "m3" to "立方米",
            "ml" to "毫升", "m" to "米", "mm" to "毫米", "s" to "秒",
        )
        private val ASMD_MAP = mapOf("+" to "加", "-" to "减", "×" to "乘", "÷" to "除", "=" to "等于")
        private val POWER_MAP = mapOf(
            "⁰" to "0", "¹" to "1", "²" to "2", "³" to "3", "⁴" to "4", "⁵" to "5",
            "⁶" to "6", "⁷" to "7", "⁸" to "8", "⁹" to "9", "ˣ" to "x", "ʸ" to "y", "ⁿ" to "n",
        )
        private val DROP_SPLIT = Regex("""[——《》【】<>{}()（）#&@“”^_|\\]""")
        private val DROP_POST = Regex("""[-——《》【】<=>{}()（）#&@“”^_|\\]""")
        private val SPLIT = Regex("""([：、，；。？！,;?!][”’]?)""")
        private val DATE = Regex("""(\d{4}|\d{2})年((0?[1-9]|1[0-2])月)?(((0?[1-9])|((1|2)[0-9])|30|31)([日号]))?""")
        private val DATE2 = Regex("""(\d{4})([- /.])(0[1-9]|1[012])\2(0[1-9]|[12][0-9]|3[01])""")
        private val TIME = Regex("""([0-1]?[0-9]|2[0-3]):([0-5][0-9])(:([0-5][0-9]))?""")
        private val TIME_RANGE = Regex(
            """([0-1]?[0-9]|2[0-3]):([0-5][0-9])(:([0-5][0-9]))?(~|-)([0-1]?[0-9]|2[0-3]):([0-5][0-9])(:([0-5][0-9]))?""",
        )
        private val TO_RANGE = Regex(
            """((-?)((\d+)(\.\d+)?)|(\.(\d+)))(%|°C|℃|度|摄氏度|cm2|cm²|cm3|cm³|cm|db|ds|kg|km|m2|m²|m³|m3|ml|m|mm|s)~((-?)((\d+)(\.\d+)?)|(\.(\d+)))(%|°C|℃|度|摄氏度|cm2|cm²|cm3|cm³|cm|db|ds|kg|km|m2|m²|m³|m3|ml|m|mm|s)""",
        )
        private val TEMPERATURE = Regex("""(-?)(\d+(\.\d+)?)(°C|℃|度|摄氏度)""")
        private val ASMD = Regex(
            """((-?)((\d+)(\.\d+)?[⁰¹²³⁴⁵⁶⁷⁸⁹ˣʸⁿ]*)|(\.\d+[⁰¹²³⁴⁵⁶⁷⁸⁹ˣʸⁿ]*)|([A-Za-z][⁰¹²³⁴⁵⁶⁷⁸⁹ˣʸⁿ]*))([+\-×÷=])((-?)((\d+)(\.\d+)?[⁰¹²³⁴⁵⁶⁷⁸⁹ˣʸⁿ]*)|(\.\d+[⁰¹²³⁴⁵⁶⁷⁸⁹ˣʸⁿ]*)|([A-Za-z][⁰¹²³⁴⁵⁶⁷⁸⁹ˣʸⁿ]*))""",
        )
        private val POWER = Regex("""[⁰¹²³⁴⁵⁶⁷⁸⁹ˣʸⁿ]+""")
        private val FRAC = Regex("""(-?)(\d+)/(\d+)""")
        private val PERCENT = Regex("""(-?)(\d+(\.\d+)?)%""")
        private val MOBILE = Regex("""(?<!\d)((\+?86 ?)?1([38]\d|5[0-35-9]|7[678]|9[89])\d{8})(?!\d)""")
        private val TELEPHONE = Regex("""(?<!\d)((0(10|2[1-3]|[3-9]\d{2})-?)?[1-9]\d{6,7})(?!\d)""")
        private val NATIONAL = Regex("""(400)(-)?\d{3}(-)?\d{4}""")
        private val RANGE = Regex(
            """(?<![\d+\-×÷=])((-?)((\d+)(\.\d+)?))[-~]((-?)((\d+)(\.\d+)?))(?![\d+\-×÷=])""",
        )
        private val INTEGER = Regex("""(-)(\d+)""")
        private val VERSION = Regex("""((\d+)(\.\d+)(\.\d+)?(\.\d+)+)""")
        private val DECIMAL = Regex("""(-?)((\d+)(\.\d+))|(\.(\d+))""")
        private val QUANTIFIER = Regex(
            """(\d+)([多余几+])?(处|台|架|枚|趟|幅|平|方|堵|间|床|株|批|项|例|列|篇|栋|注|亩|封|艘|把|目|套|段|人|所|朵|匹|张|座|回|场|尾|条|个|首|阙|阵|网|炮|顶|丘|棵|只|支|袭|辆|挑|担|颗|壳|窠|曲|墙|群|腔|砣|座|客|贯|扎|捆|刀|令|打|手|罗|坡|山|岭|江|溪|钟|队|单|双|对|出|口|头|脚|板|跳|枝|件|贴|针|线|管|名|位|身|堂|课|本|页|家|户|层|丝|毫|厘|分|钱|两|斤|担|铢|石|钧|锱|忽|(千|毫|微)克|毫|厘|(公)分|分|寸|尺|丈|里|寻|常|铺|程|(千|分|厘|毫|微)米|米|撮|勺|合|升|斗|石|盘|碗|碟|叠|桶|笼|盆|盒|杯|钟|斛|锅|簋|篮|盘|桶|罐|瓶|壶|卮|盏|箩|箱|煲|啖|袋|钵|年|月|日|季|刻|时|周|天|秒|分|小时|旬|纪|岁|世|更|夜|春|夏|秋|冬|代|伏|辈|丸|泡|粒|颗|幢|堆|条|根|支|道|面|片|张|颗|块|元|(亿|千万|百万|万|千|百)|(亿|千万|百万|万|千|百|美|)元|(亿|千万|百万|万|千|百|十|)吨|(亿|千万|百万|万|千|百|)块|角|毛|分)""",
        )
        private val DEFAULT_NUM = Regex("""\d{3}\d*""")
        private val NUMBER = Regex("""(-?)((\d+)(\.\d+)?)|(\.(\d+))""")

        private fun Regex.sub(input: String, transform: (MatchResult) -> String): String = replace(input, transform)

        private fun verbalizeDigit(value: String, altOne: Boolean = false): String {
            val spoken = value.map { DIGITS.getValue(it.toString()) }.joinToString("")
            return if (altOne) spoken.replace("一", "幺") else spoken
        }

        private fun verbalizeCardinal(value: String): String {
            if (value.isEmpty()) return ""
            val stripped = value.trimStart('0')
            if (stripped.isEmpty()) return "零"
            val symbols = getValue(stripped).toMutableList()
            if (symbols.size >= 2 && symbols[0] == "一" && symbols[1] == "十") {
                symbols.removeAt(0)
            }
            return symbols.joinToString("")
        }

        private fun getValue(value: String, useZero: Boolean = true): List<String> {
            val stripped = value.trimStart('0')
            if (stripped.isEmpty()) return emptyList()
            if (stripped.length == 1) {
                val digit = DIGITS.getValue(stripped)
                return if (useZero && stripped.length < value.length) listOf("零", digit) else listOf(digit)
            }
            val largest = UNITS.keys.reversed().first { it < stripped.length }
            return getValue(value.dropLast(largest)) + listOf(UNITS.getValue(largest)) + getValue(value.takeLast(largest))
        }

        private fun num2str(value: String): String {
            val pieces = value.split('.')
            val integer = pieces[0]
            var decimal = if (pieces.size == 2) pieces[1] else ""
            var result = verbalizeCardinal(integer)
            decimal = if (decimal.endsWith("0")) decimal.trimEnd('0') + "0" else decimal.trimEnd('0')
            if (decimal.isNotEmpty()) {
                if (result.isEmpty()) result = "零"
                result += "点" + verbalizeDigit(decimal)
            }
            return result
        }
    }
}
