package app.voice.dictate.asr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import java.io.File

// NVIDIA Parakeet Unified EN 0.6B (ONNX bundle by mrfakename). Port of the prototype's src/asr/parakeet.js.
// The export only has the full-context ("offline") encoder, so this engine transcribes a whole utterance
// at a time; Segmenter turns that into near-live text.

private const val MAX_SYMBOLS = 10
private const val BLANK = 1024

class ParakeetEngine private constructor(
    private val pre: OrtSession,
    private val enc: OrtSession,
    private val dj: OrtSession,
    private val pieces: Array<String?>,
) : ClipEngine {

    override val backend = "cpu"

    override fun transcribe(audio: FloatArray): String {
        if (audio.size < 1600) return ""
        val ids = ArrayList<Int>()
        val wav = Ort.floats(audio, 1, audio.size.toLong())
        val wavLen = Ort.int64(longArrayOf(audio.size.toLong()), 1)
        val feats = try { pre.run(mapOf("waveforms" to wav, "waveforms_lens" to wavLen)) } finally { wav.close(); wavLen.close() }
        val encOut = feats.use {
            enc.run(mapOf("audio_signal" to it["features", 0] as OnnxTensor, "length" to it["features_lens", 1] as OnnxTensor))
        }
        val (data, dims, encodedLen) = encOut.use {
            val o = it["outputs", 0]
            Triple(o.floats(), o.shape(), it["encoded_lengths", 1].firstLong())
        }
        val d = dims[1].toInt()
        val t0 = dims[2].toInt()
        val tCount = minOf(t0.toLong(), encodedLen).toInt()

        val zeros1 = Ort.zeros(2, 1, 640)
        val zeros2 = Ort.zeros(2, 1, 640)
        var s1: OnnxTensor = zeros1
        var s2: OnnxTensor = zeros2
        var stateResult: OrtSession.Result? = null
        var last = BLANK
        try {
            for (t in 0 until tCount) {
                val frame = FloatArray(d) { data[it * t0 + t] }
                Ort.floats(frame, 1, d.toLong(), 1).use { encT ->
                    for (s in 0 until MAX_SYMBOLS) {
                        val targets = Ort.int32(intArrayOf(last), 1, 1)
                        val targetLen = Ort.int32(intArrayOf(1), 1)
                        val out = try {
                            dj.run(
                                mapOf(
                                    "encoder_outputs" to encT,
                                    "targets" to targets,
                                    "target_length" to targetLen,
                                    "input_states_1" to s1,
                                    "input_states_2" to s2,
                                ),
                            )
                        } finally { targets.close(); targetLen.close() }
                        val best = Tokens.argmax(out["outputs", 0].floats())
                        if (best == BLANK) { out.close(); break }
                        ids.add(best)
                        last = best
                        // The new states live in this result; the previous one has been consumed.
                        stateResult?.close()
                        stateResult = out
                        s1 = out["output_states_1", 1] as OnnxTensor
                        s2 = out["output_states_2", 2] as OnnxTensor
                    }
                }
            }
        } finally {
            stateResult?.close()
            zeros1.close(); zeros2.close()
        }
        return Tokens.piecesToText(pieces, ids)
    }

    override fun close() { pre.close(); enc.close(); dj.close() }

    companion object {
        fun create(dir: File, stage: (String) -> Unit): ParakeetEngine {
            stage("Reading model from storage…")
            val pieces = Tokens.parse(File(dir, "vocab.txt").readText())
            stage("Preparing the encoder…")
            val pre = Ort.session(File(dir, "preprocessor.onnx"), Ort.bigThreads)
            val enc = Ort.session(File(dir, "encoder-model.q4.onnx"), Ort.bigThreads)
            val dj = Ort.session(File(dir, "decoder_joint-model.int8.onnx"), 1)
            val engine = ParakeetEngine(pre, enc, dj, pieces)
            stage("Warming up…")
            engine.transcribe(FloatArray(SAMPLE_RATE))
            return engine
        }
    }
}
