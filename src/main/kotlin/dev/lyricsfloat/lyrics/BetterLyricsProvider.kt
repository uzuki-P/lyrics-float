package dev.lyricsfloat.lyrics

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * BetterLyrics (lyrics-api.boidu.dev), ported from Metrolist. Returns TTML,
 * which we convert to extended LRC.
 */
object BetterLyricsProvider : LyricsProvider {
    override val name = "BetterLyrics"

    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    private val client by lazy {
        HttpClient(CIO) {
            expectSuccess = false
        }
    }

    override suspend fun getLyrics(
        title: String,
        artist: String,
        durationMs: Long?,
        album: String?,
    ): Result<String> {
        // The API requires the duration parameter; requests without it are
        // rejected with HTTP 401. Players that don't report mpris:length can
        // never satisfy it — fail fast with a clear reason instead.
        if (durationMs == null) {
            return Result.failure(IllegalStateException("needs the track duration (player doesn't report one)"))
        }
        return runCatching {
            val response = client.get("https://lyrics-api.boidu.dev/getLyrics") {
                header("User-Agent", BROWSER_UA)
                header("Accept", "application/json")
                parameter("s", title)
                parameter("a", artist)
                parameter("d", (durationMs / 1000).toInt())
                if (!album.isNullOrBlank()) parameter("al", album)
            }
            if (response.status.value != 200) error("BetterLyrics HTTP ${response.status.value}")
            val ttml = json.parseToJsonElement(response.bodyAsText())
                .jsonObject["ttml"]?.jsonPrimitive?.content?.trim().takeUnless { it.isNullOrEmpty() }
                ?: error("Lyrics unavailable")
            val parsed = TtmlParser.parse(ttml)
            if (parsed.isEmpty()) error("Failed to parse TTML")
            TtmlParser.toLrc(parsed)
        }
    }
}
