package dev.lyricsfloat.platform

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class AppPathsTest {
    private val home = "/home/me"

    @Test
    fun linuxFollowsXdgWithHomeFallback() {
        val env = mapOf("XDG_CONFIG_HOME" to "/cfg", "XDG_DATA_HOME" to "/data")
        assertEquals(File("/cfg", "lyricsfloat"), AppPaths.configDir(false, env::get, home))
        assertEquals(File("/data", "lyricsfloat"), AppPaths.dataDir(false, env::get, home))
        assertEquals(File("$home/.config", "lyricsfloat"), AppPaths.configDir(false, { null }, home))
        assertEquals(File("$home/.local/share", "lyricsfloat"), AppPaths.dataDir(false, { "" }, home))
    }

    @Test
    fun windowsUsesAppDataAndLocalAppData() {
        val env = mapOf("APPDATA" to "C:\\Users\\me\\AppData\\Roaming", "LOCALAPPDATA" to "C:\\Users\\me\\AppData\\Local")
        assertEquals(File("C:\\Users\\me\\AppData\\Roaming", "LyricsFloat"), AppPaths.configDir(true, env::get, home))
        assertEquals(File("C:\\Users\\me\\AppData\\Local", "LyricsFloat"), AppPaths.dataDir(true, env::get, home))
        // XDG variables mean nothing on Windows.
        val xdgOnly = mapOf("XDG_CONFIG_HOME" to "/cfg")
        assertEquals(File("$home\\AppData\\Roaming", "LyricsFloat"), AppPaths.configDir(true, xdgOnly::get, home))
    }
}
