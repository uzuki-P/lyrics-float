package dev.lyricsfloat.lyrics.translation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Round trip against the T3 Code server running on this machine: connect,
 * list the catalog, translate through a one-shot thread. Skipped unless
 * LYRICS_FLOAT_LIVE_T3=1. Point XDG_CONFIG_HOME at a scratch directory so the
 * minted session does not land in the real settings file.
 */
class T3CodeLiveTest {
    @Test
    fun translatesThroughOneShotThread() {
        if (System.getenv("LYRICS_FLOAT_LIVE_T3") != "1") return
        runBlocking {
            val providers = T3CodeClient.catalog()
            println("T3 catalog: " + providers.joinToString { p -> "${p.instanceId}(${p.models.size})" })
            val claude = providers.first { it.instanceId == "claudeAgent" }
            val haiku = claude.models.first { "haiku-5" in it.slug }
            val config = TranslationConfig(
                provider = TranslationProvider.T3_CODE,
                languageCode = "en",
                mode = TranslationMode.LITERAL,
                apiKey = "",
                model = "",
                baseUrl = "",
                systemPrompt = "",
                deepLFormality = DeepLFormality.DEFAULT,
                t3 = T3Selection(claude.instanceId, haiku.slug, haiku.effortOptionId, "low"),
            )
            val lines = T3CodeClient.translate(listOf("一人で歩いてた", "君の声が聞こえる"), config)
            println("T3 translation: $lines")
            assertEquals(2, lines.size)
            assertTrue(lines.all { it.isNotBlank() })
        }
    }
}
