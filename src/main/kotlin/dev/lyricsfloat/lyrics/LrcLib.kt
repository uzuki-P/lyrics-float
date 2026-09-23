package dev.lyricsfloat.lyrics

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json

object BuildVersion {
    const val VERSION = "0.1.0"
}

/**
 * LRCLIB client, ported from Metrolist's com.metrolist.lrclib (Ktor + search-first
 * strategy chain). Uses /api/search plus client-side matching because metadata
 * arriving from browsers via MPRIS is messy. LRCLIB requires identifying clients
 * with a User-Agent header, which Metrolist forgets to send.
 */
object LrcLib {
    private const val USER_AGENT = "Lyrics Float v${BuildVersion.VERSION} (https://github.com/Uzuki-P/lyrics-float)"

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    private val client by lazy {
        HttpClient(CIO) {
            defaultRequest {
                url("https://lrclib.net")
                header("User-Agent", USER_AGENT)
            }
        }
    }

    // Patterns to clean from titles: "(Official Video)", "[Lyrics]", "(feat. X)", ...
    private val titleCleanupPatterns = listOf(
        Regex("""\s*\(.*?(official|video|audio|lyrics|lyric|visualizer|hd|hq|4k|remaster|remix|live|acoustic|version|edit|extended|radio|clean|explicit).*?\)""", RegexOption.IGNORE_CASE),
        Regex("""\s*\[.*?(official|video|audio|lyrics|lyric|visualizer|hd|hq|4k|remaster|remix|live|acoustic|version|edit|extended|radio|clean|explicit).*?\]""", RegexOption.IGNORE_CASE),
        Regex("""\s*【.*?】"""),
        Regex("""\s*\|.*$"""),
        Regex("""\s*-\s*(official|video|audio|lyrics|lyric|visualizer).*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*\(feat\..*?\)""", RegexOption.IGNORE_CASE),
        Regex("""\s*\(ft\..*?\)""", RegexOption.IGNORE_CASE),
        Regex("""\s*feat\..*$""", RegexOption.IGNORE_CASE),
        Regex("""\s*ft\..*$""", RegexOption.IGNORE_CASE),
    )

    private val artistSeparators = listOf(" & ", " and ", ", ", " x ", " X ", " feat. ", " feat ", " ft. ", " ft ", " featuring ", " with ")

    fun cleanTitle(title: String): String {
        var cleaned = title.trim()
        for (pattern in titleCleanupPatterns) cleaned = cleaned.replace(pattern, "")
        return cleaned.trim()
    }

    fun cleanArtist(artist: String): String {
        var cleaned = artist.trim()
        for (separator in artistSeparators) {
            if (cleaned.contains(separator, ignoreCase = true)) {
                cleaned = cleaned.split(separator, ignoreCase = true, limit = 2)[0]
                break
            }
        }
        return cleaned.trim()
    }

    /** Free-text search, used by the manual search page. */
    suspend fun search(query: String): List<Track> = runCatching {
        json.decodeFromString<List<Track>>(client.get("/api/search") { parameter("q", query) }.bodyAsText())
    }.getOrDefault(emptyList())

    suspend fun searchByFields(trackName: String?, artistName: String?): List<Track> = runCatching {
        json.decodeFromString<List<Track>>(
            client.get("/api/search") {
                if (!trackName.isNullOrBlank()) parameter("track_name", trackName)
                if (!artistName.isNullOrBlank()) parameter("artist_name", artistName)
            }.bodyAsText(),
        )
    }.getOrDefault(emptyList())

    suspend fun getById(id: Long): Track? = runCatching {
        json.decodeFromString<Track>(client.get("/api/get/$id").bodyAsText())
    }.getOrNull()

    /**
     * Metrolist's five-strategy fallback chain, in order:
     * cleaned title+artist -> cleaned title -> "artist title" -> title -> uncleaned pair.
     */
    private suspend fun queryLyrics(artist: String, title: String): List<Track> {
        val cleanedTitle = cleanTitle(title)
        val cleanedArtist = cleanArtist(artist)

        suspend fun withLyrics(tracks: List<Track>) =
            tracks.filter { it.syncedLyrics != null || it.plainLyrics != null }

        searchByFields(cleanedTitle, cleanedArtist).let { withLyrics(it).takeIf(List<Track>::isNotEmpty) }?.let { return it }
        searchByFields(cleanedTitle, null).let { withLyrics(it).takeIf(List<Track>::isNotEmpty) }?.let { return it }
        search("$cleanedArtist $cleanedTitle").let { withLyrics(it).takeIf(List<Track>::isNotEmpty) }?.let { return it }
        search(cleanedTitle).let { withLyrics(it).takeIf(List<Track>::isNotEmpty) }?.let { return it }
        if (cleanedTitle != title.trim()) {
            return withLyrics(searchByFields(title.trim(), artist.trim()))
        }
        return emptyList()
    }

    /** Best track for a playing song, using the same matching rules as Metrolist. */
    suspend fun bestFor(title: String, artist: String, durationMs: Long?): Track? =
        queryLyrics(artist, title).bestMatchingFor(durationMs, cleanTitle(title), cleanArtist(artist))

    suspend fun lyricsText(title: String, artist: String, durationMs: Long?): String? =
        bestFor(title, artist, durationMs)?.let { it.syncedLyrics ?: it.plainLyrics }
}
