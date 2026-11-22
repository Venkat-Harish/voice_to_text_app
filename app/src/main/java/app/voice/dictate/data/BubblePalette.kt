package app.voice.dictate.data

import app.voice.dictate.dictation.GlassStyle

/** A named bubble tint. */
data class BubbleSwatch(val name: String, val color: Int)

/**
 * The bubble's preset tints: deep, low-key glass colours (nothing bright), plus two light glasses.
 * The custom colour picker in Settings can make anything else.
 */
object BubblePalette {
    val SWATCHES = listOf(
        BubbleSwatch("Graphite", GlassStyle.GRAPHITE),
        BubbleSwatch("Slate", 0xFF2E3A46.toInt()),
        BubbleSwatch("Smoke", 0xFF55565C.toInt()),
        BubbleSwatch("Midnight", 0xFF1B2340.toInt()),
        BubbleSwatch("Ocean", 0xFF0E3A45.toInt()),
        BubbleSwatch("Forest", 0xFF1E3A2B.toInt()),
        BubbleSwatch("Olive", 0xFF3A3A23.toInt()),
        BubbleSwatch("Plum", 0xFF3B2342.toInt()),
        BubbleSwatch("Wine", 0xFF4A1F2B.toInt()),
        BubbleSwatch("Cocoa", 0xFF3E2B22.toInt()),
        BubbleSwatch("Frost", 0xFFF2F2F7.toInt()),
        BubbleSwatch("Sand", 0xFFE6DCCB.toInt()),
    )

    fun nameOf(color: Int): String? = SWATCHES.firstOrNull { (it.color and 0xFFFFFF) == (color and 0xFFFFFF) }?.name
}

/** Bubble size limits (dp) for the Settings slider. */
const val BUBBLE_SIZE_MIN = 32
const val BUBBLE_SIZE_MAX = 64
const val BUBBLE_SIZE_DEFAULT = 42

/** Maps the old Small / Medium / Large choice to a size in dp, so a saved choice carries over. */
fun legacyBubbleSizeDp(name: String?): Int? = when (name) {
    "SMALL" -> 36
    "MEDIUM" -> 42
    "LARGE" -> 50
    else -> null
}
