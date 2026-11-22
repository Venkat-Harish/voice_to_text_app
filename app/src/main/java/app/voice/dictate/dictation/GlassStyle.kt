package app.voice.dictate.dictation

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The bubble's glass colours, derived from a tint and an opacity. Pure Kotlin (ARGB ints), so it is
 * unit-tested. Dark tints get white marks; light tints (frost, sand) get dark marks, so the mic and the
 * live text stay readable whatever colour you pick.
 *
 * @param color the tint (alpha is ignored).
 * @param opacity how solid the glass fill is, 0.3..1 (icons and text always stay solid).
 */
data class GlassStyle(
    val color: Int = GRAPHITE,
    val opacity: Float = DEFAULT_OPACITY,
) {
    private val rgb = color and 0xFFFFFF
    private val glassAlpha = opacity.coerceIn(MIN_OPACITY, 1f)

    /** Tints where dark marks contrast better than white ones (WCAG): they get dark icons and text. */
    val light: Boolean = prefersDarkMarks(color)

    val fill: Int = argb(glassAlpha, rgb)
    val highlight: Int = argb(if (light) 0.32f else 0.20f, WHITE)
    val rim: Int = argb(if (light) 0.55f else 0.22f, WHITE)
    val hairline: Int = argb(if (light) 0.22f else 0.12f, BLACK)
    val shadow: Int = argb(0.18f, BLACK)

    /** The mic, speaker, stop square and waveform. */
    val icon: Int = if (light) argb(0.88f, INK) else argb(0.92f, WHITE)
    val iconDim: Int = if (light) argb(0.40f, INK) else argb(0.43f, WHITE)
    /** The faint halo that breathes with your voice. */
    val halo: Int = if (light) argb(0.16f, INK) else argb(0.14f, WHITE)

    /** Live-text pill: the same tint, nearly solid so the words stay readable. */
    val pill: Int = argb(maxOf(glassAlpha, 0.86f), rgb)
    val pillRim: Int = if (light) argb(0.12f, BLACK) else argb(0.12f, WHITE)
    val pillText: Int = if (light) argb(0.92f, INK) else argb(0.95f, WHITE)

    /** Tucked tab: crisp and nearly solid, with a light rim and a dark outline so it shows on any screen. */
    val tabFill: Int = argb(maxOf(glassAlpha, TAB_MIN_FILL), rgb)
    val tabRim: Int = if (light) argb(0.75f, WHITE) else argb(0.40f, WHITE)
    val tabOutline: Int = if (light) argb(0.30f, BLACK) else argb(0.50f, BLACK)

    companion object {
        const val GRAPHITE = 0xFF1C1C1E.toInt()
        /** On the Settings slider's 5 % steps; looks like the original 62 % glass did with its shadow underneath. */
        const val DEFAULT_OPACITY = 0.7f
        const val MIN_OPACITY = 0.3f
        /** The tucked tab is never drawn thinner than this. */
        const val TAB_MIN_FILL = 0.85f
        private const val WHITE = 0xFFFFFF
        private const val BLACK = 0x000000
        private const val INK = 0x1C1C1E

        private val INK_LUMINANCE = luminance(INK)

        /**
         * True when dark ink marks contrast better with [color] than white ones (WCAG 2 contrast ratio), which
         * happens above a luminance of about 0.2. Mid-tones like #9E9E9E or #66BB6A get dark marks.
         */
        fun prefersDarkMarks(color: Int): Boolean {
            val l = luminance(color) + 0.05
            return l * l > 1.05 * (INK_LUMINANCE + 0.05)
        }

        /** WCAG relative luminance (0 = black, 1 = white) of an RGB colour. */
        fun luminance(color: Int): Double {
            fun channel(shift: Int): Double {
                val c = ((color shr shift) and 0xFF) / 255.0
                return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
        }

        /** An ARGB colour from an alpha (0..1) and an RGB value. */
        fun argb(alpha: Float, rgb: Int): Int = ((alpha.coerceIn(0f, 1f) * 255).roundToInt() shl 24) or (rgb and 0xFFFFFF)

        fun alphaOf(color: Int): Int = (color ushr 24) and 0xFF

        /** [color] with its alpha multiplied by [k]. */
        fun scaleAlpha(color: Int, k: Float): Int =
            ((alphaOf(color) * k.coerceIn(0f, 1f)).roundToInt() shl 24) or (color and 0xFFFFFF)
    }
}
