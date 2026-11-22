package app.voice.dictate.asr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.ceil
import kotlin.math.min

// Moonshine Base (Useful Sensors, MIT): the small "Lite" option. In the web prototype this ran through
// Transformers.js; here it is a direct ONNX Runtime port of the same files (onnx-community/moonshine-base-ONNX):
// the encoder reads raw 16 kHz audio, then the merged decoder runs a greedy loop with its key/value cache.

class MoonshineEngine private constructor(
    private val enc: OrtSession,
    private val dec: OrtSession,
    private val tokenizer: MoonshineTokenizer,
    private val startId: Long,
    private val eosId: Int,
    private val layers: Int,
    private val heads: Long,
    private val headDim: Long,
) : ClipEngine {

    override val backend = "cpu"
    private val decInputs = dec.inputNames

    override fun transcribe(audio: FloatArray): String {
        if (audio.size < 1600) return ""
        val n = audio.size.toLong()
        val input = Ort.floats(audio, 1, n)
        val mask = if ("attention_mask" in enc.inputNames) Ort.int64(LongArray(audio.size) { 1 }, 1, n) else null
        val encOut = try {
            enc.run(buildMap { put("input_values", input); if (mask != null) put("attention_mask", mask) })
        } finally { input.close(); mask?.close() }

        val tokens = ArrayList<Int>()
        val owned = ArrayList<AutoCloseable>()
        var first: OrtSession.Result? = null
        var prev: OrtSession.Result? = null
        try {
            val hidden = encOut["last_hidden_state", 0] as OnnxTensor
            val encFrames = hidden.info.shape[1]
            // About 6.5 tokens per second of speech is plenty for English.
            val maxLen = min(194, ceil(audio.size / SAMPLE_RATE.toDouble() * 6.5).toInt() + 4)
            val empty = Ort.zeros(1, heads, 0, headDim).also { owned.add(it) }
            val encMask = if ("encoder_attention_mask" in decInputs) Ort.int64(LongArray(encFrames.toInt()) { 1 }, 1, encFrames).also { owned.add(it) } else null
            val noCache = Ort.bool(false).also { owned.add(it) }
            val useCache = Ort.bool(true).also { owned.add(it) }

            var pastDecoder: Map<String, OnnxValue> = emptyMap()
            var pastEncoder: Map<String, OnnxValue> = emptyMap()
            var token = startId
            for (step in 0 until maxLen) {
                val ids = Ort.int64(longArrayOf(token), 1, 1)
                val feeds = HashMap<String, OnnxTensor>()
                feeds["input_ids"] = ids
                feeds["encoder_hidden_states"] = hidden
                if (encMask != null) feeds["encoder_attention_mask"] = encMask
                if ("use_cache_branch" in decInputs) feeds["use_cache_branch"] = if (step == 0) noCache else useCache
                for (i in 0 until layers) {
                    for (kind in KV) {
                        val d = "past_key_values.$i.decoder.$kind"
                        val e = "past_key_values.$i.encoder.$kind"
                        feeds[d] = if (step == 0) empty else pastDecoder.getValue(d) as OnnxTensor
                        feeds[e] = if (step == 0) empty else pastEncoder.getValue(e) as OnnxTensor
                    }
                }
                val out = try { dec.run(feeds) } finally { ids.close() }
                // The previous step's cache has been consumed (the first result also holds the encoder cache).
                if (prev != null && prev !== first) prev.close()
                prev = out
                if (step == 0) first = out

                val logits = out["logits", 0]
                val shape = logits.shape()
                val vocab = shape.last().toInt()
                val data = logits.floats()
                val next = Tokens.argmax(data, data.size - vocab, vocab)

                pastDecoder = buildMap {
                    for (i in 0 until layers) for (kind in KV) {
                        put("past_key_values.$i.decoder.$kind", out.get("present.$i.decoder.$kind").get())
                    }
                }
                if (step == 0) pastEncoder = buildMap {
                    for (i in 0 until layers) for (kind in KV) {
                        put("past_key_values.$i.encoder.$kind", out.get("present.$i.encoder.$kind").get())
                    }
                }
                if (next == eosId) break
                tokens.add(next)
                token = next.toLong()
            }
        } finally {
            if (prev != null && prev !== first) prev.close()
            first?.close()
            owned.forEach { it.close() }
            encOut.close()
        }
        return tokenizer.decode(tokens)
    }

    override fun close() { enc.close(); dec.close() }

    companion object {
        private val KV = listOf("key", "value")

        fun create(dir: File, stage: (String) -> Unit): MoonshineEngine {
            stage("Reading model from storage…")
            val json = Json { ignoreUnknownKeys = true }
            val config = json.parseToJsonElement(File(dir, "config.json").readText()).jsonObject
            val tokenizer = MoonshineTokenizer.load(File(dir, "tokenizer.json"))
            stage("Preparing…")
            val enc = Ort.session(File(dir, "onnx/encoder_model_quantized.onnx"), Ort.bigThreads)
            val dec = Ort.session(File(dir, "onnx/decoder_model_merged_quantized.onnx"), Ort.bigThreads)

            val layers = dec.inputNames.count { Regex("""past_key_values\.\d+\.decoder\.key""").matches(it) }
            val kvShape = (dec.inputInfo["past_key_values.0.decoder.key"]?.info as? TensorInfo)?.shape
            val cfgHeads = config["decoder_num_key_value_heads"]?.jsonPrimitive?.intOrNull ?: 8
            val cfgHidden = config["hidden_size"]?.jsonPrimitive?.intOrNull ?: 416
            val cfgAttnHeads = config["decoder_num_attention_heads"]?.jsonPrimitive?.intOrNull ?: cfgHeads
            val heads = kvShape?.getOrNull(1)?.takeIf { it > 0 } ?: cfgHeads.toLong()
            val headDim = kvShape?.getOrNull(3)?.takeIf { it > 0 } ?: (cfgHidden / cfgAttnHeads).toLong()
            val startId = (config["decoder_start_token_id"]?.jsonPrimitive?.intOrNull ?: 1).toLong()
            val eosId = config["eos_token_id"]?.jsonPrimitive?.intOrNull ?: 2

            val engine = MoonshineEngine(enc, dec, tokenizer, startId, eosId, layers, heads, headDim)
            stage("Warming up…")
            engine.transcribe(FloatArray(SAMPLE_RATE))
            return engine
        }
    }
}

/** Decodes Moonshine token ids using tokenizer.json (SentencePiece-style byte fallback, or GPT-2 ByteLevel). */
class MoonshineTokenizer(
    private val vocab: Array<String?>,
    private val special: Set<Int>,
    private val byteLevel: Boolean,
) {
    fun decode(ids: List<Int>): String {
        val bytes = ByteArrayOutputStream()
        for (id in ids) {
            if (id in special) continue
            val piece = vocab.getOrNull(id) ?: continue
            if (byteLevel) {
                for (ch in piece) bytes.write(BYTE_DECODER[ch] ?: continue)
            } else {
                val m = BYTE_TOKEN.matchEntire(piece)
                if (m != null) bytes.write(m.groupValues[1].toInt(16))
                else bytes.write(piece.replace('▁', ' ').toByteArray(Charsets.UTF_8))
            }
        }
        return bytes.toString("UTF-8").replace(Regex("\\s+"), " ").trim()
    }

    companion object {
        private val BYTE_TOKEN = Regex("^<0x([0-9A-Fa-f]{2})>$")

        /** GPT-2's bytes_to_unicode table, reversed: printable stand-in character → byte. */
        private val BYTE_DECODER: Map<Char, Int> = run {
            val bs = ArrayList<Int>()
            bs += ('!'.code..'~'.code); bs += ('¡'.code..'¬'.code); bs += ('®'.code..'ÿ'.code)
            val cs = ArrayList(bs)
            var n = 0
            for (b in 0 until 256) if (b !in bs) { bs += b; cs += 256 + n; n++ }
            bs.indices.associate { cs[it].toChar() to bs[it] }
        }

        fun load(file: File): MoonshineTokenizer {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            val model = root["model"]!!.jsonObject
            val vocabObj = model["vocab"]!!.jsonObject
            val byId = HashMap<Int, String>()
            for ((piece, id) in vocabObj) byId[id.jsonPrimitive.int] = piece
            val special = HashSet<Int>()
            root["added_tokens"]?.jsonArray?.forEach { el ->
                val o = el as JsonObject
                val id = o["id"]!!.jsonPrimitive.int
                byId[id] = o["content"]!!.jsonPrimitive.content
                if (o["special"]?.jsonPrimitive?.booleanOrNull == true) special += id
            }
            val maxId = byId.keys.maxOrNull() ?: -1
            val vocab = Array(maxId + 1) { byId[it] }
            val decoder = root["decoder"]?.toString() ?: ""
            val byteLevel = decoder.contains("\"ByteLevel\"")
            return MoonshineTokenizer(vocab, special, byteLevel)
        }
    }
}
