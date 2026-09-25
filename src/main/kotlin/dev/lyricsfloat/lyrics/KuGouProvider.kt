package dev.lyricsfloat.lyrics

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.abs

@Serializable
private data class KuGouSong(
    val hash: String? = null,
    val duration: Long = 0,
    val songname: String? = null,
    val singername: String? = null,
)

@Serializable
private data class KuGouSongData(val info: List<KuGouSong> = emptyList())

@Serializable
private data class KuGouSongResponse(val data: KuGouSongData? = null)

@Serializable
private data class KuGouCandidate(val id: String = "", val accesskey: String = "")

@Serializable
private data class KuGouLyricsData(val candidates: List<KuGouCandidate> = emptyList())

@Serializable
private data class KuGouDownload(val content: String = "", val fmt: String = "")

/** The download endpoint's payload moved around over the years: accept both the legacy `data` wrapper and top-level fields. */
@Serializable
private data class KuGouDownloadResponse(val data: KuGouDownload? = null, val content: String? = null)

/**
 * KuGou lyrics, ported from Metrolist's KuGou.kt (originally ViMusic). Finds
 * candidate songs, then line-synced LRC downloads.
 */
@OptIn(ExperimentalEncodingApi::class)
object KuGouProvider : LyricsProvider {
    override val name = "KuGou"

    private const val USER_AGENT = "Lyrics Float v${BuildVersion.VERSION} (https://github.com/Uzuki-P/lyrics-float)"
    private const val DURATION_TOLERANCE_SECONDS = 8

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val client by lazy {
        HttpClient(CIO) {
            expectSuccess = false
        }
    }

    private suspend fun searchSongs(keyword: String): KuGouSongResponse = runCatching {
        json.decodeFromString<KuGouSongResponse>(
            client.get("https://mobileservice.kugou.com/api/v3/search/song") {
                parameter("version", 9108)
                parameter("plat", 0)
                parameter("pagesize", 8)
                parameter("showtype", 0)
                parameter("keyword", keyword)
                header("User-Agent", USER_AGENT)
            }.bodyAsText(),
        )
    }.getOrDefault(KuGouSongResponse())

    private suspend fun searchLyrics(query: Map<String, String>): KuGouLyricsData = runCatching {
        json.decodeFromString<KuGouLyricsData>(
            client.get("https://lyrics.kugou.com/search") {
                parameter("ver", 1)
                parameter("man", "yes")
                parameter("client", "pc")
                query.forEach { (k, v) -> parameter(k, v) }
                header("User-Agent", USER_AGENT)
            }.bodyAsText(),
        )
    }.getOrDefault(KuGouLyricsData())

    private suspend fun downloadLyrics(id: String, accessKey: String): KuGouDownloadResponse? = runCatching {
        json.decodeFromString<KuGouDownloadResponse>(
            client.get("https://lyrics.kugou.com/download") {
                parameter("fmt", "lrc")
                parameter("charset", "utf8")
                parameter("client", "pc")
                parameter("ver", 1)
                parameter("id", id)
                parameter("accesskey", accessKey)
                header("User-Agent", USER_AGENT)
            }.bodyAsText(),
        )
    }.getOrNull()

    private fun buildQuery(title: String, artist: String, album: String?): String = buildString {
        append(normalizeTitle(title))
        append(" - ")
        append(normalizeArtist(artist))
        if (!album.isNullOrBlank()) append(" ").append(album)
    }.encodeURLParameter(spaceToPlus = false)

    // KuGou's search chokes on bracketed metadata and multi-artist strings.
    private fun normalizeTitle(title: String) = title
        .replace("\\(.*\\)".toRegex(), "").replace("（.*）".toRegex(), "")
        .replace("「.*」".toRegex(), "").replace("『.*』".toRegex(), "")
        .replace("<.*>".toRegex(), "").replace("《.*》".toRegex(), "")
        .replace("〈.*〉".toRegex(), "").replace("＜.*＞".toRegex(), "")

    private fun normalizeArtist(artist: String) = artist
        .replace(", ", "、").replace(" & ", "、").replace(".", "").replace("和", "、")
        .replace("\\(.*\\)".toRegex(), "").replace("（.*）".toRegex(), "")

    /** Drops singer/writer/composer credit lines KuGou prepends and appends. */
    private fun String.normalizeKugou(): String {
        val lines = lines().filter { it.matches(ACCEPTED_REGEX) }
        var headCut = 0
        for (i in minOf(30, lines.lastIndex) downTo 0) {
            if (lines[i].matches(BANNED_REGEX)) {
                headCut = i + 1
                break
            }
        }
        val filtered = lines.drop(headCut)
        var tailCut = 0
        for (i in minOf(lines.size - 30, lines.lastIndex) downTo 0) {
            if (lines[lines.lastIndex - i].matches(BANNED_REGEX)) {
                tailCut = i + 1
                break
            }
        }
        return filtered.dropLast(tailCut).joinToString("\n")
    }

    private val ACCEPTED_REGEX = """\[(\d\d):(\d\d)\.(\d{2,3})\].*""".toRegex()
    private val BANNED_REGEX = """.+].+[:：].+""".toRegex()

    override suspend fun getLyrics(
        title: String,
        artist: String,
        durationMs: Long?,
        album: String?,
    ): Result<String> = runCatching {
        val query = buildQuery(title, artist, album)
        val seconds = durationMs?.let { (it / 1000).toInt() }

        fun durationOk(songDurationSeconds: Long) =
            seconds == null || abs(songDurationSeconds - seconds) <= DURATION_TOLERANCE_SECONDS

        // The lyrics index takes the duration in milliseconds, -1 = don't care.
        val keywordQuery = mapOf(
            "duration" to ((seconds ?: -1) * 1000L).toString(),
            "keyword" to query,
        )

        // Preferred path: match a song by duration, take its first lyric candidate.
        val candidate = searchSongs(query).data?.info.orEmpty()
            .firstOrNull { durationOk(it.duration) }
            ?.hash
            ?.let { hash -> searchLyrics(mapOf("hash" to hash)).candidates.firstOrNull() }
            // Fallback: keyword search over the lyrics index itself.
            ?: searchLyrics(keywordQuery).candidates.firstOrNull()
            ?: error("No lyrics candidate")

        val content = downloadLyrics(candidate.id, candidate.accesskey)
            ?.let { it.data?.content ?: it.content }
            ?: error("Download failed")
        Base64.Default.decode(content).decodeToString().normalizeKugou()
    }

    /** Result rows for the manual search dialog. */
    suspend fun searchForManual(query: String): List<ManualSearchResult> =
        searchSongs(query).data?.info.orEmpty().take(12).map { song ->
            ManualSearchResult(
                provider = name,
                title = song.songname ?: query,
                artist = song.singername.orEmpty(),
                durationMs = if (song.duration > 0) song.duration * 1000 else null,
                synced = true,
                timing = LyricsTiming.LINE,
            ) {
                val candidate = song.hash?.let { searchLyrics(mapOf("hash" to it)).candidates.firstOrNull() }
                if (candidate == null) {
                    null
                } else {
                    downloadLyrics(candidate.id, candidate.accesskey)
                        ?.let { it.data?.content ?: it.content }
                        ?.let { Base64.Default.decode(it).decodeToString().normalizeKugou() }
                }
            }
        }
}
