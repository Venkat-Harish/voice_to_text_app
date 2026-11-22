package app.voice.dictate.tts

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class VoiceEnginesTest {
    @Test
    fun kittenTokensFollowPythonWordSplit() {
        // re.findall(r"\w+|[^\w\s]") joined by spaces: punctuation becomes its own token.
        assertEquals("həlˈoʊ , wˈɜːld !", KittenEngine.kittenTokens("həlˈoʊ, wˈɜːld!"))
    }

    @Test
    fun kittenIdsMatchItsSymbolTable() {
        // Ids from the prototype's kitten.js table: $=0 ;=1 … space=16, h=50, ə=83, l=54, ˈ=156, o=57, ʊ=135.
        assertArrayEquals(longArrayOf(0, 50, 83, 54, 156, 57, 135, 16, 1, 10, 0), KittenEngine.kittenIds("həlˈoʊ ;"))
        assertEquals(15, KittenEngine.SYMBOLS['"']) // later duplicates win, as in Python's dict
        assertEquals(177, KittenEngine.SYMBOLS['ᵻ'])
    }

    @Test
    fun kokoroIdsAndStyleRow() {
        val vocab = mapOf('h' to 50, 'ə' to 83, 'l' to 54, 'ˈ' to 156, 'O' to 31, ' ' to 16)
        assertArrayEquals(longArrayOf(0, 50, 83, 54, 156, 31, 0), KokoroEngine.kokoroIds("həlˈO#", vocab))
        assertEquals(5, KokoroEngine.styleRow(7))
        assertEquals(0, KokoroEngine.styleRow(1))
        assertEquals(509, KokoroEngine.styleRow(2000))
    }

    @Test
    fun parsesNpyAndNpz() {
        val npy = npyBytes(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f), rows = 2, dim = 3)
        val parsed = Npy.parse(npy)
        assertEquals(2, parsed.rows)
        assertEquals(3, parsed.dim)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f), parsed.data, 0f)

        val zip = File.createTempFile("voices", ".npz").apply { deleteOnExit() }
        ZipOutputStream(FileOutputStream(zip)).use { z ->
            for (name in listOf("expr-voice-2-f", "expr-voice-2-m")) {
                z.putNextEntry(ZipEntry("$name.npy")); z.write(npy); z.closeEntry()
            }
        }
        val voices = Npy.readNpz(zip)
        assertEquals(setOf("expr-voice-2-f", "expr-voice-2-m"), voices.keys)
        assertEquals(3, voices.getValue("expr-voice-2-m").dim)
    }

    @Test
    fun trimSilenceKeepsLeadAndTail() {
        val rate = 100
        val samples = FloatArray(100).also { for (i in 40 until 60) it[i] = 0.5f }
        val out = TtsHost.trimSilence(samples, rate, lead = 0.05, tail = 0.1)
        assertEquals(5 + 20 + 10, out.size)
        assertEquals(0.5f, out[5])
    }

    /** A float32 .npy (format v1) as numpy writes it. */
    private fun npyBytes(data: FloatArray, rows: Int, dim: Int): ByteArray {
        var header = "{'descr': '<f4', 'fortran_order': False, 'shape': ($rows, $dim), }"
        while ((10 + header.length + 1) % 64 != 0) header += " "
        header += "\n"
        val buf = ByteBuffer.allocate(10 + header.length + data.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0x93.toByte()).put("NUMPY".toByteArray()).put(1).put(0).putShort(header.length.toShort()).put(header.toByteArray())
        for (v in data) buf.putFloat(v)
        return buf.array()
    }
}
