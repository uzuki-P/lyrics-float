package dev.lyricsfloat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lyricsfloat.lyrics.SongOffsets
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.roundToLong

/** Offset as people read it: "+250 ms", "−1.25 s", "0 ms". */
internal fun formatOffset(offsetMs: Long): String {
    val sign = when {
        offsetMs > 0 -> "+"
        offsetMs < 0 -> "−"
        else -> ""
    }
    val magnitude = abs(offsetMs)
    return if (magnitude < 1000) {
        "$sign$magnitude ms"
    } else {
        sign + "%.2f s".format(magnitude / 1000.0).replace(".00 s", " s")
    }
}

/**
 * Parse a typed offset: plain numbers are milliseconds, a trailing "s" means
 * seconds ("1.5s", "-0.25 s"). Returns null for anything else.
 */
internal fun parseOffset(input: String): Long? {
    val text = input.trim().replace('−', '-').replace(',', '.').lowercase()
    if (text.isEmpty()) return null
    return when {
        text.endsWith("ms") -> text.removeSuffix("ms").trim().toDoubleOrNull()?.roundToLong()
        text.endsWith("s") -> text.removeSuffix("s").trim().toDoubleOrNull()?.let { (it * 1000).roundToLong() }
        else -> text.toDoubleOrNull()?.roundToLong()
    }
}

/**
 * Lyrics timing dialog for the playing song. The per-song offset adds on top
 * of the global one from Settings, and is stored per song so a badly timed
 * provider result stays fixed the next time it plays. The current line
 * updates live, so the user can nudge until it matches what they hear.
 */
@Composable
fun TimingView(
    targetTitle: String,
    targetArtist: String,
    hasTarget: Boolean,
    songOffsetMs: Long,
    globalOffsetMs: Long,
    onSongOffsetChange: (Long) -> Unit,
    currentLines: () -> Pair<String?, String?>,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
    dragHandleModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
) {
    val palette = LocalAppPalette.current
    fun change(value: Long) = onSongOffsetChange(SongOffsets.clamp(value))

    // Poll the line under the playhead every frame; it moves as the offset does.
    var lines by remember { mutableStateOf<Pair<String?, String?>>(null to null) }
    LaunchedEffect(Unit) {
        while (isActive) {
            lines = currentLines()
            withFrameNanos { }
        }
    }

    DialogFrame(
        title = "Lyrics timing",
        subtitle = if (hasTarget) {
            "For: ${targetTitle.ifBlank { "Unknown" }} · ${targetArtist.ifBlank { "Unknown" }}"
        } else {
            "Nothing playing"
        },
        onClose = onClose,
        modifier = modifier,
        dragHandleModifier = dragHandleModifier,
    ) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!hasTarget) {
                InlineMessage("Play a song in any media player to adjust its timing.", MessageTone.INFO)
            }

            // Value + what it means.
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    formatOffset(songOffsetMs),
                    style = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold),
                    color = if (songOffsetMs == 0L) palette.onSurface else palette.accent,
                )
                Text(
                    when {
                        songOffsetMs > 0 -> "Lyrics show ${formatOffset(songOffsetMs).drop(1)} earlier for this song"
                        songOffsetMs < 0 -> "Lyrics show ${formatOffset(songOffsetMs).drop(1)} later for this song"
                        else -> "No adjustment for this song"
                    },
                    style = AppType.caption,
                    color = palette.onSurfaceDim,
                )
            }

            // Live preview of the line under the playhead.
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.card)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("NOW SHOWING", style = AppType.overline, color = palette.accent.copy(alpha = 0.9f))
                Spacer(Modifier.height(4.dp))
                Text(
                    lines.first?.takeIf(String::isNotBlank) ?: "♪",
                    style = AppType.bodyStrong.copy(fontSize = 15.sp),
                    color = palette.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                lines.second?.takeIf(String::isNotBlank)?.let { next ->
                    Text(
                        next,
                        style = AppType.caption,
                        color = palette.onSurfaceFaint,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Nudges: later on the left, earlier on the right.
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(-1000L, -100L, -50L, 50L, 100L, 1000L).forEach { step ->
                        AppButton(
                            label = (if (step > 0) "+" else "−") + if (abs(step) >= 1000) "${abs(step) / 1000} s" else "${abs(step)}",
                            onClick = { change(songOffsetMs + step) },
                            enabled = hasTarget,
                            compact = true,
                            kind = if (abs(step) == 100L) ButtonKind.SECONDARY else ButtonKind.GHOST,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                    Text("‹ Later", style = AppType.caption, color = palette.onSurfaceDim, modifier = Modifier.weight(1f))
                    Text("Earlier ›", style = AppType.caption, color = palette.onSurfaceDim)
                }
            }

            // Wide fine-tune slider; typed values reach the full ±60 s.
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Fine tune", style = AppType.body, color = palette.onSurface, modifier = Modifier.weight(1f))
                    Text("±10 s", style = AppType.caption, color = palette.onSurfaceDim)
                }
                Slider(
                    value = songOffsetMs.toFloat().coerceIn(-10_000f, 10_000f),
                    onValueChange = { change((it / 50f).roundToLong() * 50) },
                    valueRange = -10_000f..10_000f,
                    enabled = hasTarget,
                    colors = SliderDefaults.colors(
                        thumbColor = palette.accent,
                        activeTrackColor = palette.accent,
                        inactiveTrackColor = palette.onSurface.copy(alpha = 0.12f),
                    ),
                    modifier = Modifier.height(30.dp).pointerHoverIcon(PointerIcon.Hand),
                )
            }

            ExactOffsetField(currentMs = songOffsetMs, enabled = hasTarget, onSet = ::change)

            InlineMessage(
                "Default for all songs: ${formatOffset(globalOffsetMs)}. This song's offset adds on top.",
                MessageTone.INFO,
            ) {
                AppButton("Settings", onClick = onOpenSettings, kind = ButtonKind.GHOST, compact = true)
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
            AppButton(
                "Reset this song",
                onClick = { change(0) },
                kind = ButtonKind.GHOST,
                icon = AppIcons.Undo,
                enabled = hasTarget && songOffsetMs != 0L,
            )
            AppButton("Done", onClick = onClose, kind = ButtonKind.PRIMARY)
        }
    }
}

@Composable
private fun ExactOffsetField(currentMs: Long, enabled: Boolean, onSet: (Long) -> Unit) {
    val palette = LocalAppPalette.current
    // Refill whenever the value changes elsewhere (nudges, slider, reset).
    var text by remember(currentMs) { mutableStateOf(if (currentMs == 0L) "" else currentMs.toString()) }
    val parsed = parseOffset(text)
    val invalid = text.isNotBlank() && parsed == null
    fun submit() {
        parsed?.let(onSet)
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = "Exact offset, e.g. 1250 or -1.5s",
                onSubmit = ::submit,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            AppButton(
                "Set",
                onClick = ::submit,
                enabled = enabled && parsed != null && parsed != currentMs,
            )
        }
        Text(
            if (invalid) "Use milliseconds (1250) or seconds (1.5s)." else "Up to ±60 s. Positive shows lines earlier.",
            style = AppType.caption,
            color = if (invalid) palette.danger else palette.onSurfaceDim,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        )
    }
}
