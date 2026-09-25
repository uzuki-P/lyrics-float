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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lyricsfloat.lyrics.LyricsProviders
import dev.lyricsfloat.lyrics.ManualSearch
import dev.lyricsfloat.lyrics.ManualSearchResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private fun providerColor(name: String): Color = when (name) {
    "Lrclib" -> Color(0xFF7DD3FC)
    "KuGou" -> Color(0xFFFCA5A5)
    "BetterLyrics" -> Color(0xFFFDE68A)
    "Paxsenix" -> Color(0xFFC4B5FD)
    "LyricsPlus" -> Color(0xFF86EFAC)
    else -> Color(0xFFCBD5E1)
}

/**
 * Manual lyrics search across providers. Opens pre-filled with the current
 * song. Picks are applied to the track that is currently playing (via MPRIS),
 * overriding the automatic match; the picked row shows an Applying spinner and
 * a failure mark when the fetch did not load (network). Results carry a
 * provider badge; a chip row filters which providers are queried. "Web"
 * searches the browser for `<query> lyrics` (Metrolist's search-online
 * action), and "Add lyrics manually" opens a paste-in editor (plain text or
 * LRC) that applies to the playing track as a Manual override.
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
    modifier: Modifier = Modifier,
) {
    val palette = LocalAppPalette.current
    val shape = RoundedCornerShape(20.dp)
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
    var searchJob by remember { mutableStateOf<Job?>(null) }

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
        searching = false
        results = null
    }

    fun submitSearch() {
        val q = query.trim()
        if (q.isEmpty()) return
        searchJob?.cancel()
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(10.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(palette.surface)
                .padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (manualMode) "Add lyrics manually" else "Search lyrics",
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold),
                    color = palette.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (manualMode) {
                    Text(
                        "Back",
                        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                        color = palette.onSurfaceDim,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                manualMode = false
                                manualError = null
                            }
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                CloseButton(onClose, palette)
            }

            Spacer(Modifier.height(10.dp))

            if (manualMode) {
                ManualLyricsForm(
                    targetTitle = targetTitle,
                    targetArtist = targetArtist,
                    hasTarget = hasTarget,
                    text = manualText,
                    onTextChange = { manualText = it },
                    applying = manualApplying,
                    error = manualError,
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
            } else {
                // Search field
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(palette.onSurface.copy(alpha = 0.06f))
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value = query,
                        onValueChange = {
                            query = it
                            invalidateSearch()
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submitSearch() }),
                        textStyle = TextStyle(fontSize = 13.sp, color = palette.onSurface),
                        cursorBrush = SolidColor(palette.accent),
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
                        decorationBox = { inner ->
                            Box {
                                if (query.isEmpty()) {
                                    Text(
                                        "Title, artist, or anything",
                                        style = TextStyle(fontSize = 13.sp),
                                        color = palette.onSurfaceDim,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(palette.accent.copy(alpha = if (query.isBlank()) 0.35f else 0.22f))
                            .clickable(enabled = query.isNotBlank()) { submitSearch() }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text("Search", style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold), color = palette.onSurface)
                    }
                    Spacer(Modifier.width(6.dp))
                    // Metrolist's search-online: the browser looks up "<query> lyrics".
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(palette.onSurface.copy(alpha = if (query.isBlank()) 0.04f else 0.06f))
                            .clickable(enabled = query.isNotBlank()) { onSearchWeb(query) }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text("Web", style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold), color = palette.onSurface)
                    }
                }

                Spacer(Modifier.height(8.dp))

                // Provider filter chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ProviderChip(
                        label = ManualSearch.ALL,
                        color = palette.accent,
                        selected = providerFilter == null,
                        palette = palette,
                    ) {
                        providerFilter = null
                        invalidateSearch()
                    }
                    LyricsProviders.names.forEach { name ->
                        ProviderChip(
                            label = name,
                            color = providerColor(name),
                            selected = providerFilter == name,
                            palette = palette,
                        ) {
                            providerFilter = name
                            invalidateSearch()
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    "Dot: pink = synced lyrics, gray = plain text.",
                    style = TextStyle(fontSize = 10.sp),
                    color = palette.onSurfaceDim,
                )
                Spacer(Modifier.height(2.dp))

                if (hasTarget) {
                    Text(
                        text = "Applies to: ${targetTitle.ifBlank { "Unknown" }} — ${targetArtist.ifBlank { "Unknown" }}",
                        style = TextStyle(fontSize = 11.sp),
                        color = palette.onSurfaceDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        text = "Play a song in any media player, then pick lyrics for it here.",
                        style = TextStyle(fontSize = 11.sp),
                        color = palette.onSurfaceDim,
                    )
                }

                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(palette.onSurface.copy(alpha = 0.06f))
                        .clickable { manualMode = true }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(
                        "Add lyrics manually",
                        style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                        color = palette.onSurfaceDim,
                    )
                }

                Spacer(Modifier.height(8.dp))

                if (overrideApplied) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(palette.accent.copy(alpha = 0.12f))
                            .clickable(onClick = onClearOverride)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Using your manual pick",
                            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                            color = palette.accent,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "Reset to auto",
                            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                            color = palette.onSurfaceDim,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }

                when {
                    searching -> {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Searching…",
                            style = TextStyle(fontSize = 12.sp),
                            color = palette.onSurfaceDim,
                        )
                    }
                    results != null && results!!.isEmpty() -> {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "No results",
                            style = TextStyle(fontSize = 12.sp),
                            color = palette.onSurfaceDim,
                        )
                    }
                    results != null -> {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            // Index-prefixed keys: providers return same-titled
                            // rows (KuGou loves them), and a plain title key made
                            // LazyColumn throw "Key was already used".
                            itemsIndexed(
                                results.orEmpty(),
                                key = { index, result -> "$index-${result.provider}-${result.lrclibId ?: result.title}" },
                            ) { index, result ->
                                val rowKey = "$index-${result.provider}-${result.lrclibId ?: result.title}"
                                SearchResultRow(
                                    result = result,
                                    pending = pendingKey == rowKey,
                                    failedReason = failedKey.takeIf { it == rowKey }?.let { failedReason },
                                    onClick = {
                                        if (pendingKey == null) {
                                            pendingKey = rowKey
                                            failedKey = null
                                            failedReason = null
                                            scope.launch {
                                                val error = onPick(result)
                                                pendingKey = null
                                                if (error != null) {
                                                    failedKey = rowKey
                                                    failedReason = error
                                                }
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                    else -> {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Enter a song title, then press Enter or Search.",
                            style = TextStyle(fontSize = 12.sp),
                            color = palette.onSurfaceDim,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The paste-in editor of the manual-entry page: multiline field prefilled
 * with the current lyrics, applies to the playing track on demand.
 */
@Composable
private fun ManualLyricsForm(
    targetTitle: String,
    targetArtist: String,
    hasTarget: Boolean,
    text: String,
    onTextChange: (String) -> Unit,
    applying: Boolean,
    error: String?,
    onApply: () -> Unit,
) {
    val palette = LocalAppPalette.current
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = if (hasTarget) {
                "Applies to: ${targetTitle.ifBlank { "Unknown" }} — ${targetArtist.ifBlank { "Unknown" }}"
            } else {
                "Play a song in any media player, then apply lyrics to it here."
            },
            style = TextStyle(fontSize = 11.sp),
            color = palette.onSurfaceDim,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(palette.onSurface.copy(alpha = 0.06f))
                .padding(10.dp),
        ) {
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                textStyle = TextStyle(fontSize = 12.sp, color = palette.onSurface),
                cursorBrush = SolidColor(palette.accent),
                modifier = Modifier.fillMaxSize(),
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) {
                            Text(
                                "Paste lyrics here — plain text or LRC with [mm:ss.xx] timestamps.",
                                style = TextStyle(fontSize = 12.sp),
                                color = palette.onSurfaceDim,
                            )
                        }
                        inner()
                    }
                },
            )
        }

        error?.let { message ->
            Spacer(Modifier.height(6.dp))
            Text(
                message,
                style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                color = Color(0xFFF87171),
            )
        }

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            val applyEnabled = hasTarget && text.isNotBlank() && !applying
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.accent.copy(alpha = if (applyEnabled) 0.22f else 0.10f))
                    .clickable(enabled = applyEnabled, onClick = onApply)
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text(
                    if (applying) "Applying…" else "Apply",
                    style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold),
                    color = palette.onSurface,
                )
            }
        }
    }
}

@Composable
private fun ProviderChip(
    label: String,
    color: Color,
    selected: Boolean,
    palette: AppPalette,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    selected -> color.copy(alpha = 0.22f)
                    else -> palette.onSurface.copy(alpha = 0.06f)
                },
            )
            .then(
                if (selected) Modifier.border(1.dp, color.copy(alpha = 0.7f), RoundedCornerShape(8.dp)) else Modifier,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            label,
            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
            color = if (selected) color else palette.onSurfaceDim,
        )
    }
}

@Composable
private fun SearchResultRow(
    result: ManualSearchResult,
    pending: Boolean,
    failedReason: String?,
    onClick: () -> Unit,
) {
    val palette = LocalAppPalette.current
    val shape = RoundedCornerShape(10.dp)
    val badge = providerColor(result.provider)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick)
            .background(
                when {
                    pending -> palette.accent.copy(alpha = 0.10f)
                    else -> palette.onSurface.copy(alpha = 0.04f)
                },
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Provider badge
        Text(
            result.provider,
            style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold),
            color = badge,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(badge.copy(alpha = 0.14f))
                .padding(horizontal = 6.dp, vertical = 3.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                result.title,
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                color = palette.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (result.artist.isNotBlank()) {
                Text(
                    result.artist,
                    style = TextStyle(fontSize = 11.sp),
                    color = palette.onSurfaceDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (failedReason != null) {
                Text(
                    "Couldn't load: $failedReason — click to retry",
                    style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                    color = Color(0xFFF87171),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        result.durationMs?.takeIf { failedReason == null }?.let { ms ->
            Spacer(Modifier.width(8.dp))
            Text(
                "%d:%02d".format(ms / 60_000, (ms % 60_000) / 1000),
                style = TextStyle(fontSize = 11.sp),
                color = palette.onSurfaceDim,
            )
        }
        when {
            pending -> {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    strokeWidth = 1.5.dp,
                    color = palette.accent,
                )
            }
            result.synced != null && failedReason == null -> {
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (result.synced) palette.accent else palette.onSurfaceDim.copy(alpha = 0.4f)),
                )
            }
        }
    }
}

@Composable
private fun CloseButton(onClose: () -> Unit, palette: AppPalette) {
    Text(
        "×",
        style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold),
        color = palette.onSurfaceDim,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClose)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
