package app.voice.dictate.tts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * TextPrep must match the web prototype's src/tts/text.js. textprep_golden.json was produced by running the
 * prototype's own normalize() and chunks() (Node) on these samples.
 */
class TextPrepTest {
    private val golden = Json.parseToJsonElement(
        javaClass.classLoader!!.getResource("textprep_golden.json")!!.readText(),
    ).jsonArray.map { it.jsonObject }

    @Test
    fun normalizeMatchesPrototype() {
        for (case in golden) {
            val text = case["text"]!!.jsonPrimitive.content
            assertEquals("normalize($text)", case["normalized"]!!.jsonPrimitive.content, TextPrep.normalize(text))
        }
    }

    @Test
    fun chunksMatchPrototype() {
        for (case in golden) {
            val text = case["text"]!!.jsonPrimitive.content
            val expected = case["chunks"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertEquals("chunks($text)", expected, TextPrep.chunks(text))
        }
    }
}

class NumberWordsTest {
    @Test
    fun cardinals() {
        assertEquals("zero", NumberWords.cardinal(0))
        assertEquals("seven", NumberWords.cardinal(7))
        assertEquals("forty two", NumberWords.cardinal(42))
        assertEquals("one hundred five", NumberWords.cardinal(105))
        assertEquals("two thousand", NumberWords.cardinal(2000))
        assertEquals("one million two hundred thirty four thousand five hundred sixty seven", NumberWords.cardinal(1_234_567))
    }

    @Test
    fun ordinals() {
        assertEquals("first", NumberWords.ordinal(1))
        assertEquals("twenty second", NumberWords.ordinal(22))
        assertEquals("twelfth", NumberWords.ordinal(12))
        assertEquals("twentieth", NumberWords.ordinal(20))
        assertEquals("one hundredth", NumberWords.ordinal(100))
    }

    @Test
    fun expandsTextAfterNormalize() {
        // "19 95" and "20 24" are what normalize() leaves for years; eSpeak read them as two numbers.
        assertEquals("in nineteen ninety five", NumberWords.expand("in 19 95"))
        assertEquals("the twenty first of May", NumberWords.expand("the 21st of May"))
        assertEquals("fifty percent off", NumberWords.expand("50% off"))
        assertEquals("agent zero zero seven", NumberWords.expand("agent 007"))
        assertEquals("COVID-nineteen", NumberWords.expand("COVID-19"))
        assertEquals("it is minus five", NumberWords.expand("it is -5"))
        assertEquals("salt and pepper", NumberWords.expand("salt & pepper"))
    }
}
