package com.limelight.preferences

import android.content.Context
import android.util.AttributeSet
import androidx.preference.ListPreference
import androidx.preference.Preference

/**
 * A ListPreference for the S Pen wheel editor that owns its own summary.
 *
 * The shared "show current value" pass must skip it: that pass reads
 * [getSummary], which already contains this provider's output, so wrapping it
 * again would print the value twice under the title.
 */
class PenWheelListPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : ListPreference(context, attrs) {

    fun setDescription(description: CharSequence?) {
        summaryProvider = Preference.SummaryProvider<ListPreference> { pref ->
            val value = pref.entry?.toString()?.takeIf { it.isNotBlank() } ?: "—"
            if (description.isNullOrBlank()) value else "$value\n$description"
        }
    }
}
