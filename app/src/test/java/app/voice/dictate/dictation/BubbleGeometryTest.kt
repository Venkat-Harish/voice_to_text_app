package app.voice.dictate.dictation

import app.voice.dictate.data.BubbleSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleGeometryTest {
    // A 1080 × 2400 phone at ~2.6× density: 42 dp disc ≈ 110 px, 6 dp margin ≈ 16 px.
    private fun geo(keyboardTop: Int = 1500) = BubbleGeometry(
        screenW = 1080, screenH = 2400, diameter = 110, margin = 16, topLimit = 84,
        keyboardTop = keyboardTop, keyboardGap = 32,
    )

    @Test
    fun restsJustAboveTheKeyboardAtTheRightEdgeByDefault() {
        val c = geo().dockCenter(BubbleSide.RIGHT, -1f)
        assertEquals(1080 - 16 - 55, c.x)
        assertEquals(1500 - 32 - 55, c.y)
    }

    @Test
    fun savedHeightIsKeptClearOfTheKeyboard() {
        assertEquals(1500 - 32 - 55, geo().dockCenter(BubbleSide.RIGHT, 0.8f).y)
        // With no keyboard the same height is allowed.
        assertEquals(1920, geo(keyboardTop = -1).dockCenter(BubbleSide.RIGHT, 0.8f).y)
    }

    @Test
    fun staysBelowTheStatusBarAndOnTheChosenSide() {
        val c = geo().dockCenter(BubbleSide.LEFT, 0f)
        assertEquals(16 + 55, c.x)
        assertEquals(84 + 55, c.y)
    }

    @Test
    fun snapsToTheNearestEdge() {
        assertEquals(BubbleSide.LEFT, geo().sideFor(300))
        assertEquals(BubbleSide.RIGHT, geo().sideFor(800))
    }

    @Test
    fun tucksOnlyWhenPushedPastTheEdge() {
        assertTrue(geo().shouldTuck(20))
        assertTrue(geo().shouldTuck(1070))
        assertFalse(geo().shouldTuck(80))
        assertFalse(geo().shouldTuck(1009)) // the normal docked position
    }

    @Test
    fun fanOpensTowardTheMiddleWithMicAboveSpeaker() {
        val g = geo(keyboardTop = -1)
        val c = Pt(1009, 1200)
        val (mic, speaker) = g.fan(c, BubbleSide.RIGHT, 110)
        assertTrue(mic.x < c.x && speaker.x < c.x)
        assertTrue(mic.y < c.y && speaker.y > c.y)
        val (lMic, lSpeaker) = g.fan(Pt(71, 1200), BubbleSide.LEFT, 110)
        assertTrue(lMic.x > 71 && lSpeaker.x > 71)
    }

    @Test
    fun fanTiltsUpAboveTheKeyboard() {
        val g = geo()
        val c = g.dockCenter(BubbleSide.RIGHT, -1f)
        val (mic, speaker) = g.fan(c, BubbleSide.RIGHT, 110)
        assertEquals(c.y, speaker.y)
        assertTrue(mic.y < c.y)
        assertTrue(speaker.y + 55 <= g.bottomLimit)
    }

    @Test
    fun fanTiltsDownBelowTheStatusBar() {
        val g = geo()
        val c = g.dockCenter(BubbleSide.RIGHT, 0f)
        val (mic, speaker) = g.fan(c, BubbleSide.RIGHT, 110)
        assertEquals(c.y, mic.y)
        assertTrue(speaker.y > c.y)
        assertTrue(mic.y - 55 >= 84)
    }

    @Test
    fun collapsedWindowIsCentredOnTheDisc() {
        assertEquals(Box(1009 - 77, 1200 - 77, 1009 + 77, 1200 + 77), geo().collapsedBox(Pt(1009, 1200), 154))
    }

    @Test
    fun tuckedWindowKeepsItsSizeAndShowsOnlyTheStrip() {
        // Same 154 px window as the disc's, so tucking only moves it; just the 63 px strip is on screen.
        assertEquals(Box(1080 - 63, 1000 - 77, 1080 - 63 + 154, 1000 + 77), geo().tuckedBox(BubbleSide.RIGHT, 1000, 154, 63))
        assertEquals(Box(63 - 154, 1000 - 77, 63, 1000 + 77), geo().tuckedBox(BubbleSide.LEFT, 1000, 154, 63))
    }

    @Test
    fun menuSquareIsCentredOnTheDiscAndFitsEveryTilt() {
        val g = geo()
        for (c in listOf(g.dockCenter(BubbleSide.RIGHT, -1f), g.dockCenter(BubbleSide.RIGHT, 0f), Pt(1009, 1000))) {
            val box = g.menuBox(c, 110, 154, 31)
            assertEquals(c.x - box.left, box.right - c.x)
            assertEquals(c.y - box.top, box.bottom - c.y)
            val (mic, speaker) = g.fan(c, BubbleSide.RIGHT, 110)
            for (p in listOf(mic, speaker)) {
                assertTrue(p.x - 77 >= box.left && p.x + 77 <= box.right)
                assertTrue(p.y - 77 >= box.top && p.y + 77 <= box.bottom)
            }
        }
    }

    @Test
    fun liveTextGoesWhereThereIsRoom() {
        val g = geo()
        // Just above the keyboard: more room above.
        assertTrue(g.previewAbove(1358, 1468))
        // Near the top with no keyboard: below.
        assertFalse(geo(keyboardTop = -1).previewAbove(100, 210))
    }
}
