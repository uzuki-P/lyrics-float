package dev.lyricsfloat.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lyricsfloat.AppSettings
import dev.lyricsfloat.lyrics.LyricsState
import dev.lyricsfloat.lyrics.withIntervalIndicators
import dev.lyricsfloat.mpris.ActivePlayback
import kotlinx.coroutines.isActive
import java.awt.Cursor

/** Metrolist's LyricsTextPosition setting: default line alignment. */
enum class LyricsTextPosition { LEFT, CENTER, RIGHT }

enum class HoverMenuPosition { TOP, BOTTOM }

/**
 * Content of the floating lyrics pill. Position comes from the monitor's
 * interpolated clock, read every frame; no D-Bus traffic happens here.
 * Rendering follows Metrolist's experimental lyrics: a scrollable stack with
 * animated auto-scroll (750 ms, FastOutSlowIn), karaoke word fill, interval
 * indicator dots, agent positioning and romaji sub-lines. The background can
 * be fully transparent (0% opacity) - readability comes from text shadows.
 *
 * Hovering reveals a floating control bar (now playing, search, settings,
 * pass-through) over the lyrics instead of pushing them aside, so the lines
 * do not jump each time the pointer crosses the pill. The bar doubles as the
 * move handle, since the lyric stack consumes vertical drags for scrolling.
 */
@Composable
fun OverlayView(
    playback: ActivePlayback?,
    lyrics: LyricsState,
    loading: Boolean,
    settings: AppSettings,
    /** Global plus per-song offset, in ms. */
    offsetMs: Long,
    songOffsetMs: Long,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onTiming: () -> Unit,
    onToggleClickPassThrough: () -> Unit,
    dragModifier: Modifier,
    resizeZones: @Composable BoxScope.() -> Unit,
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
    val items = remember(entries, settings.showIntervalIndicator) {
        entries.withIntervalIndicators(settings.showIntervalIndicator)
    }

    var hovered by remember { mutableStateOf(false) }
    val pillShape = RoundedCornerShape(22.dp)
    val opacity = settings.opacity
    val hasVisibleBg = opacity >= 0.05f
    val showBorder = opacity >= 0.2f
    val menuAtTop = settings.hoverMenuPosition == HoverMenuPosition.TOP

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(2.dp)
            .clip(pillShape)
            .then(if (hasVisibleBg) Modifier.background(palette.surface.copy(alpha = opacity)) else Modifier)
            .then(if (showBorder) Modifier.border(1.dp, palette.onSurface.copy(alpha = 0.12f), pillShape) else Modifier)
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
            // Window move gesture. When lyrics are showing the viewport
            // consumes vertical drags for scrolling, so moving is done from
            // the hover bar (like a title bar) or the hint states below.
            .then(dragModifier),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 10.dp),
        ) {
            when {
                playback == null -> OverlayHint(
                    icon = AppIcons.MusicNote,
                    text = "Play something in any media player",
                )
                loading -> OverlayHint(text = "Looking up lyrics…", busy = true)
                entries.isEmpty() -> OverlayHint(
                    icon = AppIcons.MusicNote,
                    text = "No lyrics found",
                    actionLabel = "Search lyrics",
                    onAction = onSearch,
                )
                else -> LyricsViewport(
                    lyrics = lyrics,
                    items = items,
                    positionMs = positionMs,
                    offsetMs = offsetMs,
                    fontSizeSp = settings.fontSizeSp,
                    romajiFontSizeSp = settings.romajiFontSizeSp,
                    autoScrollDefault = settings.autoScroll,
                    showIntervalIndicator = settings.showIntervalIndicator,
                    respectAgentPositioning = settings.respectAgentPositioning,
                    wordKaraoke = settings.wordKaraoke,
                    romanizeJapanese = settings.romanizeJapanese,
                    textPosition = settings.textPosition,
                    textOutline = settings.textOutline,
                    // Keep the Sync button clear of the hover bar.
                    syncAlignment = if (menuAtTop) Alignment.BottomEnd else Alignment.TopEnd,
                    syncButton = { onClick -> SyncButton(onClick) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        AnimatedVisibility(
            visible = hovered && !settings.clickPassThrough,
            enter = fadeIn(tween(140)) + slideInVertically(tween(160)) { if (menuAtTop) -it / 2 else it / 2 },
            exit = fadeOut(tween(120)),
            modifier = Modifier
                .align(if (menuAtTop) Alignment.TopCenter else Alignment.BottomCenter)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            HoverBar(
                playback = playback,
                clickPassThrough = settings.clickPassThrough,
                songOffsetMs = songOffsetMs,
                onSearch = onSearch,
                onSettings = onSettings,
                onTiming = onTiming,
                onToggleClickPassThrough = onToggleClickPassThrough,
                dragModifier = dragModifier,
            )
        }

        // Invisible edge/corner zones that hand the drag to the WM's
        // interactive resize. Drawn after the content so they win the
        // pointer over the pill gestures.
        resizeZones()
    }
}

/** Centered placeholder for the no-player / loading / no-lyrics states. */
@Composable
private fun OverlayHint(
    text: String,
    icon: ImageVector? = null,
    busy: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val palette = LocalAppPalette.current
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when {
                busy -> CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = palette.accent,
                )
                icon != null -> Icon(icon, contentDescription = null, tint = palette.accent, modifier = Modifier.size(17.dp))
            }
            if (busy || icon != null) Spacer(Modifier.width(8.dp))
            Text(
                text = text,
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, shadow = TEXT_SHADOW),
                color = Color.White.copy(alpha = 0.9f),
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(8.dp))
            OverlayPillButton(label = actionLabel, icon = AppIcons.Search, onClick = onAction)
        }
    }
}

/**
 * The hover bar: now-playing info on a translucent pill (readable at 0%
 * background opacity) and icon actions. Dragging anywhere on it moves the
 * window.
 */
@Composable
private fun HoverBar(
    playback: ActivePlayback?,
    clickPassThrough: Boolean,
    songOffsetMs: Long,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onTiming: () -> Unit,
    onToggleClickPassThrough: () -> Unit,
    dragModifier: Modifier,
) {
    val palette = LocalAppPalette.current
    val shape = RoundedCornerShape(14.dp)
    val track = playback?.track
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(palette.surface.copy(alpha = 0.88f))
            .border(1.dp, palette.outline, shape)
            .then(dragModifier)
            .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)))
            .padding(start = 6.dp, end = 4.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            AppIcons.DragIndicator,
            contentDescription = "Drag to move",
            tint = palette.onSurfaceFaint,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = track?.title?.takeIf(String::isNotBlank) ?: "Lyrics Float",
                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                color = palette.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            track?.artist?.takeIf(String::isNotBlank)?.let { artist ->
                Text(
                    text = artist,
                    style = TextStyle(fontSize = 10.sp),
                    color = palette.onSurfaceDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconAction(AppIcons.Search, "Search lyrics", onSearch, size = 28.dp, iconSize = 17.dp)
        // Highlighted while this song has its own offset.
        IconAction(
            AppIcons.Timer,
            if (songOffsetMs == 0L) "Lyrics timing" else "Lyrics timing: ${formatOffset(songOffsetMs)} for this song",
            onTiming,
            selected = songOffsetMs != 0L,
            size = 28.dp,
            iconSize = 17.dp,
        )
        IconAction(AppIcons.Tune, "Settings", onSettings, size = 28.dp, iconSize = 17.dp)
        IconAction(
            icon = if (clickPassThrough) AppIcons.PointerPassThrough else AppIcons.PointerActive,
            label = if (clickPassThrough) "Click pass-through: on" else "Click pass-through: off (clicks go through the pill when on)",
            onClick = onToggleClickPassThrough,
            selected = clickPassThrough,
            size = 28.dp,
            iconSize = 16.dp,
        )
    }
}

@Composable
private fun SyncButton(onClick: () -> Unit) {
    OverlayPillButton(label = "Sync", icon = AppIcons.Sync, onClick = onClick)
}

/** Small accent pill used on top of the lyrics (Sync, Search lyrics). */
@Composable
private fun OverlayPillButton(label: String, icon: ImageVector?, onClick: () -> Unit) {
    val palette = LocalAppPalette.current
    val shape = RoundedCornerShape(50)
    val (source, hovered) = rememberHoverSource()
    Row(
        modifier = Modifier
            .clip(shape)
            .background(palette.surface.copy(alpha = if (hovered) 0.95f else 0.85f))
            .border(1.dp, palette.accent.copy(alpha = if (hovered) 0.8f else 0.5f), shape)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = palette.accent, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(label, style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold), color = palette.accent)
    }
}
