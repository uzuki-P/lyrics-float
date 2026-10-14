package dev.lyricsfloat.platform

import java.io.File

/**
 * Per-user config and data roots. Linux follows XDG
 * ($XDG_CONFIG_HOME/lyricsfloat, $XDG_DATA_HOME/lyricsfloat); Windows uses
 * %APPDATA%\LyricsFloat for settings and %LOCALAPPDATA%\LyricsFloat for
 * caches and logs.
 */
object AppPaths {
    val configDir: File by lazy { configDir(isWindows, System::getenv, System.getProperty("user.home")) }
    val dataDir: File by lazy { dataDir(isWindows, System::getenv, System.getProperty("user.home")) }

    /** How the settings file location reads in UI text. */
    val stateFileDisplay: String
        get() = if (isWindows) "%APPDATA%\\LyricsFloat\\state.properties" else "~/.config/lyricsfloat/state.properties"

    internal fun configDir(windows: Boolean, env: (String) -> String?, home: String): File =
        if (windows) {
            File(env("APPDATA")?.takeIf(String::isNotBlank) ?: "$home\\AppData\\Roaming", WINDOWS_DIR)
        } else {
            File(env("XDG_CONFIG_HOME")?.takeIf(String::isNotBlank) ?: "$home/.config", LINUX_DIR)
        }

    internal fun dataDir(windows: Boolean, env: (String) -> String?, home: String): File =
        if (windows) {
            File(env("LOCALAPPDATA")?.takeIf(String::isNotBlank) ?: "$home\\AppData\\Local", WINDOWS_DIR)
        } else {
            File(env("XDG_DATA_HOME")?.takeIf(String::isNotBlank) ?: "$home/.local/share", LINUX_DIR)
        }

    private const val LINUX_DIR = "lyricsfloat"
    private const val WINDOWS_DIR = "LyricsFloat"
}
