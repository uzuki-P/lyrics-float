package dev.lyricsfloat.platform

import dev.lyricsfloat.ui.ThemeMode
import dev.lyricsfloat.ui.HoverMenuPosition
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

    fun loadHoverMenuPosition(): HoverMenuPosition =
        readField("overlay.hoverMenuPosition")?.let { runCatching { HoverMenuPosition.valueOf(it) }.getOrNull() }
            ?: HoverMenuPosition.TOP

    fun saveHoverMenuPosition(position: HoverMenuPosition) = writeField("overlay.hoverMenuPosition", position.name)

    // Overlay size, in dp. Resizable since the Metrolist feature port; the
    // historical fixed 560x170 dp pill is the default.
    const val DEFAULT_WIDTH_DP = 560f
    const val DEFAULT_HEIGHT_DP = 170f
    const val MIN_WIDTH_DP = 380f
    const val MIN_HEIGHT_DP = 120f
    const val MAX_WIDTH_DP = 1600f
    const val MAX_HEIGHT_DP = 1200f

    fun loadOverlayWidthDp(): Float = readField("overlay.width")?.toFloatOrNull()
        ?.takeIf { it in MIN_WIDTH_DP..MAX_WIDTH_DP } ?: DEFAULT_WIDTH_DP

    fun loadOverlayHeightDp(): Float = readField("overlay.height")?.toFloatOrNull()
        ?.takeIf { it in MIN_HEIGHT_DP..MAX_HEIGHT_DP } ?: DEFAULT_HEIGHT_DP

    fun saveOverlaySize(widthDp: Float, heightDp: Float) {
        writeField("overlay.width", widthDp.coerceIn(MIN_WIDTH_DP, MAX_WIDTH_DP).toString())
        writeField("overlay.height", heightDp.coerceIn(MIN_HEIGHT_DP, MAX_HEIGHT_DP).toString())
    }

    fun loadFontSizeSp(): Int = readField("overlay.fontSize")?.toIntOrNull()?.takeIf { it in 12..48 } ?: 20

    fun saveFontSizeSp(size: Int) = writeField("overlay.fontSize", size.coerceIn(12, 48).toString())

    fun loadRomajiFontSizeSp(): Int = readField("overlay.romajiFontSize")?.toIntOrNull()
        ?.takeIf { it in 8..30 } ?: 10

    fun saveRomajiFontSizeSp(size: Int) = writeField("overlay.romajiFontSize", size.coerceIn(8, 30).toString())

    /** Pill background opacity, 0 (fully transparent) .. 1. */
    fun loadOpacity(): Float = readField("overlay.opacity")?.toFloatOrNull()?.takeIf { it in 0f..1f } ?: 0.75f

    fun saveOpacity(opacity: Float) = writeField("overlay.opacity", opacity.coerceIn(0f, 1f).toString())

    fun loadShowNextLine(): Boolean = readField("overlay.showNext") != "false"

    fun saveShowNextLine(show: Boolean) = writeField("overlay.showNext", show.toString())

    /** Lyrics sync offset in milliseconds; positive shows lines earlier. */
    fun loadOffsetMs(): Long = readField("lyrics.offsetMs")?.toLongOrNull()?.takeIf { it in -10_000..10_000 } ?: 0L

    fun saveOffsetMs(offset: Long) = writeField("lyrics.offsetMs", offset.coerceIn(-10_000, 10_000).toString())

    // ----- lyrics rendering (Metrolist behavior toggles) -----

    fun loadShowIntervalIndicator(): Boolean = readField("lyrics.intervalIndicator") != "false"

    fun saveShowIntervalIndicator(value: Boolean) = writeField("lyrics.intervalIndicator", value.toString())

    fun loadRespectAgentPositioning(): Boolean = readField("lyrics.agentPositioning") != "false"

    fun saveRespectAgentPositioning(value: Boolean) = writeField("lyrics.agentPositioning", value.toString())

    fun loadWordKaraoke(): Boolean = readField("lyrics.wordKaraoke") != "false"

    fun saveWordKaraoke(value: Boolean) = writeField("lyrics.wordKaraoke", value.toString())

    fun loadRomanizeJapanese(): Boolean = readField("lyrics.romanizeJapanese") != "false"

    fun saveRomanizeJapanese(value: Boolean) = writeField("lyrics.romanizeJapanese", value.toString())

    /** Default line alignment: LEFT, CENTER or RIGHT (Metrolist LyricsTextPositionKey). */
    fun loadTextPosition(): String = readField("lyrics.textPosition")?.takeIf { it in setOf("LEFT", "CENTER", "RIGHT") }
        ?: "CENTER"

    fun saveTextPosition(value: String) = writeField("lyrics.textPosition", value)

    /** Metrolist LyricsScrollKey: follow the playback position automatically. */
    fun loadAutoScroll(): Boolean = readField("lyrics.autoScroll") != "false"

    fun saveAutoScroll(value: Boolean) = writeField("lyrics.autoScroll", value.toString())

    /** Outline stroke around lyric text instead of a drop shadow. */
    fun loadTextOutline(): Boolean = readField("lyrics.textOutline") != "false"

    fun saveTextOutline(value: Boolean) = writeField("lyrics.textOutline", value.toString())

    // ----- behavior -----

    fun loadAutoHide(): Boolean = readField("behavior.autoHide") != "false"

    fun saveAutoHide(autoHide: Boolean) = writeField("behavior.autoHide", autoHide.toString())

    /** Clicks fall through the pill except for the hover menu strip. */
    fun loadClickPassThrough(): Boolean = readField("behavior.clickPassThrough") == "true"

    fun saveClickPassThrough(value: Boolean) = writeField("behavior.clickPassThrough", value.toString())

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
