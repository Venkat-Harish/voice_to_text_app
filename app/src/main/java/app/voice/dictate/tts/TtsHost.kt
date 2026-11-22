package app.voice.dictate.tts

import android.util.Log
import app.voice.dictate.asr.EngineKind
import app.voice.dictate.asr.SpeechModel
import app.voice.dictate.asr.Voice
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The read-aloud engine thread: the Android counterpart of the prototype's src/tts/worker.js. It is separate
 * from the speech-to-text thread, so reading aloud never holds up listening. [speak] generates one sentence at a
 * time; a newer speak or a [cancel] stops the rest.
 */
class TtsHost {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "tts").apply { priority = Thread.NORM_PRIORITY + 1 } }

    // Only touched on the TTS thread.
    private var engine: VoiceEngine? = null
    private var loadedId: String? = null

    @Volatile private var current = 0L // the speak request allowed to run (0: none)

    interface Listener {
        fun onStage(stage: String) {}
        fun onReady() {}
        fun onAudio(samples: FloatArray, sampleRate: Int, index: Int, total: Int) {}
        /** Seconds of speech made per second of compute (> 1 keeps ahead of playback). */
        fun onStats(speed: Double) {}
        fun onDone() {}
        fun onError(message: String, duringLoad: Boolean) {}
    }

    private fun post(block: () -> Unit) = executor.execute(block)

    val loadedModelId: String? get() = loadedId

    /** Loads [model] from [dir] (no-op if already loaded). Runs before any speak sent after it. */
    fun load(model: SpeechModel, dir: File, listener: Listener) = post {
        if (loadedId == model.id && engine != null) { listener.onReady(); return@post }
        try {
            unloadNow()
            val t0 = System.nanoTime()
            val e = when (model.engine) {
                EngineKind.KITTEN -> KittenEngine.create(dir, listener::onStage)
                EngineKind.KOKORO -> KokoroEngine.create(dir, listener::onStage)
                else -> error("${model.name} is not a voice model")
            }
            // The first run is several times slower; do it now, not on the first sentence.
            listener.onStage("Warming up…")
            e.generate("Hello there.", model.voices.first(), 1f)
            engine = e
            loadedId = model.id
            Log.i(TAG, "${model.name} ready in ${(System.nanoTime() - t0) / 1_000_000} ms")
            listener.onReady()
        } catch (t: Throwable) {
            Log.e(TAG, "voice model failed to load", t)
            unloadNow()
            listener.onError(message(t), duringLoad = true)
        }
    }

    fun unload() = post { unloadNow() }

    private fun unloadNow() {
        runCatching { engine?.close() }
        engine = null
        loadedId = null
    }

    /** Takes effect at once, even while a long text is still being generated. */
    fun cancel() { current = 0 }

    fun speak(id: Long, chunks: List<String>, voice: Voice, speed: Float, listener: Listener) {
        current = id
        post {
            if (current != id) return@post // cancelled before it started
            val e = engine ?: run { listener.onError("No voice model loaded", false); return@post }
            var audio = 0.0
            var ms = 0.0
            try {
                for ((i, part) in chunks.withIndex()) {
                    if (current != id) break
                    val t0 = System.nanoTime()
                    // A full stop gets a sentence-length pause; a clause break (the split first sentence) a short one.
                    val tail = if (SENTENCE_END.containsMatchIn(part)) 0.35 else 0.12
                    val samples = trimSilence(e.generate(part, voice, speed), e.sampleRate, 0.06, tail)
                    ms += (System.nanoTime() - t0) / 1e6
                    audio += samples.size.toDouble() / e.sampleRate
                    if (current != id) break
                    listener.onAudio(samples, e.sampleRate, i, chunks.size)
                    listener.onStats(audio / max(1e-3, ms / 1000))
                }
                Log.d(TAG, "${"%.1f".format(audio)} s of speech in ${"%.2f".format(ms / 1000)} s (${chunks.size} sentences)")
                listener.onDone()
            } catch (t: Throwable) {
                Log.e(TAG, "speech generation failed", t)
                listener.onError(message(t), false)
            }
        }
    }

    private fun message(t: Throwable) = when (t) {
        is OutOfMemoryError -> "Not enough memory for this voice model."
        else -> t.message?.lineSequence()?.firstOrNull()?.take(200) ?: t.javaClass.simpleName
    }

    companion object {
        private const val TAG = "TtsHost"
        private val SENTENCE_END = Regex("[.!?…][\"')\\]]*$")

        /** Keeps [lead] / [tail] seconds of quiet around the speech (Kitten adds almost a second at the start). */
        fun trimSilence(samples: FloatArray, rate: Int, lead: Double, tail: Double): FloatArray {
            val loud = 0.01f
            var a = 0
            while (a < samples.size && abs(samples[a]) < loud) a++
            var b = samples.size - 1
            while (b > a && abs(samples[b]) < loud) b--
            if (a >= b) return samples
            val from = max(0, a - (lead * rate).roundToInt())
            val to = min(samples.size, b + 1 + (tail * rate).roundToInt())
            return samples.copyOfRange(from, to)
        }
    }
}
