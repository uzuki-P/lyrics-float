package dev.lyricsfloat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lyricsfloat.lyrics.Track
import kotlinx.coroutines.delay

/**
 * Manual lyrics search. Picks are applied to the track that is currently
 * playing (via MPRIS), overriding the automatic LRCLIB match.
 */
@Composable
fun SearchView(
    targetTitle: String,
    targetArtist: String,
    hasTarget: Boolean,
    overrideApplied: Boolean,
    onPick: (Track) -> Unit,
    onClearOverride: () -> Unit,
    onClose: () -> Unit,
    search: suspend (String) -> List<Track>,
    modifier: Modifier = Modifier,
) {
    val palette = LocalAppPalette.current
    val shape = RoundedCornerShape(20.dp)
    val focusRequester = remember { FocusRequester() }

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Track>?>(null) }
    var searching by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) {
            results = null
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(350) // debounce
        results = search(q)
        searching = false
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
                    "Search lyrics",
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold),
                    color = palette.onSurface,
                    modifier = Modifier.weight(1f),
                )
                CloseButton(onClose, palette)
            }

            Spacer(Modifier.height(10.dp))

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
                    onValueChange = { query = it },
                    singleLine = true,
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
            }

            Spacer(Modifier.height(6.dp))

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
                        items(results.orEmpty(), key = { it.id }) { track ->
                            SearchResultRow(
                                track = track,
                                onClick = { onPick(track) },
                            )
                        }
                    }
                }
                else -> {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Type at least a few letters of the song title.",
                        style = TextStyle(fontSize = 12.sp),
                        color = palette.onSurfaceDim,
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(track: Track, onClick: () -> Unit) {
    val palette = LocalAppPalette.current
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick)
            .background(palette.onSurface.copy(alpha = 0.04f))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                track.trackName,
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                color = palette.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.artistName,
                style = TextStyle(fontSize = 11.sp),
                color = palette.onSurfaceDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        val minutes = (track.duration / 60).toInt()
        val seconds = (track.duration % 60).toInt()
        Text(
            "%d:%02d".format(minutes, seconds),
            style = TextStyle(fontSize = 11.sp),
            color = palette.onSurfaceDim,
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (track.syncedLyrics != null) palette.accent else palette.onSurfaceDim),
        )
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
