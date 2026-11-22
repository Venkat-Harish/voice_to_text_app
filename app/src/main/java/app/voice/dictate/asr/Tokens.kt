package app.voice.dictate.asr

// SentencePiece vocab files ("piece id" per line) → text. Port of the prototype's src/asr/tokens.js.

object Tokens {
    private val LANG_TAG = Regex("^<[a-z]{2,3}-[A-Z]{2}>$")
    private val SKIP = setOf("<unk>", "<blk>", "<pad>", "<s>", "</s>")
    private val SPACES = Regex("\\s+")

    fun parse(text: String): Array<String?> {
        val byId = HashMap<Int, String>()
        var maxId = -1
        for (raw in text.split('\n')) {
            val l = raw.removeSuffix("\r")
            if (l.isEmpty()) continue
            val i = l.lastIndexOf(' ')
            if (i < 0) continue
            val id = l.substring(i + 1).toIntOrNull() ?: continue
            byId[id] = l.substring(0, i)
            if (id > maxId) maxId = id
        }
        return Array(maxId + 1) { byId[it] }
    }

    /** Concatenate pieces; "▁" marks a word start. Language tags and control tokens are dropped. */
    fun piecesToText(pieces: Array<String?>, ids: List<Int>): String {
        val sb = StringBuilder()
        for (id in ids) {
            val p = pieces.getOrNull(id) ?: continue
            if (p in SKIP || LANG_TAG.matches(p)) continue
            sb.append(p)
        }
        return sb.toString().replace('▁', ' ').replace(SPACES, " ").trim()
    }

    fun argmax(data: FloatArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var best = 0
        var max = Float.NEGATIVE_INFINITY
        for (i in 0 until length) {
            val v = data[offset + i]
            if (v > max) { max = v; best = i }
        }
        return best
    }
}
