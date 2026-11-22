package app.voice.dictate.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.StringReader

class LexiconTest {
    @Test
    fun streamsStringsNullsAndHeteronyms() {
        val json = """
            {
              "hello": "həlˈO",
              "read": {"ADJ": "ɹˈɛd", "DEFAULT": "ɹˈid", "VBD": "ɹˈɛd"},
              "AA": {"DEFAULT": "ˈɑˌɑ", "NOUN": null},
              "lead": {"NOUN": "lˈɛd", "VERB": "lˈid"},
              "gone": null,
              "quote\"d": "kwˈOtᵻd",
              "café": "kæfˈA"
            }
        """.trimIndent()
        val map = HashMap<String, String>()
        Lexicon.parseInto(StringReader(json), map)
        assertEquals("həlˈO", map["hello"])
        assertEquals("ɹˈid", map["read"]) // DEFAULT wins
        assertEquals("ˈɑˌɑ", map["AA"])
        assertEquals("lˈɛd", map["lead"]) // no DEFAULT: the first variant
        assertTrue("gone" !in map)
        assertEquals("kwˈOtᵻd", map["quote\"d"])
        assertEquals("kæfˈA", map["café"])
    }

    @Test
    fun earlierFileWins() {
        val map = HashMap<String, String>()
        Lexicon.parseInto(StringReader("""{"tomato": "gold"}"""), map)
        Lexicon.parseInto(StringReader("""{"tomato": "silver", "potato": "silver"}"""), map)
        assertEquals("gold", map["tomato"])
        assertEquals("silver", map["potato"])
    }
}

class G2PTest {
    private val lexicon = Lexicon(
        mapOf(
            "hello" to "həlˈO", "world" to "wˈɜɹld", "the" to "ði", "cat" to "kˈæt", "dog" to "dˈɔɡ",
            "walk" to "wˈɔk", "want" to "wˈɑnt", "bus" to "bˈʌs", "run" to "ɹˈʌn", "make" to "mˈAk",
            "city" to "sˈɪɾi", "smart" to "smˈɑɹt", "watch" to "wˈɑʧ", "Monday" to "mˈʌndˌA", "whats" to "wˈʌts",
            "app" to "ˈæp", "four" to "fˈɔɹ", "twenty" to "twˈɛnti", "is" to "ɪz", "Anna" to "ˈænə",
            "U" to "jˈu", "S" to "ˈɛs", "A" to "ˈA", "quick" to "kwˈɪk",
        ),
    )
    private val g2p = G2P(lexicon, british = false)

    @Test
    fun keepsPunctuationAndSpacing() {
        assertEquals("həlˈO, wˈɜɹld!", g2p.phonemize("Hello, world!"))
        assertEquals("ði kˈæt (ði dˈɔɡ).", g2p.phonemize("The cat (the dog)."))
    }

    @Test
    fun looksUpCaseVariants() {
        assertEquals("həlˈO", g2p.word("HELLO"))
        assertEquals("mˈʌndˌA", g2p.word("monday"))
    }

    @Test
    fun inflectionsGetTheRightEnding() {
        assertEquals("kˈæts", g2p.word("cats"))
        assertEquals("dˈɔɡz", g2p.word("dogs"))
        assertEquals("bˈʌsᵻz", g2p.word("buses"))
        assertEquals("sˈɪɾiz", g2p.word("cities"))
        assertEquals("wˈɔkt", g2p.word("walked"))
        assertEquals("wˈɑntᵻd", g2p.word("wanted"))
        assertEquals("ɹˈʌnɪŋ", g2p.word("running"))
        assertEquals("mˈAkɪŋ", g2p.word("making"))
        assertEquals("ˈænəz", g2p.word("Anna's"))
    }

    @Test
    fun acronymsCamelCaseAndCompounds() {
        assertEquals("jˌuˌɛsˈA", g2p.word("USA"))
        assertEquals("wˈʌtsˌæp", g2p.word("WhatsApp"))
        assertEquals("smˈɑɹtwˌɑʧ", g2p.word("smartwatch"))
    }

    @Test
    fun numbersAreSpelledOut() {
        assertEquals("twˈɛnti fˈɔɹ", g2p.phonemize("24"))
    }

    @Test
    fun unknownWordsFallBackToRules() {
        val p = g2p.word("Zorbly")!!
        assertTrue(p, p.startsWith("z") && p.contains('ˈ'))
        assertTrue(LetterRules.convert("blimp", british = false).contains("ˈɪ"))
        assertEquals("ˈ", LetterRules.convert("make", british = false).filter { it == 'ˈ' })
    }

    /** Runs against the real misaki dictionaries when MISAKI_DIR points at them (not in CI). */
    @Test
    fun realDictionaryCoverage() {
        val dir = System.getenv("MISAKI_DIR")?.let(::File)
        assumeTrue(dir != null && File(dir, "us_gold.json").exists())
        val real = G2P(Lexicon.load(File(dir, "us_gold.json"), File(dir, "us_silver.json")), british = false)
        val text = "Voice reads your messages aloud. Dr. Patel's 3rd meeting about the smartwatch app is on Monday at 9:30, " +
            "and it'll cost $4.99 — or 20% less on WhatsApp! Kotlin developers love it."
        val words = Regex("[A-Za-z']+").findAll(TextPrep.normalize(text)).map { it.value }.toList()
        val lex = Lexicon.load(File(dir, "us_gold.json"), File(dir, "us_silver.json"))
        val inDictionary = words.count { w -> lex.get(w) != null || lex.get(w.lowercase()) != null }
        println("G2P: ${lex.size} dictionary words; ${words.size} words in the sample, $inDictionary straight from the dictionary")
        assertTrue(words.all { real.word(it)?.isNotEmpty() == true })
        for (chunk in TextPrep.chunks(text)) println("  $chunk\n  → ${real.phonemize(chunk)}\n  → kitten: ${EspeakStyle.fromMisaki(real.phonemize(chunk))}")
    }
}

class EspeakStyleTest {
    @Test
    fun convertsMisakiToEspeakIpa() {
        assertEquals("həlˈoʊ wˈɜːld", EspeakStyle.fromMisaki("həlˈO wˈɜɹld"))
        assertEquals("wˈɔːɾɚ", EspeakStyle.fromMisaki("wˈɔɾəɹ"))
        assertEquals("dʒˈʌdʒ tʃˈiːz", EspeakStyle.fromMisaki("ʤˈʌʤ ʧˈiz"))
        assertEquals("mˈaɪ bˈɔɪ nˈaʊ", EspeakStyle.fromMisaki("mˈI bˈY nˈW"))
        assertEquals("ðə ɹˈɛd", EspeakStyle.fromMisaki("ðᵊ ɹˈɛd"))
    }
}
