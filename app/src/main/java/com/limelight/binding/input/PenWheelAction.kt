package com.limelight.binding.input

import android.view.KeyEvent
import com.limelight.R

/**
 * What a pen button can trigger.
 *
 * Every action is expressed as a shortcut that the host executes, because that
 * is the only way to drive CSP / Photoshop / Krita from the tablet: there is no
 * shared "switch tool" protocol, only keystrokes. Anything the drawing app
 * binds to a key can go in here.
 *
 * ctrl / shift / alt are passed to the host as a modifier mask. Sunshine
 * synthesises those modifier keys on the host side (see its input.cpp), so a
 * single down/up pair with the mask set is enough -- we never have to hold a
 * modifier ourselves, which keeps the local modifier state untouched even if a
 * packet is dropped mid-chord.
 */
enum class PenWheelAction(
    /** Stable key used in preferences. Never rename: it is stored in user settings. */
    val prefValue: String,
    val labelRes: Int,
    /** Android key code; 0 means "do nothing". */
    val androidKeyCode: Int,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false
) {
    NONE("none", R.string.pen_wheel_action_none, 0),
    BRUSH("brush", R.string.pen_wheel_action_brush, KeyEvent.KEYCODE_B),
    ERASER("eraser", R.string.pen_wheel_action_eraser, KeyEvent.KEYCODE_E),
    PENCIL("pencil", R.string.pen_wheel_action_pencil, KeyEvent.KEYCODE_N),
    EYEDROPPER("eyedropper", R.string.pen_wheel_action_eyedropper, KeyEvent.KEYCODE_I),
    HAND("hand", R.string.pen_wheel_action_hand, KeyEvent.KEYCODE_SPACE),
    LASSO("lasso", R.string.pen_wheel_action_lasso, KeyEvent.KEYCODE_M),
    FILL("fill", R.string.pen_wheel_action_fill, KeyEvent.KEYCODE_G),
    BRUSH_BIGGER("brush_bigger", R.string.pen_wheel_action_brush_bigger, KeyEvent.KEYCODE_RIGHT_BRACKET),
    BRUSH_SMALLER("brush_smaller", R.string.pen_wheel_action_brush_smaller, KeyEvent.KEYCODE_LEFT_BRACKET),
    UNDO("undo", R.string.pen_wheel_action_undo, KeyEvent.KEYCODE_Z, ctrl = true),
    REDO("redo", R.string.pen_wheel_action_redo, KeyEvent.KEYCODE_Y, ctrl = true),
    ZOOM_FIT("zoom_fit", R.string.pen_wheel_action_zoom_fit, KeyEvent.KEYCODE_0, ctrl = true),
    SAVE("save", R.string.pen_wheel_action_save, KeyEvent.KEYCODE_S, ctrl = true),
    NEW_LAYER("new_layer", R.string.pen_wheel_action_new_layer, KeyEvent.KEYCODE_N, ctrl = true, shift = true),
    ;

    /** True when this slot actually sends something. */
    val sendsKey: Boolean get() = androidKeyCode != 0

    /** Short badge shown on the wheel, e.g. "B" or "Ctrl+Z". */
    fun shortcutLabel(ctrlText: String, shiftText: String, altText: String): String {
        if (!sendsKey) return ""
        val parts = mutableListOf<String>()
        if (ctrl) parts += ctrlText
        if (alt) parts += altText
        if (shift) parts += shiftText
        parts += keyLabel()
        return parts.joinToString("+")
    }

    private fun keyLabel(): String = when (androidKeyCode) {
        KeyEvent.KEYCODE_SPACE -> "Space"
        KeyEvent.KEYCODE_LEFT_BRACKET -> "["
        KeyEvent.KEYCODE_RIGHT_BRACKET -> "]"
        KeyEvent.KEYCODE_0 -> "0"
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ->
            ('A'.code + (androidKeyCode - KeyEvent.KEYCODE_A)).toChar().toString()
        else -> androidKeyCode.toString()
    }

    companion object {
        /** Values exposed as ListPreference entryValues, in display order. */
        val prefValues: Array<String> = entries.map { it.prefValue }.toTypedArray()

        /** Labels matching [prefValues], filled in by the caller (needs a Context). */
        val labelResIds: Array<Int> = entries.map { it.labelRes }.toTypedArray()

        fun fromPref(value: String?): PenWheelAction =
            entries.firstOrNull { it.prefValue == value } ?: NONE

        /** Order the wheel slots ship with. */
        val defaultSlots: List<PenWheelAction> = listOf(
            BRUSH, ERASER, PENCIL, EYEDROPPER, HAND, UNDO, REDO, ZOOM_FIT
        )

        const val SLOT_COUNT = 8
    }
}
