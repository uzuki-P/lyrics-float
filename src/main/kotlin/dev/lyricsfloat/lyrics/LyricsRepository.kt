package dev.lyricsfloat.lyrics

import dev.lyricsfloat.mpris.NowPlayingMonitor
import dev.lyricsfloat.mpris.TrackInfo
import dev.lyricsfloat.platform.AppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Lyrics for one track, ready to render. */
data class LyricsState(
    val trackKey: String,
    val title: String,
    val artist: String,
    val entries: List<LyricsEntry>,
    val synced: Boolean,
    /** LRCLIB track id when lyrics were resolved; null when nothing was found. */
    val trackId: Long?,
    val fromOverride: Boolean,
) {
    companion object {
        val EMPTY = LyricsState("", "", "", emptyList(), false, null, false)
    }
}

/**
 * Keeps [current] following the MPRIS playback: fetches lyrics per track from
 * LRCLIB, caches them in memory, and applies per-track manual overrides.
 */
class LyricsRepository(private val scope: CoroutineScope) {
    @Serializable
    private data class OverrideEntry(val lrclibId: Long)

    private val json = Json { ignoreUnknownKeys = true }

    private val currentInternal = MutableStateFlow(LyricsState.EMPTY)
    val current: StateFlow<LyricsState> = currentInternal.asStateFlow()

    private val loadingInternal = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = loadingInternal.asStateFlow()

    /** Track key the manual search page applies picks to; empty when nothing is playing. */
    @Volatile
    var overrideTargetKey: String = ""
        private set

    private val overrides = HashMap<String, OverrideEntry>()
    private val trackInfoByKey = HashMap<String, TrackInfo>()
    private var loadJob: Job? = null

    init {
        overrides.putAll(
            AppState.readRaw(OVERRIDES_KEY)?.let { text ->
                runCatching { json.decodeFromString<Map<String, OverrideEntry>>(text) }.getOrNull()
            }.orEmpty(),
        )
    }

    /**
     * Keeps [current] following the MPRIS playback: fetches lyrics per track from
     * LRCLIB, caches them in memory, and applies per-track manual overrides.
     */
    fun followPlayback(monitor: NowPlayingMonitor) {
        scope.launch {
            // The monitor emits a fresh snapshot every poll; only act when the
            // playing track (or its absence) changes.
            var lastKey: String? = null
            monitor.active.collectLatest { playback ->
                val track = playback?.track?.takeIf(TrackInfo::hasContent)
                val key = track?.let(::keyFor)
                if (key == lastKey) return@collectLatest
                lastKey = key
                if (track == null) {
                    refresh(null)
                } else {
                    // Remember before refreshing so the load can resolve this track.
                    val nonNullKey = key ?: return@collectLatest
                    trackInfoByKey[nonNullKey] = track
                    refresh(nonNullKey)
                }
            }
        }
    }

    private fun keyFor(track: TrackInfo): String =
        "${LrcLib.cleanArtist(track.artist)}|${LrcLib.cleanTitle(track.title)}"

    private fun refresh(key: String?) {
        val track = key?.let { trackInfoByKey[it] }
        if (key == null || track == null) {
            loadJob?.cancel()
            overrideTargetKey = ""
            currentInternal.value = LyricsState.EMPTY
            return
        }
        overrideTargetKey = key
        println("Lyrics Float: now playing \"${track.title}\" — ${track.artist}")
        loadJob?.cancel()
        currentInternal.value = LyricsState.EMPTY.copy(trackKey = key, title = track.title, artist = track.artist)
        loadJob = scope.launch {
            loadingInternal.value = true
            val state = load(track, key)
            loadingInternal.value = false
            if (key == overrideTargetKey) currentInternal.value = state
        }
    }

    private suspend fun load(track: TrackInfo, key: String): LyricsState {
        // A manual pick wins over anything automatic.
        overrides[key]?.let { entry ->
            LrcLib.getById(entry.lrclibId)?.let { return toState(track, key, it, fromOverride = true) }
        }
        val best = LrcLib.bestFor(track.title, track.artist, track.lengthMs)
        return if (best != null) {
            toState(track, key, best, fromOverride = false)
        } else {
            LyricsState(key, track.title, track.artist, emptyList(), synced = false, trackId = null, fromOverride = false)
        }
    }

    private fun toState(track: TrackInfo, key: String, result: Track, fromOverride: Boolean): LyricsState {
        val text = result.syncedLyrics ?: result.plainLyrics
        val parsed = LyricsParser.parse(text.orEmpty(), track.lengthMs ?: result.durationMillis())
        return LyricsState(
            trackKey = key,
            title = track.title,
            artist = track.artist,
            entries = parsed.entries,
            synced = parsed.synced && result.syncedLyrics != null,
            trackId = result.id,
            fromOverride = fromOverride,
        )
    }

    // ----- manual search -----

    suspend fun search(query: String): List<Track> = LrcLib.search(query)

    /** Applies a manually picked result to the current track and reloads its lyrics. */
    fun applyPick(track: Track) {
        val key = overrideTargetKey
        if (key.isEmpty()) return
        overrides[key] = OverrideEntry(track.id)
        saveOverrides()
        refresh(key)
    }

    /** Removes the manual pick for the current track; the automatic lookup takes over. */
    fun clearOverride() {
        val key = overrideTargetKey
        if (key.isEmpty()) return
        overrides.remove(key)
        saveOverrides()
        refresh(key)
    }

    fun hasOverride(key: String = overrideTargetKey): Boolean = key.isNotEmpty() && overrides.containsKey(key)

    private fun saveOverrides() {
        runCatching { AppState.writeRaw(OVERRIDES_KEY, json.encodeToString(overrides)) }
    }

    private companion object {
        const val OVERRIDES_KEY = "lyrics.overrides"
    }
}
