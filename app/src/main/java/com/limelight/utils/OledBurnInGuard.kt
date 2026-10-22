package com.limelight.utils

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import kotlin.math.abs

/**
 * OLED burn-in mitigation for a tablet used as a long-lived drawing display.
 *
 * THE PROBLEM
 * -----------
 * An AMOLED panel ages per subpixel. Leave a PC taskbar, CSP's toolbox, a
 * palette or a window border in the same place for hundreds of hours and those
 * pixels decay faster than their neighbours, which leaves a permanent ghost.
 *
 * WHAT THIS DOES
 * --------------
 * 1. Pixel shift - while nobody is touching the tablet, the image is nudged a
 *    few pixels and the offset walks a fixed ring pattern. A hard edge that would
 *    otherwise sit on one row of subpixels is smeared across ~2*A instead.
 * 2. Idle dim - after a longer period with no input, a black layer fades in.
 *    Less drive current, less ageing, and it doubles as an idle power saving.
 *
 * WHY THE OFFSET IS APPLIED ONLY WHILE IDLE
 * -----------------------------------------
 * Shifting mid-stroke would displace the image relative to the stroke the user
 * is drawing, leaving a visible kink in the line. Nobody is looking at a stroke
 * when they are not drawing, so that is when the shift happens. The offset then
 * FREEZES where it is when input arrives, rather than snapping back to zero --
 * snapping would be a second, equally visible jump.
 *
 * WHY INPUT NEEDS NO COMPENSATION
 * -------------------------------
 * MotionEvent coordinates are relative to the view's own top-left, and the view
 * content moves together with the view. The pixel under the pen therefore keeps
 * the same local coordinate at every offset. Translation is a pure additive
 * offset; scaling is not, which is why PanZoomHandler needs pivot maths and this
 * class does not. That also means the shift can never break the pen mapping, no
 * matter where the offset currently sits.
 */
class OledBurnInGuard(
    private val panZoom: PanZoomHandler,
    private val root: ViewGroup,
    private val enableShift: Boolean,
    private val amplitudePx: Int,
    private val intervalMs: Long,
    private val idleBeforeShiftMs: Long,
    private val dimAfterMinutes: Int
) {
    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var lastInputAt = SystemClock.uptimeMillis()
    @Volatile private var lastStepAt = SystemClock.uptimeMillis()
    private var index = 0
    private var running = false

    private var dimView: View? = null
    private var dimTarget = 0f

    /**
     * Nine positions on a ring of radius A. Positive and negative are both
     * represented so the average position stays centred rather than drifting,
     * and the visible gap at the edge is at most A pixels.
     */
    private val pattern = arrayOf(
        0 to 0, 1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1, 1 to -1
    )

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            val idle = now - lastInputAt

            if (enableShift && amplitudePx > 0 &&
                idle >= idleBeforeShiftMs && now - lastStepAt >= intervalMs
            ) {
                lastStepAt = now
                index = (index + 1) % pattern.size
                val (px, py) = pattern[index]
                panZoom.setBurnInOffset(px * amplitudePx, py * amplitudePx)
            }

            updateDim(idle)

            handler.postDelayed(this, TICK_MS)
        }
    }

    fun start() {
        if (!(enableShift && amplitudePx > 0) && dimAfterMinutes <= 0) return
        // Idempotent: onResume can fire without a matching onStop pause.
        if (running) return
        running = true
        lastInputAt = SystemClock.uptimeMillis()
        lastStepAt = lastInputAt

        if (enableShift && amplitudePx > 0) {
            // The image no longer covers the parent exactly, so the strip it
            // uncovers has to be black: on OLED, black pixels are off pixels.
            root.setBackgroundColor(Color.BLACK)
        }
        if (dimAfterMinutes > 0 && dimView == null) {
            dimView = View(root.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(Color.BLACK)
                alpha = 0f
                // Above every other overlay, so a bright local cursor or a
                // notification cannot stay lit while the rest of the screen dims.
                elevation = 24f
                // Not focusable/clickable: input must still reach the stream.
                isClickable = false
                isFocusable = false
                isFocusableInTouchMode = false
            }
            root.addView(dimView)
        }

        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
        panZoom.setBurnInOffset(0, 0)
        dimView?.let {
            it.animate().cancel()
            root.removeView(it)
        }
        dimView = null
        dimTarget = 0f
    }

    /**
     * Any user activity: freezes the shift (by keeping it "not idle") and wakes
     * the screen back up. Cheap enough to call on every motion event.
     */
    fun notifyInput() {
        lastInputAt = SystemClock.uptimeMillis()
        // Do not reset the step timer: otherwise a burst of taps would starve
        // the shift forever. Idle time is what gates it, not input count.
        if (dimTarget != 0f) {
            dimTarget = 0f
            dimView?.animate()?.cancel()
            dimView?.animate()?.alpha(0f)?.setDuration(DIM_FADE_MS)?.start()
        }
    }

    private fun updateDim(idleMs: Long) {
        val view = dimView ?: return
        val target = if (dimAfterMinutes > 0 && idleMs >= dimAfterMinutes * 60_000L) DIM_ALPHA else 0f
        if (abs(target - dimTarget) < 0.001f) return
        dimTarget = target
        view.animate().cancel()
        view.animate().alpha(target).setDuration(if (target > 0f) DIM_FADE_IN_MS else DIM_FADE_MS).start()
    }

    companion object {
        private const val TICK_MS = 1000L
        private const val DIM_ALPHA = 0.88f
        private const val DIM_FADE_IN_MS = 1500L
        private const val DIM_FADE_MS = 250L
    }
}
