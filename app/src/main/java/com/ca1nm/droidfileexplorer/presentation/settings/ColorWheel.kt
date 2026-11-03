package com.ca1nm.droidfileexplorer.presentation.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

internal val WheelHues = listOf(
    Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red
)

/**
 * Hue and saturation picker. A swipe that starts anywhere except on the dot is left alone, so the
 * page keeps scrolling. To change the colour, drag the dot, or press and hold anywhere on the disc
 * to jump the dot there and keep dragging. [onChange] fires when the finger lifts, so the app
 * doesn't re-theme on every frame of a drag.
 * [value] is the brightness (0..1) the disc is drawn at.
 */
@Composable
fun ColorWheel(
    hue: Float,
    saturation: Float,
    value: Float,
    onChange: (hue: Float, saturation: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    // Local copy so the marker follows the finger while dragging.
    var h by remember { mutableFloatStateOf(hue) }
    var s by remember { mutableFloatStateOf(saturation) }
    LaunchedEffect(hue, saturation) {
        h = hue
        s = saturation
    }
    val currentOnChange by rememberUpdatedState(onChange)
    val haptic = LocalHapticFeedback.current

    Canvas(
        modifier = modifier
            .aspectRatio(1f)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = true)
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val radius = min(center.x, center.y)
                    val angle = Math.toRadians(h.toDouble())
                    val marker = Offset(
                        center.x + (cos(angle) * s * radius).toFloat(),
                        center.y + (sin(angle) * s * radius).toFloat()
                    )
                    val startH = h
                    val startS = s
                    var dragging = false
                    if ((down.position - marker).getDistance() <= 40.dp.toPx()) {
                        // Grabbed the dot itself.
                        down.consume()
                        dragging = true
                    } else {
                        // Anywhere else: ignore a normal swipe (it scrolls the page), but a press-and-hold
                        // jumps the dot to the finger and carries on as a drag.
                        val held = awaitLongPressOrCancellation(down.id)
                        if (held != null) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            val (nh, ns) = pointToHueSat(held.position, size)
                            h = nh
                            s = ns
                            dragging = true
                        }
                    }
                    if (dragging) {
                        drag(down.id) { change ->
                            change.consume()
                            val (nh, ns) = pointToHueSat(change.position, size)
                            h = nh
                            s = ns
                        }
                        if (h != startH || s != startS) currentOnChange(h, s)
                    }
                }
            }
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension / 2f

        drawCircle(
            brush = Brush.sweepGradient(WheelHues, center),
            radius = radius,
            center = center
        )
        // White at the centre fading to transparent at the rim gives saturation from 0 to 1.
        drawCircle(
            brush = Brush.radialGradient(listOf(Color.White, Color.Transparent), center, radius),
            radius = radius,
            center = center
        )
        if (value < 1f) {
            drawCircle(color = Color.Black.copy(alpha = 1f - value), radius = radius, center = center)
        }

        val angle = Math.toRadians(h.toDouble())
        val distance = s * radius
        val marker = Offset(
            center.x + (cos(angle) * distance).toFloat(),
            center.y + (sin(angle) * distance).toFloat()
        )
        drawCircle(
            color = hsvColor(h, s, value),
            radius = 9.dp.toPx(),
            center = marker
        )
        drawCircle(
            color = Color.White,
            radius = 12.dp.toPx(),
            center = marker,
            style = Stroke(width = 3.dp.toPx())
        )
    }
}

/** Maps a point on the wheel to (hue in degrees 0..360, saturation 0..1). */
private fun pointToHueSat(point: Offset, size: IntSize): Pair<Float, Float> {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val radius = min(cx, cy)
    val dx = point.x - cx
    val dy = point.y - cy
    val saturation = (hypot(dx, dy) / radius).coerceIn(0f, 1f)
    val degrees = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
    return ((degrees + 360f) % 360f) to saturation
}

/** Colour from hue (degrees), saturation and value (0..1). */
internal fun hsvColor(hue: Float, saturation: Float, value: Float): Color =
    Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value)))
