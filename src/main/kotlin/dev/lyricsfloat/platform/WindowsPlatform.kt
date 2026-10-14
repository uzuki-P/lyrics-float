package dev.lyricsfloat.platform

import com.sun.jna.Native
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.platform.win32.WinUser
import dev.lyricsfloat.mpris.NowPlayingSource
import dev.lyricsfloat.mpris.SmtcNowPlayingMonitor
import java.awt.Window
import java.io.File
import java.util.WeakHashMap

internal object WindowsPlatform : DesktopPlatform {
    override val windowMover: WindowMover get() = WindowsWindowMover
    override val clickThrough: ClickThrough get() = WindowsClickThrough
    override val autostart: AutostartEntry get() = WindowsAutostart
    override val noPlayersMessage =
        "No media sessions detected. The player must support Windows media controls, and Windows " +
            "PowerShell must be allowed to run (errors go to %LOCALAPPDATA%\\LyricsFloat\\smtc-helper.log)."

    override fun createNowPlayingSource(): NowPlayingSource = SmtcNowPlayingMonitor()

    override fun createTray(actions: TrayActions): AppTray = WindowsTray(actions)
}

/**
 * Windows moves and resizes undecorated windows smoothly with plain
 * setLocation/setBounds, so there is no native hand-off. Returning false
 * selects the manual path in `ui/WindowChrome.kt`, which follows the screen
 * pointer on this platform.
 */
internal object WindowsWindowMover : WindowMover {
    override val fallbackTracksScreenPointer = true

    override fun requestInteractiveMove(window: Window): Boolean = false

    override fun requestInteractiveResize(window: Window, direction: Int): Boolean = false
}

/**
 * Click pass-through through the extended window style: WS_EX_TRANSPARENT on
 * a layered window (WS_EX_LAYERED) makes Windows skip it, child windows
 * included, during hit-testing, so clicks reach whatever is below. Like the
 * Linux version, pass-through covers the whole pill, hover bar included.
 */
internal object WindowsClickThrough : ClickThrough {
    // Windows whose WS_EX_LAYERED bit we added ourselves, so turning
    // pass-through off restores the style AWT set.
    private val addedLayered = WeakHashMap<Window, Boolean>()

    override fun warmUp() {
        runCatching { User32.INSTANCE }
    }

    override fun apply(window: Window, passThrough: Boolean) {
        try {
            if (!window.isDisplayable) return
            val hwnd = WinDef.HWND(Native.getWindowPointer(window) ?: return)
            val user32 = User32.INSTANCE
            val style = user32.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE)
            val next = if (passThrough) {
                var value = style or WinUser.WS_EX_TRANSPARENT
                if (style and WinUser.WS_EX_LAYERED == 0) {
                    // Transparent AWT windows are normally layered already. If
                    // this one is not, a fresh layered window stays invisible
                    // until it gets attributes, so give it full opacity.
                    value = value or WinUser.WS_EX_LAYERED
                    user32.SetWindowLong(hwnd, WinUser.GWL_EXSTYLE, value)
                    user32.SetLayeredWindowAttributes(hwnd, 0, 255.toByte(), WinUser.LWA_ALPHA)
                    addedLayered[window] = true
                    return
                }
                value
            } else {
                var value = style and WinUser.WS_EX_TRANSPARENT.inv()
                if (addedLayered.remove(window) == true) value = value and WinUser.WS_EX_LAYERED.inv()
                value
            }
            if (next != style) user32.SetWindowLong(hwnd, WinUser.GWL_EXSTYLE, next)
        } catch (_: Throwable) {
        }
    }
}

/** Start on login through the per-user Run key (HKCU, no admin rights needed). */
internal object WindowsAutostart : AutostartEntry {
    private const val RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val VALUE_NAME = "Lyrics Float"

    override val failureMessage = "Could not set autostart. Run the installed app."

    override fun isEnabled(): Boolean = runCatching {
        Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME)
    }.getOrDefault(false)

    override fun setEnabled(enabled: Boolean): Boolean = runCatching {
        if (enabled) {
            val executable = executablePath() ?: return false
            writeEntry(executable)
        } else if (isEnabled()) {
            Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME)
        }
        true
    }.getOrDefault(false)

    override fun refresh() {
        if (isEnabled()) executablePath()?.let { runCatching { writeEntry(it) } }
    }

    // jpackage's launcher sets jpackage.app-path to the installed .exe; a
    // Gradle dev run has none, so autostart stays unavailable there.
    private fun executablePath(): String? =
        System.getProperty("jpackage.app-path")?.takeIf(String::isNotBlank)?.let { File(it).absolutePath }

    private fun writeEntry(executable: String) {
        Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, VALUE_NAME, "\"$executable\"")
    }
}
