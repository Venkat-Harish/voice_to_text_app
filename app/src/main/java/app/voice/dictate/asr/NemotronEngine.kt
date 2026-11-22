package app.voice.dictate.asr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import java.io.File

// Cache-aware streaming RNNT for NVIDIA Nemotron Speech Streaming (EN) and Nemotron 3.5 ASR Streaming.
// Port of the prototype's src/asr/nemotron.js. Each encoder call sees 65 log-mel frames (9 frames of left
// context + 56 new = 560 ms) and carries its attention/conv caches forward, so text arrives word by word
// and is never rewritten.

private const val WINDOW = 65
private const val SHIFT = 56
private const val LEFT_CONTEXT = WINDOW - SHIFT
private const val MAX_SYMBOLS_PER_FRAME = 10
private const val CHUNK_SECONDS = SHIFT / 100.0
private const val PARAGRAPH_SILENCE_S = 1.5
private const val LONG_LINE = 320
private val SENTENCE_END = Regex("[.?!。？！]$")

class NemotronEngine private constructor(
    private val enc: OrtSession,
    private val dec: OrtSession,
    private val join: OrtSession,
    private val pieces: Array<String?>,
    private val cfg: NemotronConfig,
) : StreamTarget, AutoCloseable {

    override val backend = "cpu"
    private val blank = pieces.size - 1
    private var language = cfg.defaultLanguage

    // Decoder (LSTM predictor): inputs are [targets, target_length, h, c] and outputs
    // [outputs, prednet_lengths, h', c']. The state tensors have export-specific names, so go by position.
    private val decIn = dec.inputNames.toList()
    private val decOut = dec.outputNames.toList().filter { it != "prednet_lengths" }

    // Encoder caches: either zeros we own, or tensors inside the last encoder result.
    private var cacheChannel: OnnxTensor? = null
    private var cacheTime: OnnxTensor? = null
    private var cacheLen: OnnxTensor? = null
    private var ownedCaches = mutableListOf<OnnxTensor>()
    private var encResult: OrtSession.Result? = null

    // Predictor state: zeros we own, or tensors inside the last decoder result.
    private var h: OnnxTensor? = null
    private var c: OnnxTensor? = null
    private var predOut: OnnxTensor? = null
    private var ownedState = mutableListOf<OnnxTensor>()
    private var decResult: OrtSession.Result? = null

    private var mel = LogMel()
    private var pending = ArrayDeque<FloatArray>()
    private val line = ArrayList<Int>()
    private var silentChunks = 0

    /** Debug timing (ms) like the prototype worker's console stats. */
    var chunks = 0; private set
    var encoderMs = 0.0; private set
    var decoderMs = 0.0; private set

    fun setLanguage(lang: String) { language = lang }

    override fun reset() {
        releaseEncoder()
        val c0 = Ort.zeros(1, cfg.layers.toLong(), cfg.channelCacheFrames.toLong(), cfg.dModel.toLong())
        val t0 = Ort.zeros(1, cfg.layers.toLong(), cfg.dModel.toLong(), cfg.timeCacheFrames.toLong())
        val l0 = Ort.int64(0)
        ownedCaches = mutableListOf(c0, t0, l0)
        cacheChannel = c0; cacheTime = t0; cacheLen = l0

        releaseDecoder()
        val h0 = Ort.zeros(cfg.predLayers.toLong(), 1, cfg.predHidden.toLong())
        val cc0 = Ort.zeros(cfg.predLayers.toLong(), 1, cfg.predHidden.toLong())
        ownedState = mutableListOf(h0, cc0)
        h = h0; c = cc0

        mel = LogMel()
        // Start with 9 frames of digital silence as left context, so no speech is dropped.
        pending = ArrayDeque<FloatArray>().apply { repeat(LEFT_CONTEXT) { add(silentFrame()) } }
        line.clear()
        silentChunks = 0
        chunks = 0; encoderMs = 0.0; decoderMs = 0.0
        predict(blank)
    }

    private fun silentFrame() = FloatArray(MEL_BINS) { SILENT_LOG }

    private fun predict(token: Int) {
        val targets = Ort.int32(intArrayOf(token), 1, 1)
        val targetLen = Ort.int32(intArrayOf(1), 1)
        val out = targets.use { t ->
            targetLen.use { tl ->
                dec.run(mapOf(decIn[0] to t, decIn[1] to tl, decIn[2] to h!!, decIn[3] to c!!))
            }
        }
        // The previous result (or the owned zeros) has been consumed; release it.
        releaseDecoder()
        decResult = out
        predOut = out[decOut[0], 0] as OnnxTensor
        h = out[decOut[1], 2] as OnnxTensor
        c = out[decOut[2], 3] as OnnxTensor
    }

    private fun releaseDecoder() {
        decResult?.close(); decResult = null
        ownedState.forEach { it.close() }; ownedState.clear()
    }

    private fun releaseEncoder() {
        encResult?.close(); encResult = null
        ownedCaches.forEach { it.close() }; ownedCaches.clear()
    }

    /** Runs one 65-frame window through the encoder. Returns [1, D, T] encoder output (copied) and its dims. */
    private fun runEncoder(frames: List<FloatArray>): Pair<FloatArray, LongArray> {
        val audio = FloatArray(MEL_BINS * WINDOW)
        for (t in 0 until WINDOW) {
            val f = frames[t]
            for (m in 0 until MEL_BINS) audio[m * WINDOW + t] = f[m]
        }
        val audioT = Ort.floats(audio, 1, MEL_BINS.toLong(), WINDOW.toLong())
        val lenT = Ort.int64(WINDOW.toLong())
        val promptT = cfg.prompts?.let { Ort.int64((it[language] ?: it[cfg.defaultLanguage] ?: 0).toLong()) }
        val feeds = buildMap<String, OnnxTensor> {
            put("audio_signal", audioT)
            put("length", lenT)
            put("cache_last_channel", cacheChannel!!)
            put("cache_last_time", cacheTime!!)
            put("cache_last_channel_len", cacheLen!!)
            if (promptT != null) put("prompt_index", promptT)
        }
        val out = try { enc.run(feeds) } finally { audioT.close(); lenT.close(); promptT?.close() }
        val encoded = out["outputs", 0]
        val result = encoded.floats() to encoded.shape()
        releaseEncoder()
        encResult = out
        cacheChannel = out["cache_last_channel_next", 1] as OnnxTensor
        cacheTime = out["cache_last_time_next", 2] as OnnxTensor
        cacheLen = out["cache_last_channel_next_len", 3] as OnnxTensor
        return result
    }

    /** Greedy RNNT over the encoder frames of one chunk. Returns emitted token ids. */
    private fun decodeChunk(data: FloatArray, dims: LongArray): List<Int> {
        val d = dims[1].toInt()
        val tCount = dims[2].toInt()
        val emitted = ArrayList<Int>()
        for (t in 0 until tCount) {
            val frame = FloatArray(d) { data[it * tCount + t] }
            Ort.floats(frame, 1, d.toLong(), 1).use { encT ->
                for (s in 0 until MAX_SYMBOLS_PER_FRAME) {
                    val best = join.run(mapOf("encoder_outputs" to encT, "decoder_outputs" to predOut!!)).use { out ->
                        Tokens.argmax(out[0].floats())
                    }
                    if (best == blank) break
                    emitted.add(best)
                    predict(best)
                }
            }
        }
        return emitted
    }

    private fun lineText() = Tokens.piecesToText(pieces, line)

    override fun accept(samples: FloatArray, emit: Emit) {
        pending.addAll(mel.accept(samples))
        while (pending.size >= WINDOW) {
            val t0 = System.nanoTime()
            val (data, dims) = runEncoder(pending.take(WINDOW))
            val t1 = System.nanoTime()
            repeat(SHIFT) { pending.removeFirst() }
            val ids = decodeChunk(data, dims)
            chunks++
            encoderMs += (t1 - t0) / 1e6
            decoderMs += (System.nanoTime() - t1) / 1e6
            onTokens(ids, emit)
        }
    }

    private fun onTokens(ids: List<Int>, emit: Emit) {
        if (ids.isEmpty()) {
            silentChunks++
            if (line.isNotEmpty() && silentChunks * CHUNK_SECONDS >= PARAGRAPH_SILENCE_S) commit(emit)
            return
        }
        silentChunks = 0
        for (id in ids) {
            val startsWord = pieces.getOrNull(id)?.startsWith('▁') == true
            // Start a new paragraph at a word boundary after a long sentence-ending line.
            if (startsWord && line.isNotEmpty()) {
                val text = lineText()
                if (text.length > LONG_LINE && SENTENCE_END.containsMatchIn(text)) commit(emit)
            }
            line.add(id)
        }
        val text = lineText()
        if (text.isNotEmpty()) emit(AsrEvent.Partial(text, stable = true, inline = false))
    }

    private fun commit(emit: Emit) {
        val text = lineText()
        line.clear()
        silentChunks = 0
        if (text.isNotEmpty()) emit(AsrEvent.Commit(text, newParagraph = true))
    }

    /** Push the tail through the encoder (1.2 s of silence covers the last chunk + look-ahead), then commit. */
    override fun finish(emit: Emit) {
        accept(FloatArray((SAMPLE_RATE * 1.2).toInt()), emit)
        commit(emit)
    }

    private fun warmUp() {
        reset()
        runEncoder(pending.toList() + List(SHIFT) { silentFrame() })
        reset()
    }

    override fun close() {
        releaseEncoder()
        releaseDecoder()
        enc.close(); dec.close(); join.close()
    }

    companion object {
        fun create(model: SpeechModel, dir: File, stage: (String) -> Unit): NemotronEngine {
            val cfg = requireNotNull(model.nemotron)
            stage("Reading model from storage…")
            val pieces = Tokens.parse(File(dir, "tokens.txt").readText())
            stage("Preparing the encoder…")
            val enc = Ort.session(File(dir, "encoder.q4.onnx"), Ort.bigThreads)
            stage("Preparing the decoder…")
            // The predictor and joiner are tiny and run many times per chunk: one thread avoids pool overhead.
            val dec = Ort.session(File(dir, "decoder.onnx"), 1)
            val join = Ort.session(File(dir, "joiner.onnx"), 1)
            val engine = NemotronEngine(enc, dec, join, pieces, cfg)
            stage("Warming up…")
            engine.warmUp()
            return engine
        }
    }
}
