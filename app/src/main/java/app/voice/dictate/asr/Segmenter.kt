package app.voice.dictate.asr

import kotlin.math.max
import kotlin.math.sqrt

// Turns a "transcribe this clip" engine (Parakeet, Moonshine) into near-live text. Port of the prototype's
// src/asr/segmenter.js: an energy voice-activity detector cuts speech into utterances; while you talk the
// current utterance is re-transcribed every ~0.8 s (grey, may change), and it is committed at a pause.

private const val FRAME = 320 // 20 ms
private const val FRAME_S = FRAME.toDouble() / SAMPLE_RATE
private const val PREROLL_FRAMES = 15 // 300 ms kept from before speech starts
private const val ONSET_FRAMES = 3 // 60 ms of voice to start an utterance
private const val END_SILENCE_S = 0.6
private const val MAX_UTTERANCE_S = 15.0
private const val MIN_PARTIAL_S = 0.8
private const val MIN_SPEECH_S = 0.25
private const val PARAGRAPH_GAP_S = 2.5 // a pause this long starts a new paragraph

private fun rms(x: FloatArray): Float {
    var s = 0.0
    for (v in x) s += v * v
    return sqrt(s / x.size).toFloat()
}

class Segmenter(private val engine: ClipEngine) : StreamTarget {
    override val backend get() = engine.backend

    private var rest = FloatArray(0)
    private var noise = 0.002f
    private var onset = 0
    private var inSpeech = false
    private var preroll = ArrayDeque<FloatArray>()
    private var utt = ArrayList<FloatArray>()
    private var voiceFrames = 0
    private var silence = 0
    private var sinceDecode = 0.0
    private var decodeEvery = MIN_PARTIAL_S
    private var first = true
    private var idle = 0
    private var newParagraph = true

    override fun reset() {
        rest = FloatArray(0)
        noise = 0.002f
        onset = 0
        inSpeech = false
        preroll = ArrayDeque()
        utt = ArrayList()
        voiceFrames = 0
        silence = 0
        sinceDecode = 0.0
        decodeEvery = MIN_PARTIAL_S
        first = true
        idle = 0
        newParagraph = true
    }

    override fun accept(samples: FloatArray, emit: Emit) {
        val x = FloatArray(rest.size + samples.size)
        rest.copyInto(x); samples.copyInto(x, rest.size)
        var p = 0
        while (p + FRAME <= x.size) {
            frame(x.copyOfRange(p, p + FRAME), emit)
            p += FRAME
        }
        rest = x.copyOfRange(p, x.size)
    }

    private fun frame(f: FloatArray, emit: Emit) {
        val level = rms(f)
        val voice = level > max(0.008f, noise * 3)
        if (!inSpeech) {
            idle++
            preroll.addLast(f)
            if (preroll.size > PREROLL_FRAMES) preroll.removeFirst()
            if (!voice) noise = max(1e-4f, 0.98f * noise + 0.02f * level)
            onset = if (voice) onset + 1 else 0
            if (onset >= ONSET_FRAMES) {
                inSpeech = true
                newParagraph = first || idle * FRAME_S >= PARAGRAPH_GAP_S
                utt = ArrayList(preroll)
                preroll = ArrayDeque()
                voiceFrames = onset
                silence = 0
                sinceDecode = 0.0
            }
            return
        }
        utt.add(f)
        sinceDecode += FRAME_S
        if (voice) { silence = 0; voiceFrames++ } else silence++
        val ended = silence * FRAME_S >= END_SILENCE_S
        if (ended || utt.size * FRAME_S >= MAX_UTTERANCE_S) commit(emit)
        else if (sinceDecode >= decodeEvery) partial(emit)
    }

    private fun audio(): FloatArray {
        val out = FloatArray(utt.size * FRAME)
        utt.forEachIndexed { i, f -> f.copyInto(out, i * FRAME) }
        return out
    }

    private fun partial(emit: Emit) {
        sinceDecode = 0.0
        val t0 = System.nanoTime()
        val text = engine.transcribe(audio())
        // Re-decoding gets slower as the utterance grows; space partials out so we never fall behind.
        decodeEvery = max(MIN_PARTIAL_S, (System.nanoTime() - t0) / 1e9 * 1.5)
        if (text.isNotEmpty()) emit(AsrEvent.Partial(text, stable = false, inline = !newParagraph))
    }

    private fun commit(emit: Emit) {
        val enough = voiceFrames * FRAME_S >= MIN_SPEECH_S
        val text = if (enough) engine.transcribe(audio()) else ""
        inSpeech = false
        utt = ArrayList()
        onset = 0
        decodeEvery = MIN_PARTIAL_S
        if (text.isNotEmpty()) { first = false; idle = 0 }
        emit(if (text.isNotEmpty()) AsrEvent.Commit(text, newParagraph) else AsrEvent.Partial("", stable = false, inline = false))
    }

    override fun finish(emit: Emit) {
        if (rest.isNotEmpty()) {
            val pad = FloatArray(FRAME)
            rest.copyInto(pad)
            rest = FloatArray(0)
            frame(pad, emit)
        }
        if (inSpeech) commit(emit)
    }
}
