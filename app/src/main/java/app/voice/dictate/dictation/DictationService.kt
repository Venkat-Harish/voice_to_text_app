package app.voice.dictate.dictation

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.voice.dictate.ClipboardGrabActivity
import app.voice.dictate.MainActivity
import app.voice.dictate.R
import app.voice.dictate.VoiceApp
import app.voice.dictate.asr.AsrStatus
import app.voice.dictate.asr.EngineKind
import app.voice.dictate.asr.ModelCatalog
import app.voice.dictate.data.BubbleSide
import app.voice.dictate.tts.TtsStatus
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The "Voice bubble" accessibility service. While you type it shows a small glass bubble by the keyboard,
 * records while you hold it (or after you tap its mic), and types the text into whichever field has focus.
 * It reads the window content only to find that field, insert at its cursor, and see where the keyboard is.
 */
class DictationService : AccessibilityService(), BubbleController.Callbacks {
    private val app get() = VoiceApp.instance
    private val scope = MainScope()
    private lateinit var bubble: BubbleController
    private lateinit var controller: DictationController
    private lateinit var screen: ScreenWatcher

    private var lastEditable: AccessibilityNodeInfo? = null
    private var editableFocused = false
    private var passwordFocused = false
    /** The keyboard (input method) window is on screen, and where its top edge is. */
    private var keyboardOpen = false
    private var keyboardTop = -1
    /** Some phones never report the keyboard window; until one is seen, a focused text field counts as typing. */
    private var sawKeyboard = false

    /** Set once Android has blocked background mic capture on this phone. */
    var micForegroundNeeded = false
    private var micForeground = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        bubble = BubbleController(this, this)
        controller = DictationController(this, bubble)
        screen = ScreenWatcher(this) { onScreenStateChanged() }
        screen.start()
        running.value = true

        val s = app.settings.value
        applyBubbleSettings(s)
        bubble.setDock(s.bubbleSide, s.bubbleYFraction, s.bubbleTucked)
        scope.launch {
            app.settings.flow.collect {
                applyBubbleSettings(it)
                bubble.setDock(it.bubbleSide, it.bubbleYFraction, it.bubbleTucked)
                updateVisibility()
            }
        }
        // While reading aloud the bubble pulses a speaker, and the menu's speaker becomes a stop button.
        // The bubble stays on screen while reading (even if the keyboard closes), so it can always be stopped.
        scope.launch {
            app.readAloud.state.collect { t ->
                bubble.setReading(t.reading, t.reading && t.status == TtsStatus.LOADING)
                updateVisibility()
            }
        }
        // Read-aloud errors ("Couldn't load the voice…") show by the bubble.
        scope.launch { app.readAloud.messages.collect { bubble.showPreview(it, 3500) } }
        // A dimmed bubble with a spinner while the on-device model loads.
        scope.launch {
            combine(app.asr.state, app.settings.flow) { asr, settings -> asr to settings.modelId }.collect {
                if (!controller.active) bubble.setMode(idleMode())
            }
        }
        app.loadSelected()
        refreshInput()
        updateVisibility()
    }

    private fun applyBubbleSettings(s: app.voice.dictate.data.Settings) {
        bubble.setSize(s.bubbleSizeDp)
        bubble.setStyle(GlassStyle(s.bubbleColor, s.bubbleGlassOpacity))
        bubble.setIdleOpacity(s.bubbleIdleOpacity)
        bubble.setMenuSpeaker(s.speakerButton)
        bubble.hapticsEnabled = s.haptics
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (!::bubble.isInitialized) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val src = event.source
                if (src != null && src.isEditable) {
                    lastEditable = src
                    editableFocused = true
                    passwordFocused = src.isPassword
                    // A text field was tapped: reload the speech model if it was freed while idle.
                    if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) wakeSpeechModel()
                } else if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
                    editableFocused = false
                    passwordFocused = false
                }
                if (event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) refreshInput()
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                refreshInput()
                screen.refreshLock()
            }
            else -> Unit
        }
        updateVisibility()
    }

    /** Reads whether the keyboard is open (and its top edge), whether an app is full screen, and what kind of field has focus. */
    private fun refreshInput() {
        // If the window list can't be read this time, keep what we knew: "unknown" is not "keyboard closed".
        val all = runCatching { windows }.getOrNull()
        var keyboardOpened = false
        if (all != null) {
            val metrics = resources.displayMetrics
            screen.updateWindows(
                all.map { w ->
                    val r = Rect()
                    w.getBoundsInScreen(r)
                    WindowSnapshot(w.type, r.left, r.top, r.right, r.bottom, w.isActive)
                },
                metrics.widthPixels,
                metrics.heightPixels,
            )
            val keyboard = all.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
            if (keyboard != null) sawKeyboard = true
            keyboardOpened = keyboard != null && !keyboardOpen
            keyboardOpen = keyboard != null
            keyboardTop = keyboard?.let { k ->
                val r = Rect()
                k.getBoundsInScreen(r)
                // Only a keyboard in the lower part of the screen counts (not a floating or odd report).
                r.top.takeIf { it > metrics.heightPixels * 0.35f }
            } ?: -1
        }
        val focus = runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
        editableFocused = focus?.isEditable == true
        passwordFocused = focus?.isPassword == true
        bubble.setKeyboardTop(keyboardTop)
        // The keyboard just opened: reload the speech model if it was freed while idle.
        if (keyboardOpened) wakeSpeechModel()
    }

    /** Typing = the keyboard is open (or, on phones that never report it, a text field has focus). */
    private val typing get() = if (sawKeyboard) keyboardOpen else editableFocused

    private fun onScreenStateChanged() {
        // Never record behind the lock screen: finish what was said, and type it once you unlock.
        if (screen.locked && controller.active) controller.stop()
        if (!screen.locked) controller.deliverPending()
        updateVisibility()
    }

    /** True while the phone is locked (the screen is off or the lock screen shows). */
    val screenLocked: Boolean get() = ::screen.isInitialized && screen.locked

    /** A dictation ended: re-check visibility (the keyboard may have closed), leaving time to read any message. */
    fun onDictationEnded() {
        scope.launch {
            delay(3000)
            if (!controller.active) updateVisibility()
        }
    }

    /** How the bubble looks when not recording: grey with a spinner while the on-device model loads. */
    fun idleMode(): BubbleView.Mode {
        val asr = app.asr.state.value
        val onDevice = ModelCatalog.byId(app.settings.value.modelId)?.engine != EngineKind.SYSTEM
        return when {
            !onDevice -> BubbleView.Mode.IDLE
            asr.status == AsrStatus.LOADING -> BubbleView.Mode.LOADING
            asr.status == AsrStatus.ERROR -> BubbleView.Mode.UNAVAILABLE
            else -> BubbleView.Mode.IDLE
        }
    }

    /** The field the text should go into: the focused input, or the last editable field that had focus. */
    fun findTarget(): AccessibilityNodeInfo? {
        runCatching { findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()?.let { if (it.isEditable) return it }
        lastEditable?.let { if (it.refresh() && it.isFocused && it.isEditable) return it }
        return null
    }

    private fun updateVisibility() {
        if (!::bubble.isInitialized) return
        val s = app.settings.value
        val state = when {
            !s.bubbleEnabled -> BubbleStatus.TURNED_OFF
            screen.locked -> BubbleStatus.LOCKED
            controller.active -> BubbleStatus.SHOWING // keep it while a dictation runs, even if the keyboard closes
            app.readAloud.state.value.reading -> BubbleStatus.SHOWING // and while reading aloud: it holds the stop button
            passwordFocused -> BubbleStatus.PASSWORD
            s.bubbleHideFullscreen && screen.fullscreen -> BubbleStatus.FULLSCREEN
            s.bubbleOnlyWhenTyping && !typing -> BubbleStatus.WAITING_FOR_KEYBOARD
            else -> BubbleStatus.SHOWING
        }
        // Turning the bubble off removes its stop button, so finish any dictation now.
        if (state == BubbleStatus.TURNED_OFF && controller.active) controller.stop()
        bubble.setVisible(state == BubbleStatus.SHOWING)
        status.value = state
    }

    // Bubble gestures
    override fun onHoldStart() = controller.onHoldStart()
    override fun onHoldEnd() = controller.onHoldEnd()
    override fun onHandsFree() = controller.onHandsFree()
    override fun onStopTap() = controller.onStopTap()
    override fun onDockChanged(side: BubbleSide, yFraction: Float, tucked: Boolean) =
        app.settings.setBubbleDock(side, yFraction, tucked)
    override fun onBubbleTouched() = wakeSpeechModel()

    /** Typing may lead to dictating: load the speech model if it was freed, and restart its idle countdown. */
    private fun wakeSpeechModel() {
        if (!app.settings.value.bubbleEnabled || passwordFocused || screenLocked) return
        app.wakeSpeechModel()
    }

    /**
     * Speaker button: stops reading if it is reading. Otherwise reads the text selected in the focused field,
     * or else whatever is on the clipboard, straight away.
     */
    override fun onSpeaker() {
        if (app.readAloud.state.value.reading) {
            app.readAloud.stop()
            return
        }
        selectedText()?.let { app.readAloud.speak(it); return }
        app.readAloud.prepare()
        // Android 10+ lets only the focused app read the clipboard, so an invisible activity reads it for us.
        val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                val text = resultData?.getString(ClipboardGrabActivity.KEY_TEXT)
                when {
                    resultCode == ClipboardGrabActivity.RESULT_TEXT && !text.isNullOrBlank() && app.readAloud.speak(text) -> Unit
                    resultCode == ClipboardGrabActivity.RESULT_FAILED -> bubble.showPreview("Couldn't read the clipboard. Tap the speaker again.", 2500)
                    else -> bubble.showPreview("Copy some text first, then tap the speaker.", 2500)
                }
            }
        }
        val intent = Intent(this, ClipboardGrabActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            .putExtra(ClipboardGrabActivity.EXTRA_RECEIVER, receiver)
        runCatching { startActivity(intent) }.onFailure {
            Log.w(TAG, "Couldn't read the clipboard", it)
            bubble.showPreview("Couldn't read the clipboard.", 2500)
        }
    }

    /** The text selected in the focused field, if any (never from a password field). */
    private fun selectedText(): String? {
        val node = findTarget() ?: return null
        if (!node.refresh() || node.isPassword) return null
        val raw = node.text?.toString().orEmpty()
        val showingHint = node.isShowingHintText || (raw.isNotEmpty() && raw == node.hintText?.toString())
        if (showingHint || raw.isBlank()) return null
        val a = node.textSelectionStart
        val b = node.textSelectionEnd
        if (a !in 0..raw.length || b !in 0..raw.length || a == b) return null
        return raw.substring(minOf(a, b), maxOf(a, b)).takeIf { it.isNotBlank() }
    }

    /** Holds a "microphone" foreground state while recording, for phones that silence background capture. */
    fun startMicForeground() {
        if (micForeground) return
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n: Notification = NotificationCompat.Builder(this, VoiceApp.CHANNEL_DICTATION)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Voice is listening")
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .build()
        try {
            val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            ServiceCompat.startForeground(this, MIC_NOTIFICATION, n, type)
            micForeground = true
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't enter the foreground for the microphone", e)
        }
    }

    fun stopMicForeground() {
        if (!micForeground) return
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        micForeground = false
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::bubble.isInitialized) bubble.onScreenChanged()
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        if (::controller.isInitialized) controller.cancel()
        if (::bubble.isInitialized) bubble.destroy()
        if (::screen.isInitialized) screen.stop()
        status.value = BubbleStatus.SERVICE_OFF
        scope.cancel()
        running.value = false
    }

    companion object {
        private const val TAG = "DictationService"
        private const val MIC_NOTIFICATION = 4200

        private val running = MutableStateFlow(false)
        /** True while the service is connected (the bubble can be shown). */
        val isRunning: StateFlow<Boolean> = running

        private val status = MutableStateFlow(BubbleStatus.SERVICE_OFF)
        /** Whether the bubble is on screen right now, and if not, why (shown in Settings). */
        val bubbleStatus: StateFlow<BubbleStatus> = status

        /** Whether the user has turned the service on in Accessibility settings. */
        fun isEnabled(context: Context): Boolean {
            val me = ComponentName(context, DictationService::class.java)
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}

/** Why the bubble is (or isn't) on screen, for the status line in Settings. */
enum class BubbleStatus(val label: String) {
    SHOWING("Showing"),
    WAITING_FOR_KEYBOARD("Hidden until a keyboard opens"),
    FULLSCREEN("Hidden: an app is full screen"),
    PASSWORD("Hidden: password field"),
    LOCKED("Hidden: phone locked"),
    TURNED_OFF("Turned off"),
    SERVICE_OFF("Service off: turn on Voice bubble in Accessibility"),
}
