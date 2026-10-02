package dev.lyricsfloat.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

internal val TEXT_SHADOW = Shadow(Color.Black.copy(alpha = 0.75f), Offset(0f, 2f), 8f)
private val OUTLINE_COLOR = Color.Black.copy(alpha = 0.85f)

/** Lines away from the active one render at this scale (the old 0.72x font). */
private const val INACTIVE_SCALE = 0.74f

/** Auto-scroll: a decelerating curve, staggered per line below the focus. */
private val ScrollEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private const val SCROLL_MS = 700
private const val STAGGER_MS = 32
private const val MAX_STAGGER_STEPS = 7

internal fun focusLyricsItemIndex(items: List<LyricsItem>, currentIdx: Int, gap: LyricsItem.Indicator?): Int =
    when {
        gap != null -> items.indexOfFirst { it == gap }.coerceAtLeast(0)
        else -> items.indexOfFirst { it is LyricsItem.Line && it.index == currentIdx.coerceAtLeast(0) }.coerceAtLeast(0)
    }

/**
 * The scrollable lyrics stack, following Metrolist: every line is laid out in
 * flow and auto-scroll brings the active line to the viewport center. Manual
 * drag or wheel scroll pauses auto-scroll and shows a Sync button. A taller
 * window simply reveals more lines.
 *
 * Motion on top of Metrolist's: each line scrolls on its own clock, with the
 * lines below the focus starting a little later, so a line change ripples
 * down the stack instead of moving as one rigid block. Lines grow into focus
 * on a soft spring, and the dimming and depth blur of the other lines
 * animate with them.
 */
@Composable
internal fun LyricsViewport(
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
    syncAlignment: Alignment,
    syncButton: @Composable (onClick: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val palette = LocalAppPalette.current
    val entries = lyrics.entries

    // Reset manual scroll when the song changes.
    var autoScrolling by remember { mutableStateOf(autoScrollDefault) }
    var manualOffsetPx by remember { mutableFloatStateOf(0f) }
    // Where the user left the manual scroll; auto-scroll resumes from there
    // instead of jumping to a stale animation value.
    var resumeFrom by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(lyrics.trackKey) {
        autoScrolling = autoScrollDefault
        manualOffsetPx = 0f
        resumeFrom = null
    }

    val currentIdx = entries.currentIndexAt(positionMs, offsetMs)
    val effPos = positionMs + offsetMs
    val gap = if (showIntervalIndicator) {
        items.filterIsInstance<LyricsItem.Indicator>()
            .firstOrNull { effPos >= it.gapStartMs && effPos < it.gapEndMs }
    } else {
        null
    }

    val focusItemIndex = focusLyricsItemIndex(items, currentIdx, gap)
    fun isActiveItem(index: Int): Boolean =
        gap == null && (items.getOrNull(index) as? LyricsItem.Line)?.index == currentIdx

    // Lines move in graphics layers. Clip at the viewport so they cannot
    // paint over the hover bar or outside a resized pill.
    BoxWithConstraints(modifier.clipToBounds()) {
        val viewportPx = with(density) { maxHeight.toPx() }
        // Laid-out (scaled, possibly mid-animation) heights for the edge fade,
        // and unscaled heights for scroll targets. Targets use the height each
        // line settles at, so they do not shift while lines grow or shrink.
        val heights = remember(items) { mutableStateMapOf<Int, Int>() }
        val fullHeights = remember(items) { mutableStateMapOf<Int, Int>() }
        fun settledHeight(index: Int): Float {
            val full = (fullHeights[index] ?: heights[index] ?: 0).toFloat()
            return when {
                items[index] is LyricsItem.Indicator -> (heights[index] ?: 0).toFloat()
                (items[index] as? LyricsItem.Line)?.entry?.text?.isBlank() == true -> full
                isActiveItem(index) -> full
                else -> full * INACTIVE_SCALE
            }
        }
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
        // with padded ends — implemented as a static offset in each line's
        // graphics layer rather than layout padding: padding would shrink the
        // column's measure constraints to zero height and hide every line.
        val anchorPx = viewportPx * 0.5f
        // Scroll span: from "content top at anchor" until "content bottom at
        // the viewport bottom". Overshooting would detach the content from the
        // viewport (cropped top, empty bottom) — visible right after resizes.
        fun maxOffset(): Float =
            (items.indices.sumOf { settledHeight(it).toDouble() }.toFloat() + totalGapPx - (viewportPx - anchorPx))
                .coerceAtLeast(0f)

        // Re-clamp the manual offset when the window resizes, or a stale
        // offset leaves the content detached from the new, smaller viewport.
        LaunchedEffect(items, viewportPx) {
            if (!autoScrolling) {
                manualOffsetPx = manualOffsetPx.coerceIn(0f, maxOffset())
            }
        }

        val target = if (autoScrolling) {
            val before = (0 until focusItemIndex).sumOf { settledHeight(it).toDouble() }.toFloat()
            // Bringing the item center to the anchor needs exactly this much
            // scroll on top of the static anchor offset.
            (before + gapsBefore(focusItemIndex) + settledHeight(focusItemIndex) / 2f).coerceIn(0f, maxOffset())
        } else {
            null
        }

        // One scroll clock per item for the ripple. The first target snaps so
        // a freshly loaded song does not sweep in from the top.
        val scrolls = remember(items) { List(items.size) { Animatable(target ?: 0f) } }
        LaunchedEffect(target, autoScrolling, scrolls) {
            if (!autoScrolling || target == null) return@LaunchedEffect
            resumeFrom?.let { from ->
                scrolls.forEach { it.snapTo(from) }
                resumeFrom = null
            }
            coroutineScope {
                scrolls.forEachIndexed { index, scroll ->
                    val steps = (index - focusItemIndex + 1).coerceIn(0, MAX_STAGGER_STEPS)
                    launch {
                        scroll.animateTo(target, tween(SCROLL_MS, delayMillis = steps * STAGGER_MS, easing = ScrollEasing))
                    }
                }
            }
        }
        fun scrollOf(index: Int): Float = if (autoScrolling) scrolls.getOrNull(index)?.value ?: 0f else manualOffsetPx
        fun beginManualScroll() {
            if (autoScrolling) {
                manualOffsetPx = scrollOf(focusItemIndex)
                autoScrolling = false
            }
        }

        // Edge fade so lines dissolve at the pill bounds instead of being cut
        // mid-glyph (Metrolist fades its edges the same way).
        val fadePx = with(density) { 26.dp.toPx() }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(items, viewportPx) {
                    // Manual scroll (drag): pauses auto-scroll like Metrolist.
                    // maxOffset() reads live heights; a snapshot captured at
                    // launch time is empty and would pin the offset to zero.
                    detectDragGestures(
                        onDragStart = {
                            beginManualScroll()
                            manualOffsetPx = manualOffsetPx.coerceIn(0f, maxOffset())
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            beginManualScroll()
                            manualOffsetPx = (manualOffsetPx + amount.y * density.density)
                                .coerceIn(0f, maxOffset())
                        },
                    )
                }
                .pointerInput(items, viewportPx) {
                    // Mouse wheel scroll, same pause behavior.
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                            if (delta != 0f) {
                                beginManualScroll()
                                manualOffsetPx = (manualOffsetPx + delta * 48f).coerceIn(0f, maxOffset())
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                },
        ) {
            Column(
                modifier = Modifier
                    // Every line must be measured, including those outside the
                    // visible viewport. A viewport-height Column makes later
                    // children measure at zero height and breaks scroll bounds.
                    .fillMaxWidth()
                    .wrapContentHeight(align = Alignment.Top, unbounded = true),
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
                        is LyricsItem.Line -> abs(item.index - currentIdx.coerceAtLeast(0))
                        is LyricsItem.Indicator -> abs(item.afterLineIndex - currentIdx.coerceAtLeast(0))
                    }
                    val translation = anchorPx - scrollOf(itemIndex)
                    // Lines dissolve over the top/bottom fade band instead of
                    // being guillotined mid-glyph at the pill edge.
                    val viewportTop = translation + itemTopPx
                    val viewportBottom = viewportTop + itemHeightPx
                    val fade = ((viewportBottom / fadePx).coerceIn(0f, 1f)) *
                        (((viewportPx - viewportTop) / fadePx).coerceIn(0f, 1f))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onSizeChanged { heights[itemIndex] = it.height }
                            .graphicsLayer {
                                translationY = translation
                                alpha = fade
                            },
                    ) {
                        when (item) {
                            is LyricsItem.Line -> if (item.entry.text.isBlank()) {
                                Spacer(
                                    Modifier.fillMaxWidth()
                                        .height(with(density) { (fontSizeSp * 1.3).sp.toDp() })
                                        .onSizeChanged { fullHeights[itemIndex] = it.height },
                                )
                            } else LyricLine(
                                entry = item.entry,
                                isActive = isActiveItem(itemIndex),
                                distance = distance,
                                depthBlur = autoScrolling,
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
                                onFullHeight = { fullHeights[itemIndex] = it },
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
        }

        AnimatedVisibility(
            visible = !autoScrolling,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(150)),
            modifier = Modifier.align(syncAlignment).padding(4.dp),
        ) {
            // Metrolist's resync overlay: pressing it hands control back to
            // the playback position.
            syncButton {
                resumeFrom = manualOffsetPx
                autoScrolling = true
                manualOffsetPx = 0f
            }
        }
    }
}

/**
 * One line plus its romaji sub-line. Every line is laid out at the active
 * size; lines out of focus shrink through a graphics layer, so focus changes
 * animate on a spring instead of re-wrapping text at a new font size. The
 * layout height follows the scale, keeping the stack tight.
 */
@Composable
private fun LyricLine(
    entry: LyricsEntry,
    isActive: Boolean,
    distance: Int,
    depthBlur: Boolean,
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
    onFullHeight: (Int) -> Unit,
) {
    val density = LocalDensity.current
    val romanized by entry.romanizedTextFlow.collectAsState()
    val showRomaji = romanizeJapanese && !romanized.isNullOrBlank()

    // Slightly underdamped: the line overshoots its size a touch as it lands.
    val focus by animateFloatAsState(
        targetValue = if (isActive) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 240f),
        label = "line-focus",
    )
    val colorFocus = focus.coerceIn(0f, 1f)
    val scale = INACTIVE_SCALE + (1f - INACTIVE_SCALE) * focus
    val dim by animateFloatAsState(
        targetValue = when {
            distance == 0 -> 1f
            distance == 1 -> 0.3f
            distance == 2 -> 0.2f
            distance == 3 -> 0.15f
            else -> 0.08f
        }.let { if (isActive) 1f else it },
        animationSpec = tween(350),
        label = "line-dim",
    )
    // Depth blur starts two lines away from the active one (the neighbors
    // stay sharp), grows with the distance, and clears while the user
    // scrolls manually so every line is readable.
    val blurDp by animateFloatAsState(
        targetValue = if (!depthBlur || isActive || distance <= 1) 0f else 0.4f + (distance - 2).coerceIn(0, 3) * 0.5f,
        animationSpec = tween(400),
        label = "line-blur",
    )
    val originX = when (align) {
        TextAlign.Start, TextAlign.Left -> 0f
        TextAlign.End, TextAlign.Right -> 1f
        else -> 0.5f
    }

    // Karaoke words: real word timings, or synthesized ones so line-synced
    // lyrics still get a quick fill sweep.
    val hasWordTimings = !entry.words.isNullOrEmpty()
    val words = when {
        !wordKaraoke || !synced -> null
        hasWordTimings -> entry.words
        entry.text.isNotBlank() -> remember(entry.text, entry.time) { synthesizeWords(entry.text, entry.time) }
        else -> null
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val height = (placeable.height * scale).roundToInt().coerceAtLeast(0)
                layout(placeable.width, height) {
                    placeable.placeWithLayer(0, 0) {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(originX, 0f)
                        alpha = dim
                        val blurPx = with(density) { blurDp.dp.toPx() }
                        renderEffect = if (blurPx > 0.3f) BlurEffect(blurPx, blurPx, TileMode.Decal) else null
                    }
                }
            }
            .onSizeChanged { onFullHeight(it.height) },
    ) {
        KaraokeLineText(
            text = entry.text.ifBlank { "♪" },
            words = words?.takeIf { entry.text.isNotBlank() },
            positionSeconds = (positionMs + offsetMs) / 1000.0,
            focus = colorFocus,
            activeColor = activeColor,
            // Real word timings leave unsung words white; synthesized sweeps
            // start from a faint accent, as before.
            unsungColor = if (hasWordTimings) Color.White else activeColor.copy(alpha = 0.32f),
            style = TextStyle(
                fontSize = fontSizeSp.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = (fontSizeSp * 1.3).sp,
            ),
            align = align,
            outline = textOutline,
            outlinePx = outlinePx,
        )

        if (showRomaji) {
            LyricText(
                text = romanized!!,
                style = TextStyle(
                    fontSize = romajiFontSizeSp.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = (romajiFontSizeSp * 1.3).sp,
                ),
                color = lerp(Color.White.copy(alpha = 0.75f), activeColor.copy(alpha = 0.85f), colorFocus),
                align = align,
                maxLines = Int.MAX_VALUE,
                outline = textOutline,
                outlinePx = outlinePx * 0.6f,
            )
        }
    }
}

/** One word (or the part of it on one visual line), measured on its own. */
private class WordSegment(
    val wordIndex: Int,
    val layout: TextLayoutResult,
    /** Same piece with [TEXT_SHADOW] in its style, for the back pass; null with outlines. */
    val shadowLayout: TextLayoutResult?,
    val topLeft: Offset,
    val bounds: Rect,
    /** Share of the word's characters before / up to the end of this segment. */
    val fracStart: Float,
    val fracEnd: Float,
)

/**
 * Lyric text drawn on a canvas. Out of focus it is a plain line (white,
 * blending to the accent with [focus]). In focus with [words], it becomes
 * karaoke after Metrolist's WordLevelLyrics: a soft-edged gradient wipes
 * each word left to right as it is sung, the word pops up a little when it
 * starts and stays slightly raised once sung, and long held words glow in
 * the accent color. The base layer draws everything outside the words
 * (spaces, unmatched text), each word draws from its own measured layout.
 */
@Composable
private fun KaraokeLineText(
    text: String,
    words: List<WordTimestamp>?,
    positionSeconds: Double,
    focus: Float,
    activeColor: Color,
    unsungColor: Color,
    style: TextStyle,
    align: TextAlign,
    outline: Boolean,
    outlinePx: Float,
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = constraints.maxWidth
        val layout = remember(text, style, width, align) {
            textMeasurer.measure(
                text = text,
                style = style.copy(textAlign = align),
                constraints = Constraints(minWidth = width, maxWidth = width),
                softWrap = true,
            )
        }
        val segments = remember(layout, words, outline) {
            words?.let { buildSegments(text, it, layout, style, textMeasurer, withShadow = !outline) }.orEmpty()
        }
        // The karaoke back pass draws shadows from a layout that carries the
        // shadow in its style. Toggling the shadow per draw call on one
        // layout would rebuild its Skia paragraph twice per frame.
        val shadowLayout = remember(layout, outline, segments) {
            if (outline || segments.isEmpty()) {
                null
            } else {
                textMeasurer.measure(
                    text = text,
                    style = style.copy(textAlign = align, shadow = TEXT_SHADOW),
                    constraints = Constraints(minWidth = width, maxWidth = width),
                    softWrap = true,
                )
            }
        }
        val fontPx = with(density) { style.fontSize.toPx() }
        val stroke = remember(outlinePx) { Stroke(width = outlinePx, join = StrokeJoin.Round) }

        Canvas(Modifier.fillMaxWidth().height(with(density) { layout.size.height.toDp() })) {
            val karaoke = segments.isNotEmpty() && focus > 0.01f
            if (!karaoke) {
                if (outline) drawText(layout, color = OUTLINE_COLOR, drawStyle = stroke)
                drawText(layout, color = lerp(Color.White, activeColor, focus), shadow = if (outline) null else TEXT_SHADOW)
                return@Canvas
            }

            val sungColor = lerp(Color.White, activeColor, focus)
            val restColor = lerp(Color.White, unsungColor, focus)
            val wordsList = words!!

            // Runs [draw] once per segment inside the word's pop and lift.
            fun DrawScope.forEachSegment(draw: DrawScope.(segment: WordSegment, progress: Float, glow: Float) -> Unit) {
                segments.forEach { segment ->
                    val word = wordsList[segment.wordIndex]
                    val duration = (word.endTime - word.startTime).coerceAtLeast(0.05)
                    val since = positionSeconds - word.startTime
                    val wordProgress = (since / duration).coerceIn(0.0, 1.0).toFloat()
                    val span = (segment.fracEnd - segment.fracStart).coerceAtLeast(0.0001f)
                    val progress = ((wordProgress - segment.fracStart) / span).coerceIn(0f, 1f)

                    // Pop: rises over 120 ms, settles over the next 580 ms.
                    val pop = when {
                        since < 0 -> 0f
                        since < 0.12 -> (since / 0.12).toFloat()
                        since < 0.7 -> (1 - (since - 0.12) / 0.58).toFloat()
                        else -> 0f
                    }
                    val rise = if (since <= 0) 0f else easeOutCubic((since / 0.35).toFloat().coerceIn(0f, 1f))
                    val lift = -fontPx * 0.07f * rise * focus
                    val wordScale = 1f + 0.05f * pop * focus

                    // Glow only for held notes: grows with the word's length,
                    // fades in and out at the word's edges.
                    val hold = ((duration - 0.6) / 1.2).toFloat().coerceIn(0f, 1f)
                    val edges = (wordProgress * 5f).coerceIn(0f, 1f) * ((1f - wordProgress) * 6f).coerceIn(0f, 1f)
                    val glow = hold * edges * focus

                    val segWidth = segment.layout.size.width.toFloat()
                    val segHeight = segment.layout.size.height.toFloat()
                    translate(segment.topLeft.x, segment.topLeft.y + lift) {
                        scale(wordScale, wordScale, pivot = Offset(segWidth / 2f, segHeight)) {
                            draw(segment, progress, glow)
                        }
                    }
                }
            }

            // Two passes: every outline, shadow and glow first, then every
            // fill. Drawing word by word put each word's shadow or outline
            // over the glyphs of the word before it, a dark smudge that
            // stayed on sung words (worst on CJK lines, one word per glyph).
            // The base layer covers whatever no word segment covers.
            val holes = Path().apply { segments.forEach { addRect(it.bounds) } }
            clipPath(holes, ClipOp.Difference) {
                if (outline) drawText(layout, color = OUTLINE_COLOR, drawStyle = stroke)
                shadowLayout?.let { drawText(it, color = Color.Transparent) }
            }
            forEachSegment { segment, _, glow ->
                if (outline) drawText(segment.layout, color = OUTLINE_COLOR, drawStyle = stroke)
                if (glow > 0.02f) {
                    val glowShadow = Shadow(activeColor.copy(alpha = 0.8f * glow), Offset.Zero, fontPx * 0.5f * glow)
                    drawText(segment.shadowLayout ?: segment.layout, color = Color.Transparent, shadow = glowShadow)
                } else {
                    segment.shadowLayout?.let { drawText(it, color = Color.Transparent) }
                }
            }

            clipPath(holes, ClipOp.Difference) {
                drawText(layout, color = restColor)
            }
            forEachSegment { segment, progress, _ ->
                drawWipe(segment.layout, progress, segment.layout.size.width.toFloat(), fontPx, sungColor, restColor)
            }
        }
    }
}

/** Fill [layout] up to [progress] with [sung], fading over a soft edge into [rest]. */
private fun DrawScope.drawWipe(
    layout: TextLayoutResult,
    progress: Float,
    width: Float,
    fontPx: Float,
    sung: Color,
    rest: Color,
) {
    when {
        progress >= 1f -> drawText(layout, color = sung)
        progress <= 0f -> drawText(layout, color = rest)
        else -> {
            // The edge travels from fully left of the word to fully right, so
            // the fill starts and ends cleanly.
            val edge = (fontPx * 0.9f).coerceAtMost(width.coerceAtLeast(1f))
            val front = -edge + (width + edge) * progress
            drawText(
                layout,
                brush = Brush.horizontalGradient(
                    0f to sung,
                    1f to rest,
                    startX = front,
                    endX = front + edge,
                    tileMode = TileMode.Clamp,
                ),
            )
        }
    }
}

private fun easeOutCubic(t: Float): Float {
    val inv = 1f - t
    return 1f - inv * inv * inv
}

/**
 * Split each word into per-visual-line pieces with their own single-line
 * layouts, positioned where the full line layout put those characters.
 */
private fun buildSegments(
    text: String,
    words: List<WordTimestamp>,
    layout: TextLayoutResult,
    style: TextStyle,
    measurer: TextMeasurer,
    withShadow: Boolean,
): List<WordSegment> {
    val segments = mutableListOf<WordSegment>()
    val pieceStyle = style.copy(textAlign = TextAlign.Start)
    val shadowStyle = pieceStyle.copy(shadow = TEXT_SHADOW)
    var searchFrom = 0
    words.forEachIndexed { wordIndex, word ->
        if (word.text.isBlank()) return@forEachIndexed
        val found = text.indexOf(word.text, searchFrom)
        if (found < 0) return@forEachIndexed
        searchFrom = found + word.text.length
        var start = found
        var end = found + word.text.length
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        if (start >= end) return@forEachIndexed
        val total = (end - start).toFloat()

        for (line in layout.getLineForOffset(start)..layout.getLineForOffset(end - 1)) {
            var pieceStart = maxOf(start, layout.getLineStart(line))
            var pieceEnd = minOf(end, layout.getLineEnd(line))
            while (pieceStart < pieceEnd && text[pieceStart].isWhitespace()) pieceStart++
            while (pieceEnd > pieceStart && text[pieceEnd - 1].isWhitespace()) pieceEnd--
            if (pieceStart >= pieceEnd) continue

            var left = Float.MAX_VALUE
            var right = -Float.MAX_VALUE
            for (offset in pieceStart until pieceEnd) {
                val box = layout.getBoundingBox(offset)
                left = minOf(left, box.left)
                right = maxOf(right, box.right)
            }
            val top = layout.getLineTop(line)
            val bottom = layout.getLineBottom(line)
            val piece = text.substring(pieceStart, pieceEnd)
            segments += WordSegment(
                wordIndex = wordIndex,
                layout = measurer.measure(piece, pieceStyle, softWrap = false, maxLines = 1),
                shadowLayout = if (withShadow) measurer.measure(piece, shadowStyle, softWrap = false, maxLines = 1) else null,
                topLeft = Offset(left, top),
                bounds = Rect(left, top, right, bottom),
                fracStart = (pieceStart - start) / total,
                fracEnd = (pieceEnd - start) / total,
            )
        }
    }
    return segments
}

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
