package dev.lyricsfloat.lyrics

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import kotlin.math.abs

@Serializable
private data class AppleSongAttributes(
    val name: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    val durationInMillis: Long? = null,
)

@Serializable
private data class AppleSongData(val id: String = "", val attributes: AppleSongAttributes? = null)

@Serializable
private data class AppleSongs(val data: List<AppleSongData> = emptyList())

@Serializable
private data class AppleResults(val songs: AppleSongs? = null)

@Serializable
private data class AppleSearchResponse(val results: AppleResults? = null)

@Serializable
private data class PaxsenixWord(val text: String = "", val timestamp: Long = 0, val endtime: Long = 0)

@Serializable
private data class PaxsenixLine(
    val timestamp: Long = 0,
    val text: List<PaxsenixWord> = emptyList(),
    val background: Boolean = false,
    val oppositeTurn: Boolean = false,
)

@Serializable
private data class PaxsenixLyricsResponse(
    val type: String? = null,
    val ttmlContent: String? = null,
    val elrcMultiPerson: String? = null,
    val elrc: String? = null,
    val plain: String? = null,
    val content: List<PaxsenixLine> = emptyList(),
)

/**
 * Paxsenix lyrics (Apple Music), ported from Metrolist. Scrapes the Apple
 * Music web player's JWT from beta.music.apple.com, searches its catalog, and
 * pulls word-synced lyrics from lyrics.paxsenix.org.
 */
object PaxsenixProvider : LyricsProvider {
    override val name = "Paxsenix"

    private const val USER_AGENT = "Lyrics Float v${BuildVersion.VERSION} (https://github.com/Uzuki-P/lyrics-float)"
    private const val APPLE_MUSIC_API_BASE = "https://amp-api.music.apple.com/v1/catalog/us"

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    private val client by lazy {
        HttpClient(CIO) {
            expectSuccess = false
        }
    }

    // ----- Apple Music web token -----

    private val tokenMutex = Mutex()
    private var cachedToken: String? = null

    private suspend fun getToken(): String = tokenMutex.withLock {
        cachedToken?.let { return it }
        val mainPage = client.get("https://beta.music.apple.com") {
            header("User-Agent", USER_AGENT)
        }.bodyAsText()
        val indexJsUri = Regex("""/assets/index~[^/]+\.js""").find(mainPage)?.value
            ?: error("Could not find Apple Music index JS URL")
        val indexJs = client.get("https://beta.music.apple.com$indexJsUri") {
            header("User-Agent", USER_AGENT)
        }.bodyAsText()
        Regex("""eyJ[A-Za-z0-9\-_=]+\.[A-Za-z0-9\-_=]+\.[A-Za-z0-9\-_=]+""").find(indexJs)?.value
            ?.also { cachedToken = it }
            ?: error("Could not find Apple Music token")
    }

    private suspend fun refreshToken(): String {
        tokenMutex.withLock { cachedToken = null }
        return getToken()
    }

    // ----- search -----

    private suspend fun search(
        query: String,
        durationMs: Long?,
        title: String,
        artist: String,
    ): List<Pair<AppleSongData, Double>> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val perform: suspend (String) -> List<AppleSongData> = { token ->
            val response = client.get("$APPLE_MUSIC_API_BASE/search") {
                parameter("term", encoded)
                parameter("types", "songs")
                parameter("limit", 25)
                parameter("l", "en-US")
                header("Authorization", "Bearer $token")
                header("Origin", "https://music.apple.com")
                header("Referer", "https://music.apple.com/")
                header("User-Agent", USER_AGENT)
                header("Accept", "application/json")
            }
            if (response.status.value != 200) emptyList()
            else {
                runCatching { json.decodeFromString<AppleSearchResponse>(response.bodyAsText()) }
                    .getOrNull()?.results?.songs?.data ?: emptyList()
            }
        }
        return runCatching { perform(getToken()) }
            .getOrElse { runCatching { perform(refreshToken()) }.getOrDefault(emptyList()) }
            .let { scoreAndFilter(it, title, artist, durationMs) }
    }

    private fun scoreAndFilter(
        results: List<AppleSongData>,
        title: String,
        artist: String,
        durationMs: Long?,
    ): List<Pair<AppleSongData, Double>> {
        val cleanup = Regex("""\s*\(.*?\)|\s*\[.*?\]""")
        val cleanedTitle = LrcLib.cleanTitle(title).replace(cleanup, "").lowercase().trim()
        val cleanedArtist = LrcLib.cleanArtist(artist).lowercase()
        val targetIsMixed = title.contains("mixed", ignoreCase = true)
        val targetIsRemix = title.contains("remix", ignoreCase = true)

        return results.mapNotNull { result ->
            val attr = result.attributes ?: return@mapNotNull null
            var score = 0.0
            val resultTitle = attr.name.orEmpty()

            attr.durationInMillis?.let { d ->
                if (durationMs != null) {
                    val diff = abs(d - durationMs)
                    when {
                        diff <= 2000 -> score += 100
                        diff <= 5000 -> score += 50
                        diff <= 10000 -> score += 10
                        else -> score -= 50
                    }
                }
            }

            val resultTitleCleaned = resultTitle.replace(cleanup, "").lowercase().trim()
            when {
                resultTitleCleaned == cleanedTitle -> score += 80
                resultTitleCleaned.contains(cleanedTitle) || cleanedTitle.contains(resultTitleCleaned) -> score += 40
            }

            if (resultTitle.contains("mixed", true) && !targetIsMixed) score -= 60
            if (resultTitle.contains("remix", true) && !targetIsRemix) score -= 40

            val resultArtist = (attr.artistName ?: "").lowercase()
            when {
                resultArtist.contains(cleanedArtist) -> score += 50
                else -> {
                    val words = cleanedArtist.split(Regex("\\s+")).filter { it.length > 2 }
                    if (words.any { resultArtist.contains(it) }) score += 25
                }
            }

            if (score > 0) result to score else null
        }.sortedByDescending { it.second }.take(10)
    }

    // ----- lyrics -----

    /** 0 none, 1 plain, 2 line-synced, 3 word-synced. */
    private fun quality(lrc: String): Int {
        if (lrc.isBlank()) return 0
        val hasWordTimings = (lrc.contains("<") && lrc.contains(">") && (lrc.contains("|") || lrc.contains(":"))) ||
            lrc.contains(Regex("<\\d{1,2}:\\d{2}\\.\\d{2,3}>"))
        if (hasWordTimings) return 3
        val hasLineTimings = lrc.contains(Regex("""\[\d\d:\d\d\.\d{2,3}\]""")) ||
            lrc.contains(Regex("""^\[bg:.*\]""", RegexOption.MULTILINE))
        return if (hasLineTimings) 2 else 1
    }

    private suspend fun fetchLyricsForTrack(id: String): String? = runCatching {
        val response = client.get("https://lyrics.paxsenix.org/apple-music/lyrics") {
            parameter("id", id)
            header("User-Agent", USER_AGENT)
        }
        if (response.status.value != 200) error("Paxsenix HTTP ${response.status.value}")
        val body = runCatching { json.decodeFromString<PaxsenixLyricsResponse>(response.bodyAsText()) }
            .getOrElse { error("Failed to parse Paxsenix response") }

        body.ttmlContent?.takeIf { it.isNotBlank() }?.let { ttml ->
            val parsed = TtmlParser.parse(ttml)
            if (parsed.isNotEmpty()) return@runCatching TtmlParser.toLrc(parsed)
        }
        body.elrcMultiPerson?.takeIf { it.isNotBlank() }?.let { return@runCatching it }
        body.elrc?.takeIf { it.isNotBlank() }?.let { return@runCatching it }
        body.plain?.takeIf { it.isNotBlank() }?.let { return@runCatching it }
        if (body.content.isEmpty()) error("No lyrics found")

        if (body.type != "Syllable") {
            return@runCatching body.content
                .map { line -> line.text.joinToString(" ") { it.text } }
                .filter { it.isNotBlank() }
                .joinToString("\n")
        }

        buildString {
            body.content.forEach { line ->
                val lineText = line.text.joinToString(" ") { it.text }
                if (lineText.isNotBlank()) {
                    val ms = line.timestamp
                    appendLine(
                        "[%02d:%02d.%02d]%s%s".format(
                            ms / 1000 / 60,
                            (ms / 1000) % 60,
                            (ms % 1000) / 10,
                            when {
                                line.background -> "{bg}"
                                line.oppositeTurn -> "{agent:v2}"
                                else -> "{agent:v1}"
                            },
                            lineText,
                        ),
                    )
                    if (line.text.isNotEmpty()) {
                        appendLine(
                            line.text.joinToString("|") { word ->
                                "${word.text}:${word.timestamp / 1000.0}:${word.endtime / 1000.0}"
                            }.let { "<$it>" },
                        )
                    }
                }
            }
        }
    }.getOrNull()

    override suspend fun getLyrics(
        title: String,
        artist: String,
        durationMs: Long?,
        album: String?,
    ): Result<String> = runCatching {
        val cleanedTitle = LrcLib.cleanTitle(title)
        val cleanedArtist = LrcLib.cleanArtist(artist)

        var scored: List<Pair<AppleSongData, Double>> = emptyList()
        for (query in listOf("$cleanedTitle $cleanedArtist", cleanedTitle)) {
            if (scored.isEmpty()) {
                scored = search(query, durationMs, title, artist)
            }
        }
        if (scored.isEmpty()) error("No tracks found on Paxsenix")

        var bestLyrics: String? = null
        var bestQuality = 0
        for ((result, _) in scored.take(10)) {
            val lrc = fetchLyricsForTrack(result.id) ?: continue
            val q = quality(lrc)
            if (q > bestQuality) {
                bestQuality = q
                bestLyrics = lrc
            }
            if (bestQuality == 3) break
        }
        bestLyrics ?: error("No lyrics available from Paxsenix")
    }
}
