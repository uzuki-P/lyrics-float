package dev.lyricsfloat.lyrics.translation

import dev.lyricsfloat.lyrics.LyricsEntry
import dev.lyricsfloat.lyrics.LyricsState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RomajiPromptTest {
    private fun state(vararg lines: String) =
        LyricsState.EMPTY.copy(entries = lines.mapIndexed { index, text -> LyricsEntry(index * 1000L, text) })

    @Test
    fun promptKeepsLinesAndCount() {
        val prompt = RomajiPrompt.forLines(listOf("一人で", "未来へ"))
        assertEquals(2, prompt.lineCount)
        assertTrue("一人で\n未来へ" in prompt.user)
        assertTrue("EXACTLY 2 items" in prompt.system)
        assertTrue("Do NOT translate" in prompt.user)
    }

    @Test
    fun japaneseSongIncludesKanjiOnlyLines() {
        val targets = LyricsTranslator.romajiTargets(state("未来", "", "hello", "君に会いたい"))
        assertEquals(listOf("未来", "君に会いたい"), targets.map { it.text })
    }

    @Test
    fun chineseSongHasNoTargets() {
        assertTrue(LyricsTranslator.romajiTargets(state("我爱你", "未来")).isEmpty())
    }

    @Test
    fun romajiIdentityIgnoresLanguageAndMode() {
        val base = TranslationConfig(
            provider = TranslationProvider.OPENAI,
            languageCode = "en",
            mode = TranslationMode.LITERAL,
            apiKey = "k",
            model = "m",
            baseUrl = "u",
            systemPrompt = "",
            deepLFormality = DeepLFormality.DEFAULT,
            t3 = null,
        )
        val other = base.copy(languageCode = "id", mode = TranslationMode.TRANSCRIBED, systemPrompt = "x")
        assertEquals(base.romajiIdentity(), other.romajiIdentity())
        assertTrue(base.canComplete())
        assertFalse(base.copy(apiKey = "").canComplete())
        assertFalse(base.copy(provider = TranslationProvider.DEEPL).canComplete())
    }
}
