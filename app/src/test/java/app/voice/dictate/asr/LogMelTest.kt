package app.voice.dictate.asr

import app.voice.dictate.audio.AudioCapture
import app.voice.dictate.audio.WavReader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * The Kotlin LogMel must match the web prototype's logmel.js, since both feed the same Nemotron encoder.
 * logmel_golden.json was produced by running the prototype's own logmel.js (Node) on jfk.wav, fed in the
 * same 1280-sample blocks.
 */
class LogMelTest {
    private val golden = Json.parseToJsonElement(
        javaClass.classLoader!!.getResource("logmel_golden.json")!!.readText(),
    ).jsonObject

    private fun frames(): List<FloatArray> {
        val samples = WavReader.decode(File("src/main/assets/samples/jfk.wav").readBytes())
        val mel = LogMel()
        val out = ArrayList<FloatArray>()
        var i = 0
        while (i < samples.size) {
            out += mel.accept(samples.copyOfRange(i, minOf(samples.size, i + AudioCapture.BLOCK)))
            i += AudioCapture.BLOCK
        }
        return out
    }

    @Test
    fun matchesPrototypeFrames() {
        val frames = frames()
        assertEquals(golden["count"]!!.jsonPrimitive.int, frames.size)
        var worst = 0.0
        for ((index, values) in golden["frames"]!!.jsonObject) {
            val expected = values.jsonArray.map { it.jsonPrimitive.double }
            val actual = frames[index.toInt()]
            for (m in expected.indices) worst = maxOf(worst, abs(expected[m] - actual[m]))
        }
        // Float32 vs the JS double intermediates: differences stay far below anything the model notices.
        assertTrue("max log-mel difference $worst", worst < 2e-3)
        val sum = frames.sumOf { f -> f.sumOf { it.toDouble() } }
        val expectedSum = golden["sum"]!!.jsonPrimitive.double
        assertTrue("sum $sum vs $expectedSum", abs(sum - expectedSum) / abs(expectedSum) < 1e-5)
    }

    @Test
    fun silenceIsTheSilentLogValue() {
        val f = LogMel().accept(FloatArray(SAMPLE_RATE / 2))
        assertTrue(f.isNotEmpty())
        assertTrue(f.all { frame -> frame.all { abs(it - SILENT_LOG) < 1e-4 } })
    }
}
