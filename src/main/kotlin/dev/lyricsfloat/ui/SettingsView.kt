package dev.lyricsfloat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lyricsfloat.lyrics.BuildVersion
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

private data class FontSizeOption(val label: String, val sp: Int)

private val fontSizeOptions = listOf(
    FontSizeOption("Small", 16),
    FontSizeOption("Medium", 20),
    FontSizeOption("Large", 26),
)

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
                .padding(16.dp),
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

            Spacer(Modifier.height(8.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                SectionTitle("Overlay")
                OptionRow("Show next line") {
                    Switch(
                        checked = showNextLine,
                        onCheckedChange = onShowNextLineChange,
                        colors = switchColors(palette),
                        modifier = Modifier.scale(0.85f),
                    )
                }
                OptionRow("Hide when nothing is playing") {
                    Switch(
                        checked = autoHide,
                        onCheckedChange = onAutoHideChange,
                        colors = switchColors(palette),
                        modifier = Modifier.scale(0.85f),
                    )
                }

                SliderRow(
                    label = "Text size",
                    valueText = "${fontSizeSp}sp",
                    value = fontSizeSp.toFloat(),
                    valueRange = 14f..40f,
                    steps = 12,
                    onValueChange = { onFontSizeChange(it.toInt()) },
                )
                SliderRow(
                    label = "Background opacity",
                    valueText = "${(opacity * 100).roundToInt()}%",
                    value = opacity,
                    valueRange = 0.3f..0.95f,
                    steps = 0,
                    onValueChange = onOpacityChange,
                )
                Spacer(Modifier.height(12.dp))
                SectionTitle("Screen position")
                ChipRow(
                    options = WindowAnchor.entries.map { it.label() },
                    selectedIndex = WindowAnchor.entries.indexOf(anchor),
                    onSelect = { onAnchorChange(WindowAnchor.entries[it]) },
                )
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

                Spacer(Modifier.height(12.dp))
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
                    StepperButton("−") { onOffsetChange(offsetMs - 100) }
                    Text(
                        text = "${offsetMs}ms",
                        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                        color = palette.onSurface,
                        modifier = Modifier.width(64.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    StepperButton("+") { onOffsetChange(offsetMs + 100) }
                }
                Text(
                    "Positive values show lines earlier.",
                    style = TextStyle(fontSize = 11.sp),
                    color = palette.onSurfaceDim,
                    modifier = Modifier.padding(top = 4.dp),
                )

                Spacer(Modifier.height(12.dp))
                SectionTitle("Appearance")
                ChipRow(
                    options = ThemeMode.entries.map { it.label() },
                    selectedIndex = ThemeMode.entries.indexOf(themeMode),
                    onSelect = { onThemeModeChange(ThemeMode.entries[it]) },
                )

                Spacer(Modifier.height(12.dp))
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

                Spacer(Modifier.height(16.dp))
                Text(
                    "Lyrics Float ${BuildVersion.VERSION} · lyrics from lrclib.net",
                    style = TextStyle(fontSize = 11.sp),
                    color = palette.onSurfaceDim,
                )
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
        color = palette.onSurfaceDim,
        modifier = Modifier.padding(bottom = 6.dp),
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
private fun OptionRow(label: String, trailing: @Composable () -> Unit) {
    val palette = LocalAppPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = TextStyle(fontSize = 13.sp),
            color = palette.onSurface,
            modifier = Modifier.weight(1f),
        )
        trailing()
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
private fun ChipRow(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    val palette = LocalAppPalette.current
    val shape = RoundedCornerShape(10.dp)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
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

