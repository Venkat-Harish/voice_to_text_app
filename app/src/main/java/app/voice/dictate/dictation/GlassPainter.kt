package app.voice.dictate.dictation

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.view.View

/**
 * Smoky glass, drawn (Android gives overlay windows no dependable background blur): a translucent tinted
 * fill, a soft highlight across the top, a light rim and a dark hairline so it reads on both white and black
 * screens, and a faint shadow. Colours come from a [GlassStyle] (tint + opacity from Settings).
 */
class GlassPainter(density: Float, initialStyle: GlassStyle = GlassStyle()) {
    private val dp = density
    private val rimWidth = 1f * dp

    /** Room the shadow needs around a glass shape. */
    val shadowPad: Int = (8 * dp).toInt()

    var style: GlassStyle = initialStyle
        set(value) {
            if (field == value) return
            field = value
            gradTop = Float.NaN // rebuild the highlight with the new colour
        }

    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = BlurMaskFilter(6 * dp, BlurMaskFilter.Blur.NORMAL) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlight = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { setStyle(Paint.Style.STROKE); strokeWidth = rimWidth }
    private val hairline = Paint(Paint.ANTI_ALIAS_FLAG).apply { setStyle(Paint.Style.STROKE); strokeWidth = rimWidth }
    private val crispFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val crispLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { setStyle(Paint.Style.STROKE); strokeWidth = 1f }
    private val rect = RectF()
    private val shadowClip = Path()
    private var gradTop = Float.NaN
    private var gradBottom = Float.NaN

    private fun shine(top: Float, bottom: Float) {
        if (top == gradTop && bottom == gradBottom) return
        gradTop = top; gradBottom = bottom
        // The catch-light: strongest at the top edge, gone by mid-height.
        highlight.shader = LinearGradient(0f, top, 0f, top + (bottom - top) * 0.55f, style.highlight, Color.TRANSPARENT, Shader.TileMode.CLAMP)
    }

    /** A glass disc of radius [r] centred on ([cx], [cy]). [alpha] scales the whole disc (0..1). */
    fun circle(canvas: Canvas, cx: Float, cy: Float, r: Float, alpha: Float = 1f) {
        shadow.color = GlassStyle.scaleAlpha(style.shadow, alpha)
        fill.color = GlassStyle.scaleAlpha(style.fill, alpha)
        rim.color = GlassStyle.scaleAlpha(style.rim, alpha)
        hairline.color = GlassStyle.scaleAlpha(style.hairline, alpha)
        highlight.alpha = (255 * alpha.coerceIn(0f, 1f)).toInt()

        // The shadow only shows around the glass: under a see-through fill it would grey the tint.
        shadowClip.rewind()
        shadowClip.addCircle(cx, cy, r, Path.Direction.CW)
        canvas.save()
        canvas.clipOutPath(shadowClip)
        canvas.drawCircle(cx, cy + 2 * dp, r, shadow)
        canvas.restore()
        canvas.drawCircle(cx, cy, r, fill)
        shine(cy - r, cy + r)
        canvas.drawCircle(cx, cy, r - rimWidth, highlight)
        canvas.drawCircle(cx, cy, r - rimWidth / 2, rim)
        canvas.drawCircle(cx, cy, r + rimWidth / 2, hairline)
    }

    /**
     * A sharp, nearly solid pill for the tucked tab, at any screen density: edges on whole pixels, no blurred
     * shadow, and 1 px lines centred on pixel centres. A light rim inside and a dark outline outside keep it
     * visible on both light and dark apps.
     */
    fun crispPill(canvas: Canvas, left: Int, top: Int, right: Int, bottom: Int) {
        val radius = (right - left) / 2f
        rect.set(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
        crispFill.color = style.tabFill
        canvas.drawRoundRect(rect, radius, radius, crispFill)
        // Rim: the outermost row of pixels inside the pill.
        rect.inset(0.5f, 0.5f)
        crispLine.color = style.tabRim
        canvas.drawRoundRect(rect, radius - 0.5f, radius - 0.5f, crispLine)
        // Outline: the row of pixels just outside the pill.
        rect.inset(-1f, -1f)
        crispLine.color = style.tabOutline
        canvas.drawRoundRect(rect, radius + 0.5f, radius + 0.5f, crispLine)
    }

    companion object {
        /** Blurred shadows need a software layer before Android 9. */
        fun prepare(view: View) {
            if (Build.VERSION.SDK_INT < 28) view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        }
    }
}
