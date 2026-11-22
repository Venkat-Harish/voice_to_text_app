package app.voice.dictate.tts

/**
 * Text → phonemes without eSpeak. Words come from misaki's dictionaries (the phonemes Kokoro was trained on);
 * words that aren't there go through, in order: digits, possessive and inflection stems, acronyms, camelCase
 * and compound splits, and finally [LetterRules]. Punctuation is kept in place because the voice models use it
 * for pauses and intonation. The output is in misaki's alphabet (A I O Q W Y = diphthongs, ᵊ = reduced schwa).
 */
class G2P(private val lexicon: Lexicon, private val british: Boolean) {

    /** Phonemes for one prepared chunk of text (already normalized; numbers may still be digits). */
    fun phonemize(text: String): String {
        val out = StringBuilder()
        var space = false
        for (m in TOKEN.findAll(NumberWords.expand(text))) {
            val t = m.value
            when {
                t.isBlank() -> space = true
                t[0].isLetterOrDigit() || t[0] == '\'' -> {
                    val p = word(t) ?: continue
                    if (p.isEmpty()) continue
                    if (space && out.isNotEmpty()) out.append(' ')
                    out.append(p)
                    space = false
                }
                t in KEEP -> {
                    // Spacing around punctuation follows the original text.
                    if (space && out.isNotEmpty()) out.append(' ')
                    out.append(t)
                    space = false
                }
                else -> space = true // hyphens, slashes and other symbols separate words
            }
        }
        return out.toString().trim()
    }

    /** Phonemes for one word, or null if it has no letters or digits. */
    fun word(raw: String, depth: Int = 0): String? {
        val w = raw.trim('\'')
        if (w.isEmpty()) return null
        lookup(raw)?.let { return it }
        lookup(w)?.let { return it }
        if (depth > 3) return LetterRules.convert(w, british)

        // Letters and digits mixed ("MP3", "4G"): read the parts separately.
        if (w.any { it.isDigit() }) {
            return MIXED.findAll(w).mapNotNull { part ->
                val v = part.value
                if (v[0].isDigit()) NumberWords.cardinal(v.take(15).toLong()).split(' ').mapNotNull { word(it, depth + 1) }.joinToString(" ")
                else word(v, depth + 1)
            }.joinToString(" ")
        }

        // Possessive: "Anna's", "James'".
        if (w.length > 2 && (w.endsWith("'s") || w.endsWith("'S"))) {
            word(w.dropLast(2), depth + 1)?.let { return plural(it) }
        }

        // Acronyms spelled out: "USA", "GPU".
        if (w.length in 2..5 && w.all { it in 'A'..'Z' }) return spell(w)

        // camelCase / PascalCase: "WhatsApp", "iPhone".
        val camel = CAMEL.split(w).filter { it.isNotEmpty() }
        if (camel.size > 1) return camel.mapNotNull { word(it, depth + 1) }.joinToString("") { it }.let(::oneStress)

        stems(w.lowercase())?.let { return it }
        compound(w.lowercase())?.let { return it }
        return LetterRules.convert(w, british)
    }

    private fun lookup(w: String): String? =
        lexicon.get(w) ?: lexicon.get(w.lowercase()) ?: lexicon.get(w.lowercase().replaceFirstChar { it.uppercaseChar() })

    private fun spell(w: String): String {
        val letters = w.map { c -> lexicon.get(c.uppercase()) ?: LetterRules.LETTER_NAMES[c.uppercaseChar()] ?: "" }
        // Only the last letter keeps primary stress, as in "jˌuɛsˈA".
        return letters.mapIndexed { i, p -> if (i < letters.lastIndex) p.replace('ˈ', 'ˌ') else p }.joinToString("")
    }

    /** Keeps the first primary stress and demotes the rest (for words joined from parts). */
    private fun oneStress(p: String): String {
        val first = p.indexOf('ˈ')
        if (first < 0) return p
        return p.substring(0, first + 1) + p.substring(first + 1).replace('ˈ', 'ˌ')
    }

    private fun stems(w: String): String? {
        fun base(b: String) = if (b.length >= 2) lookup(b) else null
        if (w.length > 3 && w.endsWith("ies")) base(w.dropLast(3) + "y")?.let { return plural(it) }
        if (w.length > 3 && w.endsWith("es")) base(w.dropLast(2))?.let { return plural(it) }
        if (w.length > 2 && w.endsWith("s") && !w.endsWith("ss")) base(w.dropLast(1))?.let { return plural(it) }
        if (w.length > 4 && w.endsWith("ied")) base(w.dropLast(3) + "y")?.let { return past(it) }
        if (w.length > 3 && w.endsWith("ed")) {
            base(w.dropLast(2))?.let { return past(it) }
            base(w.dropLast(1))?.let { return past(it) }
            if (w.length > 4 && w[w.length - 3] == w[w.length - 4]) base(w.dropLast(3))?.let { return past(it) }
        }
        if (w.length > 4 && w.endsWith("ing")) {
            base(w.dropLast(3))?.let { return it + "ɪŋ" }
            base(w.dropLast(3) + "e")?.let { return it + "ɪŋ" }
            if (w.length > 5 && w[w.length - 4] == w[w.length - 5]) base(w.dropLast(4))?.let { return it + "ɪŋ" }
        }
        if (w.length > 4 && w.endsWith("ly")) base(w.dropLast(2))?.let { return it + "li" }
        return null
    }

    private fun compound(w: String): String? {
        if (w.length < 6) return null
        for (i in w.length - 3 downTo 3) {
            val a = lookup(w.substring(0, i)) ?: continue
            val b = lookup(w.substring(i)) ?: continue
            return oneStress(a + b)
        }
        return null
    }

    private fun lastSound(p: String) = p.trimEnd('ˈ', 'ˌ', 'ː').lastOrNull()

    fun plural(p: String): String = when (lastSound(p)) {
        in SIBILANTS -> p + if (british) "ɪz" else "ᵻz"
        in VOICELESS -> p + "s"
        else -> p + "z"
    }

    fun past(p: String): String = when (lastSound(p)) {
        't', 'd' -> p + if (british) "ɪd" else "ᵻd"
        in VOICELESS_PAST -> p + "t"
        else -> p + "d"
    }

    companion object {
        private val TOKEN = Regex("[\\p{L}\\p{N}']+|\\s+|[^\\p{L}\\p{N}\\s]")
        private val MIXED = Regex("\\p{L}+|\\d+")
        private val CAMEL = Regex("(?<=\\p{Ll})(?=\\p{Lu})")
        /** Punctuation both voices understand. */
        private val KEEP = setOf(";", ":", ",", ".", "!", "?", "—", "…", "\"", "(", ")", "“", "”")
        private val SIBILANTS = setOf('s', 'z', 'ʃ', 'ʒ', 'ʧ', 'ʤ')
        private val VOICELESS = setOf('p', 't', 'k', 'f', 'θ')
        private val VOICELESS_PAST = setOf('p', 'k', 'f', 'θ', 'ʃ', 'ʧ', 's')
    }
}

/**
 * Converts misaki phonemes to the eSpeak NG style (en-us) that Kitten TTS was trained on:
 * diphthong letters are expanded (A→eɪ, I→aɪ, O→oʊ, W→aʊ, Y→ɔɪ, Q→əʊ), affricates split (ʤ→dʒ, ʧ→tʃ),
 * r-coloured vowels and stressed long vowels written the way eSpeak writes them.
 */
object EspeakStyle {
    private val VOWELS = "AIOWYQaæɑɒɔəɛɜɪiuʊʌᵊᵻeo"
    private val R_SCHWA = Regex("əɹ(?![$VOWELS])")
    private val STRESSED_LONG = Regex("([ˈˌ])([iuɑɔ])(?!ː)")

    fun fromMisaki(p: String): String {
        var s = R_SCHWA.replace(p, "ɚ")
        s = s.replace("ɜɹ", "ɜː")
        s = STRESSED_LONG.replace(s) { m -> m.groupValues[1] + m.groupValues[2] + "ː" }
        val sb = StringBuilder()
        for (c in s) sb.append(
            when (c) {
                'A' -> "eɪ"
                'I' -> "aɪ"
                'O' -> "oʊ"
                'W' -> "aʊ"
                'Y' -> "ɔɪ"
                'Q' -> "əʊ"
                'ᵊ' -> "ə"
                'ʤ' -> "dʒ"
                'ʧ' -> "tʃ"
                else -> c.toString()
            },
        )
        return sb.toString()
    }
}

/**
 * Last-resort spelling-to-sound rules for words that are in no dictionary (rare names, new words).
 * Produces misaki-style phonemes with the stress on the first vowel. Approximate by design.
 */
object LetterRules {
    val LETTER_NAMES = mapOf(
        'A' to "ˈA", 'B' to "bˈi", 'C' to "sˈi", 'D' to "dˈi", 'E' to "ˈi", 'F' to "ˈɛf", 'G' to "ʤˈi",
        'H' to "ˈAʧ", 'I' to "ˈI", 'J' to "ʤˈA", 'K' to "kˈA", 'L' to "ˈɛl", 'M' to "ˈɛm", 'N' to "ˈɛn",
        'O' to "ˈO", 'P' to "pˈi", 'Q' to "kjˈu", 'R' to "ˈɑɹ", 'S' to "ˈɛs", 'T' to "tˈi", 'U' to "jˈu",
        'V' to "vˈi", 'W' to "dˈʌbᵊlju", 'X' to "ˈɛks", 'Y' to "wˈI", 'Z' to "zˈi",
    )

    // Longest patterns first. Each maps letters to misaki phonemes.
    private val PATTERNS: List<Pair<String, String>> = listOf(
        "tion" to "ʃən", "sion" to "ʒən", "cious" to "ʃəs", "tious" to "ʃəs", "ture" to "ʧəɹ", "eigh" to "A",
        "tch" to "ʧ", "sch" to "sk", "dge" to "ʤ", "igh" to "I", "ous" to "əs", "air" to "ɛɹ", "ear" to "ɪɹ",
        "eer" to "ɪɹ", "ch" to "ʧ", "sh" to "ʃ", "th" to "θ", "ph" to "f", "wh" to "w", "ck" to "k", "ng" to "ŋ",
        "nk" to "ŋk", "qu" to "kw", "gh" to "", "ee" to "i", "ea" to "i", "oo" to "u", "oa" to "O", "oe" to "O",
        "ai" to "A", "ay" to "A", "oi" to "Y", "oy" to "Y", "ou" to "W", "au" to "ɔ", "aw" to "ɔ", "ew" to "u",
        "ue" to "u", "ui" to "u", "ei" to "A", "ey" to "A", "ie" to "i", "ar" to "ɑɹ", "or" to "ɔɹ", "er" to "ɜɹ",
        "ir" to "ɜɹ", "ur" to "ɜɹ", "x" to "ks", "j" to "ʤ", "q" to "k", "r" to "ɹ", "w" to "w",
    )
    private const val VOWEL_LETTERS = "aeiouy"
    private val VOWEL_SOUNDS = "AIOWYQaæɑɒɔəɛɜɪiuʊʌᵊ"

    fun convert(word: String, british: Boolean): String {
        var w = word.lowercase().filter { it in 'a'..'z' }
        if (w.isEmpty()) return ""
        // Silent first letters.
        for ((pre, keep) in listOf("kn" to "n", "wr" to "r", "gn" to "n", "ps" to "s", "pn" to "n")) {
            if (w.startsWith(pre) && w.length > 3) { w = keep + w.substring(2); break }
        }
        val vowelsInWord = w.count { it in VOWEL_LETTERS }
        // A final "e" after a single consonant makes the vowel before it long ("make", "time").
        val magicE = w.length > 3 && w.endsWith("e") && w[w.length - 2] !in VOWEL_LETTERS &&
            w[w.length - 3] in VOWEL_LETTERS && vowelsInWord > 1
        val body = if (w.endsWith("e") && w.length > 2 && vowelsInWord > 1 && !w.endsWith("ee") && !w.endsWith("le")) w.dropLast(1) else w

        val out = StringBuilder()
        var i = 0
        while (i < body.length) {
            // Doubled consonants sound once.
            if (i > 0 && body[i] == body[i - 1] && body[i] !in VOWEL_LETTERS) { i++; continue }
            val rest = body.substring(i)
            val pat = PATTERNS.firstOrNull { (g, _) -> rest.startsWith(g) && !(g == "gh" && i == 0) }
            if (pat != null) {
                out.append(pat.second); i += pat.first.length; continue
            }
            val c = body[i]
            val next = body.getOrNull(i + 1)
            val long = magicE && i == body.length - 2
            out.append(
                when (c) {
                    'a' -> if (long) "A" else "æ"
                    'e' -> if (long) "i" else if (i == body.length - 1 && body.length <= 3) "i" else "ɛ"
                    'i' -> if (long) "I" else "ɪ"
                    'o' -> if (long || (i == body.length - 1)) "O" else "ɑ"
                    'u' -> if (long) "u" else "ʌ"
                    'y' -> when {
                        i == 0 -> "j"
                        vowelsInWord == 1 -> "I"
                        i == body.length - 1 -> "i"
                        else -> "ɪ"
                    }
                    'c' -> if (next in setOf('e', 'i', 'y')) "s" else "k"
                    'g' -> if (next in setOf('e', 'i', 'y') && i > 0) "ʤ" else "ɡ"
                    's' -> if (i > 0 && body[i - 1] in VOWEL_LETTERS && (next in VOWEL_LETTERS.toSet() || (magicE && i == body.length - 1))) "z" else "s"
                    'l' -> if (i == body.length - 1 && w.endsWith("le") && i > 0 && body[i - 1] !in VOWEL_LETTERS) "ᵊl" else "l"
                    else -> c.toString()
                },
            )
            i++
        }
        if (w.endsWith("le") && w.length > 3 && w[w.length - 3] !in VOWEL_LETTERS && !out.endsWith("ᵊl")) out.append("ᵊl")
        var p = stress(out.toString())
        if (british) p = p.replace("ɑɹ", "ɑː").replace("ɔɹ", "ɔː").replace("ɜɹ", "ɜː").replace("ɛɹ", "ɛː")
            .replace("ɪɹ", "ɪə").replace('æ', 'a').replace('O', 'Q').replace("əɹ", "ə")
        return p
    }

    /** Primary stress before the first vowel; later short vowels are reduced to schwa. */
    private fun stress(p: String): String {
        val first = p.indexOfFirst { it in VOWEL_SOUNDS }
        if (first < 0) return p
        val sb = StringBuilder(p.substring(0, first)).append('ˈ').append(p[first])
        for (k in first + 1 until p.length) {
            val c = p[k]
            sb.append(
                when {
                    c in "æʌɑɛ" && k > first + 1 -> 'ə'
                    else -> c
                },
            )
        }
        return sb.toString().let { s ->
            // An unstressed "er" at the end is a schwa ("water" → …təɹ).
            if (s.endsWith("ɜɹ") && s.indexOf('ˈ') < s.length - 3) s.dropLast(2) + "əɹ" else s
        }
    }
}
