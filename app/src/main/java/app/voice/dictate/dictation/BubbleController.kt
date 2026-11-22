package app.voice.dictate.dictation

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import app.voice.dictate.R
import app.voice.dictate.data.BubbleSide
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The glass bubble, its mic / speaker menu, its tucked tab and the live-text pill, drawn as accessibility
 * overlays: they float over other apps and, being FLAG_NOT_FOCUSABLE, never take focus from the field you
 * are typing in.
 *
 * Gestures on the bubble: tap = open the menu (mic above, speaker below); hold ≥ 180 ms = push-to-talk;
 * drag = move (snaps to the nearest edge); drag about halfway past the edge = tuck it into a thin tab.
 * In the menu: tap mic = hands-free, hold mic = push-to-talk, tap speaker = read aloud.
 * After a few idle seconds the bubble fades to the chosen opacity.
 *
 * Why two windows of fixed size: when a window changes size and position in one step, Android plays its own
 * ~400 ms "move" animation from the old position (and ignores further moves meanwhile), which made the bubble
 * jump and drift when the menu opened and closed. So:
 * - the **bubble window** (the disc, or the tucked tab) always keeps the same size and only ever moves
 *   (dragging, snapping, tucking, the keyboard changing height). Opening and closing the menu never touch it.
 * - the **menu window** (the mic and speaker buttons) is a fixed square centred on the bubble, underneath it.
 *   Opening and closing only switch it on and off, so the buttons slide out from under the bubble and back
 *   into it while the bubble morphs between the mic and a close mark.
 * On Android 14+ the move animation is also switched off for these windows.
 */
class BubbleController(
    private val context: Context,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onHoldStart()
        fun onHoldEnd()
        /** Mic tapped in the menu (or the bubble tapped when read aloud is off): start hands-free. */
        fun onHandsFree()
        /** The bubble tapped while a hands-free recording runs. */
        fun onStopTap()
        /** Speaker tapped in the menu. */
        fun onSpeaker()
        /** The bubble was moved or tucked; remember it. */
        fun onDockChanged(side: BubbleSide, yFraction: Float, tucked: Boolean)
        /** A finger went down on the bubble (or its tucked tab): it may be about to dictate. */
        fun onBubbleTouched() {}
    }

    private enum class State { COLLAPSED, EXPANDED, TUCKED }
    private enum class Touch { NONE, PENDING, HOLDING, DRAGGING }

    private val wm = context.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val density = context.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop * 1.5f
    private fun dp(v: Float) = (v * density).roundToInt()

    private var diameterDp = 42
    private val orb = BubbleView(context, dp(42f))
    private val mic = MenuButtonView(context, dp(42f), R.drawable.ic_mic, "Dictate. Double-tap for hands-free")
    private val speaker = MenuButtonView(context, dp(42f), R.drawable.ic_speaker, "Read aloud", "Stop reading")
    private val tab = EdgeTabView(context)

    private var state = State.COLLAPSED
    private var side = BubbleSide.RIGHT
    private var yFraction = -1f
    private var keyboardTop = -1
    /** The disc's centre on screen. */
    private var center = Pt(0, 0)
    private var micCenter = Pt(0, 0)
    private var speakerCenter = Pt(0, 0)
    /** The bubble window's bounds on screen. */
    private var box = Box(0, 0, 0, 0)
    /** The menu window's bounds on screen: a fixed square centred on the bubble. */
    private var menuBox = Box(0, 0, 1, 1)

    private var visible = false
    private var added = false
    private var menuAdded = false
    private var idleOpacity = 0.4f
    private var menuSpeaker = true
    var hapticsEnabled = true

    private var touch = Touch.NONE
    private var micTouch = Touch.NONE
    /** A finger is down on the bubble window / on the menu window. */
    private var fingerDown = false
    private var menuFingerDown = false
    /** Work deferred until the finger lifts, so no window changes under a live touch. */
    private var layoutPending = false
    private var menuClosePending = false
    /** The keyboard moved while the menu was open or closing: glide to the new spot once it has closed. */
    private var redockAfterMenu = false
    /** The menu's buttons are still gliding back under the bubble. */
    private var menuClosing = false
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var moveAnim: ValueAnimator? = null
    private var morphAnim: ValueAnimator? = null
    private var menuGen = 0
    private var tuckGen = 0
    private var lastSavedDock: Triple<BubbleSide, Float, Boolean>? = null
    /** Gesture start times: a tap that already closed the menu as an outside touch must not reopen it. */
    private var lastOutsideDownTime = -1L
    private var orbDownTime = -2L
    private val alphaAnims = HashMap<View, ObjectAnimator>()

    /** The bubble window: the disc and the tucked tab. */
    private val root = object : FrameLayout(context) {
        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) { fingerDown = true; wake(); callbacks.onBubbleTouched() }
            val handled = super.dispatchTouchEvent(e)
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                fingerDown = false
                if (layoutPending) { layoutPending = false; glideToDock() }
                scheduleFade()
            }
            return handled
        }
    }.apply {
        clipChildren = false
        clipToPadding = false
        addView(tab)
        addView(orb)
    }

    /** The menu window: the mic and speaker buttons, underneath the bubble window. */
    private val menuRoot = object : FrameLayout(context) {
        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_OUTSIDE -> {
                    // Any touch elsewhere closes the menu. (Its coordinates are blanked for other apps' windows,
                    // so they are never read. Touches on the bubble don't arrive here: its window is on top.)
                    lastOutsideDownTime = e.downTime
                    collapse()
                    return true
                }
                MotionEvent.ACTION_DOWN -> { menuFingerDown = true; wake() }
            }
            val handled = super.dispatchTouchEvent(e)
            if (e.actionMasked == MotionEvent.ACTION_DOWN && !handled && state == State.EXPANDED) collapse()
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                menuFingerDown = false
                if (menuClosePending) { menuClosePending = false; closeMenuWindow(menuGen) }
                scheduleFade()
            }
            return handled
        }
    }.apply {
        clipChildren = false
        clipToPadding = false
        addView(mic)
        addView(speaker)
    }

    private val params = overlayParams(touchable = true)
    private val menuParams = overlayParams(touchable = false)

    private var style = GlassStyle()
    private val previewBackground = GradientDrawable().apply {
        setColor(style.pill)
        cornerRadius = dp(18f).toFloat()
        setStroke(dp(1f), style.pillRim)
    }

    private val preview = TextView(context).apply {
        setTextColor(style.pillText)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setLineSpacing(0f, 1.15f)
        maxLines = 4
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
        background = previewBackground
    }
    private val previewParams = overlayParams(touchable = false)
    private var previewAdded = false
    private val hidePreviewTask = Runnable { hidePreview() }

    private val fadeTask = Runnable {
        // The tucked tab is already slim: it never fades so far that it turns into a smudge.
        val target = if (state == State.TUCKED) maxOf(idleOpacity, GlassStyle.TAB_MIN_FILL) else idleOpacity
        if (canFade()) root.animate().alpha(target).setDuration(450).start()
    }
    private val autoCollapse = Runnable { if (micTouch == Touch.NONE) collapse() }

    private val holdTask = Runnable {
        if (touch != Touch.PENDING) return@Runnable
        touch = Touch.HOLDING
        haptic(true)
        collapse()
        callbacks.onHoldStart()
    }
    private val micHoldTask = Runnable {
        if (micTouch != Touch.PENDING) return@Runnable
        micTouch = Touch.HOLDING
        haptic(true)
        collapse()
        callbacks.onHoldStart()
    }

    init {
        mic.visibility = View.GONE
        speaker.visibility = View.GONE
        tab.visibility = View.GONE
        orb.setOnTouchListener(::onOrbTouch)
        orb.setOnClickListener { onOrbClick() }
        mic.setOnTouchListener(::onMicTouch)
        mic.setOnClickListener { collapse(); callbacks.onHandsFree() }
        speaker.setOnClickListener { haptic(false); collapse(); callbacks.onSpeaker() }
        tab.setOnTouchListener(::onTabTouch)
        tab.setOnClickListener { untuck() }
    }

    // ---------- public API ----------

    fun setVisible(show: Boolean) {
        visible = show
        if (show) {
            // Compared with `added`, not `visible`: if adding a window ever fails, the next call tries again.
            if (added) return
            if (state == State.EXPANDED) resetMenu()
            center = geometry().dockCenter(side, yFraction)
            computeLayout()
            computeMenuLayout(open = false)
            try {
                // The menu window goes in first so it sits underneath the bubble window.
                if (!menuAdded) { wm.addView(menuRoot, menuParams); menuAdded = true }
                wm.addView(root, params)
                added = true
            } catch (e: RuntimeException) {
                Log.w(TAG, "Couldn't add the bubble window", e)
                return
            }
            root.animate().cancel()
            root.alpha = 0f
            root.animate().alpha(1f).setDuration(180).withEndAction { scheduleFade() }.start()
        } else {
            if (!added && !menuAdded && !previewAdded) return
            main.removeCallbacks(holdTask)
            main.removeCallbacks(micHoldTask)
            main.removeCallbacks(fadeTask)
            touch = Touch.NONE
            micTouch = Touch.NONE
            fingerDown = false
            menuFingerDown = false
            layoutPending = false
            menuClosePending = false
            redockAfterMenu = false
            orb.tuckHint = false
            tuckGen++
            moveAnim?.cancel()
            if (state == State.EXPANDED) resetMenu()
            removePreview()
            if (added) { runCatching { wm.removeView(root) }; added = false }
            if (menuAdded) { runCatching { wm.removeView(menuRoot) }; menuAdded = false }
            if (state != State.TUCKED) orb.visibility = View.VISIBLE
        }
    }

    fun setSize(sizeDp: Int) {
        if (sizeDp == diameterDp) return
        diameterDp = sizeDp
        val d = dp(sizeDp.toFloat())
        orb.diameter = d; mic.diameter = d; speaker.diameter = d
        if (state == State.EXPANDED) resetMenu()
        redock()
    }

    /** Tint and opacity of the glass, for the bubble, its menu, the tucked tab and the live-text pill. */
    fun setStyle(style: GlassStyle) {
        if (style == this.style) return
        this.style = style
        orb.style = style
        mic.style = style
        speaker.style = style
        tab.style = style
        previewBackground.setColor(style.pill)
        previewBackground.setStroke(dp(1f), style.pillRim)
        preview.setTextColor(style.pillText)
    }

    /** Applies a saved (or reset) resting place. */
    fun setDock(side: BubbleSide, yFraction: Float, tucked: Boolean) {
        val saved = Triple(side, yFraction, tucked)
        if (saved == lastSavedDock) return // our own write coming back, or an unrelated settings change
        if (touch == Touch.DRAGGING || moveAnim != null) return // try again on the next change
        lastSavedDock = saved
        if (side == this.side && yFraction == this.yFraction && tucked == (state == State.TUCKED)) return
        this.side = side
        this.yFraction = yFraction
        if (state == State.EXPANDED) resetMenu()
        tuckGen++
        state = if (tucked) State.TUCKED else State.COLLAPSED
        redock()
    }

    /** Top edge of the open keyboard (−1 when closed): the bubble glides to stay just above it. */
    fun setKeyboardTop(top: Int) {
        if (top == keyboardTop) return
        keyboardTop = top
        if (state == State.EXPANDED || menuClosing) redockAfterMenu = true else glideToDock()
    }

    fun setIdleOpacity(opacity: Float) {
        idleOpacity = opacity
        if (opacity >= 0.99f) { main.removeCallbacks(fadeTask); root.animate().cancel(); root.alpha = 1f } else scheduleFade()
    }

    /** With read aloud off the menu would only hold the mic, so a tap starts hands-free directly. */
    fun setMenuSpeaker(enabled: Boolean) {
        menuSpeaker = enabled
        if (!enabled) collapse()
    }

    fun setMode(mode: BubbleView.Mode) {
        orb.mode = mode
        // Keep the screen awake while recording, so a screen timeout can't end a long hands-free dictation.
        val recording = mode == BubbleView.Mode.LISTENING || mode == BubbleView.Mode.LOCKED || mode == BubbleView.Mode.FINISHING
        val flags = if (recording) params.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        else params.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
        if (flags != params.flags) {
            params.flags = flags
            if (added) runCatching { wm.updateViewLayout(root, params) }
        }
        when (mode) {
            BubbleView.Mode.LISTENING, BubbleView.Mode.LOCKED, BubbleView.Mode.FINISHING -> { collapse(); wake() }
            else -> scheduleFade()
        }
    }

    fun setLevel(level: Float) = orb.setLevel(level)

    /** Read aloud: the bubble pulses a speaker while reading; the menu's speaker becomes a stop button. */
    fun setReading(reading: Boolean, loading: Boolean) {
        orb.reading = reading
        speaker.mode = when {
            reading && loading -> MenuButtonView.Mode.LOADING
            reading -> MenuButtonView.Mode.ACTIVE
            else -> MenuButtonView.Mode.IDLE
        }
    }

    fun haptic(strong: Boolean) {
        if (!hapticsEnabled) return
        val c = if (strong) {
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS
        } else HapticFeedbackConstants.CLOCK_TICK
        orb.performHapticFeedback(c, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
    }

    /** Shows live text above (or below) the bubble until [hidePreview] or the timeout. */
    fun showPreview(text: String, timeoutMs: Long = 0) {
        if (!added) return
        main.removeCallbacks(hidePreviewTask)
        // Keep the newest words visible: long text is trimmed from the front.
        preview.text = if (text.length > 220) "…" + text.takeLast(220).substringAfter(' ') else text
        val s = screen()
        preview.maxWidth = s.width() - dp(32f)
        val g = geometry()
        val top = if (state == State.TUCKED) box.top else center.y - orb.diameter / 2
        val bottom = if (state == State.TUCKED) box.bottom else center.y + orb.diameter / 2
        if (g.previewAbove(top, bottom)) {
            previewParams.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            previewParams.y = s.height() - top + dp(10f)
        } else {
            previewParams.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            previewParams.y = bottom + dp(10f)
        }
        if (previewAdded) runCatching { wm.updateViewLayout(preview, previewParams) }
        else runCatching { wm.addView(preview, previewParams); previewAdded = true }
        wake()
        if (timeoutMs > 0) main.postDelayed(hidePreviewTask, timeoutMs)
    }

    fun hidePreview() {
        main.removeCallbacks(hidePreviewTask)
        removePreview()
        scheduleFade()
    }

    /** After rotation or a screen-size change. */
    fun onScreenChanged() {
        if (state == State.EXPANDED) resetMenu()
        moveAnim?.cancel()
        redock()
    }

    fun destroy() {
        main.removeCallbacksAndMessages(null)
        moveAnim?.cancel()
        morphAnim?.cancel()
        setVisible(false)
    }

    // ---------- layout ----------

    private fun overlayParams(touchable: Boolean) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            (if (touchable) WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        // Plain screen coordinates: never pushed around by the status or navigation bar.
        if (Build.VERSION.SDK_INT >= 30) { fitInsetsTypes = 0; fitInsetsSides = 0 }
        // No system "move" animation: a size + position change lands together with the redrawn frame.
        if (Build.VERSION.SDK_INT >= 34) setCanPlayMoveAnimation(false)
    }

    private fun screen(): Rect =
        if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds
        else @Suppress("DEPRECATION") android.util.DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
            .let { Rect(0, 0, it.widthPixels, it.heightPixels) }

    private fun geometry(): BubbleGeometry {
        val s = screen()
        return BubbleGeometry(
            screenW = s.width(), screenH = s.height(), diameter = orb.diameter,
            margin = dp(6f), topLimit = dp(32f), keyboardTop = keyboardTop, keyboardGap = dp(12f),
        )
    }

    /** Puts the disc at its resting place at once (unless the finger or an animation is moving it). */
    private fun redock() {
        if (touch == Touch.DRAGGING || moveAnim != null) return
        if (fingerDown) { layoutPending = true; return }
        center = geometry().dockCenter(side, yFraction)
        applyLayout()
        if (state != State.EXPANDED) computeMenuLayout(open = false, apply = true)
    }

    /** Glides the disc to its resting place (keyboard height changes), or jumps there if it isn't on screen. */
    private fun glideToDock() {
        if (touch == Touch.DRAGGING || moveAnim != null) return
        if (fingerDown) { layoutPending = true; return }
        val target = geometry().dockCenter(side, yFraction)
        if (target == center) return
        if (!added || state == State.TUCKED) { redock(); return }
        animateCenterTo(target, KEYBOARD_GLIDE_MS) {
            computeMenuLayout(open = false, apply = true)
            scheduleFade()
        }
    }

    private fun applyLayout() {
        computeLayout()
        if (added) runCatching { wm.updateViewLayout(root, params) }
    }

    /** Sizes and places the bubble window and its children. Opening the menu never changes it. */
    private fun computeLayout() {
        val g = geometry()
        if (state == State.TUCKED) {
            // Same size as the disc's window, so tucking and untucking only move it; just the tab's strip is on screen.
            box = g.tuckedBox(side, center.y, orb.size, tab.viewWidth)
            tab.onRight = side == BubbleSide.RIGHT
            tab.visibility = View.VISIBLE
            orb.visibility = View.GONE
            val stripLeft = if (side == BubbleSide.RIGHT) box.left else box.right - tab.viewWidth
            place(box, tab, Pt(stripLeft + tab.viewWidth / 2, box.top + box.height / 2), tab.viewWidth, box.height)
        } else {
            box = g.collapsedBox(center, orb.size)
            tab.visibility = View.GONE
            orb.visibility = View.VISIBLE
            place(box, orb, center, orb.size, orb.size)
        }
        params.x = box.left
        params.y = box.top
        params.width = box.width
        params.height = box.height
    }

    /**
     * The menu window: a fixed square centred on the bubble (its size depends only on the bubble size). Opening
     * and closing only switch it between touchable + visible and untouchable + invisible; while closed it simply
     * follows the bubble around (a move of nothing visible).
     */
    private fun computeMenuLayout(open: Boolean, apply: Boolean = false) {
        val g = geometry()
        menuBox = g.menuBox(center, mic.diameter, mic.size, dp(12f))
        if (open) {
            val (m, s) = g.fan(center, side, mic.diameter)
            micCenter = m
            speakerCenter = s
            place(menuBox, mic, m, mic.size, mic.size)
            place(menuBox, speaker, s, speaker.size, speaker.size)
            menuParams.flags = MENU_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            menuRoot.visibility = View.VISIBLE
        } else {
            menuParams.flags = MENU_FLAGS or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            menuRoot.visibility = View.INVISIBLE
        }
        menuParams.x = menuBox.left
        menuParams.y = menuBox.top
        menuParams.width = menuBox.width
        menuParams.height = menuBox.height
        if (apply && menuAdded) runCatching { wm.updateViewLayout(menuRoot, menuParams) }
    }

    /** Places [v] centred on screen point [c], inside a window with bounds [container]. */
    private fun place(container: Box, v: View, c: Pt, w: Int, h: Int) {
        val lp = (v.layoutParams as? FrameLayout.LayoutParams) ?: FrameLayout.LayoutParams(w, h)
        lp.width = w
        lp.height = h
        lp.gravity = Gravity.TOP or Gravity.START
        lp.leftMargin = c.x - w / 2 - container.left
        lp.topMargin = c.y - h / 2 - container.top
        v.layoutParams = lp
    }

    // ---------- menu ----------

    private fun onOrbClick() {
        when {
            orb.mode == BubbleView.Mode.LOCKED -> callbacks.onStopTap()
            orb.mode == BubbleView.Mode.LISTENING || orb.mode == BubbleView.Mode.FINISHING -> Unit
            state == State.EXPANDED -> collapse()
            // This very tap already closed the menu as an "outside" touch: don't reopen it.
            orbDownTime == lastOutsideDownTime -> Unit
            !menuSpeaker -> callbacks.onHandsFree()
            else -> expand()
        }
    }

    /** The buttons slide out from under the bubble while it morphs from the mic into a close mark. */
    private fun expand() {
        if (state != State.COLLAPSED || !added || !menuAdded) return
        moveAnim?.end() // a snap still running: finish it, so the menu opens around the bubble's resting spot
        state = State.EXPANDED
        orb.expanded = true
        menuGen++
        menuClosing = false
        menuClosePending = false
        // Only switches the (fixed-size) menu window on; the buttons start hidden under the bubble.
        computeMenuLayout(open = true, apply = true)
        for ((b, c) in listOf(mic to micCenter, speaker to speakerCenter)) {
            b.animate().cancel()
            if (b.visibility != View.VISIBLE) {
                // From under the bubble. (Reopened mid-close: carry on from where the button is.)
                b.visibility = View.VISIBLE
                b.translationX = (center.x - c.x).toFloat()
                b.translationY = (center.y - c.y).toFloat()
                b.scaleX = 0.4f; b.scaleY = 0.4f; b.alpha = 0f
            }
            b.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f)
                .setStartDelay(0).setDuration(OPEN_MS).setInterpolator(OvershootInterpolator(1.1f)).withEndAction(null).start()
            fadeTo(b, 1f, FADE_MS)
        }
        animateMorph(to = 1f, delayMs = 0, durationMs = MORPH_MS)
        wake()
        main.removeCallbacks(autoCollapse)
        main.postDelayed(autoCollapse, MENU_MS)
    }

    /** The buttons glide back under the bubble while it morphs from the close mark back into the mic. */
    private fun collapse() {
        if (state != State.EXPANDED) return
        state = State.COLLAPSED
        orb.expanded = false
        main.removeCallbacks(autoCollapse)
        val gen = ++menuGen
        menuClosing = true
        for ((b, c) in listOf(mic to micCenter, speaker to speakerCenter)) {
            b.animate().cancel()
            b.animate().translationX((center.x - c.x).toFloat()).translationY((center.y - c.y).toFloat())
                .scaleX(0.4f).scaleY(0.4f)
                .setStartDelay(0).setDuration(CLOSE_MS).setInterpolator(EASE)
                .withEndAction(if (b === speaker) Runnable { onMenuFolded(gen) } else null)
                .start()
            // Fade out before arriving: the glass is see-through, so a parked button would show as a ghost mic.
            fadeTo(b, 0f, FADE_MS)
        }
        animateMorph(to = 0f, delayMs = CLOSE_MS / 3, durationMs = MORPH_MS)
    }

    /** The buttons are back under the bubble: shrink the menu window (after the finger lifts, if it is down). */
    private fun onMenuFolded(gen: Int) {
        if (gen != menuGen) return
        if (menuFingerDown) { menuClosePending = true; return }
        closeMenuWindow(gen)
    }

    private fun closeMenuWindow(gen: Int) {
        // One more frame, so the buttons' last (invisible) frame is on screen before the window switches off.
        menuRoot.postOnAnimation {
            if (gen != menuGen || state == State.EXPANDED) return@postOnAnimation
            menuClosing = false
            resetButtons()
            computeMenuLayout(open = false, apply = true)
            if (redockAfterMenu) { redockAfterMenu = false; glideToDock() }
            scheduleFade()
        }
    }

    /** Closes the menu at once (no animation). */
    private fun resetMenu() {
        main.removeCallbacks(autoCollapse)
        menuGen++
        state = State.COLLAPSED
        orb.expanded = false
        morphAnim?.cancel()
        orb.menuProgress = 0f
        menuClosing = false
        menuClosePending = false
        resetButtons()
        computeMenuLayout(open = false, apply = true)
    }

    private fun resetButtons() {
        for (b in listOf(mic, speaker)) {
            b.animate().cancel()
            alphaAnims.remove(b)?.cancel()
            b.visibility = View.GONE
            b.translationX = 0f; b.translationY = 0f
            b.scaleX = 1f; b.scaleY = 1f; b.alpha = 1f
        }
    }

    /** A button's opacity, animated on its own clock (faster than its travel). */
    private fun fadeTo(b: View, to: Float, durationMs: Long) {
        alphaAnims.remove(b)?.cancel()
        alphaAnims[b] = ObjectAnimator.ofFloat(b, View.ALPHA, b.alpha, to).apply {
            duration = durationMs
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun animateMorph(to: Float, delayMs: Long, durationMs: Long) {
        morphAnim?.cancel()
        morphAnim = ValueAnimator.ofFloat(orb.menuProgress, to).apply {
            startDelay = delayMs
            duration = durationMs
            interpolator = EASE
            addUpdateListener { orb.menuProgress = it.animatedValue as Float }
            start()
        }
    }

    // ---------- moving, docking and tucking ----------

    private fun animateCenterTo(target: Pt, durationMs: Long = 220, end: () -> Unit) {
        moveAnim?.cancel()
        val from = center
        var cancelled = false
        moveAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                center = Pt(from.x + ((target.x - from.x) * f).roundToInt(), from.y + ((target.y - from.y) * f).roundToInt())
                applyLayout()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    moveAnim = null
                    if (!cancelled) end()
                }
            })
            start()
        }
    }

    /** After a drag: tuck into the edge, or snap to the nearest edge. */
    private fun settle() {
        val g = geometry()
        orb.tuckHint = false
        side = g.sideFor(center.x)
        yFraction = g.yFraction(center.y)
        if (g.shouldTuck(center.x)) { tuck(); return }
        animateCenterTo(g.dockCenter(side, yFraction)) {
            computeMenuLayout(open = false, apply = true)
            callbacks.onDockChanged(side, yFraction, false)
            scheduleFade()
        }
    }

    private fun tuck() {
        haptic(false)
        val g = geometry()
        val offX = if (side == BubbleSide.RIGHT) g.screenW + orb.diameter / 2 else -orb.diameter / 2
        val gen = ++tuckGen
        animateCenterTo(Pt(offX, g.clampY(center.y))) {
            // Show one empty frame before the window becomes the thin tab, so the disc's last frame can't
            // flash at the edge when the window moves.
            orb.visibility = View.INVISIBLE
            root.postOnAnimation {
                root.postOnAnimation {
                    if (gen != tuckGen || !added) return@postOnAnimation
                    state = State.TUCKED
                    applyLayout()
                    computeMenuLayout(open = false, apply = true)
                    callbacks.onDockChanged(side, yFraction, true)
                    scheduleFade()
                }
            }
        }
    }

    private fun untuck() {
        if (state != State.TUCKED) return
        haptic(false)
        tuckGen++
        val g = geometry()
        val target = g.dockCenter(side, yFraction)
        state = State.COLLAPSED
        center = Pt(if (side == BubbleSide.RIGHT) g.screenW + orb.diameter / 2 else -orb.diameter / 2, target.y)
        applyLayout()
        wake()
        animateCenterTo(target) {
            computeMenuLayout(open = false, apply = true)
            callbacks.onDockChanged(side, yFraction, false)
            scheduleFade()
        }
    }

    // ---------- touch ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun onOrbTouch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                orbDownTime = e.downTime
                downX = e.rawX; downY = e.rawY
                touch = Touch.PENDING
                // While hands-free (or finishing), a touch is only a tap; otherwise holding starts push-to-talk.
                if (orb.mode != BubbleView.Mode.LOCKED && orb.mode != BubbleView.Mode.FINISHING) main.postDelayed(holdTask, HOLD_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (touch == Touch.PENDING && hypot(dx, dy) > touchSlop) {
                    main.removeCallbacks(holdTask)
                    touch = if (state == State.COLLAPSED && !menuClosing) Touch.DRAGGING else Touch.NONE
                    if (touch == Touch.DRAGGING) {
                        // Grab the bubble where it is now (a snap may still have been gliding it).
                        moveAnim?.cancel()
                        startX = params.x - dx.roundToInt()
                        startY = params.y - dy.roundToInt()
                    }
                }
                if (touch == Touch.DRAGGING) {
                    params.x = startX + dx.roundToInt()
                    params.y = startY + dy.roundToInt()
                    box = Box(params.x, params.y, params.x + box.width, params.y + box.height)
                    center = Pt(params.x + box.width / 2, params.y + box.height / 2)
                    orb.tuckHint = geometry().shouldTuck(center.x)
                    runCatching { wm.updateViewLayout(root, params) }
                    removePreview()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(holdTask)
                val t = touch
                touch = Touch.NONE
                when (t) {
                    Touch.HOLDING -> { haptic(false); callbacks.onHoldEnd() }
                    Touch.DRAGGING -> settle()
                    Touch.PENDING -> if (e.actionMasked == MotionEvent.ACTION_UP) { haptic(false); v.performClick() }
                    Touch.NONE -> Unit
                }
            }
        }
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun onMicTouch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY
                micTouch = Touch.PENDING
                main.removeCallbacks(autoCollapse)
                main.postDelayed(micHoldTask, HOLD_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                if (micTouch == Touch.PENDING && hypot(e.rawX - downX, e.rawY - downY) > touchSlop) {
                    main.removeCallbacks(micHoldTask)
                    micTouch = Touch.NONE
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(micHoldTask)
                val t = micTouch
                micTouch = Touch.NONE
                when (t) {
                    Touch.HOLDING -> { haptic(false); callbacks.onHoldEnd() }
                    Touch.PENDING -> if (e.actionMasked == MotionEvent.ACTION_UP) { haptic(false); v.performClick() }
                    else -> if (state == State.EXPANDED) main.postDelayed(autoCollapse, MENU_MS)
                }
            }
        }
        return true
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun onTabTouch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; touch = Touch.PENDING }
            MotionEvent.ACTION_MOVE -> {
                // Pulling the tab out of the edge brings the bubble back too.
                if (touch == Touch.PENDING && hypot(e.rawX - downX, e.rawY - downY) > touchSlop) {
                    touch = Touch.NONE
                    untuck()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (touch == Touch.PENDING && e.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                touch = Touch.NONE
            }
        }
        return true
    }

    // ---------- fading ----------

    private fun canFade() = idleOpacity < 0.99f && state != State.EXPANDED && !fingerDown && !menuFingerDown &&
        touch == Touch.NONE && micTouch == Touch.NONE && !previewAdded && moveAnim == null &&
        (orb.mode == BubbleView.Mode.IDLE || orb.mode == BubbleView.Mode.LOADING || orb.mode == BubbleView.Mode.UNAVAILABLE)

    /** Back to full opacity (any touch, the menu, recording, live text). */
    private fun wake() {
        main.removeCallbacks(fadeTask)
        root.animate().cancel()
        root.alpha = 1f
    }

    private fun scheduleFade() {
        main.removeCallbacks(fadeTask)
        if (idleOpacity < 0.99f && added) main.postDelayed(fadeTask, IDLE_MS)
    }

    private fun removePreview() {
        if (previewAdded) { runCatching { wm.removeView(preview) }; previewAdded = false }
    }

    companion object {
        private const val TAG = "BubbleController"
        private const val HOLD_MS = 180L
        private const val IDLE_MS = 3000L
        private const val MENU_MS = 4000L
        private const val OPEN_MS = 240L
        private const val CLOSE_MS = 220L
        private const val MORPH_MS = 190L
        private const val KEYBOARD_GLIDE_MS = 160L
        private const val FADE_MS = 140L
        /** Material "standard" easing: smooth start and end. */
        private val EASE = PathInterpolator(0.4f, 0f, 0.2f, 1f)
        private const val MENU_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
    }
}
