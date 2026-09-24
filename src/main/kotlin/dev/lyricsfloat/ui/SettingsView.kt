@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.lyricsfloat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lyricsfloat.lyrics.BuildVersion
import dev.lyricsfloat.lyrics.LyricsProviders
import dev.lyricsfloat.mpris.PlayerInfo
import dev.lyricsfloat.platform.WindowAnchor
import kotlin.math.roundToInt

private fun ThemeMode.label(): String = when (this) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.DARK -> "Dark"
    ThemeMode.LIGHT -> "Light"
}

private fun WindowAnchor.label(): String = when (this) {
    WindowAnchor.TOP_LEFT -> "Top left"
    WindowAnchor.TOP_CENTER -> "Top center"
    WindowAnchor.TOP_RIGHT -> "Top right"
    WindowAnchor.MIDDLE_LEFT -> "Middle left"
    WindowAnchor.MIDDLE_RIGHT -> "Middle right"
    WindowAnchor.BOTTOM_LEFT -> "Bottom left"
    WindowAnchor.BOTTOM_CENTER -> "Bottom center"
    WindowAnchor.BOTTOM_RIGHT -> "Bottom right"
}

/**
 * Settings dialog, structured after Metrolist's lyrics settings: a Lyrics
 * section first (text size, text position, scroll and animation behavior,
 * romanization), then providers, then the overlay/appearance knobs.
 */
@Composable
fun SettingsView(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    fontSizeSp: Int,
    onFontSizeChange: (Int) -> Unit,
    opacity: Float,
    onOpacityChange: (Float) -> Unit,
    showNextLine: Boolean,
    onShowNextLineChange: (Boolean) -> Unit,
    offsetMs: Long,
    onOffsetChange: (Long) -> Unit,
    autoHide: Boolean,
    onAutoHideChange: (Boolean) -> Unit,
    anchor: WindowAnchor,
    onAnchorChange: (WindowAnchor) -> Unit,
    preferredPlayer: String?,
    onPreferredPlayerChange: (String?) -> Unit,
    players: List<PlayerInfo>,
    onResetOverlayPosition: () -> Unit,
    showIntervalIndicator: Boolean,
    onShowIntervalIndicatorChange: (Boolean) -> Unit,
    respectAgentPositioning: Boolean,
    onRespectAgentPositioningChange: (Boolean) -> Unit,
    wordKaraoke: Boolean,
    onWordKaraokeChange: (Boolean) -> Unit,
    romanizeJapanese: Boolean,
    onRomanizeJapaneseChange: (Boolean) -> Unit,
    textPosition: LyricsTextPosition,
    onTextPositionChange: (LyricsTextPosition) -> Unit,
    autoScroll: Boolean,
    onAutoScrollChange: (Boolean) -> Unit,
    textOutline: Boolean,
    onTextOutlineChange: (Boolean) -> Unit,
    enabledProviders: Set<String>,
    onProviderEnabledChange: (String, Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalAppPalette.current
    val shape = RoundedCornerShape(20.dp)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(10.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(palette.surface)
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Settings",
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold),
                    color = palette.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "Close",
                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                    color = palette.accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onClose)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            Spacer(Modifier.height(6.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                // ----- Lyrics (Metrolist's lyrics settings) -----
                SectionTitle("Lyrics")
                SliderRow(
                    label = "Text size",
                    valueText = "${fontSizeSp}sp",
                    value = fontSizeSp.toFloat(),
                    valueRange = 12f..48f,
                    steps = 17,
                    onValueChange = { onFontSizeChange(it.roundToInt()) },
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Text position",
                        style = TextStyle(fontSize = 13.sp),
                        color = palette.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    ChipRow(
                        options = listOf("Left", "Center", "Right"),
                        selectedLabels = listOf(
                            LyricsTextPosition.LEFT,
                            LyricsTextPosition.CENTER,
                            LyricsTextPosition.RIGHT,
                        ).map { it == textPosition },
                        onSelect = { index ->
                            onTextPositionChange(
                                listOf(
                                    LyricsTextPosition.LEFT,
                                    LyricsTextPosition.CENTER,
                                    LyricsTextPosition.RIGHT,
                                )[index],
                            )
                        },
                    )
                }
                ToggleRow("Auto scroll", autoScroll, onAutoScrollChange, palette)
                ToggleRow("Text outline", textOutline, onTextOutlineChange, palette)
                ToggleRow("Karaoke word highlight", wordKaraoke, onWordKaraokeChange, palette)
                ToggleRow("Show interval indicator", showIntervalIndicator, onShowIntervalIndicatorChange, palette)
                ToggleRow("Respect agent positioning", respectAgentPositioning, onRespectAgentPositioningChange, palette)
                ToggleRow("Romanize Japanese lyrics", romanizeJapanese, onRomanizeJapaneseChange, palette)

                Spacer(Modifier.height(10.dp))
                SectionTitle("Lyrics sync")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Offset",
                        style = TextStyle(fontSize = 13.sp),
                        color = palette.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    StepperButton("−") { onOffsetChange(offsetMs - 50) }
                    Text(
                        text = "%+dms".format(offsetMs),
                        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                        color = palette.onSurface,
                        modifier = Modifier.width(72.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                    StepperButton("+") { onOffsetChange(offsetMs + 50) }
                }
                SliderRow(
                    label = "Fine tune",
                    valueText = "%+dms".format(offsetMs),
                    value = offsetMs.toFloat().coerceIn(-3000f, 3000f),
                    valueRange = -3000f..3000f,
                    steps = 59,
                    onValueChange = { onOffsetChange((it / 100).toLong() * 100) },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Text(
                        "Reset offset",
                        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                        color = palette.accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onOffsetChange(0) }
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }

                Spacer(Modifier.height(10.dp))
                SectionTitle("Lyrics providers")
                LyricsProviders.names.forEach { name ->
                    ToggleRow(name, name in enabledProviders, { onProviderEnabledChange(name, it) }, palette)
                }
                Text(
                    "Tried top to bottom until one returns lyrics.",
                    style = TextStyle(fontSize = 11.sp),
                    color = palette.onSurfaceDim,
                    modifier = Modifier.padding(top = 2.dp),
                )

                Spacer(Modifier.height(10.dp))
                SectionTitle("Overlay")
                SliderRow(
                    label = "Background opacity",
                    valueText = "${(opacity * 100).roundToInt()}%",
                    value = opacity,
                    valueRange = 0f..1f,
                    steps = 0,
                    onValueChange = onOpacityChange,
                )
                Text(
                    "0% keeps only the text, readable through its shadow.",
                    style = TextStyle(fontSize = 11.sp),
                    color = palette.onSurfaceDim,
                )
                ToggleRow("Show surrounding lines", showNextLine, onShowNextLineChange, palette)
                ToggleRow("Hide when nothing is playing", autoHide, onAutoHideChange, palette)

                Spacer(Modifier.height(10.dp))
                SectionTitle("Screen position")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    WindowAnchor.entries.forEach { option ->
                        ChipRow(
                            options = listOf(option.label()),
                            selectedLabels = listOf(anchor == option),
                            onSelect = { onAnchorChange(option) },
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Text(
                        "Reset to saved corner",
                        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                        color = palette.accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onResetOverlayPosition)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }

                Spacer(Modifier.height(10.dp))
                SectionTitle("Appearance")
                ChipRow(
                    options = ThemeMode.entries.map { it.label() },
                    selectedLabels = ThemeMode.entries.map { it == themeMode },
                    onSelect = { onThemeModeChange(ThemeMode.entries[it]) },
                )

                Spacer(Modifier.height(10.dp))
                SectionTitle("Song source")
                RadioRow(
                    label = "Auto (follows the playing song)",
                    selected = preferredPlayer == null,
                    onClick = { onPreferredPlayerChange(null) },
                )
                players.forEach { player ->
                    RadioRow(
                        label = player.identity,
                        selected = preferredPlayer == player.identity,
                        onClick = { onPreferredPlayerChange(player.identity) },
                    )
                }
                if (players.isEmpty()) {
                    Text(
                        "No media players detected on the session bus.",
                        style = TextStyle(fontSize = 11.sp),
                        color = palette.onSurfaceDim,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                Spacer(Modifier.height(14.dp))
                Text(
                    "Lyrics Float ${BuildVersion.VERSION} · lyrics via BetterLyrics, Lrclib, KuGou, Paxsenix, LyricsPlus",
                    style = TextStyle(fontSize = 11.sp),
                    color = palette.onSurfaceDim,
                )
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    val palette = LocalAppPalette.current
    Text(
        text,
        style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold),
        color = palette.accent.copy(alpha = 0.85f),
        modifier = Modifier.padding(bottom = 6.dp, top = 2.dp),
    )
}

@Composable
private fun switchColors(palette: AppPalette) = SwitchDefaults.colors(
    checkedThumbColor = palette.onAccent,
    checkedTrackColor = palette.accent,
    uncheckedThumbColor = palette.onSurfaceDim,
    uncheckedTrackColor = palette.onSurface.copy(alpha = 0.15f),
    uncheckedBorderColor = Color.Transparent,
)

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    palette: AppPalette,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = TextStyle(fontSize = 13.sp),
            color = palette.onSurface,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = switchColors(palette),
            modifier = Modifier.scale(0.8f),
        )
    }
}

@Composable
private fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    val palette = LocalAppPalette.current
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Row {
            Text(
                label,
                style = TextStyle(fontSize = 13.sp),
                color = palette.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                valueText,
                style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                color = palette.onSurfaceDim,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = palette.accent,
                activeTrackColor = palette.accent,
                inactiveTrackColor = palette.onSurface.copy(alpha = 0.15f),
            ),
        )
    }
}

@Composable
private fun ChipRow(options: List<String>, selectedLabels: List<Boolean>, onSelect: (Int) -> Unit) {
    val palette = LocalAppPalette.current
    val shape = RoundedCornerShape(10.dp)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEachIndexed { index, option ->
            val selected = selectedLabels[index]
            Box(
                modifier = Modifier
                    .clip(shape)
                    .background(
                        if (selected) palette.accent else palette.onSurface.copy(alpha = 0.06f),
                    )
                    .clickable { onSelect(index) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            ) {
                Text(
                    option,
                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                    color = if (selected) palette.onAccent else palette.onSurface,
                )
            }
        }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalAppPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .padding(end = 10.dp)
                .width(14.dp)
                .height(14.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(if (selected) palette.accent else Color.Transparent),
        )
        Text(
            label,
            style = TextStyle(fontSize = 13.sp),
            color = if (selected) palette.onSurface else palette.onSurfaceDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun StepperButton(symbol: String, onClick: () -> Unit) {
    val palette = LocalAppPalette.current
    Text(
        symbol,
        style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold),
        color = palette.accent,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 2.dp),
    )
}
