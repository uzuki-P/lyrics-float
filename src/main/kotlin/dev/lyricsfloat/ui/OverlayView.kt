package dev.lyricsfloat.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lyricsfloat.lyrics.LyricsEntry
import dev.lyricsfloat.lyrics.LyricsState
import dev.lyricsfloat.lyrics.currentIndexAt
import dev.lyricsfloat.mpris.ActivePlayback
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

/**
 * Content of the floating lyrics pill. Position comes from the monitor's
 * interpolated clock, read every frame; no D-Bus traffic happens here.
 */
@Composable
fun OverlayView(
    playback: ActivePlayback?,
    lyrics: LyricsState,
    loading: Boolean,
    fontSizeSp: Int,
    opacity: Float,
    showNext: Boolean,
    offsetMs: Long,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (dx: Int, dy: Int) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalAppPalette.current

    var positionMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(playback) {
        while (isActive) {
            positionMs = playback?.positionMs() ?: 0L
            withFrameNanos { }
        }
    }

    val entries = lyrics.entries
    var currentIndex = -1
    if (entries.isNotEmpty()) {
        currentIndex = entries.currentIndexAt(positionMs, offsetMs)
    }

    var hovered by remember { mutableStateOf(false) }
    val pillShape = RoundedCornerShape(22.dp)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(10.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .shadow(10.dp, pillShape)
                .clip(pillShape)
                .background(palette.surface.copy(alpha = opacity))
                .border(1.dp, palette.onSurface.copy(alpha = 0.10f), pillShape)
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
                    detectDragGestures(
                        onDragStart = { onDragStart() },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            onDrag(
                                (dragAmount.x * density).roundToInt(),
                                (dragAmount.y * density).roundToInt(),
                            )
                        },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragEnd() },
                    )
                }
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Hover header: track identity + actions. Hidden otherwise so the
            // pill reads as pure lyrics.
            AnimatedVisibility(visible = hovered) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = playback?.track?.let { track ->
                            listOfNotNull(
                                track.title.takeIf(String::isNotBlank),
                                track.artist.takeIf(String::isNotBlank),
                            ).joinToString(" — ")
                        } ?: "Lyrics Float",
                        style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                        color = palette.onSurfaceDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    OverlayAction("Search", onSearch)
                    OverlayAction("Settings", onSettings)
                }
            }

            when {
                playback == null -> HintLine("Play something in any media player", palette)
                loading -> HintLine("Looking up lyrics…", palette)
                entries.isEmpty() -> HintLine("No lyrics found", palette)
                else -> {
                    val current = entries.getOrNull(currentIndex) ?: LyricsEntry.HEAD
                    val next = entries.getOrNull(currentIndex + 1)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = current.text.ifBlank { "♪" },
                        style = TextStyle(
                            fontSize = fontSizeSp.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = (fontSizeSp * 1.25).sp,
                            shadow = Shadow(palette.textShadow, Offset(0f, 2f), 6f),
                        ),
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (showNext && next != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = next.text,
                            style = TextStyle(
                                fontSize = (fontSizeSp * 0.72).sp,
                                fontWeight = FontWeight.Medium,
                                lineHeight = (fontSizeSp * 0.9).sp,
                                shadow = Shadow(palette.textShadow, Offset(0f, 1f), 4f),
                            ),
                            color = Color.White,
                            modifier = Modifier
                                .fillMaxWidth()
                                .alpha(0.55f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            // Slim progress bar along the pill bottom.
            playback?.track?.lengthMs?.takeIf { it > 0 }?.let { length ->
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(palette.onSurface.copy(alpha = 0.15f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth((positionMs.toFloat() / length).coerceIn(0f, 1f))
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(palette.accent),
                    )
                }
            }
        }
    }
}

@Composable
private fun HintLine(text: String, palette: AppPalette) {
    Text(
        text = text,
        style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
        color = palette.onSurfaceDim,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun OverlayAction(label: String, onClick: () -> Unit) {
    val palette = LocalAppPalette.current
    Text(
        text = label,
        style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
        color = palette.accent,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
