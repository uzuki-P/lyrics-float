package dev.lyricsfloat.lyrics.translation

import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Metrolist's translation modes. Literal translates the meaning; Transcribed
 * writes how the original sounds in the target language's script. Metrolist
 * also has a "Romanized" mode its settings never offer, so it is left out.
 */
enum class TranslationMode(val label: String) {
    LITERAL("Literal"),
    TRANSCRIBED("Transcribed"),
}

/** Target languages; the prompt uses the English name, DeepL the code. */
object TranslationLanguages {
    val codes: List<String> = listOf(
        "en", "id", "ja", "ko", "zh-CN", "zh-TW", "es", "fr", "de", "it", "pt", "pt-BR", "ru", "ar", "hi",
        "bn", "pa", "tr", "vi", "th", "ms", "fil", "pl", "nl", "sv", "uk", "cs", "el", "he", "fa", "ro", "hu",
    )

    fun name(code: String): String =
        Locale.forLanguageTag(code).getDisplayName(Locale.ENGLISH).takeIf { it.isNotBlank() } ?: code
}

/**
 * Prompt text and response parsing, ported from Metrolist's
 * OpenRouterService.buildTranslationRequest / parseTranslationContent. Every
 * LLM provider (including T3 Code) uses the same text, so a cached result
 * means the same thing whichever provider produced it.
 */
object TranslationPrompt {
    /** Metrolist's DEFAULT_AI_SYSTEM_PROMPT; {lineCount} is substituted. */
    const val DEFAULT_SYSTEM_PROMPT = """You are a precise lyrics translation assistant. Your output must ALWAYS be a valid JSON object of the form {"lines": ["line1", "line2", "line3"]}.

CRITICAL RULES:
1. Output ONLY the JSON object: {"lines": ["line1", "line2", "line3"]}
2. NO explanations, NO questions, NO additional text
3. Each input line maps to exactly one entry in the "lines" array
4. Preserve empty lines as empty strings ""
5. The "lines" array must contain EXACTLY {lineCount} items
6. If uncertain, provide best approximation but maintain line count"""

    fun systemPrompt(lineCount: Int, customPrompt: String): String =
        customPrompt.takeIf(String::isNotBlank).let { it ?: DEFAULT_SYSTEM_PROMPT }
            .replace("{lineCount}", lineCount.toString())

    fun userPrompt(lines: List<String>, languageCode: String, mode: TranslationMode): String {
        val lineCount = lines.size
        val language = TranslationLanguages.name(languageCode)
        val text = lines.joinToString("\n")
        return when (mode) {
            TranslationMode.TRANSCRIBED -> """Transcribe/transliterate the following $lineCount lines phonetically into $language script.

CRITICAL REQUIREMENTS:
- Convert the SOUND/PRONUNCIATION of the original text into $language script
- DO NOT translate the meaning - only represent how the original words SOUND
- Use the native script of $language (e.g., Devanagari for Hindi, Hangul for Korean, etc.)
- Preserve the original pronunciation as closely as possible in the target script
- Keep punctuation and formatting
- Preserve line-by-line structure exactly
- If text is already in $language script, return it UNCHANGED

Examples:
- Japanese "こんにちは" to Hindi → "कोन्निचिवा" (phonetic, not translation)
- English "Hello" to Hindi → "हेलो" (phonetic)
- Korean "안녕하세요" to Hindi → "अन्न्योंग हासेयो" (phonetic)

Input ($lineCount lines):
$text

Output MUST be a JSON object {"lines": [...]} with EXACTLY $lineCount strings in $language script."""

            TranslationMode.LITERAL -> """Translate the following $lineCount lines to $language.

IMPORTANT:
- Provide natural, accurate translation
- Maintain poetic flow and meaning
- Keep punctuation appropriate for target language
- Preserve line-by-line structure exactly
- For song lyrics, prioritize singability

Input ($lineCount lines):
$text

Output MUST be a JSON object {"lines": [...]} with EXACTLY $lineCount strings."""
        }
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Reads `{"lines": [...]}` or a bare array, tolerating code fences and
     * chatter around the JSON; falls back to one line per output line. The
     * result always has [expectedLineCount] entries (truncated or padded).
     */
    fun parseLines(content: String, expectedLineCount: Int): Result<List<String>> = runCatching {
        val cleaned = content.replace("```json", "").replace("```", "").trim()
        val objectSlice = cleaned.substringAfter('{', "").substringBeforeLast('}', "")
            .takeIf(String::isNotEmpty)?.let { "{$it}" }
        val arraySlice = cleaned.substringAfter('[', "").substringBeforeLast(']', "")
            .takeIf(String::isNotEmpty)?.let { "[$it]" }
        val lines = sequenceOf(content.trim(), cleaned, objectSlice, arraySlice)
            .filterNotNull()
            .mapNotNull { candidate -> runCatching { extractLines(json.parseToJsonElement(candidate)) }.getOrNull() }
            .firstOrNull()
            ?: cleaned.lines()
                .filter(String::isNotBlank)
                .map { it.trim().removeSurrounding("\"").removeSurrounding("'") }
                .takeIf(List<String>::isNotEmpty)
            ?: error("The translation reply was empty")
        lines.take(expectedLineCount) + List((expectedLineCount - lines.size).coerceAtLeast(0)) { "" }
    }

    private fun extractLines(element: JsonElement): List<String>? {
        val array = when (element) {
            is JsonObject -> element["lines"] as? JsonArray
            is JsonArray -> element
            else -> null
        } ?: return null
        return array.map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }
    }
}
