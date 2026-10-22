package com.limelight.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.limelight.R
import com.limelight.binding.input.PenWheelAction
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val PenWheelScrim = Color.Black.copy(alpha = 0.42f)
private val PenWheelText = Color(0xFFFFF7EC)
private val PenWheelDim = Color(0xFFFFF7EC).copy(alpha = 0.55f)

/**
 * Radial menu opened by holding the S Pen button.
 *
 * Motion is deliberately springy: the hub and every slot overshoot slightly and
 * settle, with the slots staggered so the wheel "unfolds" rather than fading in
 * as one flat block. The spring damping is below 1 on purpose -- that is what
 * produces the bounce.
 */
@Composable
internal fun PenWheelOverlay(
    visible: Boolean,
    centerX: Float,
    centerY: Float,
    radiusPx: Float,
    slots: List<PenWheelAction>,
    selection: Int,
    modifier: Modifier = Modifier
) {
    // Keep the wheel composed until its exit animation has finished.
    var present by remember { mutableStateOf(visible) }
    val scrim = remember { Animatable(if (visible) 1f else 0f) }

    LaunchedEffect(visible) {
        if (visible) present = true
        scrim.animateTo(if (visible) 1f else 0f, tween(if (visible) 110 else 150))
        if (!visible) {
            // Long enough for the staggered slot exit to land.
            kotlinx.coroutines.delay(200)
            present = false
        }
    }

    if (!present) return

    // radiusPx is a real pixel orbit; everything else is declared in dp, so
    // convert once instead of guessing a density.
    val density = LocalDensity.current
    val radiusDp = with(density) { radiusPx.toDp() }
    val clusterDp = radiusDp * 2.5f
    val slotDp = clusterDp * 0.30f
    val hubDp = clusterDp * 0.30f

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PenWheelScrim.copy(alpha = 0.42f * scrim.value.coerceIn(0f, 1f)))
    ) {
        Box(
            modifier = Modifier.offset {
                IntOffset(
                    (centerX - radiusPx * 1.25f).roundToInt(),
                    (centerY - radiusPx * 1.25f).roundToInt()
                )
            }.size(clusterDp),
            contentAlignment = Alignment.Center
        ) {
            slots.forEachIndexed { index, action ->
                val angle = (index.toFloat() / slots.size) * 2f * Math.PI.toFloat() - Math.PI.toFloat() / 2f
                PenWheelSlot(
                    action = action,
                    index = index,
                    count = slots.size,
                    visible = visible,
                    selected = index == selection,
                    size = slotDp,
                    modifier = Modifier.offset {
                        IntOffset(
                            (cos(angle) * radiusPx).roundToInt(),
                            (sin(angle) * radiusPx).roundToInt()
                        )
                    }
                )
            }
            PenWheelHub(
                visible = visible,
                selection = selection,
                slots = slots,
                size = hubDp
            )
        }
    }
}

@Composable
private fun PenWheelSlot(
    action: PenWheelAction,
    index: Int,
    count: Int,
    visible: Boolean,
    selected: Boolean,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val pop = remember { Animatable(0f) }

    LaunchedEffect(visible) {
        // Stagger: unfold outward on open, fold back inward on close.
        val delayMs = if (visible) index * 18L else (count - 1 - index) * 10L
        kotlinx.coroutines.delay(delayMs)
        pop.animateTo(
            targetValue = if (visible) 1f else 0f,
            animationSpec = spring(dampingRatio = 0.48f, stiffness = 380f)
        )
    }

    // Selection adds a small extra bounce on top of the entry animation.
    val selectBoost = remember { Animatable(1f) }
    LaunchedEffect(selected) {
        selectBoost.animateTo(
            targetValue = if (selected) 1.14f else 1f,
            animationSpec = spring(dampingRatio = 0.45f, stiffness = 520f)
        )
    }

    val scale = pop.value * selectBoost.value

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = pop.value.coerceIn(0f, 1f)
            },
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(
                if (selected) R.drawable.start_wheel_satellite_selected
                else R.drawable.start_wheel_satellite
            ),
            contentDescription = null,
            alpha = if (selected) 0.95f else 0.42f,
            modifier = Modifier.fillMaxSize()
        )
        Text(
            text = stringResource(action.labelRes),
            color = if (selected) PenWheelText else PenWheelDim,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
        if (action.sendsKey) {
            Text(
                text = action.shortcutLabel(
                    ctrlText = stringResource(R.string.pen_wheel_key_ctrl),
                    shiftText = stringResource(R.string.pen_wheel_key_shift),
                    altText = stringResource(R.string.pen_wheel_key_alt)
                ),
                color = PenWheelDim,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.offset(y = 14.dp)
            )
        }
    }
}

@Composable
private fun PenWheelHub(
    visible: Boolean,
    selection: Int,
    slots: List<PenWheelAction>,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val pop = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        kotlinx.coroutines.delay(if (visible) 0L else 40L)
        pop.animateTo(
            targetValue = if (visible) 1f else 0f,
            animationSpec = spring(dampingRatio = 0.5f, stiffness = 420f)
        )
    }

    val label = when {
        !visible -> ""
        selection < 0 -> stringResource(R.string.pen_wheel_hub_cancel)
        selection in slots.indices -> stringResource(slots[selection].labelRes)
        else -> ""
    }

    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = pop.value
                scaleY = pop.value
                alpha = pop.value.coerceIn(0f, 1f)
            },
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.start_wheel_center),
            contentDescription = null,
            alpha = 0.9f,
            modifier = Modifier.fillMaxSize()
        )
        Text(
            text = label,
            color = PenWheelText,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
    }
}
