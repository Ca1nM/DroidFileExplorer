package com.sza.androidfolderexplorer.presentation.browser

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints

/**
 * One-line text that, when it doesn't fit, is shortened in the middle ("Mario Kart 8 Deluxe [0100…][US].nsp")
 * so that the end of a file name, with its extension, stays visible. A plain end-ellipsis would cut exactly that off.
 */
@Composable
internal fun MiddleEllipsisText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified
) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val maxPx = constraints.maxWidth
        val shown = remember(text, maxPx, style) {
            if (maxPx == Constraints.Infinity) return@remember text
            fun fits(s: String) = measurer.measure(s, style, softWrap = false, maxLines = 1).size.width <= maxPx
            if (fits(text)) return@remember text
            val ext = text.substringAfterLast('.', "").length.let { if (it in 1..8) it + 1 else 0 }
            // Binary search for the most characters that still fit, keeping a tail that always holds the extension.
            var lo = 2
            var hi = text.length - 1
            var best = "…"
            while (lo <= hi) {
                val keep = (lo + hi) / 2
                val tail = maxOf((keep * 0.4).toInt(), minOf(keep / 2, ext + 3)).coerceIn(1, keep - 1)
                val candidate = text.take(keep - tail).trimEnd() + "…" + text.takeLast(tail)
                if (fits(candidate)) { best = candidate; lo = keep + 1 } else hi = keep - 1
            }
            best
        }
        Text(shown, style = style, color = color, maxLines = 1, softWrap = false)
    }
}
