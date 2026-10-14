package dev.lyricsfloat.platform

import dev.lyricsfloat.mpris.NowPlayingMonitor
import dev.lyricsfloat.mpris.NowPlayingSource
import java.awt.Window

/** True on Windows; everything else takes the Linux (X11/XWayland + D-Bus) path. */
val isWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

/**
 * The OS-specific pieces of the app, picked once at startup. Linux keeps its
 * MPRIS/SNI/Xlib implementations; Windows uses SMTC, the AWT tray and Win32.
 * Windows-only classes are only loaded when [current] resolves to Windows, so
 * they never touch the Linux startup path.
 */
interface DesktopPlatform {
    val windowMover: WindowMover
    val clickThrough: ClickThrough
    val autostart: AutostartEntry

    /** Message shown in Settings when the song source sees no players. */
    val noPlayersMessage: String

    fun createNowPlayingSource(): NowPlayingSource

    fun createTray(actions: TrayActions): AppTray

    companion object {
        val current: DesktopPlatform by lazy { if (isWindows) WindowsPlatform else LinuxPlatform }
    }
}

/** Native window move/resize, with a manual fallback in `ui/WindowChrome.kt`. */
interface WindowMover {
    /**
     * When true, the manual fallback follows the absolute screen pointer
     * (MouseInfo) instead of accumulating Compose drag deltas.
     */
    val fallbackTracksScreenPointer: Boolean get() = false

    fun warmUp() {}

    /** Returns false when the native move is unavailable; callers fall back to setLocation. */
    fun requestInteractiveMove(window: Window): Boolean

    /** [direction] uses the EWMH hints 0-7; returns false to request the setBounds fallback. */
    fun requestInteractiveResize(window: Window, direction: Int): Boolean
}

/** Lets pointer input fall through the lyrics pill to the windows below. */
interface ClickThrough {
    fun warmUp() {}

    fun apply(window: Window, passThrough: Boolean)
}

/** "Start on login" registration for the installed app. */
interface AutostartEntry {
    /** Shown when [setEnabled] fails, usually because the app is not installed. */
    val failureMessage: String

    fun isEnabled(): Boolean

    fun setEnabled(enabled: Boolean): Boolean

    /** Re-points an existing entry at the running executable (installs move). */
    fun refresh()
}

/** Tray menu actions and the state the menu labels read on open. */
class TrayActions(
    val onToggleOverlay: () -> Unit,
    val onSearch: () -> Unit,
    val onSettings: () -> Unit,
    val onTiming: () -> Unit,
    val onToggleClickPassThrough: () -> Unit,
    val clickPassThroughEnabled: () -> Boolean,
    val overlayVisible: () -> Boolean,
    val onQuit: () -> Unit,
    /**
     * Opens the app-drawn tray menu at a screen point. Trays whose native
     * menu cannot match the app (Windows) call this on right-click; Linux
     * keeps Plasma's DBusMenu and never calls it.
     */
    val onOpenMenu: (java.awt.Point) -> Unit = {},
)

interface AppTray {
    fun start()

    fun stop()
}

private object LinuxPlatform : DesktopPlatform {
    override val windowMover: WindowMover get() = LinuxWindowMover
    override val clickThrough: ClickThrough get() = LinuxClickThrough
    override val autostart: AutostartEntry get() = LinuxAutostart
    override val noPlayersMessage = "No media players detected on the session bus."

    override fun createNowPlayingSource(): NowPlayingSource = NowPlayingMonitor()

    override fun createTray(actions: TrayActions): AppTray = LinuxSniTray(
        onToggleOverlay = actions.onToggleOverlay,
        onSearch = actions.onSearch,
        onSettings = actions.onSettings,
        onTiming = actions.onTiming,
        onToggleClickPassThrough = actions.onToggleClickPassThrough,
        clickPassThroughEnabled = actions.clickPassThroughEnabled,
        overlayVisible = actions.overlayVisible,
        onQuit = actions.onQuit,
    )
}
