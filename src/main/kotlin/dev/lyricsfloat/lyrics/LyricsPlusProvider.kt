package dev.lyricsfloat.lyrics

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class LyricsPlusAgent(val type: String? = null, val name: String? = null, val alias: String? = null)

@Serializable
private data class LyricsPlusMetadata(
    val agents: Map<String, LyricsPlusAgent>? = null,
    val title: String? = null,
    val language: String? = null,
)

@Serializable
private data class LyricsPlusWord(
    val time: Long = 0,
    val duration: Long = 0,
    val text: String = "",
    val isBackground: Boolean = false,
)

@Serializable
private data class LyricsPlusLineElement(val key: String? = null, val singer: String? = null)

@Serializable
private data class LyricsPlusLine(
    val time: Long = 0,
    val duration: Long = 0,
    val text: String = "",
    val syllabus: List<LyricsPlusWord>? = null,
    val element: LyricsPlusLineElement? = null,
)

@Serializable
private data class LyricsPlusResponse(
    val type: String? = null,
    val metadata: LyricsPlusMetadata? = null,
    val lyrics: List<LyricsPlusLine>? = null,
)

@Serializable
private data class BinimumResponse(
    val total: Int? = null,
    val results: List<BinimumResult> = emptyList(),
)

@Serializable
private data class BinimumResult(
    val id: String? = null,
    val track_name: String? = null,
    val artist_name: String? = null,
    val duration: Int? = null,
    val timing_type: String? = null,
    val lyricsUrl: String? = null,
)

/**
 * LyricsPlus (Apple Music via community servers) plus the Binimum TTML API,
 * ported from Metrolist. Servers are tried in order with failover; the last
 * working one is preferred for the session.
 */
object LyricsPlusProvider : LyricsProvider {
    override val name = "LyricsPlus"

    private const val USER_AGENT = "Lyrics Float v${BuildVersion.VERSION} (https://github.com/Uzuki-P/lyrics-float)"
    private const val BINIMUM_API_BASE_URL = "https://lyrics-api.binimum.org/"

    private val baseUrls = listOf(
        "https://lyricsplus.binimum.org",
        "https://lyricsplus.atomix.one",
        "https://lyricsplus.prjktla.my.id",
        "https://lyricsplus-seven.vercel.app",
    )

    @Volatile
    private var lastWorkingServer: String? = null

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    private val client by lazy {
        HttpClient(CIO) {
            expectSuccess = false
        }
    }

    private fun prioritizedServers(): List<String> {
        val last = lastWorkingServer
        return if (last != null && last in baseUrls) {
            listOf(last) + baseUrls.filter { it != last }
        } else {
            baseUrls
        }
    }

    private suspend fun fetchFromServer(
        baseUrl: String,
        title: String,
        artist: String,
        durationMs: Long?,
        album: String?,
    ): LyricsPlusResponse? = runCatching {
        val response = client.get("$baseUrl/v2/lyrics/get") {
            header("User-Agent", USER_AGENT)
            parameter("title", title)
            parameter("artist", artist)
            // LyricsPlus expects duration in seconds.
            durationMs?.takeIf { it > 0 }?.let { parameter("duration", it / 1000) }
            if (!album.isNullOrBlank()) parameter("album", album)
        }
        if (response.status.isSuccess()) {
            json.decodeFromString<LyricsPlusResponse>(response.bodyAsText())
        } else {
            null
        }
    }.getOrNull()

    private suspend fun fetchLyricsPlus(
        title: String,
        artist: String,
        durationMs: Long?,
        album: String?,
    ): LyricsPlusResponse? {
        if (title.isBlank() || artist.isBlank()) return null
        for (baseUrl in prioritizedServers()) {
            val result = fetchFromServer(baseUrl, title, artist, durationMs, album)
            if (!result?.lyrics.isNullOrEmpty()) {
                lastWorkingServer = baseUrl
                return result
            }
        }
        return null
    }

    /** Binimum's TTML-backed API; returns extended LRC and whether it is word-synced. */
    private suspend fun fetchBinimum(
        title: String,
        artist: String,
        durationMs: Long?,
        album: String?,
    ): Pair<String, Boolean>? {
        if (title.isBlank() || artist.isBlank()) return null
        val response = runCatching {
            client.get(BINIMUM_API_BASE_URL) {
                header("User-Agent", USER_AGENT)
                parameter("track", title)
                parameter("artist", artist)
                if (!album.isNullOrBlank()) parameter("album", album)
                durationMs?.takeIf { it > 0 }?.let { parameter("duration", it / 1000) }
            }
        }.getOrNull() ?: return null
        if (!response.status.isSuccess()) return null

        val payload = runCatching { json.decodeFromString<BinimumResponse>(response.bodyAsText()) }
            .getOrNull() ?: return null
        val lyricsUrl = payload.results.firstOrNull { !it.lyricsUrl.isNullOrBlank() }?.lyricsUrl
            ?: return null

        val ttml = runCatching {
            val ttmlResponse = client.get(lyricsUrl) { header("User-Agent", USER_AGENT) }
            if (ttmlResponse.status.isSuccess()) ttmlResponse.bodyAsText() else null
        }.getOrNull() ?: return null

        val parsed = runCatching { TtmlParser.parse(ttml) }.getOrNull()?.takeIf { it.isNotEmpty() }
            ?: return null
        val lrc = runCatching { TtmlParser.toLrc(parsed).trim() }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: return null
        return lrc to (payload.results.first().timing_type.equals("word", ignoreCase = true))
    }

    /** Converts a LyricsPlus JSON response to the canonical extended LRC. */
    private fun convertToLrc(response: LyricsPlusResponse?): String? {
        val lyrics = response?.lyrics?.takeIf { it.isNotEmpty() } ?: return null
        val isWordSync = response.type.equals("Word", ignoreCase = true)

        // JSON aliases (v1, v2, v1000) are used directly; others map to the
        // next free v1/v2 slot.
        val agentMap = linkedMapOf<String, String>()
        lyrics.forEach { line ->
            val raw = line.element?.singer?.lowercase() ?: return@forEach
            if (raw !in agentMap) {
                agentMap[raw] = when (raw) {
                    "v1", "v2", "v1000" -> raw
                    else -> {
                        val taken = agentMap.values.toSet()
                        listOf("v1", "v2").firstOrNull { it !in taken } ?: "v1"
                    }
                }
            }
        }
        val isMultiAgent = agentMap.size > 1 || (agentMap.size == 1 && !agentMap.containsKey("v1"))

        val sb = StringBuilder(lyrics.size * 128)
        var lastWasBg = false

        fun appendLine(timeMs: Long, tag: String, text: String) {
            val m = timeMs / 60000
            val s = (timeMs % 60000) / 1000
            val c = (timeMs % 1000) / 10
            sb.append('[')
            if (m < 10) sb.append('0')
            sb.append(m).append(':')
            if (s < 10) sb.append('0')
            sb.append(s).append('.').append(c).append(']').append(tag).append(text).append('\n')
        }

        fun appendWords(words: List<LyricsPlusWord>) {
            val valid = words.filter { it.text.isNotBlank() }
            if (valid.isEmpty()) return
            sb.append('<')
            valid.forEachIndexed { i, w ->
                sb.append(w.text.trim()).append(':').append(w.time / 1000.0)
                    .append(':').append((w.time + w.duration) / 1000.0)
                if (i < valid.lastIndex) sb.append('|')
            }
            sb.append(">\n")
        }

        for (line in lyrics) {
            val mainWords = line.syllabus?.filter { !it.isBackground } ?: emptyList()
            val bgWords = line.syllabus?.filter { it.isBackground } ?: emptyList()
            val isFullBgLine = line.syllabus != null && mainWords.isEmpty() && bgWords.isNotEmpty()

            val mainText = when {
                isWordSync && mainWords.isNotEmpty() -> mainWords.joinToString("") { it.text }.trim()
                isFullBgLine -> ""
                else -> line.text.trim()
            }

            if (mainText.isNotBlank()) {
                lastWasBg = false
                val agentId = agentMap[line.element?.singer?.lowercase()]
                val agentTag = if (isMultiAgent && agentId != null) "{agent:$agentId}" else ""
                appendLine(line.time, agentTag, mainText)
                if (isWordSync && mainWords.isNotEmpty()) appendWords(mainWords)
            }

            if (bgWords.isNotEmpty()) {
                val bgText = if (isWordSync) bgWords.joinToString("") { it.text }.trim() else line.text.trim()
                if (bgText.isNotBlank()) {
                    appendLine(bgWords.minOf { it.time }, if (lastWasBg) "" else "{bg}", bgText)
                    lastWasBg = true
                    if (isWordSync) appendWords(bgWords)
                }
            }
        }

        return sb.toString().trimEnd().ifBlank { null }
    }

    override suspend fun getLyrics(
        title: String,
        artist: String,
        durationMs: Long?,
        album: String?,
    ): Result<String> = runCatching {
        val binimum = fetchBinimum(title, artist, durationMs, album)
        if (binimum?.second == true) return@runCatching binimum.first

        val response = fetchLyricsPlus(title, artist, durationMs, album)
        val lyricsPlusLrc = convertToLrc(response)
        when {
            // Line-synced binimum loses to word-synced LyricsPlus.
            binimum?.second == false -> {
                if (response?.type.equals("Word", true) && !lyricsPlusLrc.isNullOrBlank()) lyricsPlusLrc
                else binimum.first
            }
            else -> lyricsPlusLrc
        } ?: error("Lyrics unavailable")
    }
}
