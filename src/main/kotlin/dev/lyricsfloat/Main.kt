@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.lyricsfloat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingDialog
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowDecoration
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberDialogState
import androidx.compose.ui.window.rememberWindowState
import dev.lyricsfloat.lyrics.LrcLib
import dev.lyricsfloat.lyrics.LyricsProviders
import dev.lyricsfloat.lyrics.LyricsRepository
import dev.lyricsfloat.lyrics.SongOffsets
import dev.lyricsfloat.lyrics.currentIndexAt
import dev.lyricsfloat.lyrics.songKey
import dev.lyricsfloat.mpris.NowPlayingMonitor
import dev.lyricsfloat.platform.AppState
import dev.lyricsfloat.platform.Autostart
import dev.lyricsfloat.platform.LinuxClickThrough
import dev.lyricsfloat.platform.LinuxSniTray
import dev.lyricsfloat.platform.LinuxWindowMover
import dev.lyricsfloat.platform.anchorPosition
import dev.lyricsfloat.ui.AppPalette
import dev.lyricsfloat.ui.DarkPalette
import dev.lyricsfloat.ui.LightPalette
import dev.lyricsfloat.ui.LocalAppPalette
import dev.lyricsfloat.ui.OverlayView
import dev.lyricsfloat.ui.SearchView
import dev.lyricsfloat.ui.SettingsView
import dev.lyricsfloat.ui.TimingView
import dev.lyricsfloat.ui.WindowResizeZones
import dev.lyricsfloat.ui.rememberSystemThemeIsDark
import dev.lyricsfloat.ui.themeIsDark
import dev.lyricsfloat.ui.windowDragHandle
import java.awt.Dimension
import java.awt.Point
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun main() = application {
    installCrashLog()
    val scope = rememberCoroutineScope()

    var overlayVisible by remember { mutableStateOf(true) }
    var searchVisible by remember { mutableStateOf(false) }
    var settingsVisible by remember { mutableStateOf(false) }
    var timingVisible by remember { mutableStateOf(false) }

    val monitor = remember { NowPlayingMonitor() }
    val repository = remember { LyricsRepository(scope) }
    val settings = remember { AppSettings(onRomanizeJapaneseChange = repository::setRomanizeJapanese) }
    val playback by monitor.active.collectAsState()
    val players by monitor.players.collectAsState()
    val lyrics by repository.current.collectAsState()
    val lyricsLoading by repository.loading.collectAsState()
    var enabledProviders by remember { mutableStateOf(LyricsProviders.enabledNames()) }

    // Per-song offsets add on top of the global one.
    val songOffsets = remember {
        SongOffsets(
            read = { AppState.readRaw(SongOffsets.STORAGE_KEY) },
            write = { AppState.writeRaw(SongOffsets.STORAGE_KEY, it) },
        )
    }
    val songOffsetMap by songOffsets.all.collectAsState()
    val currentSongKey = playback?.track?.takeIf { it.hasContent }?.let(::songKey)
    val songOffsetMs = currentSongKey?.let { songOffsetMap[it] } ?: 0L
    val effectiveOffsetMs = settings.offsetMs + songOffsetMs

    val darkTheme by rememberSystemThemeIsDark()
    val palette = if (themeIsDark(settings.themeMode, darkTheme)) DarkPalette else LightPalette

    LaunchedEffect(Unit) {
        monitor.start(scope)
        repository.followPlayback(monitor)
        // Load JNA's native side before the first drag so the window does not
        // lag behind the cursor.
        scope.launch(Dispatchers.IO) { LinuxWindowMover.warmUp() }
        scope.launch(Dispatchers.IO) { LinuxClickThrough.warmUp() }
        scope.launch(Dispatchers.IO) { Autostart.refresh() }
    }
    LaunchedEffect(settings.preferredPlayer) {
        monitor.preferredIdentity = settings.preferredPlayer
    }

    // The pill hides itself when nothing is playing, unless auto-hide is off.
    val playbackGone = playback == null || playback?.isPastEnd() == true
    val overlayShown = overlayVisible && (!playbackGone || !settings.autoHide)
    val quit = { exitApplication() }

    val toggleClickPassThrough = {
        settings.clickPassThrough = !settings.clickPassThrough
    }
    DisposableEffect(Unit) {
        val tray = LinuxSniTray(
            onToggleOverlay = { overlayVisible = !overlayVisible },
            onSearch = { searchVisible = true },
            onSettings = { settingsVisible = true },
            onTiming = { timingVisible = true },
            onToggleClickPassThrough = toggleClickPassThrough,
            clickPassThroughEnabled = { settings.clickPassThrough },
            overlayVisible = { overlayVisible },
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
            // Keep the floating lyrics out of the taskbar and window switcher.
            runCatching { window.type = java.awt.Window.Type.UTILITY }
            window.iconImage = requireNotNull(
                LinuxSniTray::class.java.classLoader.getResourceAsStream("icons/lyrics-float.png"),
            ) { "missing taskbar icon" }.use(ImageIO::read)
            onDispose { window.iconImage = null }
        }
        TransparentWindowBackground(window)
        val density = LocalDensity.current

        // AWT floor so the pill can never shrink below a usable size, even if
        // the WM ignores our minimum-size hints. The resize zones' manual
        // fallback clamps against this too.
        MinimumWindowSize(window, AppState.MIN_WIDTH_DP.dp, AppState.MIN_HEIGHT_DP.dp)

        // Click pass-through removes the overlay's entire input region so
        // XWayland sends pointer events to the windows below. Keyed on
        // overlayShown because a hidden window has no XID to shape yet.
        DisposableEffect(settings.clickPassThrough, overlayShown) {
            LinuxClickThrough.apply(window, settings.clickPassThrough)
            onDispose {
                LinuxClickThrough.apply(window, false)
            }
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
                ?: anchorPosition(settings.overlayAnchor, bounds, window.width, window.height, margin)
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

        CompositionLocalProvider(LocalAppPalette provides palette) {
            OverlayView(
                playback = playback,
                lyrics = lyrics,
                loading = lyricsLoading,
                settings = settings,
                offsetMs = effectiveOffsetMs,
                songOffsetMs = songOffsetMs,
                onSearch = { searchVisible = true },
                onSettings = { settingsVisible = true },
                onTiming = { timingVisible = true },
                onToggleClickPassThrough = toggleClickPassThrough,
                dragModifier = Modifier.windowDragHandle(window) {
                    customPosition = Point(window.x, window.y)
                    AppState.saveOverlayPosition(window.x, window.y)
                },
                resizeZones = { WindowResizeZones(window) },
            )
        }
    }

    // ----- search window -----

    FloatingDialog(
        title = "Lyrics Float Search",
        visible = searchVisible,
        onClose = { searchVisible = false },
        size = DpSize(420.dp, 600.dp),
        minSize = DpSize(340.dp, 320.dp),
        palette = palette,
    ) { dragHandle ->
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
            onApplyManualText = { text -> repository.applyManualText(text, monitor.active.value?.track) },
            currentLyricsText = { repository.currentRawLyrics() },
            onSearchWeb = ::openLyricsWebSearch,
            onClearOverride = repository::clearOverride,
            onClose = { searchVisible = false },
            search = { query, provider -> repository.search(query, provider) },
            dragHandleModifier = dragHandle,
        )
    }

    // ----- settings window -----

    FloatingDialog(
        title = "Lyrics Float Settings",
        visible = settingsVisible,
        onClose = { settingsVisible = false },
        size = DpSize(400.dp, 640.dp),
        minSize = DpSize(340.dp, 320.dp),
        palette = palette,
    ) { dragHandle ->
        SettingsView(
            settings = settings,
            players = players,
            enabledProviders = enabledProviders,
            onProviderEnabledChange = { name, enabled ->
                val next = enabledProviders.toMutableSet()
                if (enabled) next.add(name) else next.remove(name)
                enabledProviders = next
                LyricsProviders.setEnabled(next)
            },
            onAnchorChange = { anchor ->
                settings.overlayAnchor = anchor
                customPosition = null
            },
            onResetOverlayPosition = {
                customPosition = null
            },
            onOpenTiming = { timingVisible = true },
            onClose = { settingsVisible = false },
            dragHandleModifier = dragHandle,
        )
    }

    // ----- lyrics timing window -----

    FloatingDialog(
        title = "Lyrics Float Timing",
        visible = timingVisible,
        onClose = { timingVisible = false },
        size = DpSize(400.dp, 600.dp),
        minSize = DpSize(340.dp, 420.dp),
        palette = palette,
    ) { dragHandle ->
        TimingView(
            targetTitle = playback?.track?.title.orEmpty(),
            targetArtist = playback?.track?.artist.orEmpty(),
            hasTarget = currentSongKey != null,
            songOffsetMs = songOffsetMs,
            globalOffsetMs = settings.offsetMs,
            onSongOffsetChange = { value -> currentSongKey?.let { songOffsets.set(it, value) } },
            currentLines = {
                // Same line the overlay highlights: interpolated position plus
                // the effective offset.
                val entries = repository.current.value.entries
                val position = monitor.active.value?.positionMs() ?: 0L
                val offset = settings.offsetMs + songOffsets.get(currentSongKey)
                val index = entries.currentIndexAt(position, offset)
                entries.getOrNull(index)?.text to entries.getOrNull(index + 1)?.text
            },
            onOpenSettings = { settingsVisible = true },
            onClose = { timingVisible = false },
            dragHandleModifier = dragHandle,
        )
    }
}

/**
 * Undecorated, transparent, always-on-top utility dialog (search, settings)
 * that opens centered on screen, with a drag-to-move header and edge/corner
 * resize zones. [content] receives the header's drag modifier.
 */
@Composable
private fun FloatingDialog(
    title: String,
    visible: Boolean,
    onClose: () -> Unit,
    size: DpSize,
    minSize: DpSize,
    palette: AppPalette,
    content: @Composable (dragHandle: Modifier) -> Unit,
) {
    SwingDialog(
        onCloseRequest = onClose,
        state = rememberDialogState(size = size),
        visible = visible,
        title = title,
        icon = null,
        decoration = WindowDecoration.Undecorated(0.dp),
        transparent = true,
        resizable = true,
        enabled = true,
        focusable = true,
        alwaysOnTop = true,
        onPreviewKeyEvent = { false },
        onKeyEvent = { event ->
            // Escape closes the dialog like a native one.
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                onClose()
                true
            } else {
                false
            }
        },
        modalityType = java.awt.Dialog.ModalityType.MODELESS,
        init = { dialog ->
            // Utility windows are skipped by the taskbar, pager and alt-tab.
            runCatching { dialog.type = java.awt.Window.Type.UTILITY }
        },
    ) {
        TransparentWindowBackground(window)
        val density = LocalDensity.current
        MinimumWindowSize(window, minSize.width, minSize.height)
        LaunchedEffect(Unit) {
            val bounds = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .defaultScreenDevice.defaultConfiguration.bounds
            val width = with(density) { size.width.roundToPx() }
            val height = with(density) { size.height.roundToPx() }
            // AWT centers the dialog when it becomes visible, so keep overriding
            // for a few frames until it settles centered on the screen.
            repeat(5) {
                window.setLocation(bounds.x + (bounds.width - width) / 2, bounds.y + (bounds.height - height) / 2)
                withFrameNanos { }
            }
        }
        CompositionLocalProvider(LocalAppPalette provides palette) {
            Box(Modifier.fillMaxSize()) {
                content(Modifier.windowDragHandle(window))
                WindowResizeZones(window)
            }
        }
    }
}

@Composable
private fun MinimumWindowSize(window: java.awt.Window, width: Dp, height: Dp) {
    val density = LocalDensity.current
    DisposableEffect(window, density) {
        window.minimumSize = Dimension(
            with(density) { width.roundToPx() },
            with(density) { height.roundToPx() },
        )
        onDispose { window.minimumSize = Dimension(0, 0) }
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
 * Metrolist's "search online": open the desktop browser at
 * "<query> lyrics" so the found text can be pasted into the manual form.
 */
private fun openLyricsWebSearch(query: String) {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return
    val url = "https://www.google.com/search?q=" +
        java.net.URLEncoder.encode("$trimmed lyrics", Charsets.UTF_8)
    val opened = runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }.isSuccess
    if (!opened) runCatching { ProcessBuilder("xdg-open", url).start() }
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
