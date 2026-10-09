package dev.lyricsfloat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lyricsfloat.AppSettings
import dev.lyricsfloat.lyrics.translation.DeepLFormality
import dev.lyricsfloat.lyrics.translation.T3CodeClient
import dev.lyricsfloat.lyrics.translation.T3Model
import dev.lyricsfloat.lyrics.translation.T3Provider
import dev.lyricsfloat.lyrics.translation.TranslationApi
import dev.lyricsfloat.lyrics.translation.TranslationLanguages
import dev.lyricsfloat.lyrics.translation.TranslationMode
import dev.lyricsfloat.lyrics.translation.TranslationPrompt
import dev.lyricsfloat.lyrics.translation.TranslationProvider
import dev.lyricsfloat.lyrics.translation.TranslationStatus
import kotlin.math.roundToInt

private val DropdownWidth = 180.dp

/**
 * Metrolist's "AI lyrics translation" settings (provider, credentials, mode,
 * target language, system prompt) as one settings tab, with T3 Code as an
 * extra provider whose agent, model and reasoning lists come live from the
 * running T3 Code server.
 */
@Composable
internal fun TranslateTab(
    settings: AppSettings,
    status: TranslationStatus,
    onRetranslate: () -> Unit,
    romajiStatus: TranslationStatus,
    onReromanize: () -> Unit,
) {
    val provider = settings.translationProvider

    SettingsGroup("Translation") {
        ToggleRow(
            "Translate lyrics",
            settings.translateEnabled,
            { settings.translateEnabled = it },
            description = "A translated line under each lyric, fetched once per song and cached",
        )
        SettingRow("Target language") {
            AppDropdown(
                options = TranslationLanguages.codes.map { it to TranslationLanguages.name(it) }
                    .sortedBy { it.second },
                selected = settings.translationLanguage,
                onSelect = { settings.translationLanguage = it },
                modifier = Modifier.width(DropdownWidth),
            )
        }
        if (provider.api != TranslationApi.DEEPL) {
            SettingRow(
                "Mode",
                description = when (settings.translationMode) {
                    TranslationMode.LITERAL -> "Translate the meaning"
                    TranslationMode.TRANSCRIBED -> "Write how it sounds in the target script"
                },
            ) {
                SegmentedControl(
                    options = TranslationMode.entries.map { it to it.label },
                    selected = settings.translationMode,
                    onSelect = { settings.translationMode = it },
                )
            }
        }
        SliderRow(
            label = "Translation text size",
            valueText = "${settings.translationFontSizeSp} sp",
            value = settings.translationFontSizeSp.toFloat(),
            valueRange = 8f..30f,
            steps = 21,
            onValueChange = { settings.translationFontSizeSp = it.roundToInt() },
        )
    }

    SettingsGroup(
        "Romaji",
        footer = "Japanese songs only. The offline romaji shows until the AI romaji arrives, " +
            "and stays if the request fails. Results are cached per song.",
    ) {
        ToggleRow(
            "AI romaji",
            settings.aiRomaji,
            { settings.aiRomaji = it },
            description = when {
                !settings.romanizeJapanese -> "Turn on \"Romanize Japanese lyrics\" in the Lyrics tab first"
                provider.api == TranslationApi.DEEPL -> "DeepL cannot romanize; pick an AI provider below"
                else -> "Reads kanji in context with the provider below, even while translation is off"
            },
        )
        if (settings.romajiConfig() != null) {
            val (text, tone) = when (romajiStatus) {
                TranslationStatus.Idle -> "No Japanese lyrics playing" to MessageTone.INFO
                TranslationStatus.Translating -> "Romanizing this song…" to MessageTone.ACCENT
                TranslationStatus.Done -> "This song has AI romaji" to MessageTone.INFO
                is TranslationStatus.Error -> "${romajiStatus.message} (using offline romaji)" to MessageTone.ERROR
            }
            InlineMessage(text, tone, modifier = Modifier.padding(bottom = 6.dp)) {
                AppButton(
                    "Romanize again",
                    onClick = onReromanize,
                    kind = ButtonKind.GHOST,
                    enabled = romajiStatus !is TranslationStatus.Translating,
                    compact = true,
                )
            }
        }
    }

    SettingsGroup(
        "Provider",
        footer = when (provider.api) {
            TranslationApi.T3_CODE ->
                "Each song runs once in a temporary T3 Code thread in its \"No project\" folder. " +
                    "The thread is deleted as soon as the reply arrives."
            TranslationApi.DEEPL -> "Free API keys end in :fx and use DeepL's free endpoint."
            else -> "Keys are stored unencrypted in ~/.config/lyricsfloat/state.properties."
        },
    ) {
        SettingRow("Provider") {
            AppDropdown(
                options = TranslationProvider.entries.map { it to it.label },
                selected = provider,
                onSelect = { settings.translationProvider = it },
                modifier = Modifier.width(DropdownWidth),
            )
        }
        when (provider.api) {
            TranslationApi.T3_CODE -> T3CodeSection(settings)
            TranslationApi.DEEPL -> {
                ApiKeyField(settings, provider)
                SettingRow("Formality") {
                    AppDropdown(
                        options = DeepLFormality.entries.map { it to it.label },
                        selected = settings.deepLFormality,
                        onSelect = { settings.deepLFormality = it },
                        modifier = Modifier.width(DropdownWidth),
                    )
                }
            }
            else -> {
                if (provider == TranslationProvider.CUSTOM) {
                    FieldLabel("Base URL")
                    AppTextField(
                        value = settings.customBaseUrl,
                        onValueChange = { settings.customBaseUrl = it },
                        placeholder = "https://host/v1/chat/completions",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                ApiKeyField(settings, provider)
                ModelField(settings, provider)
            }
        }
    }

    if (provider.api != TranslationApi.DEEPL) {
        SettingsGroup(
            "System prompt",
            footer = "Leave blank for Metrolist's default. {lineCount} becomes the number of lines.",
        ) {
            AppTextField(
                value = settings.translationPrompt,
                onValueChange = { settings.translationPrompt = it },
                placeholder = TranslationPrompt.DEFAULT_SYSTEM_PROMPT.lineSequence().first(),
                singleLine = false,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().height(110.dp).padding(top = 8.dp),
            )
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.End) {
                AppButton(
                    "Use default",
                    onClick = { settings.translationPrompt = "" },
                    kind = ButtonKind.GHOST,
                    enabled = settings.translationPrompt.isNotBlank(),
                    compact = true,
                )
            }
        }
    }

    if (settings.translateEnabled) {
        val (text, tone) = when (status) {
            TranslationStatus.Idle -> "Waiting for lyrics" to MessageTone.INFO
            TranslationStatus.Translating -> "Translating this song…" to MessageTone.ACCENT
            TranslationStatus.Done -> "This song is translated" to MessageTone.INFO
            is TranslationStatus.Error -> status.message to MessageTone.ERROR
        }
        InlineMessage(text, tone) {
            AppButton(
                "Translate again",
                onClick = onRetranslate,
                kind = ButtonKind.GHOST,
                enabled = status !is TranslationStatus.Translating,
                compact = true,
            )
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    val palette = LocalAppPalette.current
    Text(text, style = AppType.body, color = palette.onSurface, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
}

@Composable
private fun ApiKeyField(settings: AppSettings, provider: TranslationProvider) {
    FieldLabel(if (provider == TranslationProvider.CUSTOM) "API key (optional)" else "API key")
    AppTextField(
        value = settings.apiKey(provider),
        onValueChange = { settings.setApiKey(provider, it) },
        placeholder = "Paste your ${provider.label} key",
        secret = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Free-text model with Metrolist's presets as one-click chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelField(settings: AppSettings, provider: TranslationProvider) {
    FieldLabel("Model")
    AppTextField(
        value = settings.model(provider),
        onValueChange = { settings.setModel(provider, it) },
        placeholder = "Model id",
        modifier = Modifier.fillMaxWidth(),
    )
    if (provider.models.isNotEmpty()) {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            provider.models.forEach { model ->
                ChoiceChip(model, settings.model(provider) == model, { settings.setModel(provider, model) })
            }
        }
    }
}

/**
 * Agent, model and reasoning pickers filled from T3 Code's live catalog.
 * Loading the catalog connects on first use (`t3 auth session issue`), so
 * picking T3 Code as the provider is the only setup step.
 */
@Composable
private fun T3CodeSection(settings: AppSettings) {
    var catalog by remember { mutableStateOf<List<T3Provider>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var connected by remember { mutableStateOf(T3CodeClient.isConnected) }

    fun selectModel(provider: T3Provider, model: T3Model) {
        settings.t3Instance = provider.instanceId
        settings.t3Model = model.slug
        settings.t3EffortOption = model.effortOptionId.orEmpty()
        settings.t3Effort = model.defaultEffort.orEmpty()
    }

    LaunchedEffect(reload) {
        loading = true
        error = null
        runCatching { T3CodeClient.catalog() }
            .onSuccess { providers ->
                catalog = providers
                // Keep the saved pick when it still exists; otherwise start
                // from the first ready agent's first model.
                val current = providers.firstOrNull { it.instanceId == settings.t3Instance }
                    ?.models?.firstOrNull { it.slug == settings.t3Model }
                if (current == null) {
                    val provider = providers.firstOrNull { it.ready } ?: providers.firstOrNull()
                    provider?.models?.firstOrNull()?.let { selectModel(provider, it) }
                }
            }
            .onFailure { error = it.message ?: "Could not reach T3 Code" }
        connected = T3CodeClient.isConnected
        loading = false
    }

    val providers = catalog.orEmpty()
    val provider = providers.firstOrNull { it.instanceId == settings.t3Instance }
    val model = provider?.models?.firstOrNull { it.slug == settings.t3Model }

    SettingRow("Agent") {
        AppDropdown(
            options = providers.map { it to if (it.ready) it.name else "${it.name} (not ready)" },
            selected = provider,
            onSelect = { picked -> picked.models.firstOrNull()?.let { selectModel(picked, it) } },
            placeholder = if (loading) "Loading…" else settings.t3Instance.ifBlank { "Choose…" },
            modifier = Modifier.width(DropdownWidth),
        )
    }
    SettingRow("Model") {
        AppDropdown(
            options = provider?.models.orEmpty().map { it to it.name },
            selected = model,
            onSelect = { picked -> provider?.let { selectModel(it, picked) } },
            placeholder = settings.t3Model.ifBlank { "Choose…" },
            modifier = Modifier.width(DropdownWidth),
        )
    }
    if (model != null && model.efforts.isNotEmpty()) {
        SettingRow("Reasoning") {
            AppDropdown(
                options = model.efforts,
                selected = settings.t3Effort,
                onSelect = {
                    settings.t3EffortOption = model.effortOptionId.orEmpty()
                    settings.t3Effort = it
                },
                modifier = Modifier.width(DropdownWidth),
            )
        }
    }

    Spacer(Modifier.height(4.dp))
    InlineMessage(
        text = when {
            loading -> "Connecting to T3 Code…"
            error != null -> error!!
            connected -> "Connected to ${T3CodeClient.origin()}"
            else -> "Not connected"
        },
        tone = when {
            error != null -> MessageTone.ERROR
            connected && !loading -> MessageTone.ACCENT
            else -> MessageTone.INFO
        },
        modifier = Modifier.padding(bottom = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppButton("Refresh", onClick = { reload++ }, kind = ButtonKind.GHOST, enabled = !loading, compact = true)
            if (connected) {
                AppButton(
                    "Disconnect",
                    onClick = {
                        T3CodeClient.disconnect()
                        connected = false
                        catalog = null
                    },
                    kind = ButtonKind.GHOST,
                    enabled = !loading,
                    compact = true,
                )
            }
        }
    }
}
