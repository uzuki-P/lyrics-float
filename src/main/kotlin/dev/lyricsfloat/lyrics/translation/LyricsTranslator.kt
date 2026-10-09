package dev.lyricsfloat.lyrics.translation

import dev.lyricsfloat.lyrics.JapaneseRomaji
import dev.lyricsfloat.lyrics.LyricsEntry
import dev.lyricsfloat.lyrics.LyricsState
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

sealed interface TranslationStatus {
    data object Idle : TranslationStatus
    data object Translating : TranslationStatus
    data object Done : TranslationStatus
    data class Error(val message: String) : TranslationStatus
}

/**
 * Port of Metrolist's LyricsTranslationHelper for the overlay: one request
 * per song with every non-blank line, results written to each entry's
 * translatedTextFlow. Unlike Metrolist it runs automatically for each new
 * song while translation is on, and keeps results on disk keyed by the
 * lyrics text and every setting that changes the output, so replaying a
 * song costs nothing.
 *
 * The same provider also writes romaji for Japanese songs into
 * aiRomanizedTextFlow, in its own request and cache: romaji does not depend
 * on the target language, so switching languages keeps it. The offline
 * kuromoji romaji stays in place underneath as the fallback.
 */
class LyricsTranslator(
    private val scope: CoroutineScope,
    directory: File = defaultDirectory("translations"),
    romajiDirectory: File = defaultDirectory("romaji"),
) {
    private val statusInternal = MutableStateFlow<TranslationStatus>(TranslationStatus.Idle)
    val status: StateFlow<TranslationStatus> = statusInternal.asStateFlow()

    private val romajiStatusInternal = MutableStateFlow<TranslationStatus>(TranslationStatus.Idle)
    val romajiStatus: StateFlow<TranslationStatus> = romajiStatusInternal.asStateFlow()

    private var job: Job? = null
    private var romajiJob: Job? = null
    private val translations = LineCache(directory)
    private val romaji = LineCache(romajiDirectory)

    /**
     * Shows translations for [lyrics] under [config], fetching them when not
     * cached or when [force] is set. A null [config] (translation off) clears
     * them. Each call cancels the previous run.
     */
    fun update(lyrics: LyricsState, config: TranslationConfig?, force: Boolean = false) {
        job?.cancel()
        val targets = lyrics.entries.filter { it.text.isNotBlank() }
        if (config == null || targets.isEmpty()) {
            lyrics.entries.forEach { it.translatedTextFlow.value = null }
            statusInternal.value = TranslationStatus.Idle
            return
        }
        val lines = targets.map { it.text }
        job = scope.launch {
            run(
                cache = translations,
                key = cacheKey(config.cacheIdentity(), lines),
                force = force,
                status = statusInternal,
                label = "translation",
                clear = { lyrics.entries.forEach { it.translatedTextFlow.value = null } },
                fetch = {
                    when (config.provider.api) {
                        TranslationApi.T3_CODE -> T3CodeClient.translate(lines, config)
                        else -> HttpTranslators.translate(lines, config)
                    }
                },
                apply = { translated ->
                    targets.forEachIndexed { index, entry ->
                        // Lines already in the target language come back unchanged; a
                        // duplicate of the lyric adds nothing.
                        entry.translatedTextFlow.value = translated.getOrNull(index)?.trim()
                            ?.takeIf { it.isNotEmpty() && !it.equals(entry.text.trim(), ignoreCase = true) }
                    }
                },
            )
        }
    }

    /**
     * Fills AI romaji for the Japanese lines of [lyrics] with [config]'s
     * provider. A null [config] (AI romaji off, or a provider that cannot
     * answer, such as DeepL) clears it, so the offline romaji shows again.
     * A failed request leaves the offline romaji in place.
     */
    fun updateRomaji(lyrics: LyricsState, config: TranslationConfig?, force: Boolean = false) {
        romajiJob?.cancel()
        val targets = romajiTargets(lyrics)
        if (config == null || targets.isEmpty()) {
            lyrics.entries.forEach { it.aiRomanizedTextFlow.value = null }
            romajiStatusInternal.value = TranslationStatus.Idle
            return
        }
        val lines = targets.map { it.text }
        romajiJob = scope.launch {
            run(
                cache = romaji,
                key = cacheKey("romaji-v${RomajiPrompt.VERSION}|${config.romajiIdentity()}", lines),
                force = force,
                status = romajiStatusInternal,
                label = "romaji",
                clear = { lyrics.entries.forEach { it.aiRomanizedTextFlow.value = null } },
                fetch = {
                    val prompt = RomajiPrompt.forLines(lines)
                    when (config.provider.api) {
                        TranslationApi.T3_CODE -> T3CodeClient.complete(prompt, config)
                        else -> HttpTranslators.complete(prompt, config)
                    }
                },
                apply = { romanized ->
                    targets.forEachIndexed { index, entry ->
                        entry.aiRomanizedTextFlow.value = romanized.getOrNull(index)?.trim()?.takeIf(String::isNotEmpty)
                    }
                },
            )
        }
    }

    private suspend fun run(
        cache: LineCache,
        key: String,
        force: Boolean,
        status: MutableStateFlow<TranslationStatus>,
        label: String,
        clear: () -> Unit,
        fetch: suspend () -> List<String>,
        apply: (List<String>) -> Unit,
    ) {
        val cached = if (force) null else cache.get(key)
        if (cached != null) {
            apply(cached)
            status.value = TranslationStatus.Done
            return
        }
        clear()
        status.value = TranslationStatus.Translating
        try {
            val lines = withContext(Dispatchers.IO) { fetch() }
            cache.put(key, lines)
            apply(lines)
            status.value = TranslationStatus.Done
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("Lyrics Float: $label failed: ${e.message}")
            status.value = TranslationStatus.Error(e.message ?: "${label.replaceFirstChar(Char::uppercase)} failed")
        }
    }

    private fun cacheKey(identity: String, lines: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((identity + "\n" + lines.joinToString("\n")).toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    internal companion object {
        /**
         * Lines that get AI romaji. The offline romanizer skips kanji-only
         * lines because they may be Chinese; here the song decides instead.
         * Any kana anywhere makes it Japanese, and then a kanji-only line
         * such as 未来 is Japanese too.
         */
        fun romajiTargets(lyrics: LyricsState): List<LyricsEntry> {
            val entries = lyrics.entries.filter { it.text.isNotBlank() && JapaneseRomaji.isJapanese(it.text) }
            val hasKana = entries.any { entry -> entry.text.any { it in '\u3040'..'\u309F' || it in '\u30A0'..'\u30FF' } }
            return if (hasKana) entries else emptyList()
        }

        fun defaultDirectory(name: String): File {
            val dataHome = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
                ?: (System.getProperty("user.home") + "/.local/share")
            return File(dataHome, "lyricsfloat/$name")
        }
    }
}

/** Results per request key: a small in-memory LRU over one JSON file per key. */
private class LineCache(private val directory: File) {
    private val memory = object : LinkedHashMap<String, List<String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>) = size > 32
    }
    private val serializer = ListSerializer(String.serializer())

    suspend fun get(key: String): List<String>? {
        synchronized(memory) { memory[key] }?.let { return it }
        val lines = withContext(Dispatchers.IO) {
            runCatching {
                File(directory, "$key.json").takeIf(File::isFile)?.readText()
                    ?.let { translationJson.decodeFromString(serializer, it) }
            }.getOrNull()
        } ?: return null
        synchronized(memory) { memory[key] = lines }
        return lines
    }

    suspend fun put(key: String, lines: List<String>) {
        synchronized(memory) { memory[key] = lines }
        withContext(Dispatchers.IO) {
            runCatching {
                directory.mkdirs()
                File(directory, "$key.json").writeText(translationJson.encodeToString(serializer, lines))
            }
        }
    }
}
