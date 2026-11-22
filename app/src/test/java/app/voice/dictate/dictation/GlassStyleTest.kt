package app.voice.dictate.dictation

import app.voice.dictate.data.BubblePalette
import app.voice.dictate.data.legacyBubbleSizeDp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassStyleTest {
    private fun alpha(c: Int) = GlassStyle.alphaOf(c)
    private fun rgb(c: Int) = c and 0xFFFFFF

    @Test
    fun defaultIsTodaysGraphiteGlass() {
        val s = GlassStyle()
        assertFalse(s.light)
        assertEquals(0x1C1C1E, rgb(s.fill))
        assertEquals(179, alpha(s.fill)) // 70 %, on the slider's 5 % steps
        assertEquals(0xFFFFFF, rgb(s.icon)) // white marks on dark glass
    }

    @Test
    fun lightTintsGetDarkMarks() {
        val frost = GlassStyle(0xFFF2F2F7.toInt())
        assertTrue(frost.light)
        assertEquals(0x1C1C1E, rgb(frost.icon))
        assertEquals(0x1C1C1E, rgb(frost.pillText))
        val sand = GlassStyle(0xFFE6DCCB.toInt())
        assertTrue(sand.light)
    }

    @Test
    fun midTonesGetWhicheverMarksContrastBetter() {
        // WCAG: dark marks win above a luminance of about 0.2.
        assertTrue(GlassStyle.prefersDarkMarks(0xFF9E9E9E.toInt()))
        assertTrue(GlassStyle.prefersDarkMarks(0xFF66BB6A.toInt()))
        assertTrue(GlassStyle.prefersDarkMarks(0xFF4DB6AC.toInt()))
        assertFalse(GlassStyle.prefersDarkMarks(0xFF555555.toInt()))
        for (sw in BubblePalette.SWATCHES.filter { it.name != "Frost" && it.name != "Sand" }) {
            assertFalse("${sw.name} should get white marks", GlassStyle.prefersDarkMarks(sw.color))
        }
    }

    @Test
    fun opacityDrivesTheFillButNeverTheIcons() {
        val thin = GlassStyle(opacity = 0.3f)
        val solid = GlassStyle(opacity = 1f)
        assertEquals(77, alpha(thin.fill))
        assertEquals(255, alpha(solid.fill))
        assertEquals(alpha(thin.icon), alpha(solid.icon))
        // Below the minimum it is clamped, so the bubble never disappears.
        assertEquals(alpha(GlassStyle(opacity = 0.05f).fill), alpha(thin.fill))
    }

    @Test
    fun liveTextAndTuckedTabStayNearlySolid() {
        val thin = GlassStyle(opacity = 0.3f)
        assertTrue(alpha(thin.pill) >= 219)
        assertTrue(alpha(thin.tabFill) >= 216)
    }

    @Test
    fun scaleAlphaKeepsTheColour() {
        val c = GlassStyle.argb(1f, 0x123456)
        val half = GlassStyle.scaleAlpha(c, 0.5f)
        assertEquals(0x123456, rgb(half))
        assertEquals(128, alpha(half))
    }

    @Test
    fun presetsAreMutedAndUnique() {
        val names = BubblePalette.SWATCHES.map { it.name }
        assertEquals(names.size, names.toSet().size)
        assertEquals("Graphite", BubblePalette.nameOf(GlassStyle.GRAPHITE))
        for (sw in BubblePalette.SWATCHES) {
            val r = (sw.color shr 16) and 0xFF
            val g = (sw.color shr 8) and 0xFF
            val b = sw.color and 0xFF
            val chroma = (maxOf(r, g, b) - minOf(r, g, b)) / 255.0
            assertTrue("${sw.name} is too bright a colour ($chroma)", chroma <= 0.3)
        }
        assertNull(BubblePalette.nameOf(0xFFFF0000.toInt()))
    }

    @Test
    fun oldSizeChoiceCarriesOver() {
        assertEquals(36, legacyBubbleSizeDp("SMALL"))
        assertEquals(42, legacyBubbleSizeDp("MEDIUM"))
        assertEquals(50, legacyBubbleSizeDp("LARGE"))
        assertNull(legacyBubbleSizeDp(null))
        assertNull(legacyBubbleSizeDp("HUGE"))
    }
}
