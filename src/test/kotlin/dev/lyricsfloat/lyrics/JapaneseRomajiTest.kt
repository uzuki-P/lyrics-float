package dev.lyricsfloat.lyrics

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JapaneseRomajiTest {
    @Test
    fun katakanaRomanizes() = runBlocking {
        assertEquals("karaoke", JapaneseRomaji.romanize("カラオケ"))
        assertEquals("nihon", JapaneseRomaji.romanize("ニホン"))
    }

    @Test
    fun digraphsAndDakuten() = runBlocking {
        assertEquals("kyatto", JapaneseRomaji.romanize("キャット"))
        assertEquals("tabemasu", JapaneseRomaji.romanize("タベマス"))
    }

    @Test
    fun kanjiUsesKuromojiReadings() = runBlocking {
        // kuromoji must supply the ミライ reading for 未来, then katakana→romaji.
        assertEquals("mirai", JapaneseRomaji.romanize("未来"))
    }

    @Test
    fun detection() {
        assertTrue(JapaneseRomaji.isJapanese("ハロー"))
        assertTrue(JapaneseRomaji.isJapanese("未来"))
        assertFalse(JapaneseRomaji.isJapanese("hello world"))
        // Kanji-only text is treated as Chinese (same rule as Metrolist), so
        // it is skipped by the Japanese romanizer.
        assertTrue(JapaneseRomaji.isChinese("未来"))
        assertFalse(JapaneseRomaji.isChinese("ハローミライ"))
    }
}
