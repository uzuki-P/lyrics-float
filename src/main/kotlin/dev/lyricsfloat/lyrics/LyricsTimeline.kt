package dev.lyricsfloat.lyrics

/**
 * Render items over the parsed entries: plain lines plus interval indicators
 * inserted into long instrumental gaps (Metrolist merges these into its
 * lyrics list when a gap exceeds 4 s).
 */
sealed interface LyricsItem {
    data class Line(val index: Int, val entry: LyricsEntry) : LyricsItem

    data class Indicator(
        /** Index into the entries list of the line before the gap. */
        val afterLineIndex: Int,
        val gapStartMs: Long,
        val gapEndMs: Long,
        /** Agent of the first line after the gap, for positioning the dots. */
        val nextAgent: String? = null,
    ) : LyricsItem
}

/** A gap must be longer than this to get an indicator. */
const val INTERVAL_INDICATOR_GAP_MS = 4_000L

/**
 * Builds the same line/indicator sequence as Metrolist's updateMergedList.
 * A nonblank line without word timings has no known end, so it cannot start
 * an indicator. Blank timed lines and word-timed lines have known ends.
 */
fun List<LyricsEntry>.withIntervalIndicators(
    showIntervalIndicator: Boolean = true,
    minGapMs: Long = INTERVAL_INDICATOR_GAP_MS,
): List<LyricsItem> {
    if (isEmpty()) return emptyList()
    val items = mutableListOf<LyricsItem>()
    for (i in indices) {
        val entry = this[i]
        if (entry.text.isNotBlank()) items.add(LyricsItem.Line(i, entry))
        if (!showIntervalIndicator || i == lastIndex) continue

        val next = this[i + 1]
        val end = when {
            !entry.words.isNullOrEmpty() -> (entry.words.last().endTime * 1000).toLong()
            entry.text.isBlank() -> entry.time
            else -> null
        }
        if (end != null && end < next.time && next.time - end > minGapMs) {
            items.add(
                LyricsItem.Indicator(
                    afterLineIndex = i,
                    gapStartMs = end,
                    gapEndMs = next.time,
                    nextAgent = next.agent,
                ),
            )
        }
    }
    return items
}

/**
 * Fake word timings for synced lines without word-level data, so every
 * synced line still karaoke-animates. Metrolist starts words 0.03 s apart
 * and fades each one in over 0.18 s.
 */
fun synthesizeWords(text: String, lineStartMs: Long): List<WordTimestamp> {
    val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (tokens.isEmpty()) return emptyList()
    val startSec = lineStartMs / 1000.0
    return tokens.mapIndexed { i, token ->
        val start = startSec + i * 0.03
        WordTimestamp(text = token, startTime = start, endTime = start + 0.18)
    }
}
