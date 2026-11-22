package app.voice.dictate.tts

import android.os.Handler
import android.os.Looper
import app.voice.dictate.VoiceApp
import app.voice.dictate.asr.EngineKind
import app.voice.dictate.asr.ModelCatalog
import app.voice.dictate.asr.SpeechModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class TtsStatus { IDLE, LOADING, READY, SPEAKING, ERROR }

data class TtsState(
    val status: TtsStatus = TtsStatus.IDLE,
    val stage: String = "",
    /** The voice model loaded (or loading); null when idle. */
    val modelId: String? = null,
    /** True from speak() until the last sentence has played or reading is stopped. */
    val reading: Boolean = false,
    /** Seconds of speech made per second of compute (> 1 keeps ahead of playback). */
    val speed: Double? = null,
    /** Sentence being read (1-based) and how many there are. */
    val index: Int = 0,
    val total: Int = 0,
    val error: String? = null,
    /** The chosen voice model isn't downloaded, so the phone's own voice is reading instead. */
    val fallback: Boolean = false,
)

/**
 * Read aloud: the Android counterpart of the prototype's src/lib/tts.js. Picks the chosen voice (Kitten,
 * Kokoro, or the phone's built-in text-to-speech), splits the text into sentences, and plays each one as soon
 * as it is ready. Shared by the bubble's speaker button and the app's screens. All methods: main thread.
 */
class ReadAloud(private val app: VoiceApp) {
    private val host = TtsHost()
    private val player = TtsPlayer(app)
    private val system = SystemTts(app)
    private val main = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(TtsState())
    val state: StateFlow<TtsState> = _state

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** One-off notes and errors (shown as a toast). */
    val messages: SharedFlow<String> = _messages

    private var playback: TtsPlayer.Playback? = null
    private var currentId = 0L
    private var nextId = 1L
    private var requestedModelId: String? = null // the voice model the TTS thread was asked to load

    /** The engine that would read right now: the chosen voice model if it is downloaded, else the phone's voice. */
    fun activeModel(): SpeechModel {
        val chosen = ModelCatalog.voiceModelById(app.settings.value.ttsModelId)
        return if (chosen != null && app.modelStore.isReady(chosen)) chosen else ModelCatalog.voiceModelById(ModelCatalog.SYSTEM_TTS_ID)!!
    }

    /** Loads the chosen voice model in the background, so the first sentence starts sooner. */
    fun prepare() {
        val m = activeModel()
        if (m.needsDownload) ensureLoaded(m)
    }

    private val loadListener = object : TtsHost.Listener {
        override fun onStage(stage: String) {
            main.post { _state.update { it.copy(status = TtsStatus.LOADING, stage = stage) } }
        }

        override fun onReady() {
            main.post {
                _state.update { it.copy(status = if (it.reading) TtsStatus.SPEAKING else TtsStatus.READY, stage = "", error = null) }
            }
        }

        override fun onError(message: String, duringLoad: Boolean) {
            main.post {
                requestedModelId = null
                currentId = 0 // keep the error visible: the cancelled playback must not reset it
                stopPlayback()
                _state.value = TtsState(TtsStatus.ERROR, error = message)
                _messages.tryEmit("Couldn't load the voice: $message")
            }
        }
    }

    private fun ensureLoaded(m: SpeechModel) {
        if (requestedModelId == m.id && _state.value.status != TtsStatus.ERROR) return
        requestedModelId = m.id
        _state.update { it.copy(status = TtsStatus.LOADING, stage = "Starting the voice…", modelId = m.id, error = null) }
        host.load(m, app.modelStore.dir(m), loadListener)
    }

    /** Reads [text] aloud, replacing anything being read. Returns false if there is nothing to read. */
    fun speak(text: String): Boolean {
        val chunks = TextPrep.chunks(text)
        if (chunks.isEmpty()) return false
        stop()
        val s = app.settings.value
        val chosen = ModelCatalog.voiceModelById(s.ttsModelId)
        val model = activeModel()
        val fallback = chosen != null && chosen.needsDownload && model.id != chosen.id

        if (model.engine == EngineKind.SYSTEM_TTS) {
            _state.update {
                it.copy(status = TtsStatus.SPEAKING, reading = true, index = 1, total = chunks.size, speed = null, fallback = fallback, error = null)
            }
            val id = ++nextId
            currentId = id
            system.speak(chunks, s.ttsSpeed, object : SystemTts.Listener {
                override fun onSentence(index: Int, total: Int) {
                    main.post { if (currentId == id) _state.update { it.copy(index = index + 1, total = total) } }
                }

                override fun onDone() {
                    main.post { if (currentId == id) finished() }
                }

                override fun onError(message: String) {
                    main.post { if (currentId == id) { finished(); _messages.tryEmit(message) } }
                }
            })
            return true
        }

        val voice = model.voice(s.ttsVoice) ?: return false
        ensureLoaded(model)
        val id = ++nextId
        currentId = id
        val pb = player.Playback(24000) { main.post { if (currentId == id) finished() } }
        playback = pb
        _state.update {
            it.copy(
                status = if (host.loadedModelId == model.id) TtsStatus.SPEAKING else TtsStatus.LOADING,
                reading = true, index = 1, total = chunks.size, speed = null, fallback = false, error = null,
            )
        }
        host.speak(id, chunks, voice, s.ttsSpeed, object : TtsHost.Listener {
            override fun onAudio(samples: FloatArray, sampleRate: Int, index: Int, total: Int) {
                pb.enqueue(samples)
                main.post { if (currentId == id) _state.update { it.copy(status = TtsStatus.SPEAKING, index = index + 1) } }
            }
            override fun onStats(speed: Double) {
                main.post { if (currentId == id) _state.update { it.copy(speed = speed) } }
            }

            override fun onDone() = pb.endOfInput()

            override fun onError(message: String, duringLoad: Boolean) {
                pb.stop()
                main.post { if (currentId == id) { finished(); _messages.tryEmit(message) } }
            }
        })
        return true
    }

    fun hearVoice() = speak("Hello! This is how I sound when I read your text aloud.")

    private fun finished() {
        currentId = 0
        playback = null
        _state.update {
            it.copy(
                status = if (it.status == TtsStatus.LOADING) TtsStatus.LOADING else if (it.modelId != null && requestedModelId != null) TtsStatus.READY else TtsStatus.IDLE,
                reading = false, index = 0, total = 0,
            )
        }
    }

    private fun stopPlayback() {
        host.cancel()
        playback?.stop()
        playback = null
        system.stop()
    }

    /** Stops reading at once. */
    fun stop() {
        if (!_state.value.reading && playback == null) { system.stop(); return }
        stopPlayback()
        finished()
    }

    /** Frees the voice model (when it is deleted, or Android is short of memory). */
    fun unload() {
        stop()
        host.unload()
        requestedModelId = null
        _state.value = TtsState()
    }

    fun unloadIfIdle() { if (!_state.value.reading) unload() }
}
