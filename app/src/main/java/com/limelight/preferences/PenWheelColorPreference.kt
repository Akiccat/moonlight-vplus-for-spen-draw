package com.limelight.preferences

import android.app.AlertDialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.limelight.R
import com.limelight.binding.input.PenWheelColor

/**
 * Picks the highlight color of one wheel sector.
 *
 * A plain ListPreference would show "amber" / "violet" as words, which tells the
 * user nothing about what they are actually choosing. So the row carries a
 * swatch of the current color and the picker is a grid of colored dots -- the
 * name is only ever a fallback for accessibility.
 */
class PenWheelColorPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : Preference(context, attrs) {

    /** Stored as the enum's [PenWheelColor.prefValue], never as a raw ARGB. */
    var value: String = PenWheelColor.DEFAULT.prefValue
        private set

    private var paletteDialog: AlertDialog? = null

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
            ?.setImageDrawable(dot(PenWheelColor.fromPref(value).argb, selected = false))
    }

    override fun onClick() {
        showPalette()
    }

    private fun refreshSummary() {
        val color = PenWheelColor.fromPref(value)
        summary = context.getString(color.labelRes)
    }

    private fun choose(color: PenWheelColor) {
        if (color.prefValue == value) return
        value = color.prefValue
        persistString(value)
        refreshSummary()
        notifyChanged()
    }

    private fun showPalette() {
        paletteDialog?.dismiss()
        val view = buildPalette()
        paletteDialog = AlertDialog.Builder(context, R.style.AppDialogStyle)
            .setTitle(title)
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun buildPalette(): View {
        val ctx = context
        val density = ctx.resources.displayMetrics.density
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (18 * density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
        }

        val colors = PenWheelColor.entries
        val perRow = 4
        for (rowStart in colors.indices step perRow) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            for (i in rowStart until minOf(rowStart + perRow, colors.size)) {
                row.addView(swatchView(colors[i], density))
            }
            container.addView(row)
        }
        return container
    }

    private fun swatchView(color: PenWheelColor, density: Float): View {
        val size = (56 * density).toInt()
        val pad = (6 * density).toInt()
        return ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(size, size)
            setPadding(pad, pad, pad, pad)
            contentDescription = context.getString(color.labelRes)
            setImageDrawable(dot(color.argb, selected = color.prefValue == value))
            background = context.obtainStyledAttributes(
                intArrayOf(android.R.attr.selectableItemBackgroundBorderless)
            ).let { attrs ->
                val d = attrs.getDrawable(0)
                attrs.recycle()
                d
            }
            setOnClickListener {
                choose(color)
                paletteDialog?.dismiss()
                paletteDialog = null
            }
        }
    }

    private fun dot(argb: Int, selected: Boolean): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(argb)
            // A ring is enough to mark the current pick and, unlike a checkmark,
            // it stays readable on both the pale and the saturated colors.
            setStroke(
                (if (selected) 3 else 1) * context.resources.displayMetrics.density.toInt().coerceAtLeast(1),
                if (selected) 0xFFFFFFFF.toInt() else 0x55FFFFFF
            )
        }
}
