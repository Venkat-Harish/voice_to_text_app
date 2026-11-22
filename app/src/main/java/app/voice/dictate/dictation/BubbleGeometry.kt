package app.voice.dictate.dictation

import app.voice.dictate.data.BubbleSide
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** A point in screen pixels. */
data class Pt(val x: Int, val y: Int)

/** A rectangle in screen pixels. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top

    companion object {
        /** A [size] × [size] square centred on [c]. */
        fun around(c: Pt, size: Int) = Box(c.x - size / 2, c.y - size / 2, c.x - size / 2 + size, c.y - size / 2 + size)
    }
}

/**
 * Where the bubble, its menu and its tucked tab go, in screen pixels. Pure Kotlin, so it is unit-tested.
 *
 * @param diameter the glass disc's diameter.
 * @param margin gap between the docked disc and the screen edge.
 * @param topLimit keep the disc below this (the status bar).
 * @param keyboardTop top edge of the open keyboard, or −1 when there is none.
 * @param keyboardGap gap kept above the keyboard.
 */
class BubbleGeometry(
    val screenW: Int,
    val screenH: Int,
    val diameter: Int,
    val margin: Int,
    val topLimit: Int,
    keyboardTop: Int,
    val keyboardGap: Int,
) {
    private val r = diameter / 2

    /** Lowest point the disc may reach: just above the keyboard when one is open. */
    val bottomLimit: Int = (if (keyboardTop in 1 until screenH) keyboardTop else screenH) - keyboardGap

    fun sideFor(centerX: Int) = if (centerX < screenW / 2) BubbleSide.LEFT else BubbleSide.RIGHT

    fun dockX(side: BubbleSide) = if (side == BubbleSide.RIGHT) screenW - margin - r else margin + r

    /** Keeps the disc between the status bar and the keyboard. */
    fun clampY(cy: Int): Int {
        val lo = topLimit + r
        val hi = max(lo, bottomLimit - r)
        return cy.coerceIn(lo, hi)
    }

    /** The docked disc's centre: [yFraction] of the screen height, or (−1) resting just above the keyboard. */
    fun dockCenter(side: BubbleSide, yFraction: Float): Pt {
        val wanted = if (yFraction < 0f) bottomLimit - r else (yFraction * screenH).roundToInt()
        return Pt(dockX(side), clampY(wanted))
    }

    /** The height to remember for a disc centred at [cy]. */
    fun yFraction(cy: Int): Float = clampY(cy).toFloat() / screenH

    /** Let go with the disc pushed about halfway past an edge, and it tucks into that edge. */
    fun shouldTuck(centerX: Int): Boolean = centerX < diameter * 0.3f || centerX > screenW - diameter * 0.3f

    /**
     * Centres of the mic (upper) and speaker (lower) buttons, fanned toward the middle of the screen at ±38°.
     * Near the status bar the fan tilts down; near the keyboard (or the bottom) it tilts up.
     */
    fun fan(center: Pt, side: BubbleSide, buttonDiameter: Int): Pair<Pt, Pt> {
        val br = buttonDiameter / 2
        val dist = fanDistance(buttonDiameter).toFloat()
        val dir = if (side == BubbleSide.RIGHT) -1 else 1
        fun at(deg: Double) = Pt(
            center.x + (dir * dist * cos(Math.toRadians(deg))).roundToInt(),
            center.y + (dist * sin(Math.toRadians(deg))).roundToInt(),
        )
        var mic = at(-38.0)
        var speaker = at(38.0)
        if (mic.y - br < topLimit) {
            mic = at(0.0); speaker = at(76.0)
        } else if (speaker.y + br > bottomLimit) {
            mic = at(-76.0); speaker = at(0.0)
        }
        return mic to speaker
    }

    /** How far each menu button's centre sits from the disc's centre. */
    fun fanDistance(buttonDiameter: Int): Int = ((r + buttonDiameter / 2) * 1.42f).roundToInt()

    /**
     * The menu window: a fixed square centred on the disc, big enough for the fan in any tilt plus [margin]
     * (room for the open animation's overshoot). It never changes size, so opening and closing the menu only
     * switch it on and off.
     */
    fun menuBox(center: Pt, buttonDiameter: Int, buttonViewSize: Int, margin: Int): Box {
        val reach = fanDistance(buttonDiameter) + buttonViewSize / 2 + margin
        return Box(center.x - reach, center.y - reach, center.x + reach, center.y + reach)
    }

    /** The window around the disc alone (collapsed). [viewSize] includes the shadow room. */
    fun collapsedBox(center: Pt, viewSize: Int) = Box.around(center, viewSize)

    /**
     * The bubble window while tucked: the same size as when it shows the disc (so tucking and untucking only move
     * it), placed so that just a [strip]-wide band at the [side] edge is on screen, at height [cy].
     */
    fun tuckedBox(side: BubbleSide, cy: Int, viewSize: Int, strip: Int): Box {
        val top = clampY(cy) - viewSize / 2
        val left = if (side == BubbleSide.RIGHT) screenW - strip else strip - viewSize
        return Box(left, top, left + viewSize, top + viewSize)
    }

    /** True when the live-text pill should sit above the disc (there is more room above, e.g. over a keyboard). */
    fun previewAbove(discTop: Int, discBottom: Int): Boolean = discTop - topLimit >= bottomLimit - discBottom
}
