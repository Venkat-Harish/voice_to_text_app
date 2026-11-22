package app.voice.dictate.asr

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import app.voice.dictate.audio.AudioCapture
import java.util.concurrent.Executors

/** Receives a dictation session's output. Every callback runs on the main thread. */
interface DictationListener {
    fun onEvent(event: AsrEvent)
    fun onLevel(level: Float) {}
    /** Recording stopped and all text has been delivered. */
    fun onFinished()
    fun onError(message: String)
    /** The microphone delivers only silence: Android is blocking it. */
    fun onSilenced() {}
}

/** One push-to-talk recording with either an on-device model or the phone's recognizer. */
interface DictationSession {
    /** Returns null when listening started, or a message saying why it couldn't. */
    fun start(): String?
    /** Stop listening; the remaining text arrives, then onFinished(). */
    fun stop()
    /** Stop without delivering text; [onDone] runs on the main thread once the engine is free again. */
    fun cancel(onDone: () -> Unit = {})
}

/** Collects committed paragraphs and the live partial into one string (used for typing into a field). */
class TextAccumulator {
    private val paragraphs = ArrayList<String>()
    private var partial: String? = null

    fun on(event: AsrEvent) {
        when (event) {
            is AsrEvent.Partial -> partial = event.text.ifBlank { null }
            is AsrEvent.Commit -> {
                if (event.newParagraph || paragraphs.isEmpty()) paragraphs.add(event.text)
                else paragraphs[paragraphs.lastIndex] = paragraphs.last() + " " + event.text
                partial = null
            }
        }
    }

    fun text(): String = (paragraphs + listOfNotNull(partial)).joinToString(" ").trim()
}

private val main = Handler(Looper.getMainLooper())
private val background = Executors.newSingleThreadExecutor { Thread(it, "dictation-stop") }

/** Mic → [AsrHost] (Nemotron, Parakeet or Moonshine). */
class OnnxDictation(private val host: AsrHost, private val listener: DictationListener) : DictationSession {
    private val capture = AudioCapture()
    @Volatile private var active = false

    override fun start(): String? {
        if (!host.start { e -> main.post { if (active) listener.onEvent(e) } }) {
            return when (host.state.value.status) {
                AsrStatus.LOADING -> "The model is still loading…"
                AsrStatus.LISTENING, AsrStatus.STOPPING -> "Voice is already listening"
                AsrStatus.ERROR -> host.state.value.error ?: "The model couldn't load"
                else -> "No speech model is loaded"
            }
        }
        active = true
        val ok = capture.start(
            onAudio = host::accept,
            onLevel = { l -> main.post { if (active) listener.onLevel(l) } },
            onSilenced = { main.post { if (active) listener.onSilenced() } },
        )
        if (!ok) {
            active = false
            host.stop()
            return "Can't open the microphone"
        }
        return null
    }

    override fun stop() {
        background.execute {
            capture.stop()
            host.stop { main.post { if (active) { active = false; listener.onFinished() } } }
        }
    }

    override fun cancel(onDone: () -> Unit) {
        active = false
        background.execute { capture.stop(); host.stop { main.post(onDone) } }
    }
}

/**
 * Android's built-in speech recognizer (on-device when the phone supports it). It records the mic itself.
 * Recognizers stop after a pause, so while the button is held it is restarted and the text is appended.
 */
class SystemDictation(
    private val context: Context,
    private val language: String?,
    private val listener: DictationListener,
) : DictationSession {
    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var stopping = false
    private var finished = false
    private var onDevice = Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    private val stopTimeout = Runnable { finish() }

    override fun start(): String? {
        if (!SpeechRecognizer.isRecognitionAvailable(context) && !onDevice) {
            return "This phone has no speech recognizer. Download a model in Voice."
        }
        active = true
        listen()
        return if (active) null else "Couldn't start the speech recognizer"
    }

    private fun create(): SpeechRecognizer =
        if (onDevice && Build.VERSION.SDK_INT >= 31) SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        else SpeechRecognizer.createSpeechRecognizer(context)

    private fun listen() {
        recognizer?.destroy()
        val r = try { create() } catch (e: Exception) { active = false; return }
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {
                // Map roughly −2…10 dB onto the same 0…0.08 scale as AudioCapture's RMS.
                listener.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f) * 0.08f)
            }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isNotBlank()) listener.onEvent(AsrEvent.Partial(text, stable = false, inline = true))
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (text.isNotBlank()) listener.onEvent(AsrEvent.Commit(text, newParagraph = false))
                else listener.onEvent(AsrEvent.Partial("", stable = false, inline = true))
                if (active && !stopping) listen() else finish()
            }
            override fun onError(error: Int) {
                Log.d(TAG, "recognizer error $error")
                val languageMissing = error == 12 || error == 13 // ERROR_LANGUAGE_NOT_SUPPORTED / _UNAVAILABLE
                when {
                    languageMissing && onDevice -> { onDevice = false; if (active && !stopping) listen() else finish() }
                    stopping -> finish()
                    !active -> Unit
                    error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> listen()
                    error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> fail("Allow the microphone for Voice")
                    error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        fail("The phone's recognizer needs the internet here. Download an on-device model in Voice.")
                    error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> fail("The speech recognizer is busy")
                    else -> fail("Speech recognizer error ($error)")
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
            if (!language.isNullOrBlank() && language != "auto") putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        }
        try { r.startListening(intent) } catch (e: Exception) { fail("Couldn't start the speech recognizer") }
    }

    private fun fail(message: String) {
        if (!active) return
        active = false
        recognizer?.destroy(); recognizer = null
        listener.onError(message)
    }

    private fun finish() {
        main.removeCallbacks(stopTimeout)
        if (finished) return
        finished = true
        active = false
        recognizer?.destroy(); recognizer = null
        listener.onFinished()
    }

    override fun stop() {
        if (!active) { finish(); return }
        stopping = true
        recognizer?.stopListening()
        main.postDelayed(stopTimeout, 4000)
    }

    override fun cancel(onDone: () -> Unit) {
        active = false
        finished = true
        main.removeCallbacks(stopTimeout)
        recognizer?.cancel()
        recognizer?.destroy(); recognizer = null
        main.post(onDone)
    }

    companion object { private const val TAG = "SystemDictation" }
}
