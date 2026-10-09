package dev.lyricsfloat.lyrics.translation

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
 */
class LyricsTranslator(private val scope: CoroutineScope, private val directory: File = defaultDirectory()) {
    private val statusInternal = MutableStateFlow<TranslationStatus>(TranslationStatus.Idle)
    val status: StateFlow<TranslationStatus> = statusInternal.asStateFlow()

    private var job: Job? = null
    private val memory = object : LinkedHashMap<String, List<String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>) = size > 32
    }
    private val linesSerializer = ListSerializer(String.serializer())

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
        val key = cacheKey(config, lines)
        fun apply(translated: List<String>) {
            targets.forEachIndexed { index, entry ->
                // Lines already in the target language come back unchanged; a
                // duplicate of the lyric adds nothing.
                entry.translatedTextFlow.value = translated.getOrNull(index)?.trim()
                    ?.takeIf { it.isNotEmpty() && !it.equals(entry.text.trim(), ignoreCase = true) }
            }
        }

        job = scope.launch {
            val cached = if (force) null else synchronized(memory) { memory[key] } ?: withContext(Dispatchers.IO) { readDisk(key) }
            if (cached != null) {
                synchronized(memory) { memory[key] = cached }
                apply(cached)
                statusInternal.value = TranslationStatus.Done
                return@launch
            }
            lyrics.entries.forEach { it.translatedTextFlow.value = null }
            statusInternal.value = TranslationStatus.Translating
            try {
                val translated = withContext(Dispatchers.IO) {
                    when (config.provider.api) {
                        TranslationApi.T3_CODE -> T3CodeClient.translate(lines, config)
                        else -> HttpTranslators.translate(lines, config)
                    }
                }
                synchronized(memory) { memory[key] = translated }
                withContext(Dispatchers.IO) { writeDisk(key, translated) }
                apply(translated)
                statusInternal.value = TranslationStatus.Done
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("Lyrics Float: translation failed: ${e.message}")
                statusInternal.value = TranslationStatus.Error(e.message ?: "Translation failed")
            }
        }
    }

    private fun cacheKey(config: TranslationConfig, lines: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((config.cacheIdentity() + "\n" + lines.joinToString("\n")).toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun readDisk(key: String): List<String>? = runCatching {
        File(directory, "$key.json").takeIf(File::isFile)?.readText()
            ?.let { translationJson.decodeFromString(linesSerializer, it) }
    }.getOrNull()

    private fun writeDisk(key: String, lines: List<String>) {
        runCatching {
            directory.mkdirs()
            File(directory, "$key.json").writeText(translationJson.encodeToString(linesSerializer, lines))
        }
    }

    private companion object {
        fun defaultDirectory(): File {
            val dataHome = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
                ?: (System.getProperty("user.home") + "/.local/share")
            return File(dataHome, "lyricsfloat/translations")
        }
    }
}
