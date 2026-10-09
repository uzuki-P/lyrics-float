package dev.lyricsfloat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lyricsfloat.lyrics.LyricsParser
import dev.lyricsfloat.lyrics.LyricsProviders
import dev.lyricsfloat.lyrics.LyricsTiming
import dev.lyricsfloat.lyrics.ManualSearch
import dev.lyricsfloat.lyrics.ManualSearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun providerColor(name: String): Color = when (name) {
    "Lrclib" -> Color(0xFF7DD3FC)
    "KuGou" -> Color(0xFFFCA5A5)
    "BetterLyrics" -> Color(0xFFFDE68A)
    "Paxsenix" -> Color(0xFFC4B5FD)
    "LyricsPlus" -> Color(0xFF86EFAC)
    else -> Color(0xFFCBD5E1)
}

private data class LyricsPreview(val rawText: String, val text: String, val timing: LyricsTiming)

private fun LyricsTiming.label(): String = when (this) {
    LyricsTiming.WORD -> "Word by word"
    LyricsTiming.LINE -> "Line by line"
    LyricsTiming.PLAIN -> "Plain text"
}

/**
 * Manual lyrics search across providers. Opens pre-filled with the current
 * song. Clicking a result expands its lyrics preview; its "Use" button
 * applies it to the track that is currently playing (via MPRIS), overriding
 * the automatic match. The used row shows a spinner, then Applied, or the
 * failure reason with Retry. Results carry provider and timing badges. A
 * chip row filters which providers are queried. The web button searches the
 * browser for `<query> lyrics` (Metrolist's search-online action), and
 * "Add lyrics manually" opens a paste-in editor (plain text or LRC) that
 * applies to the playing track as a Manual override.
 */
@Composable
fun SearchView(
    targetTitle: String,
    targetArtist: String,
    hasTarget: Boolean,
    overrideApplied: Boolean,
    initialQuery: String,
    onPick: suspend (ManualSearchResult) -> String?,
    onApplyManualText: suspend (String) -> String?,
    currentLyricsText: suspend () -> String?,
    onSearchWeb: (String) -> Unit,
    onClearOverride: () -> Unit,
    onClose: () -> Unit,
    search: suspend (String, String?) -> List<ManualSearchResult>,
    dragHandleModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
) {
    val palette = LocalAppPalette.current
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    // Keyed on initialQuery: when the playing song changes, the field refills
    // with the new song (and the stale query/results go away with it).
    var query by remember(initialQuery) { mutableStateOf(initialQuery) }
    var providerFilter by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<ManualSearchResult>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var pendingKey by remember { mutableStateOf<String?>(null) }
    var failedKey by remember { mutableStateOf<String?>(null) }
    var failedReason by remember { mutableStateOf<String?>(null) }
    var appliedKey by remember { mutableStateOf<String?>(null) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    val previewCache = remember { mutableStateMapOf<String, LyricsPreview>() }
    var expandedKey by remember { mutableStateOf<String?>(null) }
    var previewLoading by remember { mutableStateOf(false) }
    var previewError by remember { mutableStateOf<String?>(null) }
    // Lyrics or the provider's raw text (timestamps, word tags); one choice
    // for every row while the dialog is open.
    var rawPreview by remember { mutableStateOf(false) }
    var previewJob by remember { mutableStateOf<Job?>(null) }

    // Manual-entry sub-page. Keyed like [query]: a song change leaves the form.
    var manualMode by remember(initialQuery) { mutableStateOf(false) }
    var manualText by remember { mutableStateOf("") }
    var manualApplying by remember { mutableStateOf(false) }
    var manualError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // Prefill the editor with the current lyrics every time the form opens.
    LaunchedEffect(manualMode, initialQuery) {
        if (manualMode) {
            manualText = currentLyricsText() ?: ""
            manualError = null
        }
    }

    fun invalidateSearch() {
        searchJob?.cancel()
        previewJob?.cancel()
        previewCache.clear()
        expandedKey = null
        previewLoading = false
        previewError = null
        searching = false
        results = null
        appliedKey = null
        failedKey = null
    }

    fun submitSearch() {
        val q = query.trim()
        if (q.isEmpty()) return
        searchJob?.cancel()
        previewJob?.cancel()
        previewCache.clear()
        expandedKey = null
        appliedKey = null
        failedKey = null
        searching = true
        results = null
        searchJob = scope.launch {
            try {
                results = search(q, providerFilter)
            } finally {
                if (currentCoroutineContext().isActive) searching = false
            }
        }
    }

    fun togglePreview(rowKey: String, result: ManualSearchResult) {
        previewJob?.cancel()
        if (expandedKey == rowKey) {
            expandedKey = null
            previewLoading = false
            return
        }
        expandedKey = rowKey
        previewError = null
        previewLoading = !previewCache.containsKey(rowKey)
        if (!previewLoading) return

        previewJob = scope.launch {
            try {
                val raw = withContext(Dispatchers.IO) { result.previewText ?: result.fetch() }
                    ?.takeIf(String::isNotBlank) ?: error("No lyrics returned")
                val preview = withContext(Dispatchers.Default) {
                    val parsed = LyricsParser.parse(raw, result.durationMs)
                    val timing = when {
                        parsed.entries.any { !it.words.isNullOrEmpty() } -> LyricsTiming.WORD
                        parsed.synced -> LyricsTiming.LINE
                        else -> LyricsTiming.PLAIN
                    }
                    val lines = parsed.entries.map { it.text }.filter(String::isNotBlank)
                    LyricsPreview(raw, lines.joinToString("\n").ifBlank { raw }, timing)
                }
                previewCache[rowKey] = preview
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (expandedKey == rowKey) previewError = e.message ?: "Could not load lyrics"
            } finally {
                if (expandedKey == rowKey && currentCoroutineContext().isActive) previewLoading = false
            }
        }
    }

    val target = if (hasTarget) {
        "For: ${targetTitle.ifBlank { "Unknown" }} · ${targetArtist.ifBlank { "Unknown" }}"
    } else {
        "Nothing playing"
    }

    DialogFrame(
        title = if (manualMode) "Add lyrics manually" else "Search lyrics",
        subtitle = target,
        onClose = onClose,
        modifier = modifier,
        dragHandleModifier = dragHandleModifier,
        onBack = if (manualMode) {
            {
                manualMode = false
                manualError = null
            }
        } else {
            null
        },
    ) {
        if (manualMode) {
            ManualLyricsForm(
                hasTarget = hasTarget,
                text = manualText,
                onTextChange = { manualText = it },
                applying = manualApplying,
                error = manualError,
                onCancel = {
                    manualMode = false
                    manualError = null
                },
                onApply = {
                    manualApplying = true
                    manualError = null
                    scope.launch {
                        val error = onApplyManualText(manualText)
                        manualApplying = false
                        if (error == null) {
                            manualMode = false
                        } else {
                            manualError = error
                        }
                    }
                },
            )
            return@DialogFrame
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            AppTextField(
                value = query,
                onValueChange = {
                    query = it
                    invalidateSearch()
                },
                placeholder = "Title, artist, or anything",
                leadingIcon = AppIcons.Search,
                onSubmit = ::submitSearch,
                modifier = Modifier.weight(1f),
                fieldModifier = Modifier.focusRequester(focusRequester),
                trailing = {
                    if (query.isNotEmpty()) {
                        IconAction(
                            AppIcons.Close,
                            "Clear",
                            onClick = {
                                query = ""
                                invalidateSearch()
                                focusRequester.requestFocus()
                            },
                            size = 24.dp,
                            iconSize = 14.dp,
                        )
                    }
                },
            )
            Spacer(Modifier.width(6.dp))
            AppButton(
                "Search",
                onClick = ::submitSearch,
                kind = ButtonKind.PRIMARY,
                enabled = query.isNotBlank(),
                loading = searching,
            )
            Spacer(Modifier.width(2.dp))
            // Metrolist's search-online: the browser looks up "<query> lyrics".
            IconAction(
                AppIcons.OpenInNew,
                "Search the web for “$query lyrics”",
                onClick = { onSearchWeb(query) },
                enabled = query.isNotBlank(),
                size = 34.dp,
            )
        }

        Spacer(Modifier.height(10.dp))

        // Provider filter chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ChoiceChip(
                label = ManualSearch.ALL,
                selected = providerFilter == null,
                onClick = {
                    providerFilter = null
                    invalidateSearch()
                },
            )
            LyricsProviders.names.forEach { name ->
                ChoiceChip(
                    label = name,
                    selected = providerFilter == name,
                    color = providerColor(name),
                    leadingDot = providerColor(name),
                    onClick = {
                        providerFilter = name
                        invalidateSearch()
                    },
                )
            }
        }

        if (!hasTarget) {
            Spacer(Modifier.height(10.dp))
            InlineMessage("Play a song in any media player, then pick lyrics for it here.", MessageTone.INFO)
        }
        if (overrideApplied) {
            Spacer(Modifier.height(10.dp))
            InlineMessage("Using your manual pick for this song", MessageTone.ACCENT) {
                AppButton("Reset to auto", onClick = onClearOverride, kind = ButtonKind.GHOST, icon = AppIcons.Undo, compact = true)
            }
        }

        Spacer(Modifier.height(10.dp))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val list = results
            when {
                searching -> SearchPlaceholder("Searching providers…", busy = true)
                list != null && list.isEmpty() -> SearchPlaceholder(
                    "No results. Try fewer words, another provider, or add the lyrics yourself.",
                )
                list != null -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Index-prefixed keys: providers return same-titled
                    // rows (KuGou loves them), and a plain title key made
                    // LazyColumn throw "Key was already used".
                    itemsIndexed(
                        list,
                        key = { index, result -> "$index-${result.provider}-${result.lrclibId ?: result.title}" },
                    ) { index, result ->
                        val rowKey = "$index-${result.provider}-${result.lrclibId ?: result.title}"
                        SearchResultRow(
                            result = result,
                            timing = previewCache[rowKey]?.timing ?: result.timing
                                ?: if (result.synced == false) LyricsTiming.PLAIN else null,
                            preview = previewCache[rowKey],
                            rawPreview = rawPreview,
                            onRawPreviewChange = { rawPreview = it },
                            previewOpen = expandedKey == rowKey,
                            previewLoading = expandedKey == rowKey && previewLoading,
                            previewError = previewError.takeIf { expandedKey == rowKey },
                            pending = pendingKey == rowKey,
                            pickEnabled = hasTarget && pendingKey == null,
                            applied = appliedKey == rowKey,
                            failedReason = failedKey.takeIf { it == rowKey }?.let { failedReason },
                            onPreview = { togglePreview(rowKey, result) },
                            onUse = {
                                if (pendingKey == null) {
                                    pendingKey = rowKey
                                    failedKey = null
                                    failedReason = null
                                    appliedKey = null
                                    scope.launch {
                                        val selected = result.copy(
                                            previewText = previewCache[rowKey]?.rawText ?: result.previewText,
                                        )
                                        val error = onPick(selected)
                                        pendingKey = null
                                        if (error != null) {
                                            failedKey = rowKey
                                            failedReason = error
                                        } else {
                                            appliedKey = rowKey
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
                else -> SearchPlaceholder("Press Enter or Search to look up lyrics for this query.")
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppButton("Add lyrics manually", onClick = { manualMode = true }, icon = AppIcons.Edit, compact = true)
            Spacer(Modifier.weight(1f))
            results?.takeIf { it.isNotEmpty() && !searching }?.let { list ->
                Text(
                    if (list.size == 1) "1 result" else "${list.size} results",
                    style = AppType.caption,
                    color = palette.onSurfaceDim,
                )
            }
        }
    }
}

@Composable
private fun SearchPlaceholder(text: String, busy: Boolean = false) {
    val palette = LocalAppPalette.current
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = palette.accent)
        } else {
            Icon(AppIcons.MusicNote, contentDescription = null, tint = palette.onSurfaceFaint, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(text, style = AppType.caption, color = palette.onSurfaceDim, textAlign = TextAlign.Center)
    }
}

/**
 * The paste-in editor of the manual-entry page: multiline field prefilled
 * with the current lyrics, applies to the playing track on demand.
 */
@Composable
private fun ManualLyricsForm(
    hasTarget: Boolean,
    text: String,
    onTextChange: (String) -> Unit,
    applying: Boolean,
    error: String?,
    onCancel: () -> Unit,
    onApply: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        InlineMessage(
            if (hasTarget) {
                "Plain text or LRC with [mm:ss.xx] timestamps. Applies to the playing song until you reset it."
            } else {
                "Play a song in any media player, then apply lyrics to it here."
            },
            MessageTone.INFO,
        )
        Spacer(Modifier.height(10.dp))

        AppTextField(
            value = text,
            onValueChange = onTextChange,
            placeholder = "Paste lyrics here",
            singleLine = false,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )

        error?.let { message ->
            Spacer(Modifier.height(8.dp))
            InlineMessage(message, MessageTone.ERROR)
        }

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
        ) {
            AppButton("Cancel", onClick = onCancel, kind = ButtonKind.GHOST)
            AppButton(
                if (applying) "Applying…" else "Apply",
                onClick = onApply,
                kind = ButtonKind.PRIMARY,
                icon = AppIcons.Check,
                enabled = hasTarget && text.isNotBlank(),
                loading = applying,
            )
        }
    }
}

/**
 * One search hit. Clicking the row expands a lyrics preview; "Use" applies
 * it to the playing song, then shows Applied, or the failure reason with a
 * Retry button.
 */
@Composable
private fun SearchResultRow(
    result: ManualSearchResult,
    timing: LyricsTiming?,
    preview: LyricsPreview?,
    rawPreview: Boolean,
    onRawPreviewChange: (Boolean) -> Unit,
    previewOpen: Boolean,
    previewLoading: Boolean,
    previewError: String?,
    pending: Boolean,
    pickEnabled: Boolean,
    applied: Boolean,
    failedReason: String?,
    onPreview: () -> Unit,
    onUse: () -> Unit,
) {
    val palette = LocalAppPalette.current
    val shape = RoundedCornerShape(12.dp)
    val badge = providerColor(result.provider)
    val (source, hovered) = rememberHoverSource()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(
                when {
                    pending || applied -> palette.accent.copy(alpha = 0.10f)
                    hovered || previewOpen -> palette.cardHover
                    else -> palette.card
                },
            )
            .border(1.dp, if (applied) palette.accent.copy(alpha = 0.5f) else Color.Transparent, shape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(interactionSource = source, indication = null, onClick = onPreview)
                .padding(start = 12.dp, end = 8.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    result.title,
                    style = AppType.bodyStrong,
                    color = palette.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (result.artist.isNotBlank()) {
                    Text(
                        result.artist,
                        style = AppType.caption,
                        color = palette.onSurfaceDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        result.provider,
                        style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                        color = badge,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(badge.copy(alpha = 0.14f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    val timingColor = when (timing) {
                        LyricsTiming.WORD -> palette.accent
                        LyricsTiming.LINE -> palette.info
                        else -> palette.onSurfaceDim
                    }
                    Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(timingColor))
                    Spacer(Modifier.width(5.dp))
                    Text(
                        timing?.label() ?: "Timing unknown",
                        style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                        color = timingColor,
                        maxLines = 1,
                    )
                    result.durationMs?.let { ms ->
                        Text(
                            "  ·  %d:%02d".format(ms / 60_000, (ms % 60_000) / 1000),
                            style = TextStyle(fontSize = 10.sp),
                            color = palette.onSurfaceDim,
                            maxLines = 1,
                        )
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            Icon(
                if (previewOpen) AppIcons.ExpandLess else AppIcons.ExpandMore,
                contentDescription = if (previewOpen) "Hide preview" else "Show preview",
                tint = palette.onSurfaceDim,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            when {
                applied -> AppButton("Applied", onClick = onUse, icon = AppIcons.Check, kind = ButtonKind.GHOST, compact = true)
                failedReason != null -> AppButton("Retry", onClick = onUse, icon = AppIcons.Sync, enabled = pickEnabled, compact = true)
                else -> AppButton(
                    "Use",
                    onClick = onUse,
                    kind = ButtonKind.PRIMARY,
                    enabled = pickEnabled || pending,
                    loading = pending,
                    compact = true,
                )
            }
        }
        if (failedReason != null) {
            InlineMessage(
                "Couldn't load: $failedReason",
                MessageTone.ERROR,
                Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
            )
        }
        if (previewOpen) {
            when {
                previewLoading -> Row(
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = palette.accent)
                    Spacer(Modifier.width(8.dp))
                    Text("Loading lyrics…", style = AppType.caption, color = palette.onSurfaceDim)
                }
                previewError != null -> InlineMessage(
                    "Preview unavailable: $previewError",
                    MessageTone.ERROR,
                    Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                )
                preview != null -> Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp)) {
                    SegmentedControl(
                        options = listOf(false to "Lyrics", true to "Raw"),
                        selected = rawPreview,
                        onSelect = onRawPreviewChange,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 230.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(palette.surface.copy(alpha = 0.6f))
                            .verticalScroll(rememberScrollState())
                            .padding(10.dp),
                    ) {
                        SelectionContainer {
                            Text(
                                if (rawPreview) preview.rawText else preview.text,
                                style = if (rawPreview) {
                                    TextStyle(fontSize = 10.5.sp, lineHeight = 16.sp, fontFamily = FontFamily.Monospace)
                                } else {
                                    TextStyle(fontSize = 11.sp, lineHeight = 17.sp)
                                },
                                color = palette.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}
