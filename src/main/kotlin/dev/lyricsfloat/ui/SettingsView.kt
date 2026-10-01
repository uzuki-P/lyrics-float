package dev.lyricsfloat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lyricsfloat.AppSettings
import dev.lyricsfloat.lyrics.BuildVersion
import dev.lyricsfloat.lyrics.LyricsProviders
import dev.lyricsfloat.mpris.PlayerInfo
import dev.lyricsfloat.platform.Autostart
import dev.lyricsfloat.platform.WindowAnchor
import kotlin.math.roundToInt

private enum class SettingsTab(val label: String) {
    LYRICS("Lyrics"),
    OVERLAY("Overlay"),
    SOURCES("Sources"),
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
 * Settings dialog, split into three tabs so no page needs a long scroll:
 * Lyrics follows Metrolist's lyrics settings (text, behavior toggles, sync
 * offset), Overlay holds the pill's look and window behavior, Sources picks
 * the MPRIS player and the provider chain. Every change applies live.
 */
@Composable
fun SettingsView(
    settings: AppSettings,
    players: List<PlayerInfo>,
    enabledProviders: Set<String>,
    onProviderEnabledChange: (String, Boolean) -> Unit,
    onAnchorChange: (WindowAnchor) -> Unit,
    onResetOverlayPosition: () -> Unit,
    onOpenTiming: () -> Unit,
    onClose: () -> Unit,
    dragHandleModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableStateOf(SettingsTab.LYRICS) }

    DialogFrame(
        title = "Settings",
        onClose = onClose,
        modifier = modifier,
        dragHandleModifier = dragHandleModifier,
    ) {
        SegmentedControl(
            options = SettingsTab.entries.map { it to it.label },
            selected = tab,
            onSelect = { tab = it },
            fill = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))

        // A fresh scroll state per tab so switching starts at the top.
        val scroll = remember(tab) { androidx.compose.foundation.ScrollState(0) }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (tab) {
                SettingsTab.LYRICS -> LyricsTab(settings, onOpenTiming)
                SettingsTab.OVERLAY -> OverlayTab(settings, onAnchorChange, onResetOverlayPosition)
                SettingsTab.SOURCES -> SourcesTab(settings, players, enabledProviders, onProviderEnabledChange)
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun LyricsTab(settings: AppSettings, onOpenTiming: () -> Unit) {
    SettingsGroup("Text") {
        SliderRow(
            label = "Text size",
            valueText = "${settings.fontSizeSp} sp",
            value = settings.fontSizeSp.toFloat(),
            valueRange = 12f..48f,
            steps = 17,
            onValueChange = { settings.fontSizeSp = it.roundToInt() },
        )
        SliderRow(
            label = "Romaji text size",
            valueText = "${settings.romajiFontSizeSp} sp",
            value = settings.romajiFontSizeSp.toFloat(),
            valueRange = 8f..30f,
            steps = 21,
            onValueChange = { settings.romajiFontSizeSp = it.roundToInt() },
        )
        SettingRow("Text position") {
            SegmentedControl(
                options = listOf(
                    LyricsTextPosition.LEFT to "Left",
                    LyricsTextPosition.CENTER to "Center",
                    LyricsTextPosition.RIGHT to "Right",
                ),
                selected = settings.textPosition,
                onSelect = { settings.textPosition = it },
            )
        }
        ToggleRow(
            "Text outline",
            settings.textOutline,
            { settings.textOutline = it },
            description = "Stroke around letters instead of a drop shadow",
        )
    }

    SettingsGroup("Behavior") {
        ToggleRow(
            "Auto scroll",
            settings.autoScroll,
            { settings.autoScroll = it },
            description = "Keep the current line centered",
        )
        ToggleRow(
            "Karaoke word highlight",
            settings.wordKaraoke,
            { settings.wordKaraoke = it },
            description = "Fill each word as it is sung",
        )
        ToggleRow(
            "Show interval indicator",
            settings.showIntervalIndicator,
            { settings.showIntervalIndicator = it },
            description = "Progress ring during instrumental breaks over 4 s",
        )
        ToggleRow(
            "Respect agent positioning",
            settings.respectAgentPositioning,
            { settings.respectAgentPositioning = it },
            description = "Duet lines align by singer, overriding text position",
        )
        ToggleRow(
            "Romanize Japanese lyrics",
            settings.romanizeJapanese,
            { settings.romanizeJapanese = it },
            description = "Romaji under each Japanese line",
        )
    }

    SettingsGroup(
        "Default sync offset",
        footer = "Applies to every song; per-song timing adds on top. Positive values show lines earlier. Up to ±60 s.",
    ) {
        SettingRow("Offset") {
            IconStepper("−", "Offset −50 ms") { settings.offsetMs -= 50 }
            ValueBadge("%+d ms".format(settings.offsetMs), Modifier.padding(horizontal = 4.dp).widthIn(min = 64.dp))
            IconStepper("+", "Offset +50 ms") { settings.offsetMs += 50 }
            Spacer(Modifier.width(6.dp))
            AppButton(
                "Reset",
                onClick = { settings.offsetMs = 0 },
                kind = ButtonKind.GHOST,
                enabled = settings.offsetMs != 0L,
                compact = true,
            )
        }
        SliderRow(
            label = "Fine tune",
            valueText = formatOffset(settings.offsetMs),
            value = settings.offsetMs.toFloat().coerceIn(-10_000f, 10_000f),
            valueRange = -10_000f..10_000f,
            steps = 0,
            onValueChange = { settings.offsetMs = (it / 100).roundToInt() * 100L },
        )
        SettingRow("This song", description = "Fix one song without moving the others") {
            AppButton("Lyrics timing…", onClick = onOpenTiming, icon = AppIcons.Timer, compact = true)
        }
    }
}

@Composable
private fun OverlayTab(
    settings: AppSettings,
    onAnchorChange: (WindowAnchor) -> Unit,
    onResetOverlayPosition: () -> Unit,
) {
    val palette = LocalAppPalette.current
    var autostartEnabled by remember { mutableStateOf(Autostart.isEnabled()) }
    var autostartError by remember { mutableStateOf(false) }

    SettingsGroup("Look") {
        SliderRow(
            label = "Background opacity",
            valueText = "${(settings.opacity * 100).roundToInt()}%",
            value = settings.opacity,
            valueRange = 0f..1f,
            steps = 0,
            onValueChange = { settings.opacity = it },
            description = "0% keeps only the text, readable through its outline or shadow",
        )
        SettingRow("Theme") {
            SegmentedControl(
                options = listOf(ThemeMode.SYSTEM to "System", ThemeMode.DARK to "Dark", ThemeMode.LIGHT to "Light"),
                selected = settings.themeMode,
                onSelect = { settings.themeMode = it },
            )
        }
        ToggleRow("Show surrounding lines", settings.showNextLine, { settings.showNextLine = it })
    }

    SettingsGroup("Behavior") {
        ToggleRow(
            "Hide when nothing is playing",
            settings.autoHide,
            { settings.autoHide = it },
        )
        ToggleRow(
            "Click pass-through",
            settings.clickPassThrough,
            { settings.clickPassThrough = it },
            description = "The pill ignores all pointer input, including its hover bar. Turn it off here or from the tray menu.",
        )
        SettingRow("Hover bar", description = "Edge where the controls appear") {
            SegmentedControl(
                options = listOf(HoverMenuPosition.TOP to "Top", HoverMenuPosition.BOTTOM to "Bottom"),
                selected = settings.hoverMenuPosition,
                onSelect = { settings.hoverMenuPosition = it },
            )
        }
        ToggleRow(
            "Start on login",
            autostartEnabled,
            { enabled ->
                autostartError = !Autostart.setEnabled(enabled)
                autostartEnabled = Autostart.isEnabled()
            },
        )
        if (autostartError) {
            InlineMessage(
                "Could not set autostart. Run the installed app or AppImage.",
                MessageTone.ERROR,
                Modifier.padding(bottom = 6.dp),
            )
        }
    }

    SettingsGroup(
        "Screen position",
        footer = "Dragging the pill keeps it where you drop it. Pick a spot or snap back to place it at the anchor.",
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnchorPicker(
                selected = settings.overlayAnchor,
                onSelect = onAnchorChange,
                modifier = Modifier.width(150.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(settings.overlayAnchor.label(), style = AppType.bodyStrong, color = palette.onSurface)
                Spacer(Modifier.height(6.dp))
                AppButton("Snap to anchor", onClick = onResetOverlayPosition, compact = true)
            }
        }
    }
}

@Composable
private fun SourcesTab(
    settings: AppSettings,
    players: List<PlayerInfo>,
    enabledProviders: Set<String>,
    onProviderEnabledChange: (String, Boolean) -> Unit,
) {
    val palette = LocalAppPalette.current

    SettingsGroup("Song source") {
        RadioRow(
            label = "Auto",
            description = "Follows whichever player is playing",
            selected = settings.preferredPlayer == null,
            onClick = { settings.preferredPlayer = null },
        )
        players.forEach { player ->
            RadioRow(
                label = player.identity,
                selected = settings.preferredPlayer == player.identity,
                onClick = { settings.preferredPlayer = player.identity },
            )
        }
        if (players.isEmpty()) {
            Text(
                "No media players detected on the session bus.",
                style = AppType.caption,
                color = palette.onSurfaceDim,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
    }

    SettingsGroup("Lyrics providers", footer = "Tried top to bottom until one returns lyrics.") {
        LyricsProviders.names.forEachIndexed { index, name ->
            if (index > 0) GroupDivider()
            ToggleRow("${index + 1}. $name", name in enabledProviders, { onProviderEnabledChange(name, it) })
        }
    }

    Text(
        "Lyrics Float ${BuildVersion.VERSION} · lyrics via BetterLyrics, Lrclib, KuGou, Paxsenix, LyricsPlus",
        style = AppType.caption,
        color = palette.onSurfaceFaint,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

/**
 * Screen-shaped 3x3 grid: each cell is one anchor (the center has none), the
 * selected one shows a small pill where the overlay will sit.
 */
@Composable
private fun AnchorPicker(
    selected: WindowAnchor,
    onSelect: (WindowAnchor) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalAppPalette.current
    val grid = listOf(
        listOf(WindowAnchor.TOP_LEFT, WindowAnchor.TOP_CENTER, WindowAnchor.TOP_RIGHT),
        listOf(WindowAnchor.MIDDLE_LEFT, null, WindowAnchor.MIDDLE_RIGHT),
        listOf(WindowAnchor.BOTTOM_LEFT, WindowAnchor.BOTTOM_CENTER, WindowAnchor.BOTTOM_RIGHT),
    )
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = modifier
            .aspectRatio(16f / 10f)
            .clip(shape)
            .background(palette.surface)
            .border(1.5.dp, palette.outline, shape)
            .padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        grid.forEach { row ->
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                row.forEach { anchor ->
                    if (anchor == null) {
                        Spacer(Modifier.weight(1f))
                    } else {
                        val isSelected = anchor == selected
                        val (source, hovered) = rememberHoverSource()
                        WithTooltip(anchor.label(), Modifier.weight(1f).fillMaxSize()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        when {
                                            isSelected -> palette.accentSoft
                                            hovered -> palette.cardHover
                                            else -> palette.card
                                        },
                                    )
                                    .pointerHoverIcon(PointerIcon.Hand)
                                    .clickable(interactionSource = source, indication = null) { onSelect(anchor) },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (isSelected || hovered) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth(0.62f)
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(50))
                                            .background(if (isSelected) palette.accent else palette.onSurfaceFaint),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit, description: String? = null) {
    val palette = LocalAppPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(RoundedCornerShape(50))
                .border(1.5.dp, if (selected) palette.accent else palette.onSurfaceFaint, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(palette.accent))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = AppType.body,
                color = if (selected) palette.onSurface else palette.onSurfaceDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (description != null) {
                Text(description, style = AppType.caption, color = palette.onSurfaceDim)
            }
        }
    }
}

@Composable
private fun IconStepper(symbol: String, label: String, onClick: () -> Unit) {
    val palette = LocalAppPalette.current
    val (source, hovered) = rememberHoverSource()
    WithTooltip(label) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (hovered) palette.cardHover else Color.Transparent)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(interactionSource = source, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(symbol, style = AppType.title, color = palette.accent)
        }
    }
}
