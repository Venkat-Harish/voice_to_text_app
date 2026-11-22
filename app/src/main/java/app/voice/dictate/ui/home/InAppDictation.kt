package app.voice.dictate.ui.home

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import app.voice.dictate.VoiceApp
import app.voice.dictate.asr.AsrEvent
import app.voice.dictate.asr.DictationListener
import app.voice.dictate.asr.DictationSession
import app.voice.dictate.asr.EngineKind
import app.voice.dictate.asr.ModelCatalog
import app.voice.dictate.asr.OnnxDictation
import app.voice.dictate.asr.SystemDictation
import app.voice.dictate.audio.AudioCapture
import app.voice.dictate.audio.WavReader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.Executors

enum class InAppStatus { IDLE, LISTENING, STOPPING }

/**
 * The Transcribe screen's own recording (the prototype's startListening/stopListening/transcribeSample),
 * writing into the persistent in-app transcript.
 */
object InAppDictation : DictationListener {
    private val app get() = VoiceApp.instance
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { Thread(it, "sample") }

    private val _status = MutableStateFlow(InAppStatus.IDLE)
    val status: StateFlow<InAppStatus> = _status
    private val _source = MutableStateFlow<String?>(null) // "mic" | "file"
    val source: StateFlow<String?> = _source
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level

    private var session: DictationSession? = null

    fun start(context: Context) {
        if (_status.value != InAppStatus.IDLE) return
        app.readAloud.stop() // never record the phone reading aloud
        val settings = app.settings.value
        val model = ModelCatalog.byId(settings.modelId)
        val s = if (model == null || model.engine == EngineKind.SYSTEM) SystemDictation(context.applicationContext, settings.language, this)
        else OnnxDictation(app.asr, this)
        val error = s.start()
        if (error != null) { toast(error); return }
        session = s
        _source.value = "mic"
        _status.value = InAppStatus.LISTENING
    }

    fun stop() {
        if (_status.value != InAppStatus.LISTENING) return
        _status.value = InAppStatus.STOPPING
        session?.stop()
    }

    /** Runs the bundled JFK recording through the same streaming path, as fast as the model allows. */
    fun transcribeSample(context: Context) {
        if (_status.value != InAppStatus.IDLE) return
        val host = app.asr
        if (!host.start { e -> main.post { onEvent(e) } }) {
            toast("Load an on-device model to try the sample")
            return
        }
        _source.value = "file"
        _status.value = InAppStatus.LISTENING
        io.execute {
            try {
                val bytes = context.assets.open("samples/jfk.wav").use { it.readBytes() }
                val samples = WavReader.decode(bytes)
                var i = 0
                while (i < samples.size) {
                    host.accept(samples.copyOfRange(i, minOf(samples.size, i + AudioCapture.BLOCK)))
                    i += AudioCapture.BLOCK
                }
            } catch (e: Exception) {
                main.post { toast("Couldn't read the sample: ${e.message}") }
            }
            main.post { _status.value = InAppStatus.STOPPING }
            host.stop { main.post { onFinished() } }
        }
    }

    override fun onEvent(event: AsrEvent) {
        val t = app.transcript
        when (event) {
            is AsrEvent.Partial -> t.setPartial(event.text, event.stable, event.inline)
            is AsrEvent.Commit -> t.commit(event.text, event.newParagraph)
        }
    }

    override fun onLevel(level: Float) { _level.value = level }

    override fun onFinished() {
        // A partial line that never got committed (e.g. Android's recognizer) becomes final text.
        app.transcript.partial.value?.let { app.transcript.commit(it.text, !it.inline) }
        session = null
        _level.value = 0f
        _source.value = null
        _status.value = InAppStatus.IDLE
    }

    override fun onError(message: String) {
        onFinished()
        toast(message)
    }

    override fun onSilenced() {
        toast("The microphone is silent. Check that no other app is using it.")
    }

    private fun toast(message: String) {
        Toast.makeText(app, message, Toast.LENGTH_LONG).show()
    }
}
