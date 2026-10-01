package dev.lyricsfloat

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import dev.lyricsfloat.lyrics.SongOffsets
import dev.lyricsfloat.platform.AppState
import dev.lyricsfloat.ui.LyricsTextPosition
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
