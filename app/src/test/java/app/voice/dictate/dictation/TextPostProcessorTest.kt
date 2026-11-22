package app.voice.dictate.dictation

import app.voice.dictate.asr.AsrEvent
import app.voice.dictate.asr.TextAccumulator
import org.junit.Assert.assertEquals
import org.junit.Test

class TextPostProcessorTest {
    @Test
    fun emptyFieldGetsCapitalizedText() {
        val s = TextSplicer.splice("", 0, 0, "hello there")
        assertEquals("Hello there", s.text)
        assertEquals(11, s.cursor)
    }

    @Test
    fun appendsWithASpaceAfterExistingWords() {
        val s = TextSplicer.splice("I said", 6, 6, "hello")
        assertEquals("I said hello", s.text)
        assertEquals(12, s.cursor)
    }

    @Test
    fun capitalizesAfterASentence() {
        assertEquals("Done. Next one", TextSplicer.splice("Done.", 5, 5, "next one").text)
        assertEquals("Done. Next one", TextSplicer.splice("Done. ", 6, 6, "next one").text)
    }

    @Test
    fun insertsInTheMiddleWithSpacesOnBothSides() {
        val s = TextSplicer.splice("Meet at noon", 4, 4, "me")
        assertEquals("Meet me at noon", s.text)
        assertEquals(7, s.cursor)
        // Cursor right before a word: a space goes after the inserted text too.
        assertEquals("Meet me at noon", TextSplicer.splice("Meet at noon", 5, 5, "me").text)
    }

    @Test
    fun replacesTheSelection() {
        val s = TextSplicer.splice("See you Monday", 8, 14, "Tuesday")
        assertEquals("See you Tuesday", s.text)
        assertEquals(15, s.cursor)
    }

    @Test
    fun missingCursorMeansEnd() {
        assertEquals("Hi there", TextSplicer.splice("Hi", -1, -1, "there").text)
    }

    @Test
    fun noSpaceBeforePunctuation() {
        assertEquals("Wait, what", TextSplicer.splice("Wait", 4, 4, ", what").text)
    }

    @Test
    fun smartOffKeepsTextAsSpoken() {
        assertEquals("abcdef", TextSplicer.splice("abc", 3, 3, "def", smart = false).text)
    }

    @Test
    fun removesFillerWords() {
        assertEquals("So I think we should go.", TextPostProcessor.removeFillers("Um, so I think uh we should go."))
        assertEquals("Hello there", TextPostProcessor.removeFillers("Hello umm there"))
        assertEquals("The drummer hummed", TextPostProcessor.removeFillers("The drummer hummed"))
    }

    @Test
    fun accumulatorJoinsCommitsAndPartial() {
        val acc = TextAccumulator()
        acc.on(AsrEvent.Partial("hello", stable = true, inline = false))
        acc.on(AsrEvent.Commit("Hello world.", newParagraph = true))
        acc.on(AsrEvent.Partial("How are", stable = false, inline = true))
        assertEquals("Hello world. How are", acc.text())
        acc.on(AsrEvent.Commit("How are you?", newParagraph = false))
        assertEquals("Hello world. How are you?", acc.text())
        acc.on(AsrEvent.Partial("", stable = false, inline = false))
        assertEquals("Hello world. How are you?", acc.text())
    }
}
