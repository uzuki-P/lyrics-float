package dev.lyricsfloat.lyrics.translation

import dev.lyricsfloat.lyrics.BuildVersion
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** How a provider is called. */
enum class TranslationApi { CHAT_COMPLETIONS, ANTHROPIC, DEEPL, T3_CODE }

/**
 * Metrolist's AI provider list (AiSettings.kt), plus T3 Code. Endpoints and
 * model presets match Metrolist. Claude uses the native Messages API here;
 * Metrolist sends it a chat-completions body, which that endpoint rejects.
 */
enum class TranslationProvider(
    val label: String,
    val api: TranslationApi,
    val defaultBaseUrl: String,
    val models: List<String>,
) {
    OPENROUTER(
        "OpenRouter", TranslationApi.CHAT_COMPLETIONS, "https://openrouter.ai/api/v1/chat/completions",
        listOf(
            "inception/mercury-2.5-preview", "meta/muse-spark-1.3", "z-ai/glm-5.3-flash", "qwen/qwen3.8-flash",
            "~deepseek/deepseek-v4-flash-latest", "~openai/gpt-mini-latest", "openai/gpt-oss-120b",
            "~google/gemini-flash-latest",
        ),
    ),
    OPENAI(
        "OpenAI", TranslationApi.CHAT_COMPLETIONS, "https://api.openai.com/v1/chat/completions",
        listOf("gpt-5.6-sol", "gpt-5.6-terra", "gpt-5.6-luna", "gpt-5.5-2026-04-23", "gpt-5.4-2026-03-05"),
    ),
    CLAUDE(
        "Claude", TranslationApi.ANTHROPIC, "https://api.anthropic.com/v1/messages",
        listOf("claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5", "claude-fable-5-1"),
    ),
    GEMINI(
        "Gemini", TranslationApi.CHAT_COMPLETIONS,
        "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
        listOf("gemini-flash-lite-latest", "gemini-pro-latest", "gemini-flash-latest", "gemini-3.8-flash"),
    ),
    PERPLEXITY(
        "Perplexity", TranslationApi.CHAT_COMPLETIONS, "https://api.perplexity.ai/chat/completions",
        listOf("sonar", "sonar-pro", "sonar-reasoning-pro"),
    ),
    XAI(
        "xAI", TranslationApi.CHAT_COMPLETIONS, "https://api.x.ai/v1/chat/completions",
        listOf("grok-4.3", "grok-4.6", "grok-4.1-fast"),
    ),
    MISTRAL(
        "Mistral", TranslationApi.CHAT_COMPLETIONS, "https://api.mistral.ai/v1/chat/completions",
        listOf("mistral-large-latest", "mistral-medium-latest", "mistral-small-latest", "mistral-tiny-latest"),
    ),
    INCEPTION(
        "Inception", TranslationApi.CHAT_COMPLETIONS, "https://api.inceptionlabs.ai/v1/chat/completions",
        listOf("mercury-2"),
    ),
    DEEPL("DeepL", TranslationApi.DEEPL, "https://api.deepl.com/v2/translate", emptyList()),
    CUSTOM("Custom", TranslationApi.CHAT_COMPLETIONS, "", emptyList()),
    T3_CODE("T3 Code", TranslationApi.T3_CODE, "", emptyList()),
}

enum class DeepLFormality(val label: String, val wire: String?) {
    DEFAULT("Default", null),
    MORE("More formal", "more"),
    LESS("Less formal", "less"),
}

/** One T3 Code model choice: provider instance, model slug, reasoning option. */
data class T3Selection(
    val instanceId: String,
    val model: String,
    /** Option id of the reasoning control (effort, reasoningEffort, variant); null when the model has none. */
    val effortOptionId: String?,
    val effort: String?,
)

/** Everything one translation run depends on; also the cache identity. */
data class TranslationConfig(
    val provider: TranslationProvider,
    val languageCode: String,
    val mode: TranslationMode,
    val apiKey: String,
    val model: String,
    val baseUrl: String,
    val systemPrompt: String,
    val deepLFormality: DeepLFormality,
    val t3: T3Selection?,
) {
    /** Settings that change the output; the API key does not. */
    fun cacheIdentity(): String = when (provider.api) {
        TranslationApi.DEEPL -> "deepl|$languageCode|${deepLFormality.name}"
        TranslationApi.T3_CODE -> "t3|${t3?.instanceId}|${t3?.model}|${t3?.effort}|$languageCode|${mode.name}|$systemPrompt"
        else -> "${provider.name}|$model|$baseUrl|$languageCode|${mode.name}|$systemPrompt"
    }

    /** True when an LLM call can be made: DeepL cannot romanize, and keys or a T3 model must be set. */
    fun canComplete(): Boolean = when (provider.api) {
        TranslationApi.DEEPL -> false
        TranslationApi.T3_CODE -> t3 != null
        TranslationApi.CHAT_COMPLETIONS -> if (provider == TranslationProvider.CUSTOM) baseUrl.isNotBlank() else apiKey.isNotBlank()
        TranslationApi.ANTHROPIC -> apiKey.isNotBlank()
    }

    /** The model that answers a romaji request; language, mode and prompt do not matter. */
    fun romajiIdentity(): String = when (provider.api) {
        TranslationApi.T3_CODE -> "t3|${t3?.instanceId}|${t3?.model}|${t3?.effort}"
        else -> "${provider.name}|$model|$baseUrl"
    }
}

class TranslationException(message: String) : Exception(message)

internal val translationJson = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

internal val translationHttp by lazy {
    HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 15_000
        }
        expectSuccess = false
    }
}

private const val REFERER = "https://github.com/Uzuki-P/lyrics-float"
private val USER_AGENT = "Lyrics Float v${BuildVersion.VERSION} ($REFERER)"

/**
 * Direct HTTP translators. Like Metrolist they retry network errors and 5xx
 * up to three times; 4xx (bad key, unknown model) fails at once.
 */
internal object HttpTranslators {
    suspend fun translate(lines: List<String>, config: TranslationConfig): List<String> = when (config.provider.api) {
        TranslationApi.DEEPL -> withRetry { deepL(lines, config) }
        else -> complete(TranslationPrompt.forTranslation(lines, config), config)
    }

    /** Runs any line prompt (translation or romaji) on an LLM provider. */
    suspend fun complete(prompt: LinePrompt, config: TranslationConfig): List<String> = when (config.provider.api) {
        TranslationApi.CHAT_COMPLETIONS -> withRetry { chatCompletions(prompt, config) }
        TranslationApi.ANTHROPIC -> withRetry { anthropic(prompt, config) }
        TranslationApi.DEEPL -> throw TranslationException("DeepL only translates")
        TranslationApi.T3_CODE -> error("T3 Code is not an HTTP translator")
    }

    private class RetryableException(message: String) : Exception(message)

    private suspend fun <T> withRetry(block: suspend () -> T): T {
        var last: Exception? = null
        repeat(3) { attempt ->
            try {
                return block()
            } catch (e: TranslationException) {
                throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
                delay(1000L * (attempt + 1))
            }
        }
        throw TranslationException(last?.message ?: "Translation failed")
    }

    private suspend fun chatCompletions(prompt: LinePrompt, config: TranslationConfig): List<String> {
        val url = config.baseUrl.ifBlank { config.provider.defaultBaseUrl }
        if (url.isBlank()) throw TranslationException("Set a base URL for the custom provider")
        if (config.apiKey.isBlank() && config.provider != TranslationProvider.CUSTOM) {
            throw TranslationException("${config.provider.label} needs an API key")
        }
        val body = buildJsonObject {
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", prompt.system)
                }
                addJsonObject {
                    put("role", "user")
                    put("content", prompt.user)
                }
            }
            if (config.model.isNotBlank()) put("model", config.model)
            put("temperature", 0.3)
            put("max_tokens", prompt.lineCount * 100)
            putJsonObject("response_format") {
                put("type", "json_schema")
                putJsonObject("json_schema") {
                    put("name", "translated_lyrics")
                    put("strict", true)
                    putJsonObject("schema") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("lines") {
                                put("type", "array")
                                putJsonObject("items") { put("type", "string") }
                            }
                        }
                        putJsonArray("required") { add("lines") }
                        put("additionalProperties", false)
                    }
                }
            }
            if ("openrouter.ai" in url) putJsonObject("provider") { put("require_parameters", true) }
        }
        val response = translationHttp.post(url) {
            if (config.apiKey.isNotBlank()) header("Authorization", "Bearer ${config.apiKey.trim()}")
            header("HTTP-Referer", REFERER)
            header("X-Title", "Lyrics Float")
            header("User-Agent", USER_AGENT)
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        val text = response.bodyAsText()
        checkStatus(response.status.value, text)
        val content = translationJson.parseToJsonElement(text).jsonObject["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            ?: throw TranslationException("The reply had no message content")
        return TranslationPrompt.parseLines(content, prompt.lineCount).getOrElse { throw TranslationException(it.message ?: "Bad reply") }
    }

    private suspend fun anthropic(prompt: LinePrompt, config: TranslationConfig): List<String> {
        if (config.apiKey.isBlank()) throw TranslationException("Claude needs an API key")
        val body = buildJsonObject {
            put("model", config.model.ifBlank { config.provider.models.first() })
            put("max_tokens", (prompt.lineCount * 100).coerceAtLeast(1024))
            put("temperature", 0.3)
            put("system", prompt.system)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", prompt.user)
                }
            }
        }
        val response = translationHttp.post(config.baseUrl.ifBlank { config.provider.defaultBaseUrl }) {
            header("x-api-key", config.apiKey.trim())
            header("anthropic-version", "2023-06-01")
            header("User-Agent", USER_AGENT)
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        val text = response.bodyAsText()
        checkStatus(response.status.value, text)
        val content = translationJson.parseToJsonElement(text).jsonObject["content"]?.jsonArray
            ?.mapNotNull { block ->
                block.jsonObject.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
                    ?.get("text")?.jsonPrimitive?.contentOrNull
            }
            ?.joinToString("")
            ?.takeIf(String::isNotBlank)
            ?: throw TranslationException("The reply had no text")
        return TranslationPrompt.parseLines(content, prompt.lineCount).getOrElse { throw TranslationException(it.message ?: "Bad reply") }
    }

    /** Metrolist's DeepLService: free keys end in ":fx" and use the free host. */
    private suspend fun deepL(lines: List<String>, config: TranslationConfig): List<String> {
        val key = config.apiKey.trim()
        if (key.isBlank()) throw TranslationException("DeepL needs an API key")
        val url = if (key.endsWith(":fx")) "https://api-free.deepl.com/v2/translate" else "https://api.deepl.com/v2/translate"
        val body = buildJsonObject {
            putJsonArray("text") { lines.forEach { add(it) } }
            put("target_lang", deepLLanguage(config.languageCode))
            config.deepLFormality.wire?.let { put("formality", it) }
            put("preserve_formatting", true)
        }
        val response = translationHttp.post(url) {
            header("Authorization", "DeepL-Auth-Key $key")
            header("User-Agent", USER_AGENT)
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        val text = response.bodyAsText()
        checkStatus(response.status.value, text)
        val translated = translationJson.parseToJsonElement(text).jsonObject["translations"]?.jsonArray
            ?.map { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
            ?: throw TranslationException("DeepL returned no translations")
        return translated.take(lines.size) + List((lines.size - translated.size).coerceAtLeast(0)) { "" }
    }

    internal fun deepLLanguage(code: String): String = when (code.lowercase()) {
        "zh", "zh-cn", "zh-hans" -> "ZH-HANS"
        "zh-tw", "zh-hant" -> "ZH-HANT"
        "en", "en-us" -> "EN-US"
        "en-gb" -> "EN-GB"
        "pt", "pt-pt" -> "PT-PT"
        "pt-br" -> "PT-BR"
        else -> code.take(2).uppercase()
    }

    private fun checkStatus(status: Int, body: String) {
        if (status in 200..299) return
        val message = runCatching {
            val root = translationJson.parseToJsonElement(body).jsonObject
            val error = root["error"]
            (error as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
                ?: error?.jsonPrimitive?.contentOrNull
                ?: root["message"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()?.takeIf(String::isNotBlank) ?: "HTTP $status"
        if (status >= 500) throw RetryableException(message)
        throw TranslationException(message)
    }
}
