package app.voice.dictate.dictation

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.View
import kotlin.math.roundToInt

/**
 * The bubble tucked into the screen edge: a thin glass tab, drawn sharp at any screen density (whole-pixel
 * edges, no blurred shadow, nearly solid). Tap or drag it to bring the bubble back.
 */
@SuppressLint("ViewConstructor")
class EdgeTabView(context: Context) : View(context) {
    private val dp = resources.displayMetrics.density
    private val glass = GlassPainter(dp)

    /** Which screen edge the tab hugs. */
    var onRight = true
        set(value) { if (field != value) { field = value; invalidate() } }

    /** Tint of the tab (the bubble's glass colour). */
    var style: GlassStyle = GlassStyle()
        set(value) { if (field != value) { field = value; glass.style = value; invalidate() } }

    // Whole pixels, so the edges never land between pixels (that is what made the old tab look soft).
    private val tabW = (5 * dp).roundToInt().coerceAtLeast(4)
    private val tabH = (40 * dp).roundToInt()
    private val inset = (3 * dp).roundToInt()

    /** The on-screen strip: wider than the visible tab so it is easy to grab. */
    val viewWidth: Int = (24 * dp).roundToInt()
    val viewHeight: Int = tabH + (20 * dp).roundToInt()

    init { contentDescription = "Show the Voice bubble" }

    // The bubble window decides the height (it keeps the disc's size while tucked), so honour exact sizes.
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(resolveSize(viewWidth, widthMeasureSpec), resolveSize(viewHeight, heightMeasureSpec))

    override fun onDraw(canvas: Canvas) {
        val h = minOf(tabH, height - 2 * inset).coerceAtLeast(tabW)
        val left = if (onRight) width - inset - tabW else inset
        val top = (height - h) / 2
        glass.crispPill(canvas, left, top, left + tabW, top + h)
    }

    override fun performClick(): Boolean = super.performClick()
}
