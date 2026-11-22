package app.voice.dictate.dictation

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import app.voice.dictate.R
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * The floating bubble: a small disc of dark smoky glass with white marks.
 * Idle = mic; listening = a live waveform with a faint halo that breathes with your voice; hands-free =
 * a stop square; finishing / loading = a thin spinner; reading aloud = a pulsing speaker; menu open = ×.
 */
@SuppressLint("ViewConstructor")
class BubbleView(context: Context, diameterPx: Int) : View(context) {
    enum class Mode { IDLE, LOADING, LISTENING, LOCKED, FINISHING, UNAVAILABLE }

    private val dp = resources.displayMetrics.density
    private val glass = GlassPainter(dp)

    var diameter = diameterPx
        set(value) { field = value; requestLayout(); invalidate() }

    var mode = Mode.IDLE
        set(value) {
            if (field == value) return
            field = value
            if (value == Mode.LOADING || value == Mode.FINISHING) spinner.start() else spinner.cancel()
            if (!listening) { level = 0f; shown = 0f }
            describe()
            invalidate()
        }

    /** Read aloud is playing: the idle bubble shows a speaker instead of the mic. */
    var reading = false
        set(value) { if (field != value) { field = value; describe(); invalidate() } }

    /** The mic / speaker menu is open (for TalkBack; the look follows [menuProgress]). */
    var expanded = false
        set(value) { if (field != value) { field = value; describe() } }

    /**
     * 0 = the bubble's own icon (the mic), 1 = the close mark. BubbleController animates it as the menu opens and
     * closes, so the mic and the × morph into each other instead of swapping.
     */
    var menuProgress = 0f
        set(value) {
            val v = value.coerceIn(0f, 1f)
            if (field != v) { field = v; invalidate() }
        }

    /** Tint and opacity of the glass (Settings → Bubble). */
    var style: GlassStyle = GlassStyle()
        set(value) {
            if (field == value) return
            field = value
            glass.style = value
            halo.color = value.halo
            mark.color = value.icon
            stroke.color = value.icon
            invalidate()
        }

    /** While dragging past the edge: the bubble dims to say "let go to tuck it away". */
    var tuckHint = false
        set(value) { if (field != value) { field = value; invalidate() } }

    private val listening get() = mode == Mode.LISTENING || mode == Mode.LOCKED

    private var level = 0f
    private var shown = 0f
    private var spin = 0f

    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GlassStyle().halo }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = GlassStyle().icon }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setStyle(Paint.Style.STROKE)
        strokeCap = Paint.Cap.ROUND
        color = GlassStyle().icon
    }
    private val mic: Drawable = ContextCompat.getDrawable(context, R.drawable.ic_mic)!!.mutate()
    private val speaker: Drawable = ContextCompat.getDrawable(context, R.drawable.ic_speaker)!!.mutate()
    private val rect = RectF()

    private val spinner = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { spin = it.animatedValue as Float; invalidate() }
    }

    /** Room around the disc for the shadow and the voice halo. */
    val pad: Int get() = maxOf(glass.shadowPad, (diameter * 0.2f).toInt())

    /** Width and height of this view. */
    val size: Int get() = diameter + pad * 2

    init {
        GlassPainter.prepare(this)
        describe()
    }

    fun setLevel(value: Float) {
        level = value
        if (listening) invalidate()
    }

    private fun describe() {
        contentDescription = when {
            expanded -> "Close the Voice menu"
            mode == Mode.LOCKED -> "Voice is listening. Double-tap to finish"
            mode == Mode.LISTENING -> "Voice is listening"
            else -> "Voice: double-tap for dictation and read aloud"
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension(size, size)

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = diameter / 2f

        if (listening) {
            // Smooth the mic level, then let a faint halo grow with it.
            shown += (level - shown) * 0.3f
            canvas.drawCircle(cx, cy, min(r * (1f + min(0.35f, shown * 5f)), cx), halo)
        }
        glass.circle(canvas, cx, cy, r, if (tuckHint) 0.5f else 1f)

        when {
            mode == Mode.LOCKED -> {
                val s = r * 0.32f
                rect.set(cx - s, cy - s, cx + s, cy + s)
                canvas.drawRoundRect(rect, s * 0.35f, s * 0.35f, mark)
                if (abs(level - shown) > 0.001f) postInvalidateOnAnimation()
            }
            mode == Mode.LISTENING -> {
                drawBars(canvas, cx, cy, r)
                postInvalidateOnAnimation()
            }
            mode == Mode.FINISHING -> {
                drawIcon(canvas, mic, cx, cy, r, style.iconDim, 0.7f)
                drawArc(canvas, cx, cy, r, 1f)
            }
            else -> {
                // The resting look, morphing into the close mark as the menu opens (and back as it closes):
                // the mic fades and shrinks while the × fades in and turns into place.
                val p = menuProgress
                if (p < 1f) drawRestingIcon(canvas, cx, cy, r, 1f - p, 1f - 0.3f * p)
                if (p > 0f) drawClose(canvas, cx, cy, r, p)
            }
        }
    }

    /** Mic (or the loading / unavailable / reading look) at [alpha] and [scale]. */
    private fun drawRestingIcon(canvas: Canvas, cx: Float, cy: Float, r: Float, alpha: Float, scale: Float) {
        when {
            mode == Mode.LOADING -> {
                drawIcon(canvas, mic, cx, cy, r, withAlpha(style.iconDim, alpha), 0.85f * scale)
                drawArc(canvas, cx, cy, r, alpha)
            }
            mode == Mode.UNAVAILABLE -> drawIcon(canvas, mic, cx, cy, r, withAlpha(style.iconDim, alpha), scale)
            reading -> {
                val t = SystemClock.uptimeMillis() / 1000f
                val a = (0.72f + 0.28f * sin(t * 4f)).coerceIn(0f, 1f)
                drawIcon(canvas, speaker, cx, cy, r, withAlpha(style.icon, a * alpha), scale)
                postInvalidateOnAnimation()
            }
            else -> drawIcon(canvas, mic, cx, cy, r, withAlpha(style.icon, alpha), scale)
        }
    }

    /** Four white bars that move with your voice. */
    private fun drawBars(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val w = r * 0.13f
        val gap = r * 0.11f
        val total = 4 * w + 3 * gap
        val base = r * 0.2f
        val top = r * 0.95f
        val amp = (shown * 9f).coerceIn(0.12f, 1f)
        val t = SystemClock.uptimeMillis() / 1000f
        for (i in 0 until 4) {
            val h = base + (top - base) * amp * (0.55f + 0.45f * sin(t * 7f + i * 1.7f))
            val x = cx - total / 2 + i * (w + gap)
            rect.set(x, cy - h / 2, x + w, cy + h / 2)
            canvas.drawRoundRect(rect, w / 2, w / 2, mark)
        }
    }

    /** The × at [p] (0..1): fades in, grows from 70 % and turns a quarter turn into place. */
    private fun drawClose(canvas: Canvas, cx: Float, cy: Float, r: Float, p: Float) {
        stroke.strokeWidth = 2f * dp
        stroke.color = withAlpha(style.icon, p)
        val a = r * 0.3f * (0.7f + 0.3f * p)
        canvas.save()
        canvas.rotate(90f * (1f - p), cx, cy)
        canvas.drawLine(cx - a, cy - a, cx + a, cy + a, stroke)
        canvas.drawLine(cx - a, cy + a, cx + a, cy - a, stroke)
        canvas.restore()
    }

    private fun drawArc(canvas: Canvas, cx: Float, cy: Float, r: Float, alpha: Float) {
        stroke.strokeWidth = 2f * dp
        stroke.color = withAlpha(style.icon, alpha)
        val a = r * 0.66f
        rect.set(cx - a, cy - a, cx + a, cy + a)
        canvas.drawArc(rect, spin, 100f, false, stroke)
    }

    private fun drawIcon(canvas: Canvas, icon: Drawable, cx: Float, cy: Float, r: Float, tint: Int, scale: Float) {
        val half = (r * 0.5f * scale).toInt()
        icon.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        icon.setTint(tint)
        icon.draw(canvas)
    }

    // Taps arrive through BubbleController's touch listener; TalkBack's double-tap arrives here.
    override fun performClick(): Boolean = super.performClick()

    override fun onDetachedFromWindow() {
        spinner.cancel()
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (mode == Mode.LOADING || mode == Mode.FINISHING) spinner.start()
    }

    companion object {
        fun withAlpha(color: Int, k: Float): Int {
            val a = (android.graphics.Color.alpha(color) * k).toInt().coerceIn(0, 255)
            return (color and 0x00FFFFFF) or (a shl 24)
        }
    }
}
