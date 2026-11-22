package app.voice.dictate.dictation

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat

/**
 * A round glass button in the bubble's menu (mic or speaker), the same size and glass as the bubble.
 * ACTIVE shows a stop square (the speaker while reading aloud); LOADING a thin spinner.
 */
@SuppressLint("ViewConstructor")
class MenuButtonView(
    context: Context,
    diameterPx: Int,
    @DrawableRes iconRes: Int,
    private val label: String,
    private val activeLabel: String = label,
) : View(context) {
    enum class Mode { IDLE, LOADING, ACTIVE }

    private val dp = resources.displayMetrics.density
    private val glass = GlassPainter(dp)
    private val icon: Drawable = ContextCompat.getDrawable(context, iconRes)!!.mutate()
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GlassStyle().icon }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setStyle(Paint.Style.STROKE)
        strokeCap = Paint.Cap.ROUND
        color = GlassStyle().icon
    }
    private val rect = RectF()
    private var spin = 0f

    var diameter = diameterPx
        set(value) { field = value; requestLayout(); invalidate() }

    /** Tint and opacity of the glass (Settings → Bubble). */
    var style: GlassStyle = GlassStyle()
        set(value) {
            if (field == value) return
            field = value
            glass.style = value
            mark.color = value.icon
            arc.color = value.icon
            invalidate()
        }

    var mode = Mode.IDLE
        set(value) {
            if (field == value) return
            field = value
            if (value == Mode.LOADING) spinner.start() else spinner.cancel()
            contentDescription = if (value == Mode.IDLE) label else activeLabel
            invalidate()
        }

    private val spinner = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { spin = it.animatedValue as Float; invalidate() }
    }

    val pad: Int get() = maxOf(glass.shadowPad, (diameter * 0.2f).toInt())
    val size: Int get() = diameter + pad * 2

    init {
        GlassPainter.prepare(this)
        contentDescription = label
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(size, size)

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = diameter / 2f
        glass.circle(canvas, cx, cy, r)
        when (mode) {
            Mode.ACTIVE -> {
                val s = r * 0.3f
                rect.set(cx - s, cy - s, cx + s, cy + s)
                canvas.drawRoundRect(rect, s * 0.35f, s * 0.35f, mark)
            }
            Mode.LOADING -> {
                drawIcon(canvas, cx, cy, r, style.iconDim, 0.85f)
                arc.strokeWidth = 2f * dp
                val a = r * 0.66f
                rect.set(cx - a, cy - a, cx + a, cy + a)
                canvas.drawArc(rect, spin, 100f, false, arc)
            }
            Mode.IDLE -> drawIcon(canvas, cx, cy, r, style.icon, 1f)
        }
    }

    private fun drawIcon(canvas: Canvas, cx: Float, cy: Float, r: Float, tint: Int, scale: Float) {
        val half = (r * 0.5f * scale).toInt()
        icon.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        icon.setTint(tint)
        icon.draw(canvas)
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onDetachedFromWindow() {
        spinner.cancel()
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (mode == Mode.LOADING) spinner.start()
    }
}
