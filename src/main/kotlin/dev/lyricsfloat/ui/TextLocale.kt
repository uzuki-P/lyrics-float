package dev.lyricsfloat.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.intl.LocaleList
import dev.lyricsfloat.platform.isWindows

/**
 * Windows picks the fallback font for CJK text by the text's locale. With the
 * default English locale, kanji in Japanese lyrics come out in a Chinese font
 * or MS Gothic, so Japanese text is tagged `ja-JP` there and gets Yu Gothic UI
 * or Meiryo. Linux keeps fontconfig's choice unchanged.
 */
private val JapaneseLocale = LocaleList("ja-JP")

internal fun containsKana(text: String): Boolean = text.any { it in '぀'..'ヿ' }

/** [japanese] is decided per song: kanji-only lines in a Japanese song are Japanese too. */
internal fun TextStyle.withCjkLocale(japanese: Boolean): TextStyle =
    if (isWindows && japanese) copy(localeList = JapaneseLocale) else this
