package app.voice.dictate.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import app.voice.dictate.asr.Ort
import app.voice.dictate.asr.Voice
import app.voice.dictate.asr.floats
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.zip.ZipFile

/** A StyleTTS 2 voice model: text → 24 kHz audio. Runs on TtsHost's thread only. */
interface VoiceEngine : AutoCloseable {
    val sampleRate: Int
    fun generate(text: String, voice: Voice, speed: Float): FloatArray
}

/** Lexicons per accent, loaded on first use (each is ~200k words). */
class Lexicons(private val dir: File) {
    private var us: G2P? = null
    private var gb: G2P? = null

    fun forAccent(accent: String): G2P = if (accent == "UK") {
        gb ?: G2P(Lexicon.load(File(dir, "lexicon/gb_gold.json"), File(dir, "lexicon/gb_silver.json")), british = true).also { gb = it }
    } else {
        us ?: G2P(Lexicon.load(File(dir, "lexicon/us_gold.json"), File(dir, "lexicon/us_silver.json")), british = false).also { us = it }
    }
}

private fun runStyleTts(session: OrtSession, ids: LongArray, style: FloatArray, speed: Float): FloatArray {
    val idsT = Ort.int64(ids, 1, ids.size.toLong())
    val styleT = OnnxTensor.createTensor(Ort.env, FloatBuffer.wrap(style), longArrayOf(1, style.size.toLong()))
    val speedT = OnnxTensor.createTensor(Ort.env, FloatBuffer.wrap(floatArrayOf(speed)), longArrayOf(1))
    try {
        return session.run(mapOf("input_ids" to idsT, "style" to styleT, "speed" to speedT)).use { it[0].floats() }
    } finally {
        idsT.close(); styleT.close(); speedT.close()
    }
}

/**
 * Kitten TTS Nano v0.8 (KittenML, Apache-2.0), a 15M StyleTTS 2 model. Port of the prototype's
 * src/tts/kitten.js, which follows KittenTTS's onnx_model.py: eSpeak-style phonemes → word/punctuation tokens
 * joined by spaces → symbol ids wrapped as [0, …, 10, 0]; the style row is picked by the text's length.
 */
class KittenEngine private constructor(
    private val session: OrtSession,
    private val voices: Map<String, Npy>,
    private val lexicons: Lexicons,
) : VoiceEngine {
    override val sampleRate = 24000

    override fun generate(text: String, voice: Voice, speed: Float): FloatArray {
        val v = voices[voice.id] ?: error("Kitten has no voice \"${voice.id}\"")
        val ps = kittenTokens(EspeakStyle.fromMisaki(lexicons.forAccent("US").phonemize(text)))
        val ids = kittenIds(ps)
        val row = minOf(text.codePointCount(0, text.length), v.rows - 1)
        val style = v.data.copyOfRange(row * v.dim, row * v.dim + v.dim)
        val all = runStyleTts(session, ids, style, speed * voice.prior)
        // KittenTTS trims the last 5000 samples (trailing noise); skip that on very short clips.
        return if (all.size > 24000) all.copyOf(all.size - 5000) else all
    }

    override fun close() = session.close()

    companion object {
        // The symbol table exactly as KittenTTS builds it (later duplicates win, as in its Python dict).
        private const val PAD = "$"
        private const val PUNCT = ";:,.!?¡¿—…\"«»\"\" "
        private const val LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        private const val IPA = "ɑɐɒæɓʙβɔɕçɗɖðʤəɘɚɛɜɝɞɟʄɡɠɢʛɦɧħɥʜɨɪʝɭɬɫɮʟɱɯɰŋɳɲɴøɵɸθœɶʘɹɺɾɻʀʁɽʂʃʈʧʉʊʋⱱʌɣɤʍχʎʏʑʐʒʔʡʕʢǀǁǂǃˈˌːˑʼʴʰʱʲʷˠˤ˞↓↑→↗↘'̩'ᵻ"
        val SYMBOLS: Map<Char, Int> = HashMap<Char, Int>().apply {
            (PAD + PUNCT + LETTERS + IPA).forEachIndexed { i, c -> put(c, i) }
        }

        // Python's re.findall(r"\w+|[^\w\s]"): letters (incl. ˈ ː), digits and _, or one other non-space character.
        private val WORDS = Regex("[\\p{L}\\p{N}_]+|[^\\p{L}\\p{N}_\\s]")

        fun kittenTokens(phonemes: String): String = WORDS.findAll(phonemes).joinToString(" ") { it.value }

        fun kittenIds(tokens: String): LongArray {
            val ids = ArrayList<Long>(tokens.length + 3)
            ids.add(0)
            for (c in tokens) SYMBOLS[c]?.let { ids.add(it.toLong()) }
            ids.add(10); ids.add(0)
            return ids.toLongArray()
        }

        fun create(dir: File, stage: (String) -> Unit): KittenEngine {
            stage("Reading the voice model…")
            val voices = Npy.readNpz(File(dir, "voices.npz"))
            val lexicons = Lexicons(dir)
            stage("Loading the dictionary…")
            lexicons.forAccent("US")
            stage("Preparing the voice…")
            val session = Ort.session(File(dir, "onnx/model.onnx"), Ort.bigThreads)
            return KittenEngine(session, voices, lexicons)
        }
    }
}

/**
 * Kokoro-82M (hexgrad, Apache-2.0), following the prototype's src/tts/kokoro.js (itself from kokoro-js).
 * Inputs: input_ids [1, n] (phoneme ids wrapped in pad 0), style [1, 256] (the voice's row for this length),
 * speed [1]. Misaki phonemes are Kokoro's own alphabet, so no eSpeak fix-ups are needed.
 */
class KokoroEngine private constructor(
    private val session: OrtSession,
    private val vocab: Map<Char, Int>,
    private val dir: File,
    private val lexicons: Lexicons,
) : VoiceEngine {
    override val sampleRate = 24000
    private val styles = HashMap<String, FloatArray>()

    private fun style(voice: Voice): FloatArray = styles.getOrPut(voice.id) {
        val bytes = File(dir, "voices/${voice.id}.bin").readBytes()
        val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        FloatArray(fb.remaining()).also { fb.get(it) }
    }

    override fun generate(text: String, voice: Voice, speed: Float): FloatArray {
        val ps = lexicons.forAccent(voice.accent).phonemize(text)
        val ids = kokoroIds(ps, vocab)
        val all = style(voice)
        val row = styleRow(ids.size)
        return runStyleTts(session, ids, all.copyOfRange(row * STYLE_DIM, row * STYLE_DIM + STYLE_DIM), speed)
    }

    override fun close() = session.close()

    companion object {
        const val STYLE_DIM = 256
        private const val MAX_TOKENS = 510

        /** The style row for a sequence of [idCount] ids (including the two pads). */
        fun styleRow(idCount: Int) = minOf(maxOf(idCount - 2, 0), 509)

        fun kokoroIds(phonemes: String, vocab: Map<Char, Int>): LongArray {
            val ids = ArrayList<Long>(phonemes.length + 2)
            ids.add(0)
            for (c in phonemes) {
                val id = vocab[c] ?: continue
                if (ids.size <= MAX_TOKENS) ids.add(id.toLong())
            }
            ids.add(0)
            return ids.toLongArray()
        }

        /** tokenizer.json maps each phoneme character to an id; anything else is dropped. */
        fun readVocab(file: File): Map<Char, Int> {
            val vocab = Json.parseToJsonElement(file.readText()).jsonObject["model"]!!.jsonObject["vocab"]!!.jsonObject
            return vocab.entries.filter { it.key.length == 1 }.associate { it.key[0] to it.value.jsonPrimitive.int }
        }

        fun create(dir: File, stage: (String) -> Unit): KokoroEngine {
            stage("Reading the voice model…")
            val vocab = readVocab(File(dir, "tokenizer.json"))
            val lexicons = Lexicons(dir)
            stage("Loading the dictionary…")
            lexicons.forAccent("US")
            stage("Preparing the voice…")
            val session = Ort.session(File(dir, "onnx/model_quantized.onnx"), Ort.bigThreads)
            return KokoroEngine(session, vocab, dir, lexicons)
        }
    }
}

/** A float32 .npy array (as stored in Kitten's voices.npz): [rows, dim]. */
class Npy(val data: FloatArray, val rows: Int, val dim: Int) {
    companion object {
        private val DESCR = Regex("'descr'\\s*:\\s*'([^']+)'")
        private val SHAPE = Regex("'shape'\\s*:\\s*\\(([^)]*)\\)")

        fun parse(bytes: ByteArray): Npy {
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val v1 = bytes[6].toInt() == 1
            val headerLen = if (v1) bb.getShort(8).toInt() and 0xFFFF else bb.getInt(8)
            val start = if (v1) 10 else 12
            val header = String(bytes, start, headerLen, Charsets.ISO_8859_1)
            val descr = DESCR.find(header)?.groupValues?.get(1)
            val shape = SHAPE.find(header)?.groupValues?.get(1).orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }
            val body = ByteBuffer.wrap(bytes, start + headerLen, bytes.size - start - headerLen).order(ByteOrder.LITTLE_ENDIAN)
            val data = when (descr) {
                "<f4" -> FloatArray(body.remaining() / 4) { body.getFloat() }
                "<f8" -> FloatArray(body.remaining() / 8) { body.getDouble().toFloat() }
                else -> error("Unsupported voice data type $descr")
            }
            val rows = if (shape.size > 1) shape[0] else 1
            val dim = shape.lastOrNull() ?: data.size
            return Npy(data, rows, dim)
        }

        /** voices.npz is a zip of .npy files, one per voice. */
        fun readNpz(file: File): Map<String, Npy> = ZipFile(file).use { zip ->
            zip.entries().asSequence().filter { it.name.endsWith(".npy") }.associate { e ->
                e.name.removeSuffix(".npy").substringAfterLast('/') to parse(zip.getInputStream(e).use { it.readBytes() })
            }
        }
    }
}
