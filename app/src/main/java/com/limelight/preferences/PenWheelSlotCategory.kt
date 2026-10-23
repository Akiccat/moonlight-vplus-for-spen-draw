package com.limelight.preferences

import android.content.Context
import android.view.View
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceViewHolder
import com.limelight.R

/**
 * A wheel slot header with a minus button, so slots can be added and removed
 * one at a time instead of being picked from a fixed count.
 */
class PenWheelSlotCategory(context: Context) : PreferenceCategory(context) {
    var onRemove: (() -> Unit)? = null
    var canRemove: Boolean = true

    init {
        layoutResource = R.layout.preference_category_pen_slot
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.itemView.findViewById<View>(R.id.pen_slot_remove)?.apply {
            visibility = if (canRemove) View.VISIBLE else View.INVISIBLE
            setOnClickListener { onRemove?.invoke() }
        }
    }
}
