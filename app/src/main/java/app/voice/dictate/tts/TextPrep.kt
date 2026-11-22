package app.voice.dictate.tts

// Text preparation for the read-aloud voices. normalize/chunks/splitFirst are a line-by-line port of the
// prototype's src/tts/text.js (which follows kokoro-js, Apache-2.0): quotes, titles, years, times, money and
// decimals are spelled out; text is cut into sentence-sized chunks so the first sentence plays while the rest
// is still being made. eSpeak used to read bare numbers itself; here NumberWords does that.

object TextPrep {
    private fun splitNum(m: String): String {
        if (m.contains('.')) return m
        if (m.contains(':')) {
            val (h, min) = m.split(':').map { it.toInt() }
            if (min == 0) return "$h o'clock"
            return if (min < 10) "$h oh $min" else "$h $min"
        }
        val year = m.take(4).toInt()
        if (year < 1100 || year % 1000 < 10) return m
        val left = m.take(2)
        val right = m.substring(2, 4).toInt()
        val s = if (m.endsWith("s")) "s" else ""
        if (year % 1000 in 100..999) {
            if (right == 0) return "$left hundred$s"
            if (right < 10) return "$left oh $right$s"
        }
        return "$left $right$s"
    }

    private fun money(m: String): String {
        val bill = if (m[0] == '$') "dollar" else "pound"
        val amount = m.substring(1)
        if (amount.toDoubleOrNull() == null) return "$amount ${bill}s"
        if (!amount.contains('.')) return "$amount $bill${if (amount == "1") "" else "s"}"
        val (b, c) = amount.split('.')
        val d = c.padEnd(2, '0').toInt()
        val coins = if (m[0] == '$') (if (d == 1) "cent" else "cents") else if (d == 1) "penny" else "pence"
        return "$b $bill${if (b == "1") "" else "s"} and $d $coins"
    }

    private fun pointNum(m: String): String {
        val (a, b) = m.split('.')
        return "$a point ${b.toCharArray().joinToString(" ")}"
    }

    private val RULES: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        Regex("[‘’]") to { _ -> "'" },
        Regex("[“”«»]") to { _ -> "\"" },
        Regex("[^\\S \\n]") to { _ -> " " },
        Regex(" {2,}") to { _ -> " " },
        Regex("\\bD[Rr]\\.(?= [A-Z])") to { _ -> "Doctor" },
        Regex("\\b(?:Mr\\.|MR\\.(?= [A-Z]))") to { _ -> "Mister" },
        Regex("\\b(?:Ms\\.|MS\\.(?= [A-Z]))") to { _ -> "Miss" },
        Regex("\\b(?:Mrs\\.|MRS\\.(?= [A-Z]))") to { _ -> "Mrs" },
        Regex("\\betc\\.(?! [A-Z])", RegexOption.IGNORE_CASE) to { _ -> "etc" },
        Regex("\\b(y)eah?\\b", RegexOption.IGNORE_CASE) to { m -> "${m.groupValues[1]}e'a" },
        Regex("\\d*\\.\\d+|\\b\\d{4}s?\\b|(?<!:)\\b(?:[1-9]|1[0-2]):[0-5]\\d\\b(?!:)") to { m -> splitNum(m.value) },
        Regex("(?<=\\d),(?=\\d)") to { _ -> "" },
        Regex(
            "[$£]\\d+(?:\\.\\d+)?(?: hundred| thousand| (?:[bm]|tr)illion)*\\b|[$£]\\d+\\.\\d\\d?\\b",
            RegexOption.IGNORE_CASE,
        ) to { m -> money(m.value) },
        Regex("\\d*\\.\\d+") to { m -> pointNum(m.value) },
        Regex("(?<=\\d)-(?=\\d)") to { _ -> " to " },
        Regex("(?<=\\d)S") to { _ -> " S" },
        Regex("(?<=[BCDFGHJ-NP-TV-Z])'?s\\b") to { _ -> "'S" },
        Regex("(?<=X')S\\b") to { _ -> "s" },
        Regex("(?:[A-Za-z]\\.){2,} [a-z]") to { m -> m.value.replace('.', '-') },
        Regex("(?<=[A-Z])\\.(?=[A-Z])", RegexOption.IGNORE_CASE) to { _ -> "-" },
    )

    /** The prototype's normalize(): spells out titles, years, times, money and decimals. */
    fun normalize(text: String): String {
        var s = text
        for ((re, f) in RULES) s = re.replace(s, f)
        return s.trim()
    }

    private val END_PUNCT = Regex("[.!?…;:,]$")
    private fun endPunct(s: String) = if (END_PUNCT.containsMatchIn(s)) s else "$s."

    private val FIRST_CLAUSE = Regex("^.{16,}?[,;:—](?=\\s)")

    /** The first piece is cut at its first clause break when the sentence is long, so the first audio is ready sooner. */
    private fun splitFirst(out: List<String>, first: Int = 48): List<String> {
        val s = out.firstOrNull() ?: return out
        if (s.length <= first) return out
        val m = FIRST_CLAUSE.find(s) ?: return out
        if (s.length - m.value.length < 16) return out
        return listOf(m.value, s.substring(m.value.length).trim()) + out.drop(1)
    }

    private val PARAGRAPHS = Regex("\\n+")
    private val SENTENCE = Regex("[^.!?…]+(?:[.!?…]+[\"')\\]]*|$)")
    private val SPACES = Regex("\\s+")

    /** Sentence-sized pieces of at most [max] characters, each ending in punctuation. */
    fun chunks(text: String, max: Int = 300): List<String> = splitFirst(sentences(text, max))

    private fun sentences(text: String, max: Int): List<String> {
        val out = ArrayList<String>()
        for (para in normalize(text).split(PARAGRAPHS)) {
            for (match in SENTENCE.findAll(para)) {
                val s = match.value.trim()
                if (s.isEmpty()) continue
                if (s.length <= max) { out.add(endPunct(s)); continue }
                // A very long sentence: break it between words.
                var cur = ""
                for (w in s.split(SPACES)) {
                    if (cur.isNotEmpty() && cur.length + w.length + 1 > max) { out.add(endPunct(cur)); cur = w }
                    else cur = if (cur.isNotEmpty()) "$cur $w" else w
                }
                if (cur.isNotEmpty()) out.add(endPunct(cur))
            }
        }
        return out
    }
}

/** Spells out the numbers eSpeak would have read: cardinals, ordinals (1st, 22nd) and percentages. */
object NumberWords {
    private val ONES = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val TENS = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
    private val SCALES = listOf("" to 1L, "thousand" to 1_000L, "million" to 1_000_000L, "billion" to 1_000_000_000L, "trillion" to 1_000_000_000_000L)

    private fun under1000(n: Int): String {
        val parts = ArrayList<String>()
        if (n >= 100) parts += "${ONES[n / 100]} hundred"
        val r = n % 100
        if (r in 1..19) parts += ONES[r]
        else if (r >= 20) parts += if (r % 10 == 0) TENS[r / 10] else "${TENS[r / 10]} ${ONES[r % 10]}"
        return parts.joinToString(" ")
    }

    fun cardinal(n: Long): String {
        if (n == 0L) return "zero"
        if (n < 0) return "minus ${cardinal(-n)}"
        val parts = ArrayList<String>()
        var rest = n
        for ((name, size) in SCALES.reversed()) {
            if (rest >= size) {
                val count = (rest / size).toInt()
                rest %= size
                parts += if (name.isEmpty()) under1000(count) else "${under1000(count)} $name"
            }
        }
        return parts.joinToString(" ")
    }

    fun ordinal(n: Long): String {
        val words = cardinal(n).split(' ').toMutableList()
        val last = words.last()
        words[words.lastIndex] = when (last) {
            "one" -> "first"
            "two" -> "second"
            "three" -> "third"
            "five" -> "fifth"
            "eight" -> "eighth"
            "nine" -> "ninth"
            "twelve" -> "twelfth"
            else -> if (last.endsWith("y")) last.dropLast(1) + "ieth" else last + "th"
        }
        return words.joinToString(" ")
    }

    private val ORDINAL = Regex("\\b(\\d+)(?:st|nd|rd|th)\\b", RegexOption.IGNORE_CASE)
    private val PERCENT = Regex("(\\d+)\\s?%")
    private val NUMBER = Regex("(?<![\\p{L}\\d])-?\\d+")
    private val SYMBOLS = mapOf("&" to " and ", "@" to " at ", "+" to " plus ", "=" to " equals ", "%" to " percent ")

    /** Replaces digits with words; leading-zero and very long numbers are read digit by digit. */
    fun expand(text: String): String {
        var s = ORDINAL.replace(text) { m -> m.groupValues[1].toLongOrNull()?.let(::ordinal) ?: m.value }
        s = PERCENT.replace(s) { m -> "${m.groupValues[1]} percent" }
        s = NUMBER.replace(s) { m ->
            val v = m.value
            val digits = v.removePrefix("-")
            val words = if ((digits.length > 1 && digits.startsWith('0')) || digits.length > 15) {
                digits.map { ONES[it - '0'] }.joinToString(" ")
            } else cardinal(digits.toLong())
            if (v.startsWith('-')) "minus $words" else words
        }
        for ((k, v) in SYMBOLS) s = s.replace(k, v)
        return s.replace(Regex(" {2,}"), " ")
    }
}
