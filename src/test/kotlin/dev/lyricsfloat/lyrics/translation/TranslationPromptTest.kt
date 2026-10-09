package dev.lyricsfloat.lyrics.translation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TranslationPromptTest {
    @Test
    fun parsesLinesObject() {
        val lines = TranslationPrompt.parseLines("""{"lines": ["a", "b"]}""", 2).getOrThrow()
        assertEquals(listOf("a", "b"), lines)
    }

    @Test
    fun parsesFencedReplyWithChatter() {
        val reply = "Here you go:\n```json\n{\"lines\": [\"one\", \"two\", \"three\"]}\n```"
        assertEquals(listOf("one", "two", "three"), TranslationPrompt.parseLines(reply, 3).getOrThrow())
    }

    @Test
    fun parsesBareArray() {
        assertEquals(listOf("x", "y"), TranslationPrompt.parseLines("""["x", "y"]""", 2).getOrThrow())
    }

    @Test
    fun padsAndTruncatesToLineCount() {
        assertEquals(listOf("a", "", ""), TranslationPrompt.parseLines("""{"lines": ["a"]}""", 3).getOrThrow())
        assertEquals(listOf("a"), TranslationPrompt.parseLines("""{"lines": ["a", "b"]}""", 1).getOrThrow())
    }

    @Test
    fun fallsBackToPlainLines() {
        assertEquals(listOf("first", "second"), TranslationPrompt.parseLines("\"first\"\nsecond\n", 2).getOrThrow())
    }

    @Test
    fun emptyReplyFails() {
        assertTrue(TranslationPrompt.parseLines("  ", 2).isFailure)
    }

    @Test
    fun systemPromptSubstitutesLineCount() {
        assertTrue("EXACTLY 4 items" in TranslationPrompt.systemPrompt(4, ""))
        assertEquals("Only 7 lines", TranslationPrompt.systemPrompt(7, "Only {lineCount} lines"))
    }

    @Test
    fun userPromptNamesLanguageAndLines() {
        val prompt = TranslationPrompt.userPrompt(listOf("一人", "夜"), "id", TranslationMode.LITERAL)
        assertTrue(prompt.startsWith("Translate the following 2 lines to Indonesian."))
        assertTrue("一人\n夜" in prompt)
    }

    @Test
    fun deepLLanguageCodes() {
        assertEquals("EN-US", HttpTranslators.deepLLanguage("en"))
        assertEquals("ZH-HANS", HttpTranslators.deepLLanguage("zh-CN"))
        assertEquals("PT-BR", HttpTranslators.deepLLanguage("pt-BR"))
        assertEquals("JA", HttpTranslators.deepLLanguage("ja"))
    }
}
