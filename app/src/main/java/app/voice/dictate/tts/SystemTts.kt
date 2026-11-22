package app.voice.dictate.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log

/**
 * Android's built-in text-to-speech (the phone's own voices, in any language it has installed).
 * Speaks the same sentence chunks as the voice models, so progress and stop behave the same way.
 */
class SystemTts(private val context: Context) {
    interface Listener {
        fun onSentence(index: Int, total: Int)
        fun onDone()
        fun onError(message: String)
    }

    private var tts: TextToSpeech? = null
    private var ready = false
    private var failed = false
    private var pending: (() -> Unit)? = null
    private var session = 0

    private fun engine(): TextToSpeech = tts ?: TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        failed = !ready
        val p = pending
        pending = null
        p?.invoke()
    }.also { tts = it }

    /** Speaks [chunks] at [speed]; replaces anything already being read. */
    fun speak(chunks: List<String>, speed: Float, listener: Listener) {
        val id = ++session
        val run = run@{
            if (id != session) return@run
            val t = tts
            if (!ready || t == null) {
                listener.onError("This phone has no text-to-speech voice. Install one in Android settings, or download a voice in Voice.")
                return@run
            }
            t.setSpeechRate(speed)
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) {
                    val (s, i) = parse(utteranceId) ?: return
                    if (s == session) listener.onSentence(i, chunks.size)
                }
                override fun onDone(utteranceId: String) {
                    val (s, i) = parse(utteranceId) ?: return
                    if (s == session && i == chunks.lastIndex) listener.onDone()
                }
                override fun onStop(utteranceId: String, interrupted: Boolean) {}
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String) {
                    val (s, _) = parse(utteranceId) ?: return
                    if (s == session) listener.onError("The phone's voice couldn't read this text")
                }
                override fun onError(utteranceId: String, errorCode: Int) {
                    val (s, _) = parse(utteranceId) ?: return
                    if (s == session) listener.onError("The phone's voice failed (error $errorCode)")
                }
            })
            chunks.forEachIndexed { i, chunk ->
                t.speak(chunk, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, Bundle(), "voice-$id-$i")
            }
        }
        val t = engine()
        if (ready) run() else if (failed) { failed = false; tts = null; t.shutdown(); pending = run; engine() } else pending = run
    }

    fun stop() {
        session++
        pending = null
        tts?.stop()
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    private fun parse(id: String): Pair<Int, Int>? = runCatching {
        val parts = id.split('-')
        parts[1].toInt() to parts[2].toInt()
    }.getOrElse { Log.w("SystemTts", "unknown utterance $id"); null }
}
