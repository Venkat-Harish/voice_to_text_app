package app.voice.dictate.dictation

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Build
import android.view.WindowInsets
import android.view.WindowManager
import androidx.core.content.ContextCompat

/**
 * Tells the bubble when to stay out of sight:
 * - **locked**: the screen is off or the lock screen is showing (overlays would otherwise draw over it);
 * - **fullscreen**: the app in front has hidden the status bar (video, games, photo viewers), read from the
 *   accessibility window list by [FullscreenDetector].
 */
class ScreenWatcher(private val context: Context, private val onChange: () -> Unit) {
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val wm = context.getSystemService(WindowManager::class.java)
    private val fullscreenDetector = FullscreenDetector()
    private val main = Handler(Looper.getMainLooper())

    var locked = isLockedNow()
        private set
    val fullscreen: Boolean get() = fullscreenDetector.fullscreen

    private var started = false

    private val recheck = Runnable { refreshLock() }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> setLocked(true)
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                    refreshLock()
                    // USER_PRESENT can arrive while the lock screen is still animating away: look again shortly.
                    main.removeCallbacks(recheck)
                    main.postDelayed(recheck, 600)
                }
            }
        }
    }

    fun start() {
        if (started) return
        started = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        // These are protected broadcasts only the platform can send. USER_PRESENT comes from System UI, which runs
        // under an app user id, so the receiver must be exported for it to arrive.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    fun stop() {
        if (!started) return
        started = false
        main.removeCallbacks(recheck)
        runCatching { context.unregisterReceiver(receiver) }
    }

    /** Re-checks the lock screen (called on window changes, so unlocking with a fingerprint is noticed too). */
    fun refreshLock() = setLocked(isLockedNow())

    /** Re-checks full screen from the current accessibility window list. */
    fun updateWindows(windows: List<WindowSnapshot>, screenW: Int, screenH: Int) {
        if (fullscreenDetector.update(windows, screenW, screenH, ::statusBarHiddenOnDisplay)) onChange()
    }

    /**
     * The display-wide status bar state (not filtered by window order, unlike an overlay's own insets).
     * Before Android 11 there is no such signal, so the window list decides alone. Fail-open on errors.
     */
    private fun statusBarHiddenOnDisplay(): Boolean {
        if (Build.VERSION.SDK_INT < 30) return true
        return runCatching {
            val insets = wm.currentWindowMetrics.windowInsets
            val bar = insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars())
            val exists = bar.top > 0 || bar.bottom > 0 || bar.left > 0 || bar.right > 0
            exists && !insets.isVisible(WindowInsets.Type.statusBars())
        }.getOrDefault(false)
    }

    private fun isLockedNow() = !power.isInteractive || keyguard.isKeyguardLocked

    private fun setLocked(value: Boolean) {
        if (value == locked) return
        locked = value
        onChange()
    }
}
