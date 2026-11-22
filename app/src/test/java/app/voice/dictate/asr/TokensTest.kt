package app.voice.dictate.asr

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class TokensTest {
    private val pieces = Tokens.parse("<unk> 0\n▁hello 1\n▁wor 2\nld 3\n, 4\n<en-US> 5\n▁there 6\n<blk> 7\n")

    @Test
    fun parsesPieceIdLines() {
        assertEquals(8, pieces.size)
        assertEquals("▁wor", pieces[2])
        assertEquals("<blk>", pieces[7])
    }

    @Test
    fun joinsWordPiecesAndDropsControlTokens() {
        assertEquals("hello world, there", Tokens.piecesToText(pieces, listOf(5, 1, 2, 3, 4, 7, 6, 0)))
    }

    @Test
    fun argmaxHonoursOffset() {
        val data = floatArrayOf(9f, 1f, 3f, 2f)
        assertEquals(0, Tokens.argmax(data))
        assertEquals(1, Tokens.argmax(data, 1, 3))
    }

    @Test
    fun decodesSentencePieceByteFallback() {
        val json = """
            {"added_tokens":[{"id":0,"content":"<unk>","special":true},{"id":1,"content":"<s>","special":true},{"id":2,"content":"</s>","special":true}],
             "decoder":{"type":"Sequence","decoders":[{"type":"Replace"},{"type":"ByteFallback"},{"type":"Fuse"}]},
             "model":{"type":"BPE","vocab":{"<unk>":0,"<s>":1,"</s>":2,"▁Ask":3,"▁not":4,".":5,"<0xC3>":6,"<0xA9>":7,"▁caf":8}}}
        """.trimIndent()
        val file = File.createTempFile("tok", ".json").apply { writeText(json); deleteOnExit() }
        val tok = MoonshineTokenizer.load(file)
        assertEquals("Ask not. café", tok.decode(listOf(1, 3, 4, 5, 8, 6, 7, 2)))
    }

    @Test
    fun decodesByteLevel() {
        val json = """
            {"added_tokens":[{"id":0,"content":"<|endoftext|>","special":true}],
             "decoder":{"type":"ByteLevel"},
             "model":{"type":"BPE","vocab":{"<|endoftext|>":0,"Hello":1,"Ġworld":2,"!":3}}}
        """.trimIndent()
        val file = File.createTempFile("tok", ".json").apply { writeText(json); deleteOnExit() }
        assertEquals("Hello world!", MoonshineTokenizer.load(file).decode(listOf(1, 2, 3, 0)))
    }
}
