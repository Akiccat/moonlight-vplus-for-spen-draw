package com.limelight.preferences

import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.limelight.R
import com.limelight.binding.input.PenWheelColor
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Color picker for one wheel sector: the preset dots plus a hue ring with an
 * inner tone disc and a hex field. Wheel, field and presets all stay in sync.
 */
internal object PenWheelColorPicker {

    fun show(context: Context, initialColor: Int, onPicked: (Int) -> Unit) {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + 0.5f).toInt()
        fun color(resId: Int) = ContextCompat.getColor(context, resId)

        var updating = false

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(6))
        }

        column.addView(
            TextView(context).apply {
                setText(R.string.pen_wheel_color_picker_hint)
                textSize = 12f
                setTextColor(color(R.color.ui_shell_text_secondary))
                setPadding(0, 0, 0, dp(8))
            }
        )

        val wheel = ColorWheelView(context)
        column.addView(
            wheel,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(250)
            ).apply { bottomMargin = dp(14) }
        )

        val swatch = View(context)
        val hexInput = EditText(context).apply {
            isSingleLine = true
            hint = context.getString(R.string.pen_wheel_color_hex_hint)
            textSize = 14f
            imeOptions = EditorInfo.IME_ACTION_DONE
            setTextColor(color(R.color.ui_shell_text_primary))
            setHintTextColor(color(R.color.ui_shell_text_secondary))
            background = rounded(
                color(R.color.ui_shell_surface_pressed),
                dp(8).toFloat(),
                color(R.color.ui_shell_outline)
            )
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }

        val entryRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        entryRow.addView(swatch, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
            rightMargin = dp(12)
        })
        entryRow.addView(
            hexInput,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        column.addView(entryRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(14) })

        val dotViews = mutableListOf<Pair<Int, ImageView>>()

        fun refreshPresetSelection(argb: Int) {
            val rgb = argb and 0xFFFFFF
            dotViews.forEach { (presetArgb, view) ->
                view.setImageDrawable(dot(context, presetArgb, selected = presetArgb == rgb))
            }
        }

        fun paintSwatch(argb: Int) {
            swatch.background = rounded(
                argb,
                dp(10).toFloat(),
                color(R.color.ui_shell_outline)
            )
        }

        fun applyColor(argb: Int) {
            updating = true
            wheel.setColor(argb)
            paintSwatch(argb)
            val hex = PenWheelColor.hexForArgb(argb)
            hexInput.setText(hex)
            hexInput.setSelection(hex.length)
            refreshPresetSelection(argb)
            updating = false
        }

        val presetGrid = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        PenWheelColor.entries.chunked(4).forEach { rowColors ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            rowColors.forEach { preset ->
                val view = ImageView(context).apply {
                    val size = dp(56)
                    layoutParams = LinearLayout.LayoutParams(size, size)
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                    contentDescription = context.getString(preset.labelRes)
                    background = context.obtainStyledAttributes(
                        intArrayOf(android.R.attr.selectableItemBackgroundBorderless)
                    ).let { attrs ->
                        val drawable = attrs.getDrawable(0)
                        attrs.recycle()
                        drawable
                    }
                    setOnClickListener { applyColor(preset.argb) }
                }
                row.addView(view)
                dotViews += preset.argb to view
            }
            presetGrid.addView(row)
        }
        column.addView(presetGrid)

        wheel.onColorChanged = {
            if (!updating) {
                val argb = wheel.color()
                updating = true
                paintSwatch(argb)
                val hex = PenWheelColor.hexForArgb(argb)
                hexInput.setText(hex)
                hexInput.setSelection(hex.length)
                refreshPresetSelection(argb)
                updating = false
            }
        }

        hexInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (updating) return
                val argb = PenWheelColor.parseHex(s?.toString()) ?: return
                updating = true
                wheel.setColor(argb)
                paintSwatch(argb)
                refreshPresetSelection(argb)
                updating = false
            }
        })

        applyColor(initialColor)

        val scroll = ScrollView(context).apply { addView(column) }

        AlertDialog.Builder(context, R.style.AppDialogStyle)
            .setTitle(R.string.title_pen_wheel_color)
            .setView(scroll)
            .setPositiveButton(R.string.game_menu_ok) { _, _ -> onPicked(wheel.color()) }
            .setNegativeButton(R.string.game_menu_cancel, null)
            .show()
    }

    private fun rounded(fill: Int, radius: Float, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(fill)
            cornerRadius = radius
            if (stroke != null) setStroke(1, stroke)
        }

    private fun dot(context: Context, argb: Int, selected: Boolean): GradientDrawable {
        val density = context.resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(argb)
            setStroke(
                if (selected) (3 * density).toInt() else 1,
                if (selected) 0xFFFFFFFF.toInt() else 0x55FFFFFF
            )
        }
    }

    /**
     * Hue ring plus an inner saturation/value disc. Touching the ring changes
     * hue; touching the disc changes saturation (horizontal) and brightness
     * (vertical).
     */
    private class ColorWheelView(context: Context) : View(context) {
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val markerFill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val markerStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeWidth = dp(2f)
        }
        private val clip = Path()

        private val ringWidth = dp(24f)
        private var centerX = 0f
        private var centerY = 0f
        private var outerRadius = 0f
        private var innerRadius = 0f

        private var hue = 0f
        private var saturation = 1f
        private var value = 1f

        var onColorChanged: (() -> Unit)? = null

        fun color(): Int = Color.HSVToColor(floatArrayOf(hue, saturation, value))

        fun setColor(argb: Int) {
            val hsv = FloatArray(3)
            Color.colorToHSV(argb, hsv)
            hue = hsv[0]
            saturation = hsv[1]
            value = hsv[2]
            updateDiscShader()
            invalidate()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            centerX = w / 2f
            centerY = h / 2f
            outerRadius = minOf(w, h) / 2f - dp(2f)
            innerRadius = (outerRadius - ringWidth - dp(8f)).coerceAtLeast(dp(24f))
            ringPaint.strokeWidth = ringWidth

            val shader = SweepGradient(
                centerX,
                centerY,
                intArrayOf(
                    Color.RED,
                    Color.YELLOW,
                    Color.GREEN,
                    Color.CYAN,
                    Color.BLUE,
                    Color.MAGENTA,
                    Color.RED
                ),
                null
            )
            // SweepGradient starts at 3 o'clock; rotate so hue 0 sits at the top.
            shader.setLocalMatrix(Matrix().apply { postRotate(-90f, centerX, centerY) })
            ringPaint.shader = shader
            updateDiscShader()
        }

        private fun updateDiscShader() {
            if (innerRadius <= 0f) return
            val left = centerX - innerRadius
            val top = centerY - innerRadius
            val right = centerX + innerRadius
            val bottom = centerY + innerRadius
            val pure = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
            val saturationShader = LinearGradient(
                left, top, right, top,
                Color.WHITE, pure, Shader.TileMode.CLAMP
            )
            val valueShader = LinearGradient(
                left, top, left, bottom,
                Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP
            )
            discPaint.shader = ComposeShader(valueShader, saturationShader, PorterDuff.Mode.SRC_OVER)
        }

        override fun onDraw(canvas: Canvas) {
            val ringRadius = outerRadius - ringWidth / 2f
            canvas.drawCircle(centerX, centerY, ringRadius, ringPaint)

            clip.reset()
            clip.addCircle(centerX, centerY, innerRadius, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clip)
            canvas.drawRect(
                centerX - innerRadius,
                centerY - innerRadius,
                centerX + innerRadius,
                centerY + innerRadius,
                discPaint
            )
            canvas.restore()

            val angle = Math.toRadians((hue - 90f).toDouble())
            val hueX = centerX + (ringRadius * cos(angle)).toFloat()
            val hueY = centerY + (ringRadius * sin(angle)).toFloat()
            markerFill.color = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
            canvas.drawCircle(hueX, hueY, dp(9f), markerFill)
            canvas.drawCircle(hueX, hueY, dp(9f), markerStroke)

            val satX = centerX - innerRadius + saturation * innerRadius * 2f
            val valY = centerY - innerRadius + (1f - value) * innerRadius * 2f
            markerFill.color = color()
            canvas.drawCircle(satX, valY, dp(9f), markerFill)
            canvas.drawCircle(satX, valY, dp(9f), markerStroke)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - centerX
                    val dy = event.y - centerY
                    if (hypot(dx, dy) >= innerRadius) {
                        val degrees = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
                        hue = (degrees + 90f + 360f) % 360f
                        updateDiscShader()
                    } else {
                        val left = centerX - innerRadius
                        val top = centerY - innerRadius
                        saturation = ((event.x - left) / (innerRadius * 2f)).coerceIn(0f, 1f)
                        value = (1f - (event.y - top) / (innerRadius * 2f)).coerceIn(0f, 1f)
                    }
                    invalidate()
                    onColorChanged?.invoke()
                    return true
                }
            }
            return super.onTouchEvent(event)
        }

        private fun dp(value: Float): Float = value * resources.displayMetrics.density
    }
}
