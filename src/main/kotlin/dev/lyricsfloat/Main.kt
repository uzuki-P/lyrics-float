@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.lyricsfloat

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.awt.SwingDialog
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowDecoration
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberDialogState
import androidx.compose.ui.window.rememberWindowState
import dev.lyricsfloat.lyrics.LyricsRepository
import dev.lyricsfloat.mpris.NowPlayingMonitor
import dev.lyricsfloat.platform.AppState
import dev.lyricsfloat.platform.LinuxSniTray
import dev.lyricsfloat.platform.WindowAnchor
import dev.lyricsfloat.platform.anchorPosition
import dev.lyricsfloat.ui.DarkPalette
import dev.lyricsfloat.ui.LightPalette
import dev.lyricsfloat.ui.LocalAppPalette
import dev.lyricsfloat.ui.OverlayView
import dev.lyricsfloat.ui.SearchView
import dev.lyricsfloat.ui.SettingsView
import dev.lyricsfloat.ui.ThemeMode
import dev.lyricsfloat.ui.rememberSystemThemeIsDark
import dev.lyricsfloat.ui.themeIsDark
import java.awt.Point

fun main() = application {
    val scope = rememberCoroutineScope()

    var overlayVisible by remember { mutableStateOf(true) }
    var searchVisible by remember { mutableStateOf(false) }
    var settingsVisible by remember { mutableStateOf(false) }

    val darkTheme by rememberSystemThemeIsDark()
    var themeMode by remember { mutableStateOf(AppState.loadThemeMode()) }
    val palette = if (themeIsDark(themeMode, darkTheme)) DarkPalette else LightPalette

    var fontSizeSp by remember { mutableStateOf(AppState.loadFontSizeSp()) }
    var opacity by remember { mutableStateOf(AppState.loadOpacity()) }
    var showNextLine by remember { mutableStateOf(AppState.loadShowNextLine()) }
    var offsetMs by remember { mutableStateOf(AppState.loadOffsetMs()) }
    var autoHide by remember { mutableStateOf(AppState.loadAutoHide()) }
    var preferredPlayer by remember { mutableStateOf(AppState.loadPreferredPlayer()) }
    var overlayAnchor by remember { mutableStateOf(AppState.loadOverlayAnchor()) }

    val monitor = remember { NowPlayingMonitor() }
    val repository = remember { LyricsRepository(scope) }
    val playback by monitor.active.collectAsState()
    val players by monitor.players.collectAsState()
    val lyrics by repository.current.collectAsState()
    val lyricsLoading by repository.loading.collectAsState()

    LaunchedEffect(Unit) {
        monitor.start(scope)
        repository.followPlayback(monitor)
    }
    LaunchedEffect(preferredPlayer) {
        monitor.preferredIdentity = preferredPlayer
    }

    // The pill hides itself when nothing is playing, unless auto-hide is off.
    val playbackGone = playback == null || playback?.isPastEnd() == true
    val overlayShown = overlayVisible && (!playbackGone || !autoHide)
    val quit = { exitApplication() }

    DisposableEffect(Unit) {
        val tray = LinuxSniTray(
            onToggleOverlay = { overlayVisible = !overlayVisible },
            onSearch = { searchVisible = true },
            onSettings = { settingsVisible = true },
            onQuit = quit,
        )
        tray.start()
        onDispose { tray.stop() }
    }

    // ----- floating lyrics window -----

    var customPosition by remember { mutableStateOf(AppState.loadOverlayX()?.let { x -> AppState.loadOverlayY()?.let { y -> Point(x, y) } }) }

    Window(
        onCloseRequest = { overlayVisible = false },
        visible = overlayShown,
        title = "Lyrics Float",
        undecorated = true,
        transparent = true,
        resizable = false,
        focusable = false,
        alwaysOnTop = true,
        state = rememberWindowState(
            size = DpSize(560.dp, 170.dp),
            position = WindowPosition(Alignment.BottomCenter),
        ),
    ) {
        TransparentWindowBackground(window)
        val density = LocalDensity.current
        LaunchedEffect(overlayShown) {
            if (!overlayShown) return@LaunchedEffect
            val bounds = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .defaultScreenDevice.defaultConfiguration.bounds
            val margin = with(density) { 24.dp.roundToPx() }
            val width = with(density) { 560.dp.roundToPx() }
            val height = with(density) { 170.dp.roundToPx() }
            val position = customPosition
                ?: anchorPosition(overlayAnchor, bounds, width, height, margin)
            // AWT/KWin may re-place the window while it is being mapped, so keep
            // overriding for a few frames until it settles at the chosen spot.
            repeat(5) {
                window.setLocation(position.x, position.y)
                withFrameNanos { }
            }
        }
        var dragBase by remember { mutableStateOf<Point?>(null) }
        CompositionLocalProvider(LocalAppPalette provides palette) {
            OverlayView(
                playback = playback,
                lyrics = lyrics,
                loading = lyricsLoading,
                fontSizeSp = fontSizeSp,
                opacity = opacity,
                showNext = showNextLine,
                offsetMs = offsetMs,
                onSearch = { searchVisible = true },
                onSettings = { settingsVisible = true },
                onDragStart = { dragBase = Point(window.x, window.y) },
                onDrag = { dx, dy ->
                    val base = dragBase
                    if (base != null) window.setLocation(base.x + dx, base.y + dy)
                },
                onDragEnd = {
                    dragBase = null
                    customPosition = Point(window.x, window.y)
                    AppState.saveOverlayPosition(window.x, window.y)
                },
            )
        }
    }

    // ----- search window -----

    SwingDialog(
        onCloseRequest = { searchVisible = false },
        state = rememberDialogState(size = DpSize(400.dp, 560.dp)),
        visible = searchVisible,
        title = "Lyrics Float Search",
        icon = null,
        decoration = WindowDecoration.Undecorated(0.dp),
        transparent = true,
        resizable = false,
        enabled = true,
        focusable = true,
        alwaysOnTop = true,
        onPreviewKeyEvent = { false },
        onKeyEvent = { false },
        modalityType = java.awt.Dialog.ModalityType.MODELESS,
        init = { dialog ->
            // Utility windows are skipped by the taskbar, pager and alt-tab.
            runCatching { dialog.type = java.awt.Window.Type.UTILITY }
        },
    ) {
        TransparentWindowBackground(window)
        val density = LocalDensity.current
        LaunchedEffect(Unit) {
            val bounds = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .defaultScreenDevice.defaultConfiguration.bounds
            val width = with(density) { 400.dp.roundToPx() }
            val height = with(density) { 560.dp.roundToPx() }
            // AWT centers the dialog when it becomes visible, so keep overriding
            // for a few frames until it settles centered on the screen.
            repeat(5) {
                window.setLocation(bounds.x + (bounds.width - width) / 2, bounds.y + (bounds.height - height) / 2)
                withFrameNanos { }
            }
        }
        CompositionLocalProvider(LocalAppPalette provides palette) {
            SearchView(
                targetTitle = playback?.track?.title.orEmpty(),
                targetArtist = playback?.track?.artist.orEmpty(),
                hasTarget = repository.overrideTargetKey.isNotEmpty(),
                overrideApplied = repository.hasOverride(),
                onPick = repository::applyPick,
                onClearOverride = repository::clearOverride,
                onClose = { searchVisible = false },
                search = { query -> repository.search(query) },
            )
        }
    }

    // ----- settings window -----

    SwingDialog(
        onCloseRequest = { settingsVisible = false },
        state = rememberDialogState(size = DpSize(360.dp, 660.dp)),
        visible = settingsVisible,
        title = "Lyrics Float Settings",
        icon = null,
        decoration = WindowDecoration.Undecorated(0.dp),
        transparent = true,
        resizable = false,
        enabled = true,
        focusable = true,
        alwaysOnTop = true,
        onPreviewKeyEvent = { false },
        onKeyEvent = { false },
        modalityType = java.awt.Dialog.ModalityType.MODELESS,
        init = { dialog ->
            runCatching { dialog.type = java.awt.Window.Type.UTILITY }
        },
    ) {
        TransparentWindowBackground(window)
        val density = LocalDensity.current
        LaunchedEffect(Unit) {
            val bounds = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .defaultScreenDevice.defaultConfiguration.bounds
            val width = with(density) { 360.dp.roundToPx() }
            val height = with(density) { 660.dp.roundToPx() }
            repeat(5) {
                window.setLocation(bounds.x + (bounds.width - width) / 2, bounds.y + (bounds.height - height) / 2)
                withFrameNanos { }
            }
        }
        CompositionLocalProvider(LocalAppPalette provides palette) {
            SettingsView(
                themeMode = themeMode,
                onThemeModeChange = { mode ->
                    themeMode = mode
                    AppState.saveThemeMode(mode)
                },
                fontSizeSp = fontSizeSp,
                onFontSizeChange = { size ->
                    fontSizeSp = size
                    AppState.saveFontSizeSp(size)
                },
                opacity = opacity,
                onOpacityChange = { value ->
                    opacity = value
                    AppState.saveOpacity(value)
                },
                showNextLine = showNextLine,
                onShowNextLineChange = { value ->
                    showNextLine = value
                    AppState.saveShowNextLine(value)
                },
                offsetMs = offsetMs,
                onOffsetChange = { value ->
                    offsetMs = value
                    AppState.saveOffsetMs(value)
                },
                autoHide = autoHide,
                onAutoHideChange = { value ->
                    autoHide = value
                    AppState.saveAutoHide(value)
                },
                anchor = overlayAnchor,
                onAnchorChange = { anchor ->
                    overlayAnchor = anchor
                    AppState.saveOverlayAnchor(anchor)
                    customPosition = null
                },
                preferredPlayer = preferredPlayer,
                onPreferredPlayerChange = { identity ->
                    preferredPlayer = identity
                    AppState.savePreferredPlayer(identity)
                },
                players = players,
                onResetOverlayPosition = {
                    customPosition = null
                },
                onClose = { settingsVisible = false },
            )
        }
    }
}

@Composable
private fun TransparentWindowBackground(window: java.awt.Window) {
    DisposableEffect(window) {
        val old = window.background
        window.background = java.awt.Color(0, 0, 0, 0)
        onDispose { window.background = old }
    }
}
