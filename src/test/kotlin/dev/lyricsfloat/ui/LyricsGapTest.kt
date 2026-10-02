package dev.lyricsfloat.ui

import dev.lyricsfloat.lyrics.LyricsItem
import dev.lyricsfloat.lyrics.LyricsParser
import dev.lyricsfloat.lyrics.currentIndexAt
import dev.lyricsfloat.lyrics.withIntervalIndicators
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LyricsGapTest {
    private val entries = LyricsParser.parse(
        """
            [00:01.00]First line
            [00:43.73]Current line
            [00:49.39]
            [00:52.43]Next line
            [01:07.50]
            [01:09.86]Later line
            [01:22.92]
            [01:31.76]After long gap
            [01:35.24]
        """.trimIndent(),
        null,
    ).entries

    @Test
    fun shortBlankIntervalKeepsFocusBetweenSurroundingLines() {
        val items = entries.withIntervalIndicators()
        for ((position, expected) in listOf(
            43_730L to "Current line",
            49_390L to "",
            // Selection follows the existing 100 ms lyric lookahead.
            52_329L to "",
            52_330L to "Next line",
            52_430L to "Next line",
            67_500L to "",
            69_860L to "Later line",
        )) {
            val focus = focusLyricsItemIndex(items, entries.currentIndexAt(position), null)
            assertEquals(expected, assertIs<LyricsItem.Line>(items[focus]).entry.text, "At $position ms")
        }
    }

    @Test
    fun longBlankIntervalStillFocusesCircularIndicator() {
        val items = entries.withIntervalIndicators()
        val position = 85_000L
        val gap = items.filterIsInstance<LyricsItem.Indicator>()
            .single { position >= it.gapStartMs && position < it.gapEndMs }
        val focus = focusLyricsItemIndex(items, entries.currentIndexAt(position), gap)
        assertEquals(gap, items[focus])
    }

    @Test
    fun offsetMovesFocusIntoAndOutOfBlankInterval() {
        val items = entries.withIntervalIndicators()
        val position = 48_390L
        for ((offset, expected) in listOf(0L to "Current line", 1_000L to "", 4_040L to "Next line")) {
            val focus = focusLyricsItemIndex(items, entries.currentIndexAt(position, offset), null)
            assertEquals(expected, assertIs<LyricsItem.Line>(items[focus]).entry.text)
        }
    }

    @Test
    fun disablingIndicatorKeepsLongBlankIntervalAndOutroEmpty() {
        val items = entries.withIntervalIndicators(showIntervalIndicator = false)
        for (position in listOf(0L, 82_920L, 90_000L, 95_240L, 110_000L)) {
            val focus = focusLyricsItemIndex(items, entries.currentIndexAt(position), null)
            assertEquals("", assertIs<LyricsItem.Line>(items[focus]).entry.text, "At $position ms")
        }
    }
}
