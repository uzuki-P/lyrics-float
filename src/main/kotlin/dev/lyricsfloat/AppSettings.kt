package dev.lyricsfloat

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import dev.lyricsfloat.lyrics.SongOffsets
import dev.lyricsfloat.lyrics.translation.DeepLFormality
import dev.lyricsfloat.lyrics.translation.T3Selection
import dev.lyricsfloat.lyrics.translation.TranslationApi
import dev.lyricsfloat.lyrics.translation.TranslationConfig
import dev.lyricsfloat.lyrics.translation.TranslationLanguages
import dev.lyricsfloat.lyrics.translation.TranslationMode
import dev.lyricsfloat.lyrics.translation.TranslationProvider
import dev.lyricsfloat.platform.AppState
import dev.lyricsfloat.ui.LyricsTextPosition
import java.util.Locale
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Every user-facing preference as Compose state. Assigning a property
 * recomposes its readers and writes it through to [AppState] (or the owner
 * passed in, for settings that live elsewhere), so the windows never pair a
 * state var with a manual save call.
 */
class AppSettings(onRomanizeJapaneseChange: (Boolean) -> Unit) {
    var themeMode by persisted(AppState.loadThemeMode(), AppState::saveThemeMode)
    var fontSizeSp by persisted(AppState.loadFontSizeSp(), AppState::saveFontSizeSp)
    var romajiFontSizeSp by persisted(AppState.loadRomajiFontSizeSp(), AppState::saveRomajiFontSizeSp)
    var opacity by persisted(AppState.loadOpacity(), AppState::saveOpacity)
    var showNextLine by persisted(AppState.loadShowNextLine(), AppState::saveShowNextLine)
    var offsetMs by persisted(AppState.loadOffsetMs(), AppState::saveOffsetMs, SongOffsets::clamp)
    var autoHide by persisted(AppState.loadAutoHide(), AppState::saveAutoHide)
    var clickPassThrough by persisted(AppState.loadClickPassThrough(), AppState::saveClickPassThrough)
    var hoverMenuPosition by persisted(AppState.loadHoverMenuPosition(), AppState::saveHoverMenuPosition)
    var preferredPlayer by persisted(AppState.loadPreferredPlayer(), AppState::savePreferredPlayer)
    var overlayAnchor by persisted(AppState.loadOverlayAnchor(), AppState::saveOverlayAnchor)
    var showIntervalIndicator by persisted(AppState.loadShowIntervalIndicator(), AppState::saveShowIntervalIndicator)
    var respectAgentPositioning by persisted(AppState.loadRespectAgentPositioning(), AppState::saveRespectAgentPositioning)
    var wordKaraoke by persisted(AppState.loadWordKaraoke(), AppState::saveWordKaraoke)

    // The repository persists this one itself and restarts romanization.
    var romanizeJapanese by persisted(AppState.loadRomanizeJapanese(), onRomanizeJapaneseChange)
    var textPosition by persisted(
        runCatching { LyricsTextPosition.valueOf(AppState.loadTextPosition()) }.getOrDefault(LyricsTextPosition.CENTER),
        save = { AppState.saveTextPosition(it.name) },
    )
    var autoScroll by persisted(AppState.loadAutoScroll(), AppState::saveAutoScroll)
    var textOutline by persisted(AppState.loadTextOutline(), AppState::saveTextOutline)

    // ----- translation (Metrolist's AI lyrics translation, plus T3 Code) -----

    var translateEnabled by persisted(AppState.loadTranslateEnabled(), AppState::saveTranslateEnabled)
    var translationFontSizeSp by persisted(AppState.loadTranslationFontSizeSp(), AppState::saveTranslationFontSizeSp)
    var translationProvider by persistedEnum("translate.provider", TranslationProvider.OPENROUTER)
    var translationLanguage by persistedText(
        "translate.language",
        Locale.getDefault().language.takeIf { it in TranslationLanguages.codes && it != "en" } ?: "en",
    )
    var translationMode by persistedEnum("translate.mode", TranslationMode.LITERAL)
    /** Custom system prompt; blank uses Metrolist's default. */
    var translationPrompt by persistedText("translate.prompt", "")
    var deepLFormality by persistedEnum("translate.deeplFormality", DeepLFormality.DEFAULT)
    var customBaseUrl by persistedText("translate.customUrl", "")
    var t3Instance by persistedText("translate.t3.instance", "")
    var t3Model by persistedText("translate.t3.model", "")
    var t3EffortOption by persistedText("translate.t3.effortOption", "")
    var t3Effort by persistedText("translate.t3.effort", "")

    // API keys and models are kept per provider, so switching back and forth
    // does not lose them.
    private val apiKeys = mutableStateMapOf<TranslationProvider, String>().apply {
        TranslationProvider.entries.forEach { put(it, AppState.loadText("translate.key.${it.name}")) }
    }
    private val models = mutableStateMapOf<TranslationProvider, String>().apply {
        TranslationProvider.entries.forEach {
            put(it, AppState.loadText("translate.model.${it.name}").ifBlank { it.models.firstOrNull().orEmpty() })
        }
    }

    fun apiKey(provider: TranslationProvider): String = apiKeys[provider].orEmpty()

    fun setApiKey(provider: TranslationProvider, value: String) {
        apiKeys[provider] = value
        AppState.saveText("translate.key.${provider.name}", value)
    }

    fun model(provider: TranslationProvider): String = models[provider].orEmpty()

    fun setModel(provider: TranslationProvider, value: String) {
        models[provider] = value
        AppState.saveText("translate.model.${provider.name}", value)
    }

    /** Use the translation provider for romaji, with the offline romaji as the fallback. */
    var aiRomaji by persisted(AppState.loadText("translate.aiRomaji") != "false", save = { AppState.saveText("translate.aiRomaji", it.toString()) })

    /** What the translator should run with; null while translation is off. */
    fun translationConfig(): TranslationConfig? = if (translateEnabled) providerConfig() else null

    /**
     * What AI romaji runs with. It follows the provider settings even while
     * translation is off, and is null when romaji or AI romaji is off or the
     * provider cannot answer (DeepL, or no key or model yet).
     */
    fun romajiConfig(): TranslationConfig? =
        if (romanizeJapanese && aiRomaji) providerConfig().takeIf(TranslationConfig::canComplete) else null

    private fun providerConfig(): TranslationConfig {
        val provider = translationProvider
        return TranslationConfig(
            provider = provider,
            languageCode = translationLanguage,
            mode = translationMode,
            apiKey = apiKey(provider),
            model = model(provider),
            baseUrl = if (provider == TranslationProvider.CUSTOM) customBaseUrl.trim() else provider.defaultBaseUrl,
            systemPrompt = translationPrompt,
            deepLFormality = deepLFormality,
            t3 = if (provider.api == TranslationApi.T3_CODE && t3Instance.isNotBlank() && t3Model.isNotBlank()) {
                T3Selection(t3Instance, t3Model, t3EffortOption.ifBlank { null }, t3Effort.ifBlank { null })
            } else {
                null
            },
        )
    }

    private fun persistedText(key: String, default: String): ReadWriteProperty<Any?, String> =
        persisted(AppState.loadText(key).ifBlank { default }, save = { AppState.saveText(key, it) })

    private inline fun <reified E : Enum<E>> persistedEnum(key: String, default: E): ReadWriteProperty<Any?, E> =
        persisted(
            runCatching { enumValueOf<E>(AppState.loadText(key)) }.getOrDefault(default),
            save = { AppState.saveText(key, it.name) },
        )

    private fun <T> persisted(initial: T, save: (T) -> Unit, sanitize: (T) -> T = { it }): ReadWriteProperty<Any?, T> =
        object : ReadWriteProperty<Any?, T> {
            private val state: MutableState<T> = mutableStateOf(initial)

            override fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value

            override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
                val clean = sanitize(value)
                state.value = clean
                save(clean)
            }
        }
}
