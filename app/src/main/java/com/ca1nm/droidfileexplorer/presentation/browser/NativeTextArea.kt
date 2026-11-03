package com.ca1nm.droidfileexplorer.presentation.browser

import android.content.Context
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.EditText
import android.widget.OverScroller
import kotlin.math.abs
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import com.ca1nm.droidfileexplorer.data.settings.EditorOptions
import com.ca1nm.droidfileexplorer.data.text.Syntax
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** A colour span put on the text by syntax colouring (kept apart from the user's own selection and the Find highlight). */
private class SyntaxSpan(color: Int) : ForegroundColorSpan(color)

private fun clearSyntax(text: Editable) {
    for (s in text.getSpans(0, text.length, SyntaxSpan::class.java)) text.removeSpan(s)
}

private fun applySyntax(text: Editable, tokens: IntArray, palette: IntArray) {
    clearSyntax(text)
    var i = 0
    while (i + 2 < tokens.size) {
        val start = tokens[i]
        val end = tokens[i + 1]
        if (end > start && end <= text.length) text.setSpan(SyntaxSpan(palette[tokens[i + 2]]), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        i += 3
    }
}

/** Colours for each token kind: GitHub's dark and light code themes, which read well on both of this app's themes. */
private fun syntaxPalette(dark: Boolean): IntArray {
    val p = IntArray(Syntax.KIND_COUNT)
    fun c(kind: Int, dk: Long, lt: Long) { p[kind] = (if (dark) dk else lt).toInt() }
    c(Syntax.KEYWORD, 0xFFFF7B72, 0xFFCF222E)
    c(Syntax.STRING, 0xFFA5D6FF, 0xFF0A3069)
    c(Syntax.COMMENT, 0xFF8B949E, 0xFF6E7781)
    c(Syntax.NUMBER, 0xFF79C0FF, 0xFF0550AE)
    c(Syntax.FUNCTION, 0xFFD2A8FF, 0xFF8250DF)
    c(Syntax.TYPE, 0xFFFFA657, 0xFF953800)
    c(Syntax.VARIABLE, 0xFFFFA657, 0xFF953800)
    c(Syntax.TAG, 0xFF7EE787, 0xFF116329)
    c(Syntax.ATTR, 0xFF79C0FF, 0xFF0550AE)
    c(Syntax.HEADING, 0xFF79C0FF, 0xFF0550AE)
    c(Syntax.ADDED, 0xFF7EE787, 0xFF1A7F37)
    c(Syntax.REMOVED, 0xFFFFA198, 0xFFCF222E)
    c(Syntax.ERROR, 0xFFFF7B72, 0xFFCF222E)
    c(Syntax.WARN, 0xFFE3B341, 0xFF9A6700)
    c(Syntax.KEY, 0xFF7EE787, 0xFF116329)
    c(Syntax.BUILTIN, 0xFF79C0FF, 0xFF0550AE)
    return p
}

/**
 * The native text widget with proper touch scrolling. Android's own TextView scrolling has no fling (the text stops
 * the instant you let go) and re-measures every visible line on each touch event, which makes a big file stutter.
 * This version scrolls by dragging with precomputed limits, keeps going after a flick, and leaves taps, long-press
 * selection and the selection handles to the normal widget.
 */
internal class FlingEditText(context: Context) : EditText(context) {
    private val scroller = OverScroller(context)
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maxFling = ViewConfiguration.get(context).scaledMaximumFlingVelocity
    private var tracker: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var cachedMaxX = -1

    private fun maxY(): Int {
        val l = layout ?: return 0
        return maxOf(0, l.height + totalPaddingTop + totalPaddingBottom - height)
    }

    /** Longest line decides how far sideways you can scroll. The font is monospace, so its width is characters x one advance. */
    private fun maxX(): Int {
        if (!noWrap) return 0
        if (cachedMaxX < 0) {
            val s = text.toString()
            var longest = 0
            var run = 0
            for (ch in s) { if (ch == '\n') { if (run > longest) longest = run; run = 0 } else run++ }
            if (run > longest) longest = run
            val advance = paint.measureText("M")
            cachedMaxX = (longest * advance).toInt() + totalPaddingLeft + totalPaddingRight
        }
        return maxOf(0, cachedMaxX - width)
    }

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        cachedMaxX = -1
    }

    private var noWrap = false

    override fun setHorizontallyScrolling(whether: Boolean) {
        super.setHorizontallyScrolling(whether)
        noWrap = whether
        if (!whether) scrollTo(0, scrollY)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                tracker?.recycle(); tracker = VelocityTracker.obtain().also { it.addMovement(ev) }
                downX = ev.x; downY = ev.y; lastX = ev.x; lastY = ev.y; dragging = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return super.onTouchEvent(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                tracker?.addMovement(ev)
                if (!dragging && (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop)) {
                    dragging = true
                    // Stop the widget treating this gesture as a tap or long-press (and doing its own scrolling).
                    val cancel = MotionEvent.obtain(ev).also { it.action = MotionEvent.ACTION_CANCEL }
                    super.onTouchEvent(cancel); cancel.recycle()
                }
                if (dragging) {
                    val nx = (scrollX + (lastX - ev.x).toInt()).coerceIn(0, maxX())
                    val ny = (scrollY + (lastY - ev.y).toInt()).coerceIn(0, maxY())
                    scrollTo(nx, ny)
                    lastX = ev.x; lastY = ev.y
                    return true
                }
                return super.onTouchEvent(ev)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val t = tracker
                tracker = null
                if (dragging) {
                    t?.addMovement(ev)
                    t?.computeCurrentVelocity(1000, maxFling.toFloat())
                    val vx = t?.xVelocity ?: 0f
                    val vy = t?.yVelocity ?: 0f
                    if (ev.actionMasked == MotionEvent.ACTION_UP && (abs(vx) > minFling || abs(vy) > minFling)) {
                        scroller.fling(scrollX, scrollY, -vx.toInt(), -vy.toInt(), 0, maxX(), 0, maxY())
                        postInvalidateOnAnimation()
                    }
                    t?.recycle()
                    dragging = false
                    return true
                }
                t?.recycle()
                return super.onTouchEvent(ev)
            }
            else -> return super.onTouchEvent(ev)
        }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.currX, scroller.currY)
            postInvalidateOnAnimation()
        } else {
            super.computeScroll()
        }
    }
}

/**
 * A scrolling text area backed by Android's own text widget instead of a Compose text field. Compose lays out all of
 * a field's text at once, which freezes the app with a few megabytes; the native widget lays out only what is
 * visible, so multi-megabyte files stay responsive. Used for the text editor and the read-only raw text view.
 *
 * [text] is put in once when the area is created. [onReady] hands back the widget (read `.text` from it to save),
 * and [onEdited] fires after every change the user makes.
 */
@Composable
internal fun NativeTextArea(
    text: String,
    editable: Boolean,
    modifier: Modifier = Modifier,
    /** false = every line stays on one row and scrolls sideways ("infinite" rows); true = long lines wrap. */
    wrap: Boolean = false,
    onReady: (EditText) -> Unit = {},
    onEdited: () -> Unit = {},
    /** Called once, when the text has been laid out and drawn for the first time. */
    onFirstDraw: () -> Unit = {},
    /** The file's name; its type decides the syntax colouring (null or an unknown type stays plain). */
    fileName: String? = null
) {
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    val highlight = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f).toArgb()

    // Syntax colouring: tokenise off the main thread, then put the colours on as spans. After an edit it waits for a short
    // pause in typing first. Large files (see Syntax.MAX_CHARS) and unknown types stay plain.
    val lang = remember(fileName) { fileName?.let { Syntax.langFor(it) } }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val coloursOn = EditorOptions.syntax
    var widgetRef by remember { mutableStateOf<EditText?>(null) }
    var edits by remember { mutableIntStateOf(0) }
    val target = widgetRef
    LaunchedEffect(target, lang, coloursOn, dark, edits) {
        if (target == null) return@LaunchedEffect
        if (lang == null || !coloursOn) { clearSyntax(target.text); return@LaunchedEffect }
        if (edits > 0) delay(350)
        val snapshot = target.text.toString()
        if (snapshot.length > Syntax.MAX_CHARS) { clearSyntax(target.text); return@LaunchedEffect }
        val tokens = withContext(Dispatchers.Default) { Syntax.tokenize(snapshot, lang) }
        if (target.text.length != snapshot.length) return@LaunchedEffect   // edited meanwhile: the next pass redoes it
        applySyntax(target.text, tokens, syntaxPalette(dark))
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            FlingEditText(ctx).apply {
                background = null
                typeface = Typeface.MONOSPACE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.TOP or Gravity.START
                val pad = (12 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad + (8 * resources.displayMetrics.density).toInt(), pad)
                setHorizontallyScrolling(!wrap)
                isHorizontalScrollBarEnabled = true
                isVerticalScrollBarEnabled = true
                isScrollbarFadingEnabled = true
                // The widget would otherwise copy its whole text into the saved instance state when the app goes
                // to the background, which is far over the size limit for a few megabytes.
                isSaveEnabled = false
                // Cheapest line breaking: no hyphenation, no balancing.
                breakStrategy = LineBreaker.BREAK_STRATEGY_SIMPLE
                hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                setTextColor(color)
                setHighlightColor(highlight)
                setText(text)
                viewTreeObserver.addOnDrawListener(object : android.view.ViewTreeObserver.OnDrawListener {
                    var done = false
                    override fun onDraw() {
                        if (done) return
                        done = true
                        post { viewTreeObserver.removeOnDrawListener(this) }   // can't be removed during the draw itself
                        onFirstDraw()
                    }
                })
                if (editable) {
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                        override fun afterTextChanged(s: Editable?) { edits++; onEdited() }
                    })
                } else {
                    // Read-only: selectable and copyable, but no keyboard.
                    keyListener = null
                    setTextIsSelectable(true)
                    isCursorVisible = false
                }
                onReady(this)
            }
        },
        update = {
            if (widgetRef !== it) widgetRef = it
            it.setTextColor(color)
            it.setHorizontallyScrolling(!wrap)   // does nothing when unchanged
        }
    )
}
