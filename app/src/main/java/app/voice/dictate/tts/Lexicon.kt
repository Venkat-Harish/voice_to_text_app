package app.voice.dictate.tts

import java.io.BufferedReader
import java.io.File
import java.io.Reader

/**
 * A pronunciation dictionary: word → misaki phonemes (Kokoro's own phoneme alphabet). Loaded from misaki's
 * gold and silver JSON files (Apache-2.0). Entries are either a string, null, or an object of part-of-speech
 * variants for heteronyms ("read": DEFAULT / VBD …); without a tagger the DEFAULT reading is used.
 */
class Lexicon(private val entries: Map<String, String>) {
    val size get() = entries.size

    fun get(word: String): String? = entries[word]

    companion object {
        /** Loads the files in order; an earlier file (gold) wins over a later one (silver). */
        fun load(vararg files: File): Lexicon {
            val map = HashMap<String, String>(220_000)
            for (f in files) f.bufferedReader(Charsets.UTF_8, 256 * 1024).use { parseInto(it, map) }
            return Lexicon(map)
        }

        /** Streams a flat JSON object straight into [out], without building a JSON tree (the files are ~3 MB each). */
        fun parseInto(reader: Reader, out: MutableMap<String, String>) {
            JsonStream(if (reader is BufferedReader) reader else BufferedReader(reader)).readObject { key, value ->
                if (value != null && key !in out) out[key] = value
            }
        }
    }
}

/** Just enough JSON for misaki's dictionaries: an object whose values are strings, null, or flat objects. */
private class JsonStream(private val r: BufferedReader) {
    private var peeked = -2

    private fun peek(): Int { if (peeked == -2) peeked = r.read(); return peeked }
    private fun next(): Int { val c = peek(); peeked = -2; return c }

    private fun skipWs() { while (peek().let { it == ' '.code || it == '\n'.code || it == '\r'.code || it == '\t'.code }) next() }

    private fun expect(c: Char) {
        skipWs()
        val got = next()
        if (got != c.code) throw IllegalStateException("Expected '$c' but found '${if (got < 0) "end" else got.toChar()}'")
    }

    private fun readString(): String {
        expect('"')
        val sb = StringBuilder()
        while (true) {
            val c = next()
            if (c < 0) throw IllegalStateException("Unterminated string")
            when (c) {
                '"'.code -> return sb.toString()
                '\\'.code -> when (val e = next()) {
                    'n'.code -> sb.append('\n')
                    't'.code -> sb.append('\t')
                    'r'.code -> sb.append('\r')
                    'b'.code -> sb.append('\b')
                    'f'.code -> sb.append('\u000c')
                    'u'.code -> {
                        var v = 0
                        repeat(4) { v = v * 16 + Character.digit(next(), 16) }
                        sb.append(v.toChar())
                    }
                    else -> sb.append(e.toChar())
                }
                else -> sb.append(c.toChar())
            }
        }
    }

    private fun readLiteral(word: String) {
        skipWs()
        for (ch in word) if (next() != ch.code) throw IllegalStateException("Bad literal")
    }

    /** A value: a string, null, or a {POS: phonemes} object (returns its DEFAULT, else its first string). */
    private fun readValue(): String? {
        skipWs()
        return when (peek()) {
            '"'.code -> readString()
            'n'.code -> { readLiteral("null"); null }
            '{'.code -> {
                var default: String? = null
                var first: String? = null
                readObject { k, v ->
                    if (k == "DEFAULT") default = v
                    if (first == null) first = v
                }
                default ?: first
            }
            else -> throw IllegalStateException("Unexpected value")
        }
    }

    fun readObject(onEntry: (String, String?) -> Unit) {
        expect('{')
        skipWs()
        if (peek() == '}'.code) { next(); return }
        while (true) {
            val key = readString()
            expect(':')
            onEntry(key, readValue())
            skipWs()
            when (next()) {
                ','.code -> continue
                '}'.code -> return
                else -> throw IllegalStateException("Expected ',' or '}'")
            }
        }
    }
}
