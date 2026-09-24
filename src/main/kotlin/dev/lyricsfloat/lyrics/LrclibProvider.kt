package dev.lyricsfloat.lyrics

/**
 * LRCLIB as one provider in the registry. The heavy lifting (search strategy
 * chain, matching) lives in [LrcLib]; manual picks still come from here.
 */
object LrclibProvider : LyricsProvider {
    override val name = "Lrclib"

    override suspend fun getLyrics(
        title: String,
        artist: String,
        durationMs: Long?,
        album: String?,
    ): Result<String> = runCatching {
        val best = LrcLib.bestFor(title, artist, durationMs)
        best?.syncedLyrics ?: best?.plainLyrics ?: error("No lyrics on Lrclib")
    }
}
