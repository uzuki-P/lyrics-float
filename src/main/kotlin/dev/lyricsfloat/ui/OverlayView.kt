package dev.lyricsfloat.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lyricsfloat.lyrics.LyricsEntry
import dev.lyricsfloat.lyrics.LyricsItem
import dev.lyricsfloat.lyrics.LyricsState
import dev.lyricsfloat.lyrics.WordTimestamp
import dev.lyricsfloat.lyrics.currentIndexAt
import dev.lyricsfloat.lyrics.synthesizeWords
import dev.lyricsfloat.lyrics.withIntervalIndicators
import dev.lyricsfloat.mpris.ActivePlayback
import kotlinx.coroutines.isActive
import java.awt.Cursor
import kotlin.math.roundToInt

/** Metrolist's LyricsTextPosition setting: default line alignment. */
enum class LyricsTextPosition { LEFT, CENTER, RIGHT }

enum class HoverMenuPosition { TOP, BOTTOM }

private val TEXT_SHADOW = Shadow(Color.Black.copy(alpha = 0.75f), Offset(0f, 2f), 8f)
private val SUB_SHADOW = Shadow(Color.Black.copy(alpha = 0.7f), Offset(0f, 1f), 5f)
private val OUTLINE_COLOR = Color.Black.copy(alpha = 0.85f)

/**
 * Lyric text with an outline instead of a shadow: a stroked back layer under
 * the filled front layer (Compose drawStyle = Stroke). Reads cleanly on any
 * background, including a fully transparent pill. With [outline] off, falls
 * back to a drop shadow.
 */
@Composable
private fun LyricText(
    text: String,
    style: TextStyle,
    color: Color,
    align: TextAlign,
    maxLines: Int,
    outline: Boolean,
    outlinePx: Float,
    modifier: Modifier = Modifier,
) {
    if (outline) {
        Box(modifier) {
            Text(
                text = text,
                style = style.copy(drawStyle = Stroke(width = outlinePx, join = StrokeJoin.Round)),
                color = OUTLINE_COLOR,
                textAlign = align,
                maxLines = maxLines,
                softWrap = true,
                overflow = TextOverflow.Clip,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = text,
                style = style,
                color = color,
                textAlign = align,
                maxLines = maxLines,
                softWrap = true,
                overflow = TextOverflow.Clip,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    } else {
        Text(
            text = text,
            style = style.copy(shadow = TEXT_SHADOW),
            color = color,
            textAlign = align,
            maxLines = maxLines,
            softWrap = true,
            overflow = TextOverflow.Clip,
            modifier = modifier.fillMaxWidth(),
        )
    }
}

/** Same as [LyricText] for karaoke text: the outline layer is plain, the fill layer carries per-word colors. */
@Composable
private fun LyricKaraokeText(
    plainText: String,
    words: List<WordTimestamp>,
    positionSeconds: Double,
    bright: Color,
    dim: Color,
    smoothTransition: Boolean,
    style: TextStyle,
    align: TextAlign,
    outline: Boolean,
    outlinePx: Float,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    if (outline) {
        Box(modifier) {
            Text(
                text = plainText,
                style = style.copy(drawStyle = Stroke(width = outlinePx, join = StrokeJoin.Round)),
                color = OUTLINE_COLOR,
                textAlign = align,
                maxLines = Int.MAX_VALUE,
                softWrap = true,
                overflow = TextOverflow.Clip,
                modifier = Modifier.fillMaxWidth(),
            )
            KaraokeColorFill(
                text = plainText,
                words = words,
                positionSeconds = positionSeconds,
                bright = bright,
                dim = dim,
                smoothTransition = smoothTransition,
                style = style,
                align = align,
                textMeasurer = textMeasurer,
                density = density,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    } else {
        KaraokeColorFill(
            text = plainText,
            words = words,
            positionSeconds = positionSeconds,
            bright = bright,
            dim = dim,
            smoothTransition = smoothTransition,
            style = style.copy(shadow = TEXT_SHADOW),
            align = align,
            textMeasurer = textMeasurer,
            density = density,
            modifier = modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun KaraokeColorFill(
    text: String,
    words: List<WordTimestamp>,
    positionSeconds: Double,
    bright: Color,
    dim: Color,
    smoothTransition: Boolean,
    style: TextStyle,
    align: TextAlign,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    density: androidx.compose.ui.unit.Density,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth
        val layout = remember(text, style, width, align) {
            textMeasurer.measure(
                text = text,
                style = style.copy(textAlign = align),
                constraints = Constraints(minWidth = width, maxWidth = width),
                softWrap = true,
            )
        }
        Canvas(Modifier.fillMaxWidth().height(with(density) { layout.size.height.toDp() })) {
            drawText(layout, color = dim)

            var searchFrom = 0
            words.forEach { word ->
                if (word.text.isEmpty()) return@forEach
                val start = text.indexOf(word.text, searchFrom)
                if (start < 0) return@forEach
                val end = (start + word.text.length).coerceAtMost(text.length)
                searchFrom = end
                if (word.hasTrailingSpace && searchFrom < text.length && text[searchFrom].isWhitespace()) {
                    searchFrom++
                }

                val color = if (smoothTransition) {
                    val progress = ((positionSeconds - word.startTime) / (word.endTime - word.startTime))
                        .toFloat().coerceIn(0f, 1f)
                    androidx.compose.ui.graphics.lerp(dim, bright, progress)
                } else if (positionSeconds >= word.startTime) bright else dim

                val boundsByLine = mutableMapOf<Int, androidx.compose.ui.geometry.Rect>()
                for (offset in start until end) {
                    val line = layout.getLineForOffset(offset)
                    val bounds = layout.getBoundingBox(offset)
                    boundsByLine[line] = boundsByLine[line]?.let {
                        androidx.compose.ui.geometry.Rect(
                            left = minOf(it.left, bounds.left),
                            top = minOf(it.top, bounds.top),
                            right = maxOf(it.right, bounds.right),
                            bottom = maxOf(it.bottom, bounds.bottom),
                        )
                    } ?: bounds
                }
                boundsByLine.values.forEach { bounds ->
                    clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom) {
                        drawText(layout, color = color)
                    }
                }
            }
        }
    }
}

/**
 * Content of the floating lyrics pill. Position comes from the monitor's
 * interpolated clock, read every frame; no D-Bus traffic happens here.
 * Rendering follows Metrolist's experimental lyrics: a scrollable stack with
 * animated auto-scroll (750 ms, FastOutSlowIn), karaoke word fill, interval
 * indicator dots, agent positioning and romaji sub-lines. The background can
 * be fully transparent (0% opacity) - readability comes from text shadows.
 */
@Composable
fun OverlayView(
    playback: ActivePlayback?,
    lyrics: LyricsState,
    loading: Boolean,
    fontSizeSp: Int,
    romajiFontSizeSp: Int,
    opacity: Float,
    showNext: Boolean,
    offsetMs: Long,
    showIntervalIndicator: Boolean,
    respectAgentPositioning: Boolean,
    wordKaraoke: Boolean,
    romanizeJapanese: Boolean,
    textPosition: LyricsTextPosition,
    textOutline: Boolean,
    autoScroll: Boolean,
    clickPassThrough: Boolean,
    hoverMenuPosition: HoverMenuPosition,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onToggleClickPassThrough: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (dx: Float, dy: Float) -> Unit,
    onDragEnd: () -> Unit,
    onResizeStart: (direction: Int) -> Boolean,
    onManualResize: (direction: Int, dx: Float, dy: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalAppPalette.current

    var positionMs by remember { mutableStateOf(0L) }
    LaunchedEffect(playback) {
        while (isActive) {
            positionMs = playback?.positionMs() ?: 0L
            withFrameNanos { }
        }
    }

    val entries = lyrics.entries
    val items = remember(entries, showIntervalIndicator) {
        entries.withIntervalIndicators(showIntervalIndicator)
    }

    var hovered by remember { mutableStateOf(false) }
    val pillShape = RoundedCornerShape(22.dp)
    val hasVisibleBg = opacity >= 0.05f
    val showBorder = opacity >= 0.2f

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(2.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(pillShape)
                .then(
                    if (hasVisibleBg) {
                        Modifier.background(palette.surface.copy(alpha = opacity))
                    } else {
                        Modifier
                    },
                )
                .then(
                    if (showBorder) {
                        Modifier.border(1.dp, palette.onSurface.copy(alpha = 0.12f), pillShape)
                    } else {
                        Modifier
                    },
                )
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            when (event.type) {
                                PointerEventType.Enter -> hovered = true
                                PointerEventType.Exit -> hovered = false
                                else -> {}
                            }
                        }
                    }
                }
                .pointerInput(Unit) {
                    // Window move gesture. When lyrics are showing the viewport
                    // consumes vertical drags for scrolling, so moving is done
                    // from the hover header (like a title bar) or the hint
                    // states below.
                    detectDragGestures(
                        onDragStart = { onDragStart() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            onDrag(dragAmount.x, dragAmount.y)
                        },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragEnd() },
                    )
                },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            ) {
                // The menu also acts as a move handle while lyrics occupy the pill.
                if (hoverMenuPosition == HoverMenuPosition.TOP) AnimatedVisibility(visible = hovered) {
                    HoverMenu(playback, palette, onDragStart, onDrag, onDragEnd, onSearch, onSettings,
                        clickPassThrough, onToggleClickPassThrough)
                }

                when {
                    playback == null -> {
                        HintLine("Play something in any media player", palette)
                        if (hoverMenuPosition == HoverMenuPosition.BOTTOM) Spacer(Modifier.weight(1f))
                    }
                    loading -> {
                        HintLine("Looking up lyrics…", palette)
                        if (hoverMenuPosition == HoverMenuPosition.BOTTOM) Spacer(Modifier.weight(1f))
                    }
                    entries.isEmpty() -> {
                        HintLine("No lyrics found", palette)
                        if (hoverMenuPosition == HoverMenuPosition.BOTTOM) Spacer(Modifier.weight(1f))
                    }
                    else -> {
                        LyricsViewport(
                            lyrics = lyrics,
                            items = items,
                            positionMs = positionMs,
                            offsetMs = offsetMs,
                            fontSizeSp = fontSizeSp,
                            romajiFontSizeSp = romajiFontSizeSp,
                            autoScrollDefault = autoScroll,
                            showIntervalIndicator = showIntervalIndicator,
                            respectAgentPositioning = respectAgentPositioning,
                            wordKaraoke = wordKaraoke,
                            romanizeJapanese = romanizeJapanese,
                            textPosition = textPosition,
                            textOutline = textOutline,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        )
                    }
                }
                if (hoverMenuPosition == HoverMenuPosition.BOTTOM) AnimatedVisibility(visible = hovered) {
                    HoverMenu(playback, palette, onDragStart, onDrag, onDragEnd, onSearch, onSettings,
                        clickPassThrough, onToggleClickPassThrough)
                }
            }

            // Invisible edge/corner zones that hand the drag to the WM's
            // interactive resize. Drawn after the content so they win the
            // pointer over the pill gestures.
            ResizeZones(onResizeStart, onManualResize)
        }
    }
}

/**
 * The scrollable lyrics stack, following Metrolist: every line is laid out in
 * flow, auto-scroll animates the active line to ~38% of the viewport height
 * (750 ms FastOutSlowIn), manual drag or wheel scroll pauses auto-scroll and
 * shows a Sync button. A taller window simply reveals more lines.
 */
@Composable
private fun LyricsViewport(
    lyrics: LyricsState,
    items: List<LyricsItem>,
    positionMs: Long,
    offsetMs: Long,
    fontSizeSp: Int,
    romajiFontSizeSp: Int,
    autoScrollDefault: Boolean,
    showIntervalIndicator: Boolean,
    respectAgentPositioning: Boolean,
    wordKaraoke: Boolean,
    romanizeJapanese: Boolean,
    textPosition: LyricsTextPosition,
    textOutline: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val palette = LocalAppPalette.current
    val entries = lyrics.entries

    // Reset manual scroll when the song changes.
    var autoScrolling by remember { mutableStateOf(autoScrollDefault) }
    var manualOffsetPx by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(lyrics.trackKey) {
        autoScrolling = autoScrollDefault
        manualOffsetPx = 0f
    }

    val currentIdx = entries.currentIndexAt(positionMs, offsetMs)
    val effPos = positionMs + offsetMs
    val gap = if (showIntervalIndicator) {
        items.filterIsInstance<LyricsItem.Indicator>()
            .firstOrNull { effPos >= it.gapStartMs && effPos < it.gapEndMs }
    } else {
        null
    }

    val focusItemIndex = when {
        gap != null -> items.indexOfFirst { it == gap }.coerceAtLeast(0)
        else -> items.indexOfFirst { it is LyricsItem.Line && it.index == currentIdx.coerceAtLeast(0) }.coerceAtLeast(0)
    }

    // The lyric column moves in a graphics layer. Clip at the viewport so
    // lines cannot paint over the hover header or outside a resized pill.
    BoxWithConstraints(modifier.clipToBounds()) {
        val viewportPx = with(density) { maxHeight.toPx() }
        val heights = remember(items) { mutableStateMapOf<Int, Int>() }
        val contentPx = heights.values.sum()
        val lineGapDp = (fontSizeSp * 0.22).dp
        val lineGapPx = with(density) { lineGapDp.toPx() }
        // Metrolist places indicators and background vocals directly after
        // the preceding item. Other lines get the normal line gap.
        fun gapBefore(index: Int): Float = when {
            index == 0 -> 0f
            items[index] is LyricsItem.Indicator -> 0f
            (items[index] as? LyricsItem.Line)?.entry?.isBackground == true -> 0f
            else -> lineGapPx
        }
        fun gapsBefore(index: Int): Float = (1..index).fold(0f) { total, i -> total + gapBefore(i) }
        val totalGapPx = gapsBefore(items.lastIndex)
        // Outline stroke width for lyric text, scaled with the font size.
        val outlinePx = with(density) { (fontSizeSp * 0.14).sp.toPx() }.coerceAtLeast(1.2f)

        // Anchor space so the active line can sit centered and the first/last
        // lines never clip against the pill edge when the offset clamps to 0
        // (song start, short lyrics). Same trick as Metrolist's 35% anchor
        // with padded ends — implemented as a static top offset in the
        // graphics layer rather than layout padding: padding would shrink the
        // column's measure constraints to zero height and hide every line.
        val anchorPx = viewportPx * 0.5f
        // Scroll span: from "content top at anchor" until "content bottom at
        // the viewport bottom". Overshooting would detach the content from the
        // viewport (cropped top, empty bottom) — visible right after resizes.
        val maxOffset = (contentPx + totalGapPx - (viewportPx - anchorPx))
            .coerceAtLeast(0f)

        // Re-clamp the manual offset when the window resizes, or a stale
        // offset leaves the content detached from the new, smaller viewport.
        LaunchedEffect(items, viewportPx) {
            if (!autoScrolling) {
                manualOffsetPx = manualOffsetPx.coerceIn(0f, maxOffset)
            }
        }

        val target = if (autoScrolling) {
            val before = (0 until focusItemIndex).sumOf { heights[it] ?: 0 }
            val focusH = heights[focusItemIndex] ?: 0
            // Bringing the item center to the anchor needs exactly this much
            // scroll on top of the static anchor offset.
            (before + gapsBefore(focusItemIndex) + focusH / 2f).coerceIn(0f, maxOffset)
        } else {
            null
        }
        val animated by animateFloatAsState(
            targetValue = target ?: 0f,
            animationSpec = tween(750, easing = FastOutSlowInEasing),
            label = "auto-scroll",
        )
        val scrollOffset = if (autoScrolling) animated else manualOffsetPx
        // Edge fade so lines dissolve at the pill bounds instead of being cut
        // mid-glyph (Metrolist fades its edges the same way).
        val fadePx = with(density) { 26.dp.toPx() }

        Column(
            modifier = Modifier
                // Every line must be measured, including those outside the
                // visible viewport. A viewport-height Column makes later
                // children measure at zero height and breaks scroll bounds.
                .fillMaxWidth()
                .wrapContentHeight(align = Alignment.Top, unbounded = true)
                .graphicsLayer { translationY = anchorPx - scrollOffset }
                .pointerInput(items, viewportPx) {
                    // Manual scroll (drag): pauses auto-scroll like Metrolist.
                    // Clamp against live heights — the captured snapshot from
                    // launch time is empty and would pin the offset to zero.
                    fun liveMax(): Float =
                        (heights.values.sum() + totalGapPx - (viewportPx - anchorPx))
                            .coerceAtLeast(0f)
                    detectDragGestures(
                        onDragStart = {
                            autoScrolling = false
                            manualOffsetPx = manualOffsetPx.coerceIn(0f, liveMax())
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            autoScrolling = false
                            manualOffsetPx = (manualOffsetPx + amount.y * density.density)
                                .coerceIn(0f, liveMax())
                        },
                    )
                }
                .pointerInput(items, viewportPx) {
                    // Mouse wheel scroll, same pause behavior.
                    fun liveMax(): Float =
                        (heights.values.sum() + totalGapPx - (viewportPx - anchorPx))
                            .coerceAtLeast(0f)
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                            if (delta != 0f) {
                                autoScrolling = false
                                manualOffsetPx = (manualOffsetPx + delta * 48f).coerceIn(0f, liveMax())
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                },
        ) {
            // Running content-space top of each item, for the edge fade.
            var runningTopPx = 0f
            items.forEachIndexed { itemIndex, item ->
                val gapPx = gapBefore(itemIndex)
                if (gapPx > 0f) Spacer(Modifier.height(lineGapDp))
                val itemTopPx = runningTopPx + gapPx
                val itemHeightPx = heights[itemIndex] ?: 0
                runningTopPx = itemTopPx + itemHeightPx

                val distance = when (item) {
                    is LyricsItem.Line -> kotlin.math.abs(item.index - currentIdx.coerceAtLeast(0))
                    is LyricsItem.Indicator -> kotlin.math.abs(item.afterLineIndex - currentIdx.coerceAtLeast(0))
                }
                val dim = when {
                    distance == 0 -> 1f
                    distance == 1 -> 0.3f
                    distance == 2 -> 0.2f
                    distance == 3 -> 0.15f
                    else -> 0.08f
                }
                // Lines dissolve over the top/bottom fade band instead of
                // being guillotined mid-glyph at the pill edge.
                val viewportTop = anchorPx - scrollOffset + itemTopPx
                val viewportBottom = viewportTop + itemHeightPx
                val fade = ((viewportBottom / fadePx).coerceIn(0f, 1f)) *
                    (((viewportPx - viewportTop) / fadePx).coerceIn(0f, 1f))
                val alpha = dim * fade
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { heights[itemIndex] = it.height },
                ) {
                    when (item) {
                        is LyricsItem.Line -> LyricLineItem(
                            entry = item.entry,
                            isActive = item.index == currentIdx && gap == null,
                            activeColor = palette.accent,
                            positionMs = positionMs,
                            offsetMs = offsetMs,
                            synced = lyrics.synced,
                            fontSizeSp = fontSizeSp,
                            romajiFontSizeSp = romajiFontSizeSp,
                            wordKaraoke = wordKaraoke,
                            romanizeJapanese = romanizeJapanese,
                            textOutline = textOutline,
                            outlinePx = outlinePx,
                            align = alignFor(item.entry.agent, respectAgentPositioning, textPosition),
                            alpha = alpha,
                        )
                        is LyricsItem.Indicator -> {
                            // Metrolist hides the ring 650 ms before the next
                            // line starts, and while the user scrolls manually.
                            val visible = autoScrolling &&
                                positionMs >= item.gapStartMs &&
                                positionMs <= item.gapEndMs - 650L
                            IntervalIndicator(
                                gapStartMs = item.gapStartMs,
                                gapEndMs = item.gapEndMs - 650L,
                                currentPositionMs = positionMs,
                                visible = visible,
                                color = palette.accent,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }

        if (!autoScrolling) {
            // Metrolist's resync overlay: pressing it hands control back to
            // the playback position.
            Text(
                text = "Sync",
                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, shadow = SUB_SHADOW),
                color = palette.accent,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(palette.surface.copy(alpha = 0.85f))
                    .border(1.dp, palette.accent.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    .clickable {
                        autoScrolling = true
                        manualOffsetPx = 0f
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** One line (or its karaoke/romaji variant) inside the scroll stack. */
@Composable
private fun LyricLineItem(
    entry: LyricsEntry,
    isActive: Boolean,
    activeColor: Color,
    positionMs: Long,
    offsetMs: Long,
    synced: Boolean,
    fontSizeSp: Int,
    romajiFontSizeSp: Int,
    wordKaraoke: Boolean,
    romanizeJapanese: Boolean,
    textOutline: Boolean,
    outlinePx: Float,
    align: TextAlign,
    alpha: Float,
) {
    val romanized by entry.romanizedTextFlow.collectAsState()
    val showRomaji = romanizeJapanese && !romanized.isNullOrBlank()

    val activeSize = fontSizeSp
    val contextSize = (fontSizeSp * 0.72).roundToInt()

    Column(modifier = Modifier.alpha(alpha)) {
        if (isActive) {
            val effPosSec = (positionMs + offsetMs) / 1000.0
            val bright = activeColor
            val hasWordTimings = !entry.words.isNullOrEmpty()
            val dimWord = if (hasWordTimings) Color.White else activeColor.copy(alpha = 0.32f)

            val words = when {
                !wordKaraoke || !synced -> null
                !entry.words.isNullOrEmpty() -> entry.words
                entry.text.isNotBlank() -> remember(entry.text, entry.time) {
                    synthesizeWords(entry.text, entry.time)
                }
                else -> null
            }

            val textStyle = TextStyle(
                fontSize = activeSize.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = (activeSize * 1.3).sp,
            )

            if (words == null) {
                LyricText(
                    text = entry.text.ifBlank { "♪" },
                    style = textStyle,
                    color = bright,
                    align = align,
                    maxLines = Int.MAX_VALUE,
                    outline = textOutline,
                    outlinePx = outlinePx,
                )
            } else {
                LyricKaraokeText(
                    plainText = entry.text,
                    words = words,
                    positionSeconds = effPosSec,
                    bright = bright,
                    dim = dimWord,
                    smoothTransition = !hasWordTimings,
                    style = textStyle,
                    align = align,
                    outline = textOutline,
                    outlinePx = outlinePx,
                )
            }
        } else {
            LyricText(
                text = entry.text.ifBlank { "♪" },
                style = TextStyle(
                    fontSize = contextSize.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = (contextSize * 1.3).sp,
                ),
                color = Color.White,
                align = align,
                maxLines = Int.MAX_VALUE,
                outline = textOutline,
                outlinePx = outlinePx * 0.8f,
            )
        }

        if (showRomaji) {
            val size = if (isActive) romajiFontSizeSp else (romajiFontSizeSp * 0.72).roundToInt()
            LyricText(
                text = romanized!!,
                style = TextStyle(
                    fontSize = size.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = (size * 1.3).sp,
                ),
                color = Color.White.copy(alpha = 0.75f),
                align = align,
                maxLines = Int.MAX_VALUE,
                outline = textOutline,
                outlinePx = outlinePx * 0.6f,
            )
        }
    }
}

/**
 * The instrumental-gap indicator, ported from Metrolist's IntervalIndicator
 * (LyricsCommon.kt): a 36 dp circular wavy progress ring tracking the gap,
 * revealed by a 200 ms height+alpha expansion and hidden again when the gap
 * ends. Not shown during manual scroll.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun IntervalIndicator(
    gapStartMs: Long,
    gapEndMs: Long,
    currentPositionMs: Long,
    visible: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val alpha = remember { Animatable(0f) }
    val expand = remember { Animatable(0f) }

    LaunchedEffect(visible) {
        if (visible) {
            expand.animateTo(1f, tween(200))
            alpha.animateTo(1f, tween(200))
        } else {
            alpha.animateTo(0f, tween(200))
            expand.animateTo(0f, tween(200))
        }
    }

    val targetHeight = 72.dp
    val progress = if (gapEndMs > gapStartMs) {
        ((currentPositionMs - gapStartMs).toFloat() / (gapEndMs - gapStartMs).toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 100, easing = LinearEasing),
        label = "intervalProgress",
    )

    Box(
        modifier = modifier
            .height(targetHeight * expand.value)
            .padding(top = 16.dp * expand.value)
            .graphicsLayer {
                this.alpha = alpha.value
                this.clip = true
            },
        contentAlignment = Alignment.Center,
    ) {
        CircularWavyProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier
                .size(36.dp)
                .alpha(alpha.value),
            color = color,
            trackColor = color.copy(alpha = 0.2f),
        )
    }
}

/**
 * Metrolist's alignment: agent positioning (v1 start, v2 end, v1000 center)
 * overrides the user's text position when enabled.
 */
private fun alignFor(agent: String?, respectAgentPositioning: Boolean, textPosition: LyricsTextPosition): TextAlign {
    if (respectAgentPositioning) {
        when (agent) {
            "v1" -> return TextAlign.Start
            "v2" -> return TextAlign.End
            "v1000" -> return TextAlign.Center
        }
    }
    return when (textPosition) {
        LyricsTextPosition.LEFT -> TextAlign.Start
        LyricsTextPosition.RIGHT -> TextAlign.End
        LyricsTextPosition.CENTER -> TextAlign.Center
    }
}

@Composable
private fun HintLine(text: String, palette: AppPalette) {
    Text(
        text = text,
        style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, shadow = TEXT_SHADOW),
        color = Color.White.copy(alpha = 0.9f),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun HoverMenu(
    playback: ActivePlayback?,
    palette: AppPalette,
    onDragStart: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    clickPassThrough: Boolean,
    onToggleClickPassThrough: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { onDragStart() },
                onDrag = { change, amount ->
                    change.consume()
                    onDrag(amount.x, amount.y)
                },
                onDragEnd = onDragEnd,
                onDragCancel = onDragEnd,
            )
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = playback?.track?.let { track ->
                listOfNotNull(track.title.takeIf(String::isNotBlank), track.artist.takeIf(String::isNotBlank))
                    .joinToString(" — ")
            } ?: "Lyrics Float",
            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, shadow = SUB_SHADOW),
            color = palette.onSurfaceDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        OverlayAction("Search", onSearch)
        OverlayAction("Settings", onSettings)
        OverlayAction(if (clickPassThrough) "Pass-through: on" else "Pass-through: off", onToggleClickPassThrough)
    }
}

@Composable
private fun OverlayAction(label: String, onClick: () -> Unit) {
    val palette = LocalAppPalette.current
    Text(
        text = label,
        style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, shadow = SUB_SHADOW),
        color = palette.accent,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

/**
 * EWMH _NET_WM_MOVERESIZE direction hints: 0 top-left, 1 top, 2 top-right,
 * 3 left, 4 right, 5 bottom-left, 6 bottom, 7 bottom-right.
 */
@Composable
private fun BoxScope.ResizeZones(
    onResizeStart: (Int) -> Boolean,
    onManualResize: (Int, Float, Float) -> Unit,
) {
    val thickness = 10.dp
    val corner = 20.dp

    @Composable
    fun Handle(direction: Int, zoneModifier: Modifier) {
        var nativeResize by remember { mutableStateOf(false) }
        var totalDrag by remember { mutableStateOf(Offset.Zero) }
        val cursor = when (direction) {
            0, 7 -> Cursor.NW_RESIZE_CURSOR
            2, 5 -> Cursor.NE_RESIZE_CURSOR
            1, 6 -> Cursor.N_RESIZE_CURSOR
            else -> Cursor.E_RESIZE_CURSOR
        }
        Box(
            modifier = zoneModifier.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(cursor))).pointerInput(direction) {
                detectDragGestures(
                    onDragStart = {
                        totalDrag = Offset.Zero
                        nativeResize = onResizeStart(direction)
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        if (!nativeResize) {
                            totalDrag += amount
                            onManualResize(direction, totalDrag.x, totalDrag.y)
                        }
                    },
                    onDragEnd = { nativeResize = false },
                    onDragCancel = { nativeResize = false },
                )
            },
        )
    }

    Handle(0, Modifier.align(Alignment.TopStart).size(20.dp))
    Handle(2, Modifier.align(Alignment.TopEnd).size(20.dp))
    Handle(5, Modifier.align(Alignment.BottomStart).size(20.dp))
    Handle(7, Modifier.align(Alignment.BottomEnd).size(20.dp))
    Handle(1, Modifier.align(Alignment.TopCenter).fillMaxWidth(0.72f).height(thickness))
    Handle(6, Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.72f).height(thickness))
    Handle(3, Modifier.align(Alignment.CenterStart).width(thickness).fillMaxHeight(0.72f))
    Handle(4, Modifier.align(Alignment.CenterEnd).width(thickness).fillMaxHeight(0.72f))
}
