package com.limelight.preferences

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.widget.ImageView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.limelight.R
import com.limelight.binding.input.PenWheelColor

/**
 * Picks the highlight color of one wheel sector.
 *
 * The row shows a live swatch; tapping opens [PenWheelColorPicker], which
 * offers the preset dots, a hue ring with a tone disc, and a hex field. The
 * stored value is either a preset name or a custom `#RRGGBB` string.
 */
class PenWheelColorPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Preference(context, attrs) {

    var value: String = PenWheelColor.DEFAULT.prefValue
        private set

    init {
        widgetLayoutResource = R.layout.preference_widget_color_swatch
        isPersistent = true
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        value = getPersistedString(
            (defaultValue as? String) ?: PenWheelColor.DEFAULT.prefValue
        )
        refreshSummary()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.itemView.findViewById<ImageView>(R.id.pen_color_swatch)
            ?.setImageDrawable(dot(PenWheelColor.argbFromPref(value)))
    }

    override fun onClick() {
        PenWheelColorPicker.show(context, PenWheelColor.argbFromPref(value)) { picked ->
            val preset = PenWheelColor.fromArgb(picked)
            value = preset?.prefValue ?: PenWheelColor.hexForArgb(picked)
            persistString(value)
            refreshSummary()
            notifyChanged()
        }
    }

    private fun refreshSummary() {
        val preset = PenWheelColor.entries.firstOrNull { it.prefValue == value }
        summary = if (preset != null) context.getString(preset.labelRes) else value
    }

    private fun dot(argb: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(argb)
            setStroke(
                context.resources.displayMetrics.density.toInt().coerceAtLeast(1),
                0x55FFFFFF
            )
        }
}
