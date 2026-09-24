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
import androidx.compose.ui.geometry.Offset
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
import dev.lyricsfloat.lyrics.LrcLib
import dev.lyricsfloat.lyrics.LyricsProviders
import dev.lyricsfloat.lyrics.LyricsRepository
import dev.lyricsfloat.mpris.NowPlayingMonitor
import dev.lyricsfloat.platform.AppState
import dev.lyricsfloat.platform.Autostart
import dev.lyricsfloat.platform.LinuxSniTray
import dev.lyricsfloat.platform.LinuxWindowMover
import dev.lyricsfloat.platform.WindowAnchor
import dev.lyricsfloat.platform.anchorPosition
import dev.lyricsfloat.ui.DarkPalette
import dev.lyricsfloat.ui.LightPalette
import dev.lyricsfloat.ui.LocalAppPalette
import dev.lyricsfloat.ui.LyricsTextPosition
import dev.lyricsfloat.ui.OverlayView
import dev.lyricsfloat.ui.SearchView
import dev.lyricsfloat.ui.SettingsView
import dev.lyricsfloat.ui.ThemeMode
import dev.lyricsfloat.ui.rememberSystemThemeIsDark
import dev.lyricsfloat.ui.themeIsDark
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

fun main() = application {
    installCrashLog()
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
    var showIntervalIndicator by remember { mutableStateOf(AppState.loadShowIntervalIndicator()) }
    var respectAgentPositioning by remember { mutableStateOf(AppState.loadRespectAgentPositioning()) }
    var wordKaraoke by remember { mutableStateOf(AppState.loadWordKaraoke()) }
    var romanizeJapanese by remember { mutableStateOf(AppState.loadRomanizeJapanese()) }
    var textPosition by remember {
        mutableStateOf(runCatching { LyricsTextPosition.valueOf(AppState.loadTextPosition()) }.getOrDefault(LyricsTextPosition.CENTER))
    }
    var autoScroll by remember { mutableStateOf(AppState.loadAutoScroll()) }
    var textOutline by remember { mutableStateOf(AppState.loadTextOutline()) }

    val monitor = remember { NowPlayingMonitor() }
    val repository = remember { LyricsRepository(scope) }
    val playback by monitor.active.collectAsState()
    val players by monitor.players.collectAsState()
    val lyrics by repository.current.collectAsState()
    val lyricsLoading by repository.loading.collectAsState()
    var enabledProviders by remember { mutableStateOf(LyricsProviders.enabledNames()) }

    LaunchedEffect(Unit) {
        monitor.start(scope)
        repository.followPlayback(monitor)
        // Load JNA's native side before the first drag so the window does not
        // lag behind the cursor.
        scope.launch(Dispatchers.IO) { LinuxWindowMover.warmUp() }
        scope.launch(Dispatchers.IO) { Autostart.refresh() }
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
        resizable = true,
        focusable = false,
        alwaysOnTop = true,
        state = rememberWindowState(
            size = DpSize(AppState.loadOverlayWidthDp().dp, AppState.loadOverlayHeightDp().dp),
            position = WindowPosition(Alignment.BottomCenter),
        ),
    ) {
        DisposableEffect(window) {
            window.iconImage = requireNotNull(
                LinuxSniTray::class.java.classLoader.getResourceAsStream("icons/lyrics-float-v2.png"),
            ) { "missing taskbar icon" }.use(ImageIO::read)
            onDispose { window.iconImage = null }
        }
        TransparentWindowBackground(window)
        val density = LocalDensity.current

        // AWT floor so the pill can never shrink below a usable size, even if
        // the WM ignores our minimum-size hints.
        DisposableEffect(window, density) {
            window.minimumSize = Dimension(
                with(density) { AppState.MIN_WIDTH_DP.dp.roundToPx() },
                with(density) { AppState.MIN_HEIGHT_DP.dp.roundToPx() },
            )
            onDispose { window.minimumSize = Dimension(0, 0) }
        }

        // Mapping-time placement: keep overriding for a few frames because
        // AWT/KWin re-place the window while it is being mapped.
        var mappingSettled by remember { mutableStateOf(false) }
        LaunchedEffect(overlayShown) {
            if (!overlayShown) return@LaunchedEffect
            mappingSettled = false
            val bounds = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .defaultScreenDevice.defaultConfiguration.bounds
            val margin = with(density) { 24.dp.roundToPx() }
            val position = customPosition
                ?: anchorPosition(overlayAnchor, bounds, window.width, window.height, margin)
            repeat(5) {
                window.setLocation(position.x, position.y)
                withFrameNanos { }
            }
            mappingSettled = true
        }

        // The WM's interactive move/resize consumes the pointer release, so
        // Compose's drag-end never fires for native gestures. Persist geometry
        // from AWT events instead, debounced, and only once mapping has
        // settled so the placement loop above is not recorded as a custom spot.
        var persistJob by remember { mutableStateOf<Job?>(null) }
        DisposableEffect(window) {
            val listener = object : ComponentAdapter() {
                override fun componentMoved(e: ComponentEvent) {
                    if (!mappingSettled) return
                    customPosition = window.location
                    persistJob?.cancel()
                    persistJob = scope.launch {
                        delay(500)
                        AppState.saveOverlayPosition(window.x, window.y)
                    }
                }

                override fun componentResized(e: ComponentEvent) {
                    if (!mappingSettled) return
                    persistJob?.cancel()
                    persistJob = scope.launch {
                        delay(500)
                        AppState.saveOverlaySize(
                            (window.width / density.density),
                            (window.height / density.density),
                        )
                    }
                }
            }
            window.addComponentListener(listener)
            onDispose {
                window.removeComponentListener(listener)
                persistJob?.cancel()
            }
        }

        var dragBase by remember { mutableStateOf<Point?>(null) }
        var dragDelta by remember { mutableStateOf(Offset.Zero) }
        var wmMoving by remember { mutableStateOf(false) }
        var manualResizeBase by remember { mutableStateOf<Rectangle?>(null) }
        val minSizePx = with(density) {
            Dimension(
                AppState.MIN_WIDTH_DP.dp.roundToPx(),
                AppState.MIN_HEIGHT_DP.dp.roundToPx(),
            )
        }
        CompositionLocalProvider(LocalAppPalette provides palette) {
            OverlayView(
                playback = playback,
                lyrics = lyrics,
                loading = lyricsLoading,
                fontSizeSp = fontSizeSp,
                opacity = opacity,
                showNext = showNextLine,
                offsetMs = offsetMs,
                showIntervalIndicator = showIntervalIndicator,
                respectAgentPositioning = respectAgentPositioning,
                wordKaraoke = wordKaraoke,
                romanizeJapanese = romanizeJapanese,
                textPosition = textPosition,
                textOutline = textOutline,
                autoScroll = autoScroll,
                onSearch = { searchVisible = true },
                onSettings = { settingsVisible = true },
                onDragStart = {
                    // setLocation dragging jitters under XWayland; the WM's own
                    // move tracks the pointer per-frame, so prefer it there.
                    wmMoving = LinuxWindowMover.requestInteractiveMove(window)
                    if (!wmMoving) {
                        dragBase = Point(window.x, window.y)
                        dragDelta = Offset.Zero
                    }
                },
                onDrag = { dx, dy ->
                    if (!wmMoving) {
                        val base = dragBase
                        if (base != null) {
                            dragDelta += Offset(dx, dy)
                            val scale = density.density
                            window.setLocation(
                                base.x + (dragDelta.x * scale).roundToInt(),
                                base.y + (dragDelta.y * scale).roundToInt(),
                            )
                        }
                    }
                },
                onDragEnd = {
                    dragBase = null
                    wmMoving = false
                    customPosition = Point(window.x, window.y)
                    AppState.saveOverlayPosition(window.x, window.y)
                },
                onResizeStart = { direction ->
                    // Native WM resize (edge/corner grab); fall back to manual
                    // setSize tracking when the WM refuses the message.
                    val native = LinuxWindowMover.requestInteractiveResize(window, direction)
                    manualResizeBase = if (native) null else window.bounds
                    native
                },
                onManualResize = { direction, dx, dy ->
                    val base = manualResizeBase
                    if (base != null) {
                        val scale = density.density
                        val ddx = (dx * scale).roundToInt()
                        val ddy = (dy * scale).roundToInt()
                        var x = base.x
                        var y = base.y
                        var w = base.width
                        var h = base.height
                        val fromLeft = direction == 0 || direction == 3 || direction == 5
                        val fromTop = direction == 0 || direction == 1 || direction == 2
                        if (direction == 2 || direction == 4 || direction == 7) w = base.width + ddx
                        if (direction == 5 || direction == 6 || direction == 7) h = base.height + ddy
                        if (fromLeft) {
                            x = base.x + ddx
                            w = base.width - ddx
                        }
                        if (fromTop) {
                            y = base.y + ddy
                            h = base.height - ddy
                        }
                        if (w < minSizePx.width) {
                            w = minSizePx.width
                            if (fromLeft) x = base.x + (base.width - minSizePx.width)
                        }
                        if (h < minSizePx.height) {
                            h = minSizePx.height
                            if (fromTop) y = base.y + (base.height - minSizePx.height)
                        }
                        window.setBounds(x, y, w, h)
                    }
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
                hasTarget = playback?.track?.hasContent == true,
                overrideApplied = repository.hasOverride(),
                initialQuery = playback?.track?.let { track ->
                    listOfNotNull(
                        LrcLib.cleanTitle(track.title).takeIf(String::isNotBlank),
                        LrcLib.cleanArtist(track.artist).takeIf(String::isNotBlank),
                    ).joinToString(" ")
                }.orEmpty(),
                onPick = { result -> repository.applyManualPick(result, monitor.active.value?.track) },
                onClearOverride = repository::clearOverride,
                onClose = { searchVisible = false },
                search = { query, provider -> repository.search(query, provider) },
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
                showIntervalIndicator = showIntervalIndicator,
                onShowIntervalIndicatorChange = { value ->
                    showIntervalIndicator = value
                    AppState.saveShowIntervalIndicator(value)
                },
                respectAgentPositioning = respectAgentPositioning,
                onRespectAgentPositioningChange = { value ->
                    respectAgentPositioning = value
                    AppState.saveRespectAgentPositioning(value)
                },
                wordKaraoke = wordKaraoke,
                onWordKaraokeChange = { value ->
                    wordKaraoke = value
                    AppState.saveWordKaraoke(value)
                },
                romanizeJapanese = romanizeJapanese,
                onRomanizeJapaneseChange = { value ->
                    romanizeJapanese = value
                    repository.setRomanizeJapanese(value)
                },
                textPosition = textPosition,
                onTextPositionChange = { value ->
                    textPosition = value
                    AppState.saveTextPosition(value.name)
                },
                autoScroll = autoScroll,
                onAutoScrollChange = { value ->
                    autoScroll = value
                    AppState.saveAutoScroll(value)
                },
                textOutline = textOutline,
                onTextOutlineChange = { value ->
                    textOutline = value
                    AppState.saveTextOutline(value)
                },
                enabledProviders = enabledProviders,
                onProviderEnabledChange = { name, enabled ->
                    val next = enabledProviders.toMutableSet()
                    if (enabled) next.add(name) else next.remove(name)
                    enabledProviders = next
                    LyricsProviders.setEnabled(next)
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

/**
 * GUI-launched apps have no terminal, so a crash would vanish without a
 * trace. Every uncaught exception lands in $XDG_DATA_HOME/lyricsfloat/crash.log.
 */
private fun installCrashLog() {
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        runCatching {
            val dir = File(
                System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
                    ?: (System.getProperty("user.home") + "/.local/share"),
                "lyricsfloat",
            ).apply { mkdirs() }
            File(dir, "crash.log").appendText(
                buildString {
                    append("==== ")
                    append(java.time.LocalDateTime.now())
                    append(" thread=")
                    append(thread.name)
                    appendLine()
                    append(throwable.stackTraceToString())
                    appendLine()
                },
            )
            throwable.printStackTrace()
        }
    }
}
