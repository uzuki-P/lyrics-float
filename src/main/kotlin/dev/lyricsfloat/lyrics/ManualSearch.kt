package dev.lyricsfloat.lyrics

enum class LyricsTiming { WORD, LINE, PLAIN }

fun detectLyricsTiming(text: String, durationMs: Long?): LyricsTiming {
    val parsed = LyricsParser.parse(text, durationMs)
    return when {
        parsed.entries.any { !it.words.isNullOrEmpty() } -> LyricsTiming.WORD
        parsed.synced -> LyricsTiming.LINE
        else -> LyricsTiming.PLAIN
    }
}

/**
 * One row of the manual search dialog. [fetch] pulls the extended LRC text
 * when the row is picked; [synced] is null when it cannot be known upfront
 * (query-based providers).
 */
data class ManualSearchResult(
    val provider: String,
    val title: String,
    val artist: String,
    val durationMs: Long?,
    val synced: Boolean?,
    val lrclibId: Long? = null,
    val timing: LyricsTiming? = null,
    val previewText: String? = null,
    val fetch: suspend () -> String?,
)

/**
 * Manual search across providers, Metrolist's "search other providers" flow:
 * Lrclib and KuGou return real result lists, the query-based providers
 * (BetterLyrics, Paxsenix, LyricsPlus) return their single best match.
 */
object ManualSearch {
    const val ALL = "All"

    suspend fun search(
        query: String,
        providerFilter: String?,
        durationMs: Long?,
        trackTitle: String? = null,
        trackArtist: String? = null,
    ): List<ManualSearchResult> {
        val wanted: (String) -> Boolean = { providerFilter == null || providerFilter == ALL || providerFilter == it }
        val results = mutableListOf<ManualSearchResult>()

        if (wanted("Lrclib")) {
            LrcLib.search(query).take(12).forEach { track ->
                val lyrics = track.syncedLyrics ?: track.plainLyrics
                results.add(
                    ManualSearchResult(
                        provider = "Lrclib",
                        title = track.trackName,
                        artist = track.artistName,
                        durationMs = track.durationMillis(),
                        synced = track.syncedLyrics != null,
                        lrclibId = track.id,
                        timing = lyrics?.let { detectLyricsTiming(it, track.durationMillis()) },
                        previewText = lyrics,
                    ) {
                        LrcLib.getById(track.id)?.let { it.syncedLyrics ?: it.plainLyrics }
                    },
                )
            }
        }

        if (wanted("KuGou")) {
            results.addAll(KuGouProvider.searchForManual(query))
        }

        // Query-based providers have no list API and need real title/artist
        // metadata; the free-text field is a poor substitute (an empty artist
        // makes BetterLyrics/LyricsPlus return nothing). Like Metrolist, use
        // the playing track's metadata for these.
        if (wanted("BetterLyrics") && !trackTitle.isNullOrBlank()) {
            results.add(singleMatch(BetterLyricsProvider, trackTitle, trackArtist.orEmpty(), durationMs))
        }
        if (wanted("Paxsenix") && !trackTitle.isNullOrBlank()) {
            results.add(singleMatch(PaxsenixProvider, trackTitle, trackArtist.orEmpty(), durationMs))
        }
        if (wanted("LyricsPlus") && !trackTitle.isNullOrBlank()) {
            results.add(singleMatch(LyricsPlusProvider, trackTitle, trackArtist.orEmpty(), durationMs))
        }

        return results
    }

    /** One best-match row backed by the provider's normal getLyrics call. */
    private fun singleMatch(
        provider: LyricsProvider,
        title: String,
        artist: String,
        durationMs: Long?,
    ): ManualSearchResult = ManualSearchResult(
        provider = provider.name,
        title = title,
        artist = artist,
        durationMs = durationMs,
        synced = null,
    ) {
        provider.getLyrics(title = title, artist = artist, durationMs = durationMs).getOrNull()
    }
}
