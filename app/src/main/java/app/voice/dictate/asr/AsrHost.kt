package app.voice.dictate.asr

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.Executors

enum class AsrStatus { IDLE, LOADING, READY, LISTENING, STOPPING, ERROR }

data class AsrState(
    val status: AsrStatus = AsrStatus.IDLE,
    val stage: String = "",
    val modelId: String? = null,
    val backend: String? = null,
    /** Real-time factor: seconds of audio processed per second of compute (> 1 keeps up with speech). */
    val speed: Double? = null,
    val error: String? = null,
)

/**
 * Owns the loaded on-device model and runs it on one dedicated thread, so loading, audio and stop never
 * overlap (the same strict ordering as the prototype's Web Worker message queue). Shared by the in-app
 * Transcribe screen and the floating bubble, so a model is loaded only once.
 */
class AsrHost(private val store: ModelStore) {
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "asr").apply { priority = Thread.NORM_PRIORITY + 2 }
    }

    private val _state = MutableStateFlow(AsrState())
    val state: StateFlow<AsrState> = _state

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** One-off errors while listening (shown as a toast or in the bubble). */
    val errors: SharedFlow<String> = _errors

    // Only touched on the ASR thread.
    private var target: StreamTarget? = null
    private var engine: AutoCloseable? = null
    private var loadedId: String? = null
    private var listener: Emit? = null
    private var statsAudio = 0.0
    private var statsMs = 0.0
    private var statsLast = 0.0

    @Volatile private var language = "en-US"

    private fun post(block: () -> Unit) = executor.execute {
        try { block() } catch (t: Throwable) { onError(t) }
    }

    /** Loads [modelId] if it is downloaded (no-op if it is already loaded or loading). */
    fun load(modelId: String) {
        val model = ModelCatalog.byId(modelId) ?: return
        val s = _state.value
        if (s.modelId == modelId && s.status in setOf(AsrStatus.LOADING, AsrStatus.READY, AsrStatus.LISTENING, AsrStatus.STOPPING)) return
        if (model.engine == EngineKind.SYSTEM) {
            // The built-in recognizer doesn't use this host; free the memory of any loaded model.
            _state.value = AsrState(AsrStatus.READY, modelId = modelId, backend = "system")
            post { unloadNow() }
            return
        }
        if (!store.isReady(model)) {
            _state.value = AsrState(AsrStatus.IDLE)
            post { unloadNow() }
            return
        }
        _state.value = AsrState(AsrStatus.LOADING, "Starting…", modelId)
        post { loadNow(model) }
    }

    private fun loadNow(model: SpeechModel) {
        if (loadedId == model.id && target != null) {
            _state.value = AsrState(AsrStatus.READY, modelId = model.id, backend = target?.backend)
            return
        }
        unloadNow()
        val t0 = System.nanoTime()
        val stage = { text: String -> _state.update { it.copy(stage = text) } }
        val dir = store.dir(model)
        when (model.engine) {
            EngineKind.NEMOTRON -> NemotronEngine.create(model, dir, stage).also {
                it.setLanguage(language); engine = it; target = it
            }
            EngineKind.PARAKEET -> ParakeetEngine.create(dir, stage).also { engine = it; target = Segmenter(it) }
            EngineKind.MOONSHINE -> MoonshineEngine.create(dir, stage).also { engine = it; target = Segmenter(it) }
            else -> return // the built-in recognizer, or a read-aloud voice (never loaded here)
        }
        loadedId = model.id
        Log.i(TAG, "${model.name} ready in ${(System.nanoTime() - t0) / 1_000_000} ms")
        _state.value = AsrState(AsrStatus.READY, modelId = model.id, backend = target?.backend)
    }

    fun unload() {
        _state.update { if (it.backend == "system") it else AsrState(AsrStatus.IDLE) }
        post { unloadNow() }
    }

    /** Frees the model if nothing is listening (called when Android is short of memory). */
    fun unloadIfIdle() {
        if (_state.value.status == AsrStatus.READY && _state.value.backend != "system") unload()
    }

    private fun unloadNow() {
        runCatching { engine?.close() }
        engine = null
        target = null
        loadedId = null
    }

    fun setLanguage(lang: String) {
        language = lang
        post { (engine as? NemotronEngine)?.setLanguage(lang) }
    }

    /** True if a model is loaded and nobody is listening. */
    val isReady: Boolean get() = _state.value.status == AsrStatus.READY && _state.value.backend != "system"

    /** Starts a listening session; [emit] gets text events on the ASR thread. False if not ready or busy. */
    @Synchronized
    fun start(emit: Emit): Boolean {
        if (!isReady) return false
        _state.update { it.copy(status = AsrStatus.LISTENING, speed = null) }
        post {
            listener = emit
            target?.reset()
            statsAudio = 0.0; statsMs = 0.0; statsLast = 0.0
        }
        return true
    }

    fun accept(samples: FloatArray) = post {
        val t = target ?: return@post
        if (listener == null) return@post
        val t0 = System.nanoTime()
        t.accept(samples) { listener?.invoke(it) }
        statsAudio += samples.size.toDouble() / SAMPLE_RATE
        statsMs += (System.nanoTime() - t0) / 1e6
        if (statsAudio - statsLast >= 1) {
            statsLast = statsAudio
            val speed = statsAudio / maxOf(1e-3, statsMs / 1000)
            _state.update { it.copy(speed = speed) }
        }
    }

    /** Finishes the session: the tail of the audio is decoded, then [onDone] runs (on the ASR thread). */
    fun stop(onDone: () -> Unit = {}) {
        _state.update { if (it.status == AsrStatus.LISTENING) it.copy(status = AsrStatus.STOPPING) else it }
        post {
            try {
                val l = listener
                if (l != null) target?.finish { l(it) }
                val e = engine
                if (e is NemotronEngine && e.chunks > 0) {
                    Log.d(TAG, "${"%.1f".format(statsAudio)} s of audio in ${"%.1f".format(statsMs / 1000)} s · per chunk: " +
                        "encoder ${(e.encoderMs / e.chunks).toInt()} ms, decoder ${(e.decoderMs / e.chunks).toInt()} ms")
                }
            } finally {
                listener = null
                _state.update {
                    if (it.status == AsrStatus.STOPPING || it.status == AsrStatus.LISTENING) it.copy(status = AsrStatus.READY) else it
                }
                onDone()
            }
        }
    }

    private fun onError(t: Throwable) {
        Log.e(TAG, "speech engine error", t)
        val message = when (t) {
            is OutOfMemoryError -> "Not enough memory for this model. Try a smaller one."
            else -> t.message?.lineSequence()?.firstOrNull()?.take(200) ?: t.javaClass.simpleName
        }
        val s = _state.value
        if (s.status == AsrStatus.LOADING) {
            runCatching { unloadNow() }
            _state.value = AsrState(AsrStatus.ERROR, modelId = s.modelId, error = "Couldn't load the model: $message")
        } else {
            _errors.tryEmit(message)
            if (target != null) _state.update { it.copy(status = if (it.status == AsrStatus.LISTENING) AsrStatus.LISTENING else AsrStatus.READY) }
        }
    }

    companion object { private const val TAG = "AsrHost" }
}
