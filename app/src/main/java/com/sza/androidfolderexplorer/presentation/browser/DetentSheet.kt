package com.sza.androidfolderexplorer.presentation.browser

import android.view.View
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Where an iOS-style sheet is. It has three resting places: hidden, a short one just tall enough for the part of the content
 * that matters ([pinsPx]), and full height. [offsetPx] is the sheet's top edge measured from the top of the window.
 */
@Stable
internal class DetentSheetState {
    var offsetPx by mutableFloatStateOf(Float.MAX_VALUE)
        internal set
    var hiddenY by mutableFloatStateOf(0f)
        internal set
    var fullY by mutableFloatStateOf(0f)
        internal set
    var shortY by mutableFloatStateOf(0f)
        internal set

    /** Height of the content that the short detent has to show completely. Set by the content once it is measured. */
    var pinsPx by mutableIntStateOf(0)

    /** The detent the sheet last settled in, so a change in content height can move it along with the short one. */
    internal var restingAtShort = false

    val isFull: Boolean get() = offsetPx <= fullY + 1f

    /** Where the current gesture began (NaN between gestures), so a flick from full height stops at the short detent. */
    internal var gestureStartY = Float.NaN

    internal fun dragBy(delta: Float): Float {
        val start = offsetPx.coerceAtMost(hiddenY)
        if (gestureStartY.isNaN()) gestureStartY = start
        val next = (start + delta).coerceIn(fullY, hiddenY)
        offsetPx = next
        return next - start
    }

    private suspend fun animateTo(target: Float) {
        animate(
            initialValue = offsetPx.coerceAtMost(hiddenY),
            targetValue = target,
            animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)
        ) { value, _ -> offsetPx = value }
        offsetPx = target
    }

    suspend fun expand() { restingAtShort = false; animateTo(fullY) }
    suspend fun toShort() { restingAtShort = true; animateTo(shortY) }
    suspend fun hide() { restingAtShort = false; animateTo(hiddenY) }

    /** After a drag or flick ends: go to the detent the movement points at (or the nearest one when it is slow). */
    internal suspend fun settle(velocityY: Float, onDismiss: () -> Unit) {
        val here = offsetPx.coerceAtMost(hiddenY)
        val anchors = listOf(fullY, shortY, hiddenY).distinct().sorted()
        val startedAtFull = !gestureStartY.isNaN() && gestureStartY <= fullY + 1f
        gestureStartY = Float.NaN
        var target = when {
            velocityY < -600f -> anchors.lastOrNull { it < here - 1f } ?: anchors.first()      // flicked up: the next one above
            velocityY > 600f -> anchors.firstOrNull { it > here + 1f } ?: anchors.last()       // flicked down: the next one below
            else -> anchors.minByOrNull { abs(it - here) } ?: shortY
        }
        // From full height one flick goes to the short detent, not all the way off the screen, unless it was dragged well past it.
        if (startedAtFull && target == hiddenY && here < shortY + (hiddenY - shortY) * 0.5f) target = shortY
        when (target) {
            fullY -> expand()
            hiddenY -> { hide(); onDismiss() }
            else -> toShort()
        }
    }
}

/**
 * A bottom sheet that behaves like the ones on iOS: it rests at a short height that fits its first section exactly, or at
 * full height, and you drag it between them (or flick, or pull down to close). All of [content] is laid out from the start,
 * so nothing loads or resizes while you drag; the sheet only moves.
 */
@Composable
internal fun DetentSheet(
    state: DetentSheetState,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val close: () -> Unit = { scope.launch { state.hide(); onDismissRequest() } }

    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        // No dimming or background from the dialog window itself: the scrim is drawn below, in step with the sheet.
        val view = LocalView.current
        SideEffect {
            (view.parent as? DialogWindowProvider)?.window?.apply {
                setDimAmount(0f)
                setBackgroundDrawableResource(android.R.color.transparent)
                decorView.systemUiVisibility = decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val windowPx = with(density) { maxHeight.toPx() }
            // A dialog window doesn't always report the system bars, so each margin has a sensible minimum.
            val statusTop = maxOf(WindowInsets.statusBars.getTop(density).toFloat(), with(density) { 24.dp.toPx() })
            val fullY = statusTop + with(density) { 8.dp.toPx() }
            val bottomInset = maxOf(WindowInsets.navigationBars.getBottom(density).toFloat(), with(density) { 24.dp.toPx() })
            val handlePx = with(density) { 28.dp.toPx() }
            // Just tall enough for the pins (plus the grab handle, the gesture bar and a little air), but never more than most
            // of the window.
            val wanted = handlePx + state.pinsPx + bottomInset + with(density) { 40.dp.toPx() }
            val shortHeight = wanted.coerceIn(with(density) { 200.dp.toPx() }, (windowPx - fullY) * 0.9f)
            state.hiddenY = windowPx
            state.fullY = fullY
            val oldShort = state.shortY
            state.shortY = windowPx - shortHeight

            // Slide in from the bottom once the content has been measured.
            LaunchedEffect(state.pinsPx > 0) {
                if (state.pinsPx > 0 && state.offsetPx >= windowPx) {
                    state.offsetPx = windowPx
                    state.toShort()
                }
            }
            // The content changed height (a pin was added or removed): follow it if the sheet is resting at the short detent.
            LaunchedEffect(state.shortY) {
                if (state.restingAtShort && oldShort != state.shortY && state.offsetPx < windowPx) state.toShort()
            }

            val connection = remember(state) { sheetConnection(state, close) }
            val progress = ((state.hiddenY - state.offsetPx.coerceAtMost(state.hiddenY)) /
                (state.hiddenY - state.shortY).coerceAtLeast(1f)).coerceIn(0f, 1f)

            // Scrim: tapping it closes the sheet.
            Box(
                Modifier.fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f * progress))
                    .pointerInput(Unit) { detectTapGestures { close() } }
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .height(with(density) { (windowPx - fullY).toDp() })
                    .offset { IntOffset(0, state.offsetPx.coerceAtMost(state.hiddenY).roundToInt()) }
                    .nestedScroll(connection)
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { delta -> state.dragBy(delta) },
                        onDragStopped = { velocity -> scope.launch { state.settle(velocity, onDismissRequest) } }
                    ),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 1.dp
            ) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().height(28.dp), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                        )
                    }
                    content()
                }
            }
        }
    }
}

/**
 * Lets the content scroll and the sheet move as one gesture: pulling up moves the sheet to full height before the content
 * scrolls, and pulling down at the top of the content moves the sheet down. Letting go settles on a detent.
 */
private fun sheetConnection(state: DetentSheetState, dismiss: () -> Unit) = object : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source == NestedScrollSource.UserInput && available.y < 0f && state.offsetPx > state.fullY) {
            return Offset(0f, state.dragBy(available.y))
        }
        return Offset.Zero
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source == NestedScrollSource.UserInput && available.y > 0f) return Offset(0f, state.dragBy(available.y))
        return Offset.Zero
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        // Between detents when the flick starts: let the sheet carry it instead of the content.
        if (state.offsetPx > state.fullY + 1f && state.offsetPx < state.hiddenY && available.y < 0f) {
            state.settle(available.y, dismiss)
            return available
        }
        return Velocity.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        if (state.offsetPx > state.fullY + 1f) {
            state.settle(available.y, dismiss)
            return available
        }
        return Velocity.Zero
    }
}
