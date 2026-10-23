package com.limelight.binding.input

import com.limelight.R

/**
 * Accent color of a wheel sector while it is the highlighted one.
 *
 * Every unselected sector is the same dark gray, so this only ever shows up on
 * the slice the pen is pointing at. Values are stored in preferences by
 * [prefValue], so those strings must never be renamed.
 */
enum class PenWheelColor(
    val prefValue: String,
    val labelRes: Int,
    val argb: Int
) {
    AMBER("amber", R.string.pen_wheel_color_amber, 0xFFFFC46B.toInt()),
    CORAL("coral", R.string.pen_wheel_color_coral, 0xFFFF8A65.toInt()),
    CRIMSON("crimson", R.string.pen_wheel_color_crimson, 0xFFE57373.toInt()),
    ROSE("rose", R.string.pen_wheel_color_rose, 0xFFF06292.toInt()),
    VIOLET("violet", R.string.pen_wheel_color_violet, 0xFFB39DDB.toInt()),
    BLUE("blue", R.string.pen_wheel_color_blue, 0xFF64B5F6.toInt()),
    CYAN("cyan", R.string.pen_wheel_color_cyan, 0xFF4DD0E1.toInt()),
    TEAL("teal", R.string.pen_wheel_color_teal, 0xFF4DB6AC.toInt()),
    GREEN("green", R.string.pen_wheel_color_green, 0xFF81C784.toInt()),
    LIME("lime", R.string.pen_wheel_color_lime, 0xFFDCE775.toInt()),
    WHITE("white", R.string.pen_wheel_color_white, 0xFFECEFF1.toInt()),
    ;

    companion object {
        val DEFAULT = AMBER

        /** Values exposed as ListPreference entryValues, in display order. */
        val prefValues: Array<String> = entries.map { it.prefValue }.toTypedArray()

        /** Labels matching [prefValues], filled in by the caller (needs a Context). */
        val labelResIds: Array<Int> = entries.map { it.labelRes }.toTypedArray()

        fun fromPref(value: String?): PenWheelColor =
            entries.firstOrNull { it.prefValue == value } ?: DEFAULT
    }
}
