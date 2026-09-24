package dev.lyricsfloat.lyrics

import dev.lyricsfloat.platform.AppState

/**
 * One lyrics source, ported from Metrolist's LyricsProvider (minus the
 * Android Context). Implementations must serialize whatever wire format they
 * speak into the canonical extended LRC text.
 */
interface LyricsProvider {
    val name: String

    /**
     * Returns extended LRC (or plain text) for the track, or a failure.
     * [durationMs] is null when the player does not report a length.
     */
    suspend fun getLyrics(
        title: String,
        artist: String,
        durationMs: Long?,
        album: String? = null,
    ): Result<String>
}

/**
 * Provider order and enablement, following Metrolist's registry. Every
 * provider is enabled by default except LyricsPlus (its default upstream).
 * Enablement is persisted as a comma list under "providers.enabled".
 */
object LyricsProviders {
    private val all = linkedMapOf(
        "BetterLyrics" to BetterLyricsProvider,
        "Lrclib" to LrclibProvider,
        "KuGou" to KuGouProvider,
        "Paxsenix" to PaxsenixProvider,
        "LyricsPlus" to LyricsPlusProvider,
    )

    private const val KEY = "providers.enabled"

    val names: List<String> = all.keys.toList()

    fun defaultEnabled(): Set<String> = names.toSet() - "LyricsPlus"

    fun enabledNames(): Set<String> {
        val raw = AppState.readRaw(KEY) ?: return defaultEnabled()
        return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    fun enabled(): List<LyricsProvider> {
        val enabled = enabledNames()
        return all.filterKeys { it in enabled }.values.toList()
    }

    fun byName(name: String): LyricsProvider? = all[name]

    fun setEnabled(enabled: Set<String>) =
        AppState.writeRaw(KEY, enabled.joinToString(","))
}
