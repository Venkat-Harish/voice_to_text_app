package app.voice.dictate.dictation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import app.voice.dictate.VoiceApp
import app.voice.dictate.asr.AsrEvent
import app.voice.dictate.asr.AsrStatus
import app.voice.dictate.asr.DictationListener
import app.voice.dictate.asr.DictationSession
import app.voice.dictate.asr.EngineKind
import app.voice.dictate.asr.ModelCatalog
import app.voice.dictate.asr.OnnxDictation
import app.voice.dictate.asr.SystemDictation
import app.voice.dictate.asr.TextAccumulator

/**
 * Runs one bubble dictation: remember the focused field, record, show live text, then type the result
 * into that field and save it to History.
 */
class DictationController(
    private val service: DictationService,
    private val bubble: BubbleController,
) : DictationListener {
    private val app = VoiceApp.instance
    private val main = Handler(Looper.getMainLooper())
    private val inserter = TextInserter(service)

    private var session: DictationSession? = null
    private var acc = TextAccumulator()
    private var target: AccessibilityNodeInfo? = null
    private var targetPackage: String? = null
    private var locked = false
    private var stopping = false
    private var retriedSilence = false
    private var restarting = false
    private var pendingStop = false
    /** Text from a dictation that ended because the phone locked: typed once you unlock. */
    private var pendingText: String? = null
    private val maxLength = Runnable { stop() }

    val active get() = session != null || restarting

    fun onHoldStart() = start(lock = false)

    fun onHoldEnd() { if (!locked) stop() }

    /** Mic tapped in the bubble's menu: dictate hands-free until the bubble is tapped again. */
    fun onHandsFree() { if (!active) start(lock = true) }

    /** The bubble tapped during hands-free dictation: finish and type the text. */
    fun onStopTap() { if (active && locked) stop() }

    private fun start(lock: Boolean) {
        if (active) return
        pendingStop = false
        if (ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            bubble.showPreview("Open Voice and allow the microphone first.", 3500)
            return
        }
        app.readAloud.stop() // never record the phone reading aloud
        target = service.findTarget()
        targetPackage = target?.packageName?.toString()
        retriedSilence = false
        if (!begin()) return
        locked = lock
        bubble.setMode(if (lock) BubbleView.Mode.LOCKED else BubbleView.Mode.LISTENING)
        bubble.showPreview(if (lock) "Listening… tap the bubble to finish" else "Listening…")
        main.postDelayed(maxLength, MAX_MS)
    }

    /** Creates and starts the engine session. Returns false (and says why) if it couldn't start. */
    private fun begin(): Boolean {
        val settings = app.settings.value
        val model = ModelCatalog.byId(settings.modelId)
        val s: DictationSession = if (model == null || model.engine == EngineKind.SYSTEM) {
            SystemDictation(service, settings.language, this)
        } else {
            val status = app.asr.state.value.status
            if ((status == AsrStatus.IDLE || status == AsrStatus.LOADING) && app.modelStore.isReady(model)) {
                // Freed earlier to save memory (or still coming back): start loading and ask for another try.
                app.wakeSpeechModel()
                bubble.setMode(service.idleMode())
                bubble.showPreview("Loading the model… try again in a moment.", 2500)
                return false
            }
            OnnxDictation(app.asr, this)
        }
        if (service.micForegroundNeeded) service.startMicForeground()
        acc = TextAccumulator()
        stopping = false
        val error = s.start()
        if (error != null) {
            service.stopMicForeground()
            bubble.setMode(service.idleMode())
            bubble.showPreview(error, 3500)
            return false
        }
        session = s
        return true
    }

    fun stop() {
        if (restarting) { pendingStop = true; return }
        val s = session ?: return
        if (stopping) return
        stopping = true
        main.removeCallbacks(maxLength)
        bubble.setMode(BubbleView.Mode.FINISHING)
        s.stop()
    }

    /** Drops the current recording without typing anything (e.g. the service is shutting down). */
    fun cancel() {
        main.removeCallbacks(maxLength)
        session?.cancel {}
        session = null
        restarting = false
        service.stopMicForeground()
    }

    override fun onEvent(event: AsrEvent) {
        acc.on(event)
        if (!stopping || acc.text().isNotEmpty()) bubble.showPreview(acc.text().ifBlank { "Listening…" })
    }

    override fun onLevel(level: Float) = bubble.setLevel(level)

    override fun onSilenced() {
        // Android delivered only silence: background mic access is blocked on this phone. Become a
        // foreground (microphone) service and restart the recording once, while the finger is still down.
        val s = session ?: return
        if (retriedSilence) return
        retriedSilence = true
        service.micForegroundNeeded = true
        restarting = true
        s.cancel {
            restarting = false
            session = null
            if (!begin()) { main.removeCallbacks(maxLength); return@cancel }
            bubble.showPreview("Listening…")
            if (pendingStop) stop()
        }
    }

    override fun onFinished() {
        session = null
        main.removeCallbacks(maxLength)
        service.stopMicForeground()
        deliver(acc.text())
    }

    override fun onError(message: String) {
        val text = acc.text()
        session = null
        main.removeCallbacks(maxLength)
        service.stopMicForeground()
        if (text.isNotBlank()) deliver(text)
        else {
            bubble.setMode(service.idleMode())
            bubble.showPreview(message, 4000)
            service.onDictationEnded()
        }
    }

    /** After unlocking: type what was said before the lock into the field it was meant for (or copy it). */
    fun deliverPending() {
        val raw = pendingText ?: return
        pendingText = null
        deliver(raw, retarget = false)
    }

    /**
     * Types the text into the field. [retarget] lets it fall back to whichever field has focus now; it is off
     * after an unlock, so text never lands in a different app's field.
     */
    private fun deliver(raw: String, retarget: Boolean = true) {
        if (service.screenLocked) {
            // The field is out of reach behind the lock screen: keep the text until you unlock.
            pendingText = raw
            bubble.setMode(service.idleMode())
            return
        }
        val settings = app.settings.value
        var text = raw.trim()
        if (settings.removeFillers) text = TextPostProcessor.removeFillers(text)
        bubble.setMode(service.idleMode())
        service.onDictationEnded()
        if (text.isEmpty()) {
            bubble.showPreview("Didn't catch that.", 1800)
            return
        }
        val node = target?.takeIf { it.refresh() } ?: if (retarget) service.findTarget() else null
        val result = inserter.insert(node, text, settings.insertMethod, settings.smartSpacing)
        when (result) {
            InsertResult.COPIED -> bubble.showPreview("No text field here. Copied to the clipboard.", 3000)
            else -> bubble.hidePreview()
        }
        if (node?.isPassword != true) app.history.add(text, node?.packageName?.toString() ?: targetPackage, result.name.lowercase())
        target = null
    }

    companion object {
        private const val MAX_MS = 5 * 60 * 1000L
    }
}
