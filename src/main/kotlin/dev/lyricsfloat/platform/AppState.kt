package dev.lyricsfloat.platform

import dev.lyricsfloat.ui.ThemeMode
import java.io.File

/**
 * Persists app state as a simple key=value file at $XDG_CONFIG_HOME/lyricsfloat/state.properties.
 * Values are enum names, ints, and one JSON blob for lyric overrides - no quoting needed.
 */
object AppState {
    val dir: File by lazy {
        val configHome = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
            ?: (System.getProperty("user.home") + "/.config")
        File(configHome, "lyricsfloat").apply { mkdirs() }
    }
    private val file: File get() = File(dir, "state.properties")

    private var cached: Map<String, String>? = null

    fun readRaw(key: String): String? = readField(key)

    fun writeRaw(key: String, value: String) = writeField(key, value)

    // ----- theme -----

    fun loadThemeMode(): ThemeMode =
        readField("theme")?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM

    fun saveThemeMode(mode: ThemeMode) = writeField("theme", mode.name)

    // ----- overlay -----

    fun loadOverlayX(): Int? = readField("overlay.x")?.toIntOrNull()
    fun loadOverlayY(): Int? = readField("overlay.y")?.toIntOrNull()
    fun saveOverlayPosition(x: Int, y: Int) {
        writeField("overlay.x", x.toString())
        writeField("overlay.y", y.toString())
    }

    fun loadOverlayAnchor(): WindowAnchor =
        readField("overlay.anchor")?.let { runCatching { WindowAnchor.valueOf(it) }.getOrNull() }
            ?: WindowAnchor.BOTTOM_CENTER

    fun saveOverlayAnchor(anchor: WindowAnchor) = writeField("overlay.anchor", anchor.name)

    fun loadFontSizeSp(): Int = readField("overlay.fontSize")?.toIntOrNull()?.takeIf { it in 12..48 } ?: 20

    fun saveFontSizeSp(size: Int) = writeField("overlay.fontSize", size.coerceIn(12, 48).toString())

    /** Pill background opacity, 0.3..0.95. */
    fun loadOpacity(): Float = readField("overlay.opacity")?.toFloatOrNull()?.takeIf { it in 0.3f..0.95f } ?: 0.75f

    fun saveOpacity(opacity: Float) = writeField("overlay.opacity", opacity.coerceIn(0.3f, 0.95f).toString())

    fun loadShowNextLine(): Boolean = readField("overlay.showNext") != "false"

    fun saveShowNextLine(show: Boolean) = writeField("overlay.showNext", show.toString())

    /** Lyrics sync offset in milliseconds; positive shows lines earlier. */
    fun loadOffsetMs(): Long = readField("lyrics.offsetMs")?.toLongOrNull()?.takeIf { it in -10_000..10_000 } ?: 0L

    fun saveOffsetMs(offset: Long) = writeField("lyrics.offsetMs", offset.coerceIn(-10_000, 10_000).toString())

    // ----- behavior -----

    fun loadAutoHide(): Boolean = readField("behavior.autoHide") != "false"

    fun saveAutoHide(autoHide: Boolean) = writeField("behavior.autoHide", autoHide.toString())

    fun loadPreferredPlayer(): String? = readField("players.preferred")?.takeIf { it.isNotBlank() }

    fun savePreferredPlayer(identity: String?) {
        if (identity.isNullOrBlank()) writeField("players.preferred", "") else writeField("players.preferred", identity)
    }

    private fun read(): Map<String, String> {
        cached?.let { return it }
        val map = if (file.exists()) {
            file.readLines()
                .mapNotNull { line ->
                    val idx = line.indexOf('=')
                    if (idx <= 0) null else line.take(idx) to line.substring(idx + 1)
                }
                .toMap()
        } else {
            emptyMap()
        }
        cached = map
        return map
    }

    private fun readField(key: String): String? = read()[key]

    private fun writeField(key: String, value: String) {
        val updated = read().toMutableMap()
        updated[key] = value
        commit(updated)
    }

    private fun commit(updated: Map<String, String>) {
        cached = updated
        runCatching {
            val tmp = File(dir, file.name + ".tmp")
            tmp.writeText(updated.entries.joinToString("\n") { "${it.key}=${it.value}" })
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }
}
