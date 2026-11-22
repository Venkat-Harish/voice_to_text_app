package app.voice.dictate.dictation

/** Cleans dictated text and fits it into the text around the cursor. Pure functions, unit-tested. */
object TextPostProcessor {
    private val FILLERS = Regex("""(?i)\b(?:um+|uh+|uhm+|erm+|hmm+)\b[,]?\s*""")
    private val SPACES = Regex("""[ \t]+""")
    private val SPACE_BEFORE_PUNCT = Regex("""[ \t]+([,.!?;:])""")
    private val NO_SPACE_BEFORE = setOf(',', '.', '!', '?', ';', ':', ')', ']', '}', '%', '\'', '’')
    private val SENTENCE_END = setOf('.', '!', '?', '\n')

    /** Removes filler words (um, uh, erm, hmm) and tidies the spacing left behind. */
    fun removeFillers(text: String): String {
        val cleaned = SPACE_BEFORE_PUNCT.replace(SPACES.replace(FILLERS.replace(text, ""), " "), "$1")
            .replace(",.", ".").trim().trimStart(',', ' ')
        // Re-capitalize if the text started with a filler ("Um, so…" → "So…").
        return if (text.firstOrNull()?.isUpperCase() == true) cleaned.replaceFirstChar { it.uppercaseChar() } else cleaned
    }

    /**
     * Fits [insert] between [before] and [after] (the field's text on each side of the cursor):
     * adds a space where words would otherwise run together and capitalizes at the start of a sentence.
     */
    fun fit(before: String, insert: String, after: String, smart: Boolean): String {
        var t = insert.trim()
        if (t.isEmpty() || !smart) return t
        val prev = before.lastOrNull()
        val startOfSentence = before.isBlank() || before.trimEnd(' ').lastOrNull() in SENTENCE_END
        if (startOfSentence) t = t.replaceFirstChar { it.uppercaseChar() }
        if (prev != null && !prev.isWhitespace() && t.first() !in NO_SPACE_BEFORE) t = " $t"
        val next = after.firstOrNull()
        if (next != null && (next.isLetterOrDigit())) t = "$t "
        return t
    }
}

/** Result of splicing text into a field: the field's new text and where the cursor goes. */
data class Splice(val text: String, val cursor: Int, val inserted: String)

object TextSplicer {
    /**
     * Inserts [insert] into [current] replacing the selection [selStart, selEnd] (or at the end when the
     * field reports no cursor), spacing it to fit the surrounding text.
     */
    fun splice(current: String, selStart: Int, selEnd: Int, insert: String, smart: Boolean = true): Splice {
        var s = if (selStart in 0..current.length) selStart else current.length
        var e = if (selEnd in 0..current.length) selEnd else s
        if (e < s) { val t = s; s = e; e = t }
        val before = current.substring(0, s)
        val after = current.substring(e)
        val fitted = TextPostProcessor.fit(before, insert, after, smart)
        return Splice(before + fitted + after, s + fitted.length, fitted)
    }
}
