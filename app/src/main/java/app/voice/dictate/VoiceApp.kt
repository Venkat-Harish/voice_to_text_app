package app.voice.dictate

import android.app.ActivityManager
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentCallbacks2
import android.os.SystemClock
import android.util.Log
import app.voice.dictate.asr.AsrHost
import app.voice.dictate.asr.AsrStatus
import app.voice.dictate.asr.ModelCatalog
import app.voice.dictate.asr.ModelRepository
import app.voice.dictate.asr.ModelStore
import app.voice.dictate.data.HistoryRepository
import app.voice.dictate.data.SettingsRepository
import app.voice.dictate.data.TranscriptStore
import app.voice.dictate.tts.ReadAloud
import app.voice.dictate.tts.TtsStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** App-wide singletons, shared by the screens and the bubble service (they run in the same process). */
class VoiceApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var settings: SettingsRepository; private set
    lateinit var modelStore: ModelStore; private set
    lateinit var models: ModelRepository; private set
    lateinit var asr: AsrHost; private set
    lateinit var history: HistoryRepository; private set
    lateinit var transcript: TranscriptStore; private set
    lateinit var readAloud: ReadAloud; private set

    /** The on-device model this phone should start with (by RAM). */
    val recommendedModelId: String by lazy {
        val mi = ActivityManager.MemoryInfo()
        getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
        ModelCatalog.recommendedFor(mi.totalMem)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannels()
        settings = SettingsRepository(this, scope)
        modelStore = ModelStore(this)
        models = ModelRepository(this, modelStore, scope)
        asr = AsrHost(modelStore)
        history = HistoryRepository(this, scope) { settings.value.historyDays }
        transcript = TranscriptStore(this, scope)
        readAloud = ReadAloud(this)

        // On start, and whenever the choice changes, load the selected model straight away (like the prototype).
        scope.launch {
            settings.flow.map { it.modelId }.distinctUntilChanged().collect { loadSelected() }
        }
        scope.launch {
            settings.flow.map { it.language }.distinctUntilChanged().collect { asr.setLanguage(it) }
        }
        // A shorter History setting (or "don't save") applies to what is already saved.
        scope.launch {
            settings.flow.map { it.historyDays }.distinctUntilChanged().collect { history.prune(it) }
        }
        unloadWhenIdle()
    }

    fun loadSelected() {
        val id = settings.value.modelId
        scope.launch(Dispatchers.IO) { asr.load(id) }
    }

    /** Bumped on every sign the speech model is about to be needed; restarts its idle countdown. */
    private val speechUse = MutableStateFlow(0L)

    /**
     * A text field was tapped, the keyboard opened, the bubble was touched or the Talk screen opened: load the
     * speech model if it was freed to save memory, and restart its idle countdown.
     */
    fun wakeSpeechModel() {
        speechUse.value = SystemClock.elapsedRealtime()
        if (asr.state.value.status == AsrStatus.IDLE) loadSelected()
    }

    /**
     * Frees the speech model and the read-aloud voice once they have sat unused for the time set in Settings.
     * Any change (a dictation starting, reading starting, a new setting, [wakeSpeechModel]) restarts the wait.
     */
    private fun unloadWhenIdle() {
        val unloadAfter = settings.flow
            .map { s -> if (s.modelUnloadEnabled) s.modelUnloadSec * 1000L else 0L }
            .distinctUntilChanged()
        scope.launch {
            val idle = asr.state.map { it.status == AsrStatus.READY && it.backend != "system" }.distinctUntilChanged()
            combine(idle, unloadAfter, speechUse) { isIdle, ms, _ -> isIdle to ms }.collectLatest { (isIdle, ms) ->
                if (isIdle && ms > 0) {
                    delay(ms)
                    Log.i(TAG, "Speech model unused for ${ms / 1000} s: unloading it")
                    asr.unloadIfIdle()
                }
            }
        }
        scope.launch {
            val idle = readAloud.state.map { !it.reading && it.status == TtsStatus.READY && it.modelId != null }.distinctUntilChanged()
            combine(idle, unloadAfter) { isIdle, ms -> isIdle to ms }.collectLatest { (isIdle, ms) ->
                if (isIdle && ms > 0) {
                    delay(ms)
                    Log.i(TAG, "Read-aloud voice unused for ${ms / 1000} s: unloading it")
                    readAloud.unloadIfIdle()
                }
            }
        }
    }

    fun selectModel(id: String) {
        settings.setModelId(id)
        if (settings.value.modelId == id) loadSelected()
    }

    /** Called by the download worker: use the model just downloaded if nothing on-device was chosen yet. */
    fun onModelDownloaded(id: String) {
        if (ModelCatalog.voiceModelById(id) != null) {
            // A read-aloud voice: use it if the phone's voice (or a missing model) was reading until now.
            val currentVoice = ModelCatalog.voiceModelById(settings.value.ttsModelId)
            if (currentVoice == null || !currentVoice.needsDownload || !modelStore.isReady(currentVoice)) {
                scope.launch { settings.setTtsModelId(id) }
            }
            scope.launch { models.refresh() }
            return
        }
        val current = ModelCatalog.speechById(settings.value.modelId)
        if (current == null || current.id == id || !current.needsDownload || !modelStore.isReady(current)) {
            scope.launch { selectModel(id) }
        }
        scope.launch { models.refresh() }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            asr.unloadIfIdle()
            scope.launch { readAloud.unloadIfIdle() }
        }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DOWNLOADS, getString(R.string.channel_downloads), NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_DICTATION, getString(R.string.channel_dictation), NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        private const val TAG = "VoiceApp"
        const val CHANNEL_DOWNLOADS = "downloads"
        const val CHANNEL_DICTATION = "dictation"
        lateinit var instance: VoiceApp; private set
    }
}
