package app.voice.dictate.dictation

import app.voice.dictate.dictation.FullscreenDetector.Companion.TYPE_APPLICATION
import app.voice.dictate.dictation.FullscreenDetector.Companion.TYPE_SYSTEM
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullscreenDetectorTest {
    // Galaxy S24+: 1080 × 2340, status bar 84 px tall.
    private val w = 1080
    private val h = 2340
    private val statusBar = WindowSnapshot(TYPE_SYSTEM, 0, 0, 1080, 84, active = false)
    private val app = WindowSnapshot(TYPE_APPLICATION, 0, 0, 1080, 2340, active = true)
    private val keyboard = WindowSnapshot(2, 0, 1500, 1080, 2340, active = false) // TYPE_INPUT_METHOD
    private val edgeHandle = WindowSnapshot(TYPE_SYSTEM, 1045, 0, 1080, 2340, active = false) // Samsung edge strip
    private val shade = WindowSnapshot(TYPE_SYSTEM, 0, 0, 1080, 2340, active = true)

    @Test
    fun aNormalAppWithTheStatusBarIsNotFullScreen() {
        val d = FullscreenDetector()
        d.update(listOf(app, statusBar, keyboard), w, h)
        assertFalse(d.fullscreen)
    }

    @Test
    fun failsOpenWhenThePhoneNeverReportsAStatusBar() {
        val d = FullscreenDetector()
        d.update(listOf(app, keyboard, edgeHandle), w, h)
        assertFalse(d.fullscreen)
    }

    @Test
    fun theStatusBarDisappearingMeansFullScreen() {
        val d = FullscreenDetector()
        d.update(listOf(app, statusBar), w, h)
        assertTrue(d.update(listOf(app), w, h))
        assertTrue(d.fullscreen)
        assertTrue(d.update(listOf(app, statusBar), w, h))
        assertFalse(d.fullscreen)
    }

    @Test
    fun aSecondSignalMustAgreeBeforeHiding() {
        val d = FullscreenDetector()
        d.update(listOf(app, statusBar), w, h)
        // The window list says the bar is gone, but the display says it is still visible: stay visible.
        assertFalse(d.update(listOf(app), w, h) { false })
        assertFalse(d.fullscreen)
        assertTrue(d.update(listOf(app), w, h) { true })
    }

    @Test
    fun notFullScreenWhenNoAppIsInFront() {
        val d = FullscreenDetector()
        d.update(listOf(app, statusBar), w, h)
        // e.g. the notification shade or lock screen is in front.
        d.update(listOf(shade, app.copy(active = false)), w, h)
        assertFalse(d.fullscreen)
    }

    @Test
    fun onlyAShortFullWidthTopWindowCountsAsTheStatusBar() {
        assertTrue(FullscreenDetector.isStatusBar(statusBar, w, h))
        assertFalse(FullscreenDetector.isStatusBar(edgeHandle, w, h))
        assertFalse(FullscreenDetector.isStatusBar(shade, w, h))
        assertFalse(FullscreenDetector.isStatusBar(statusBar.copy(type = TYPE_APPLICATION), w, h))
    }
}
