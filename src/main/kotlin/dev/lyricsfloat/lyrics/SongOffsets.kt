package dev.lyricsfloat.lyrics

import dev.lyricsfloat.mpris.TrackInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/** Song identity shared by manual picks and per-song offsets: cleaned "artist|title". */
fun songKey(track: TrackInfo): String =
    "${LrcLib.cleanArtist(track.artist)}|${LrcLib.cleanTitle(track.title)}"

/**
 * Per-song lyrics sync offsets, added on top of the global offset. A provider
 * whose timing is off is usually off for one recording only, so the fix
 * belongs to that song. Zero entries are dropped instead of stored. Storage
 * is injected (one JSON blob) so tests do not touch the user's config.
 */
class SongOffsets(
    private val read: () -> String?,
    private val write: (String) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val state = MutableStateFlow(load())

    val all: StateFlow<Map<String, Long>> = state.asStateFlow()

    fun get(key: String?): Long = key?.let { state.value[it] } ?: 0L

    fun set(key: String, offsetMs: Long) {
        val clamped = clamp(offsetMs)
        val next = if (clamped == 0L) state.value - key else state.value + (key to clamped)
        if (next == state.value) return
        state.value = next
        runCatching { write(json.encodeToString(next)) }
    }

    private fun load(): Map<String, Long> =
        read()?.let { text -> runCatching { json.decodeFromString<Map<String, Long>>(text) }.getOrNull() }
            ?.mapValues { clamp(it.value) }
            ?.filterValues { it != 0L }
            .orEmpty()

    companion object {
        const val STORAGE_KEY = "lyrics.songOffsets"

        /** Both the global and the per-song offsets stay within ±60 s. */
        const val MAX_MS = 60_000L

        fun clamp(offsetMs: Long): Long = offsetMs.coerceIn(-MAX_MS, MAX_MS)
    }
}
