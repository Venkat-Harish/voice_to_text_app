package app.voice.dictate.dictation

/** One window as the accessibility service reports it, reduced to what full-screen detection needs. */
data class WindowSnapshot(
    /** An AccessibilityWindowInfo.TYPE_* value. */
    val type: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    /** The window the user is interacting with. */
    val active: Boolean,
)

/**
 * Decides whether the app in front is full screen (immersive video, games, photo viewers) from the
 * accessibility window list: the status bar is a system window hugging the top of the screen, and an app
 * that hides it removes it from the list.
 *
 * Fail-open: it only reports full screen after it has seen a status bar and then sees it gone while an app
 * window is in front. A phone that never reports a status bar never hides the bubble.
 * (The old approach read the status bar's visibility from a 1 px overlay window, but Android 12+ never gives
 * overlays above the status bar that information, so every app looked full screen.)
 */
class FullscreenDetector {
    private var sawStatusBar = false

    var fullscreen = false
        private set

    /**
     * Updates from the current window list; returns true if [fullscreen] changed.
     * [confirmHidden] is a second, independent check that the status bar really is hidden; it is only asked when
     * the window list already says so.
     */
    fun update(windows: List<WindowSnapshot>, screenW: Int, screenH: Int, confirmHidden: () -> Boolean = { true }): Boolean {
        val statusBar = windows.any { isStatusBar(it, screenW, screenH) }
        if (statusBar) sawStatusBar = true
        val appInFront = windows.any { it.type == TYPE_APPLICATION && it.active }
        val now = sawStatusBar && !statusBar && appInFront && confirmHidden()
        if (now == fullscreen) return false
        fullscreen = now
        return true
    }

    companion object {
        /** AccessibilityWindowInfo.TYPE_APPLICATION / TYPE_SYSTEM. */
        const val TYPE_APPLICATION = 1
        const val TYPE_SYSTEM = 3

        /** A system window along the top edge, nearly full width and short: the status bar. */
        fun isStatusBar(w: WindowSnapshot, screenW: Int, screenH: Int): Boolean {
            val height = w.bottom - w.top
            return w.type == TYPE_SYSTEM && w.top <= 0 && height > 0 && height <= screenH * 0.12 &&
                w.right - w.left >= screenW * 0.9
        }
    }
}
