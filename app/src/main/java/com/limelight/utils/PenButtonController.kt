package com.limelight.utils

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import com.limelight.binding.input.PenWheelAction
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Turns the S Pen barrel button into a click / hold gesture pair.
 *
 * The button itself never becomes a key event we can listen for: Android
 * reports it as [MotionEvent.BUTTON_STYLUS_PRIMARY] inside the pen's own
 * motion stream (it comes from BTN_STYLUS on the digitizer). So we watch the
 * button bit on every pen event instead.
 *
 *   tap                 -> fire one of two configured tools, alternating
 *   hold past threshold -> open the radial wheel, pick by hovering, release to commit
 *
 * While the wheel is open every pen event is consumed, so hovering to choose a
 * slot cannot draw on the host. That is the whole reason this returns a
 * "consumed" flag rather than just firing callbacks.
 */
class PenButtonController(
    private val enabled: Boolean,
    private val longPressMs: Long,
    private val slots: List<PenWheelAction>,
    private val clickToolA: PenWheelAction,
    private val clickToolB: PenWheelAction,
    private val listener: Listener
) {
    interface Listener {
        /** Open the wheel centred on the given screen point. */
        fun showWheel(centerX: Float, centerY: Float, radius: Float)
        fun updateSelection(index: Int)
        fun hideWheel()
        fun fire(action: PenWheelAction)
    }

    private val handler = Handler(Looper.getMainLooper())

    private var viewportW = 0
    private var viewportH = 0

    private var buttonDown = false
    private var downAt = 0L
    private var lastEventAt = 0L
    private var wheelOpen = false
    private var selection = -1
    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f

    /** Which half of the click toggle fires next. */
    private var nextIsA = true

    private val openWheel: Runnable = Runnable {
        if (!buttonDown) return@Runnable
        wheelOpen = true
        selection = -1
        lastEventAt = SystemClock.uptimeMillis()
        listener.showWheel(centerX, centerY, radius)
        // Safety net: a release can be lost when the pen leaves the sensing
        // range first, which would otherwise leave the wheel on screen forever.
        handler.postDelayed(autoClose, IDLE_CLOSE_MS)
    }

    private val autoClose: Runnable = Runnable {
        if (!wheelOpen) return@Runnable
        val idle = SystemClock.uptimeMillis() - lastEventAt
        if (idle >= IDLE_CLOSE_MS) {
            // The release may never arrive; forget the press so the pen works again.
            buttonDown = false
            handler.removeCallbacks(openWheel)
            dismissWheel()
        } else {
            handler.postDelayed(autoClose, IDLE_CLOSE_MS - idle)
        }
    }

    fun setViewport(width: Int, height: Int) {
        viewportW = width
        viewportH = height
        radius = (minOf(width, height) * 0.36f).coerceAtMost(320f)
    }

    /**
     * @return true when the event belongs to the button gesture and must not be
     *         forwarded to the host.
     */
    fun onMotion(event: MotionEvent): Boolean {
        if (!enabled) return false

        val pressed = (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
        val x = event.x
        val y = event.y
        lastEventAt = SystemClock.uptimeMillis()

        // The pen left the digitizer's range: no further events (including the
        // button release) are guaranteed, so end the gesture here instead of
        // leaving the wheel up until the pen comes back.
        if (event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) {
            val wasActive = buttonDown || wheelOpen
            handler.removeCallbacks(openWheel)
            buttonDown = false
            if (wheelOpen) dismissWheel()
            if (wasActive) return true
        }

        // Keep the last pen position even while the button is not involved, so
        // the wheel can appear where the pen already is.
        //
        // Stop following once the wheel is open: it is anchored where it was
        // drawn, and a moving centre would make every sample land in the dead
        // zone, so no sector could ever be selected.
        if (!wheelOpen && event.actionMasked != MotionEvent.ACTION_HOVER_EXIT) {
            centerX = clampCenter(x, viewportW, radius)
            centerY = clampCenter(y, viewportH, radius)
        }

        if (pressed && !buttonDown) {
            buttonDown = true
            downAt = SystemClock.uptimeMillis()
            handler.postDelayed(openWheel, longPressMs)
            // Swallow the press itself: without this the host would also see a
            // pen-down and start a stroke under the wheel.
            return true
        }

        if (!pressed && buttonDown) {
            buttonDown = false
            handler.removeCallbacks(openWheel)
            if (wheelOpen) {
                commit()
            } else if (SystemClock.uptimeMillis() - downAt < longPressMs) {
                fireToggle()
            }
            return true
        }

        if (wheelOpen) {
            // Hovering selects; touching the screen confirms immediately, which
            // is what "tap to select" means for a pen that is already down.
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    updateSelectionFromPoint(x, y)
                    commit()
                }
                MotionEvent.ACTION_CANCEL -> dismissWheel()
                else -> updateSelectionFromPoint(x, y)
            }
            return true
        }

        // A held button that has not reached the threshold yet still belongs to
        // us; forwarding it would start a stroke mid-gesture.
        return buttonDown
    }

    /** Called when the activity goes away: never leave the wheel on screen. */
    fun cancel() {
        handler.removeCallbacks(openWheel)
        buttonDown = false
        if (wheelOpen) dismissWheel()
    }

    private fun fireToggle() {
        val action = if (nextIsA) clickToolA else clickToolB
        nextIsA = !nextIsA
        if (action.sendsKey) listener.fire(action)
    }

    private fun updateSelectionFromPoint(x: Float, y: Float) {
        val dx = x - centerX
        val dy = y - centerY
        val dist = hypot(dx, dy)

        if (slots.isEmpty()) {
            setSelection(-1)
            return
        }

        if (dist < radius * CENTER_DEAD_ZONE) {
            setSelection(-1) // hovering the hub cancels, like every radial menu
            return
        }

        val slice = 360f / slots.size
        val angle = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
        // Slot 0 sits at the top, so rotate -90deg into the 0..360 range.
        val normalized = (angle + 90f + 360f) % 360f
        val raw = ((normalized + slice / 2f) / slice).toInt() % slots.size

        // Hysteresis: a neighbouring sector only takes over once the pen is a
        // few degrees past the divider, so jitter on the boundary cannot make
        // the highlight flicker between two slots.
        val intoSector = Math.abs(((normalized - raw * slice + 540f) % 360f) - 180f)
        val next = when {
            raw == selection -> selection
            selection < 0 -> raw
            intoSector > slice / 2f - HYSTERESIS_DEG -> raw
            else -> selection
        }
        setSelection(next)
    }

    private fun setSelection(index: Int) {
        if (index != selection) {
            selection = index
            listener.updateSelection(index)
        }
    }

    private fun dismissWheel() {
        handler.removeCallbacks(autoClose)
        wheelOpen = false
        selection = -1
        listener.hideWheel()
    }

    private fun commit() {
        val index = selection
        dismissWheel()
        if (index in slots.indices) {
            val action = slots[index]
            if (action.sendsKey) listener.fire(action)
        }
    }

    private fun clampCenter(v: Float, extent: Int, r: Float): Float {
        if (extent <= 0) return v
        val margin = r * 1.12f
        return v.coerceIn(margin, (extent - margin).coerceAtLeast(margin))
    }

    companion object {
        /** Fraction of the radius that counts as "the hub", i.e. cancel. */
        private const val CENTER_DEAD_ZONE = 0.27f

        /**
         * Degrees the pen must travel past a divider before the neighbouring
         * sector wins, so a jittery hover cannot flicker between two slots.
         */
        private const val HYSTERESIS_DEG = 3f

        /** No pen event for this long while the wheel is up means the gesture is over. */
        private const val IDLE_CLOSE_MS = 2500L

        fun defaultSlots(): List<PenWheelAction> = PenWheelAction.defaultSlots
    }
}
