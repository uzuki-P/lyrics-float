package dev.lyricsfloat.lyrics

import dev.lyricsfloat.mpris.NowPlayingMonitor
import dev.lyricsfloat.mpris.TrackInfo
import dev.lyricsfloat.platform.AppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Per-provider network budget, matching Metrolist's 8 s. */
private const val PROVIDER_TIMEOUT_MS = 8_000L

/** Lyrics for one track, ready to render. */
data class LyricsState(
    val trackKey: String,
    val title: String,
    val artist: String,
    val entries: List<LyricsEntry>,
    val synced: Boolean,
    /** Provider that delivered the lyrics; null when nothing was found. */
    val provider: String?,
    val fromOverride: Boolean,
) {
    val hasWordTimings: Boolean get() = entries.any { !it.words.isNullOrEmpty() }

    companion object {
        val EMPTY = LyricsState("", "", "", emptyList(), false, null, false)
    }
}

/**
 * Keeps [current] following the MPRIS playback: fetches lyrics per track by
 * walking the enabled provider chain (Metrolist's sequential fallback),
 * caches results in memory and on disk, and applies per-track manual overrides.
 */
class LyricsRepository(private val scope: CoroutineScope) {
    /**
     * A manual pick. Either an Lrclib track id (persisted stable), or a
     * provider replay: re-run [provider] against the stored title/artist.
     */
    @Serializable
    private data class OverrideEntry(
        val lrclibId: Long? = null,
        val provider: String? = null,
        val title: String? = null,
        val artist: String? = null,
        val durationMs: Long? = null,
        /** Hand-entered lyrics (Metrolist's "Manual" provider); stored verbatim. */
        val text: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val diskCache = LyricsDiskCache()

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

    // Small LRU so hopping back to a recent song does not refetch.
    private val cache = object : LinkedHashMap<String, LyricsState>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LyricsState>) = size > 24
    }

    private var loadJob: Job? = null
    private var romanizeJob: Job? = null

    /** Mirrors the settings toggle; romaji is computed off the UI thread. */
    @Volatile
    var romanizeJapanese: Boolean = AppState.loadRomanizeJapanese()
        private set

    fun setRomanizeJapanese(enabled: Boolean) {
        romanizeJapanese = enabled
        AppState.saveRomanizeJapanese(enabled)
        currentInternal.value.takeIf { it.entries.isNotEmpty() }?.let { startRomanization(it) }
    }

    /** Fills romanizedTextFlow per line in the background; lines update live. */
    private fun startRomanization(state: LyricsState) {
        romanizeJob?.cancel()
        if (!romanizeJapanese) return
        romanizeJob = scope.launch {
            state.entries.forEach { entry ->
                if (entry.romanizedTextFlow.value == null &&
                    JapaneseRomaji.isJapanese(entry.text) &&
                    !JapaneseRomaji.isChinese(entry.text)
                ) {
                    entry.romanizedTextFlow.value = JapaneseRomaji.romanize(entry.text)
                }
            }
        }
    }

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

        // Fresh enough in cache? Show it without refetching.
        cache[key]?.let { cached ->
            if (cached.entries.isNotEmpty() || cached.fromOverride) {
                currentInternal.value = cached
                return
            }
        }

        loadJob?.cancel()
        currentInternal.value = LyricsState.EMPTY.copy(trackKey = key, title = track.title, artist = track.artist)
        loadJob = scope.launch {
            loadingInternal.value = true
            val state = load(track, key)
            loadingInternal.value = false
            if (key == overrideTargetKey) {
                cache[key] = state
                currentInternal.value = state
                startRomanization(state)
            }
        }
    }

    private suspend fun load(track: TrackInfo, key: String): LyricsState {
        withContext(Dispatchers.IO) { diskCache.read(track) }?.let { saved ->
            if (saved.manual == overrides.containsKey(key)) {
                return toState(track, key, saved.text, saved.provider, saved.manual)
            }
        }
        // A manual pick wins over anything automatic.
        overrides[key]?.let { entry ->
            fetchOverrideText(entry).text?.let { text ->
                withContext(Dispatchers.IO) { diskCache.write(track, text, entry.provider ?: "Lrclib", true) }
                return toState(track, key, text, entry.provider ?: "Lrclib", fromOverride = true)
            }
        }
        return autoChain(track, key)
    }

    /** Fetch result for a stored pick: the text, or why it failed. */
    private data class OverrideFetch(val text: String?, val error: String?)

    /** Re-fetches the manually picked lyrics; [OverrideFetch.error] says why on failure. */
    private suspend fun fetchOverrideText(entry: OverrideEntry): OverrideFetch = when {
        entry.lrclibId != null -> {
            val text = LrcLib.getById(entry.lrclibId)?.let { it.syncedLyrics ?: it.plainLyrics }
            if (text != null) OverrideFetch(text, null)
            else OverrideFetch(null, "Lrclib: track not found")
        }
        entry.text != null -> OverrideFetch(entry.text, null)
        entry.provider != null -> {
            val provider = LyricsProviders.byName(entry.provider)
            if (provider == null) {
                OverrideFetch(null, "${entry.provider}: provider unknown")
            } else {
                provider.getLyrics(entry.title.orEmpty(), entry.artist.orEmpty(), entry.durationMs)
                    .fold(
                        onSuccess = { OverrideFetch(it, null) },
                        onFailure = { OverrideFetch(null, "${provider.name}: ${it.message}") },
                    )
            }
        }
        else -> OverrideFetch(null, "broken override entry")
    }

    /** The normal provider walk, first enabled provider that returns lyrics wins. */
    private suspend fun autoChain(track: TrackInfo, key: String): LyricsState {
        for (provider in LyricsProviders.enabled()) {
            val text = withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                provider.getLyrics(track.title, track.artist, track.lengthMs).getOrNull()
            }
            if (!text.isNullOrBlank()) {
                val cleaned = LyricsParser.filterCreditLines(text)
                withContext(Dispatchers.IO) { diskCache.write(track, cleaned, provider.name, false) }
                return toState(track, key, cleaned, provider.name)
            }
        }
        return LyricsState(key, track.title, track.artist, emptyList(), synced = false, provider = null, fromOverride = false)
    }

    private fun toState(
        track: TrackInfo,
        key: String,
        text: String,
        provider: String,
        fromOverride: Boolean = false,
    ): LyricsState {
        val parsed = LyricsParser.parse(text, track.lengthMs)
        return LyricsState(
            trackKey = key,
            title = track.title,
            artist = track.artist,
            entries = parsed.entries,
            synced = parsed.synced,
            provider = provider,
            fromOverride = fromOverride,
        )
    }

    // ----- manual search -----

    /** Cross-provider search for the manual dialog; null provider = all. */
    suspend fun search(query: String, provider: String? = null): List<ManualSearchResult> {
        val track = trackInfoByKey[overrideTargetKey]
        return ManualSearch.search(
            query = query,
            providerFilter = provider,
            durationMs = track?.lengthMs,
            trackTitle = track?.title,
            trackArtist = track?.artist,
        )
    }

    /**
     * Applies a manually picked result and waits for it to load. Returns null
     * on success, or the failure reason (shown on the picked row); the pill
     * falls back to whatever was showing before on failure.
     */
    suspend fun applyManualPick(result: ManualSearchResult, playingTrack: TrackInfo?): String? {
        val track = playingTrack?.takeIf(TrackInfo::hasContent) ?: return "Nothing is playing"
        val key = keyFor(track)
        trackInfoByKey[key] = track
        overrideTargetKey = key
        val entry = when {
            result.provider == "Lrclib" && result.lrclibId != null ->
                OverrideEntry(lrclibId = result.lrclibId)
            else ->
                OverrideEntry(
                    provider = result.provider,
                    title = result.title,
                    artist = result.artist,
                    durationMs = result.durationMs,
                )
        }
        overrides[key] = entry
        saveOverrides()
        withContext(Dispatchers.IO) { diskCache.remove(track) }
        loadJob?.cancel()
        val previous = cache.remove(key)
        overrideTargetKey = key
        loadingInternal.value = true
        // Try the pick itself first: one focused request, fast feedback.
        val fetched = fetchOverrideText(entry)
        val state = if (fetched.text != null) {
            withContext(Dispatchers.IO) { diskCache.write(track, fetched.text, entry.provider ?: "Lrclib", true) }
            toState(track, key, fetched.text, entry.provider ?: "Lrclib", fromOverride = true)
        } else {
            // Pick failed: fall back to what was showing (or the auto chain).
            previous ?: autoChain(track, key)
        }
        loadingInternal.value = false
        cache[key] = state
        currentInternal.value = state
        startRomanization(state)
        return fetched.error
    }

    /**
     * Applies hand-entered lyrics (plain text or LRC) to the playing track,
     * Metrolist's Edit action: stored verbatim as a "Manual" provider override,
     * so it survives restarts and "Reset to auto" clears it like any pick.
     * Returns null on success, or the failure reason.
     */
    suspend fun applyManualText(text: String, playingTrack: TrackInfo?): String? {
        val track = playingTrack?.takeIf(TrackInfo::hasContent) ?: return "Nothing is playing"
        val cleaned = text.trim()
        if (cleaned.isEmpty()) return "Lyrics are empty"
        val key = keyFor(track)
        trackInfoByKey[key] = track
        overrides[key] = OverrideEntry(provider = "Manual", text = cleaned)
        saveOverrides()
        withContext(Dispatchers.IO) { diskCache.remove(track) }
        loadJob?.cancel()
        overrideTargetKey = key
        // No fetch to wait on, so the parsed text shows immediately.
        val state = toState(track, key, cleaned, "Manual", fromOverride = true)
        withContext(Dispatchers.IO) { diskCache.write(track, cleaned, "Manual", true) }
        loadingInternal.value = false
        cache[key] = state
        currentInternal.value = state
        startRomanization(state)
        return null
    }

    /** Raw lyrics text for the current track, for the manual-edit form's prefill. */
    suspend fun currentRawLyrics(): String? = trackInfoByKey[overrideTargetKey]?.let { track ->
        withContext(Dispatchers.IO) { diskCache.read(track) }?.text
    }

    /** Removes the manual pick for the current track; the automatic lookup takes over. */
    fun clearOverride() {
        val key = overrideTargetKey
        if (key.isEmpty()) return
        overrides.remove(key)
        saveOverrides()
        trackInfoByKey[key]?.let { track -> diskCache.remove(track) }
        cache.remove(key)
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
