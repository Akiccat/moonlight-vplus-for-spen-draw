package com.limelight.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.limelight.R
import com.limelight.binding.input.PenWheelAction
import com.limelight.binding.input.PenWheelColor
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val WheelScrim = Color.Black.copy(alpha = 0.42f)
private val WheelBacking = Color(0xF01A1714)
private val WheelIdleSector = Color(0xFF26221E)
private val WheelRim = Color(0x40FFE7C2)
private val WheelRimStrong = Color(0xCCFFF3DC)
private val WheelHub = Color(0xF2181411)
private val WheelText = Color(0xFFFFF7EC)
private val WheelTextDim = Color(0xFFFFF7EC).copy(alpha = 0.84f)
private val WheelTextOnBright = Color(0xFF1B1206)

/** Hub radius as a fraction of the wheel radius; must match the controller dead zone. */
private const val HUB_FRACTION = 0.27f

/**
 * Lottery-wheel style radial menu opened by holding the S Pen button.
 *
 * Every sector is painted the same dark gray; only the sector the pen points at
 * lights up, in the color configured for that slot. The controller selects by
 * angle alone, so the pen never has to land on a label.
 *
 * The opening animation is deliberately small -- a short scale pop, no spin.
 * A spinning wheel is tiring to look at every few seconds while drawing.
 */
@Composable
internal fun PenWheelOverlay(
    visible: Boolean,
    centerX: Float,
    centerY: Float,
    radiusPx: Float,
    slots: List<PenWheelAction>,
    colors: List<Int>,
    selection: Int,
    modifier: Modifier = Modifier
) {
    // Keep the wheel composed until its exit animation has finished.
    var present by remember { mutableStateOf(visible) }
    val scrim = remember { Animatable(if (visible) 1f else 0f) }
    val content = remember { Animatable(if (visible) 1f else 0f) }
    val scale = remember { Animatable(1f) }
    val selBoost = remember { Animatable(1f) }
    val hubPop = remember { Animatable(1f) }

    LaunchedEffect(visible) {
        if (visible) {
            present = true
            scale.snapTo(0.92f)
            hubPop.snapTo(0.78f)
            content.snapTo(0f)
            coroutineScope {
                launch { scrim.animateTo(1f, tween(90)) }
                launch { content.animateTo(1f, tween(100)) }
                launch { scale.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 520f)) }
                launch { hubPop.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 520f)) }
            }
        } else {
            // Fade the wheel itself out as well: leaving it opaque and only
            // fading the scrim makes the dismissal look like a hard cut.
            coroutineScope {
                launch { scrim.animateTo(0f, tween(80)) }
                launch { content.animateTo(0f, tween(80)) }
                launch { scale.animateTo(0.96f, tween(80)) }
            }
            delay(80)
            present = false
        }
    }

    LaunchedEffect(selection) {
        if (selection >= 0) {
            selBoost.snapTo(1.02f)
            selBoost.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 600f))
        }
    }

    if (!present) return

    val density = LocalDensity.current
    val diameter = with(density) { (radiusPx * 2f).toDp() }
    val hubSize = with(density) { (radiusPx * 2f * HUB_FRACTION).toDp() }
    val labelTop = with(density) { (radiusPx * 0.13f).toDp() }
    val labelBandHeight = with(density) { (radiusPx * 0.22f).toDp() }
    val labelBandWidth = with(density) { (radiusPx * 1.05f).toDp() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(WheelScrim.copy(alpha = 0.42f * scrim.value.coerceIn(0f, 1f)))
    ) {
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (centerX - radiusPx).roundToInt(),
                        (centerY - radiusPx).roundToInt()
                    )
                }
                .size(diameter)
                .graphicsLayer {
                    alpha = content.value
                    scaleX = scale.value
                    scaleY = scale.value
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2f
                val c = center
                val count = slots.size
                if (count == 0) return@Canvas

                val slice = 360f / count
                val gap = 1.4f
                val startOf = { index: Int -> -90f - slice / 2f + index * slice + gap / 2f }

                drawCircle(WheelBacking, radius = r, center = c)

                val chosen = selection
                for (i in slots.indices) {
                    if (i == chosen) continue
                    drawArc(
                        color = WheelIdleSector,
                        startAngle = startOf(i),
                        sweepAngle = slice - gap,
                        useCenter = true,
                        topLeft = Offset(c.x - r, c.y - r),
                        size = Size(r * 2f, r * 2f)
                    )
                }

                if (chosen in slots.indices) {
                    val rr = r * 1.02f * selBoost.value
                    drawArc(
                        color = slotColor(chosen, colors),
                        startAngle = startOf(chosen),
                        sweepAngle = slice - gap,
                        useCenter = true,
                        topLeft = Offset(c.x - rr, c.y - rr),
                        size = Size(rr * 2f, rr * 2f)
                    )
                    drawArc(
                        color = WheelRimStrong,
                        startAngle = startOf(chosen),
                        sweepAngle = slice - gap,
                        useCenter = true,
                        topLeft = Offset(c.x - rr, c.y - rr),
                        size = Size(rr * 2f, rr * 2f),
                        style = Stroke(width = 2.dp.toPx())
                    )
                }

                drawCircle(WheelRim, radius = r, center = c, style = Stroke(width = 1.5.dp.toPx()))
                drawCircle(WheelHub, radius = r * HUB_FRACTION, center = c)
                drawCircle(
                    WheelRim,
                    radius = r * HUB_FRACTION,
                    center = c,
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }

            slots.forEachIndexed { index, action ->
                val selected = index == selection
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { rotationZ = index * 360f / slots.size }
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = labelTop)
                            .width(labelBandWidth)
                            .height(labelBandHeight),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(action.labelRes),
                            color = if (selected) {
                                onSlotColor(slotColor(index, colors))
                            } else {
                                WheelTextDim
                            },
                            fontSize = 12.sp,
                            lineHeight = 13.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.graphicsLayer {
                                // Ring text reads from the hub outwards; flip the
                                // left half so it never ends up upside down.
                                rotationZ = labelRotation(index, slots.size)
                            }
                        )
                    }
                }
            }
        }

        // The hub stays upright: only the label pops, the wheel never spins.
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (centerX - radiusPx).roundToInt(),
                        (centerY - radiusPx).roundToInt()
                    )
                }
                .size(diameter),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .size(hubSize)
                    .graphicsLayer {
                        alpha = content.value
                        scaleX = hubPop.value * scale.value
                        scaleY = hubPop.value * scale.value
                    },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val chosen = selection
                Text(
                    text = if (chosen in slots.indices) {
                        stringResource(slots[chosen].labelRes)
                    } else {
                        stringResource(R.string.pen_wheel_hub_cancel)
                    },
                    color = WheelText,
                    fontSize = 13.sp,
                    lineHeight = 14.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 2
                )
                if (chosen in slots.indices && slots[chosen].sendsKey) {
                    Text(
                        text = slots[chosen].shortcutLabel(
                            ctrlText = stringResource(R.string.pen_wheel_key_ctrl),
                            shiftText = stringResource(R.string.pen_wheel_key_shift),
                            altText = stringResource(R.string.pen_wheel_key_alt)
                        ),
                        color = WheelTextDim,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

private fun slotColor(index: Int, colors: List<Int>): Color =
    Color(colors.getOrElse(index) { PenWheelColor.DEFAULT.argb })

/** Dark text on light accent colors, light text on dark ones. */
private fun onSlotColor(color: Color): Color =
    if (color.luminance() > 0.55f) WheelTextOnBright else WheelText

/** Rotation for a label so it reads from the hub outwards. */
private fun labelRotation(index: Int, count: Int): Float {
    val screenAngle = ((index * 360f / count - 90f) % 360f + 360f) % 360f
    val flip = screenAngle > 90f && screenAngle < 270f
    return -90f + if (flip) 180f else 0f
}
