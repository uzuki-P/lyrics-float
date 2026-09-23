package dev.lyricsfloat.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LyricsParserTest {
    @Test
    fun parsesStandardLrc() {
        val lrc = """
            [00:12.34] First line
            [01:02.03] Second line
        """.trimIndent()
        val parsed = LyricsParser.parse(lrc, durationMs = 120_000)
        assertTrue(parsed.synced)
        assertEquals(3, parsed.entries.size) // HEAD + 2 lines
        assertEquals("First line", parsed.entries[1].text)
        assertEquals(12_340, parsed.entries[1].time)
        assertEquals("Second line", parsed.entries[2].text)
        assertEquals(62_030, parsed.entries[2].time)
    }

    @Test
    fun parsesThreeDigitFractions() {
        val parsed = LyricsParser.parse("[00:05.500] Hello", null)
        assertTrue(parsed.synced)
        assertEquals(5_500, parsed.entries[1].time)
    }

    @Test
    fun parsesMultipleTimestampsPerLine() {
        val parsed = LyricsParser.parse("[00:01.00][01:01.00] Chorus", null)
        assertEquals(3, parsed.entries.size)
        assertEquals("Chorus", parsed.entries[1].text)
        assertEquals("Chorus", parsed.entries[2].text)
        assertEquals(1_000, parsed.entries[1].time)
        assertEquals(61_000, parsed.entries[2].time)
    }

    @Test
    fun fallsBackToUnsyncedWithFakeTimes() {
        val text = "one\ntwo\nthree"
        val parsed = LyricsParser.parse(text, durationMs = 30_000)
        assertFalse(parsed.synced)
        assertEquals(4, parsed.entries.size) // HEAD + 3 lines
        assertEquals(0, parsed.entries[1].time)
        assertEquals(10_000, parsed.entries[2].time)
        assertEquals(20_000, parsed.entries[3].time)
    }

    @Test
    fun currentIndexFollowsTime() {
        val entries = listOf(
            LyricsEntry.HEAD,
            LyricsEntry(1_000, "a"),
            LyricsEntry(5_000, "b"),
            LyricsEntry(9_000, "c"),
        )
        // Index 0 is the blank HEAD line, shown before the first real line.
        assertEquals(0, entries.currentIndexAt(0))
        assertEquals(1, entries.currentIndexAt(1_000))
        // The 100 ms threshold selects the next line slightly early, as in Metrolist.
        assertEquals(2, entries.currentIndexAt(4_999))
        assertEquals(2, entries.currentIndexAt(5_000))
        assertEquals(3, entries.currentIndexAt(60_000))
    }

    @Test
    fun offsetShiftsDetection() {
        val entries = listOf(LyricsEntry.HEAD, LyricsEntry(5_000, "b"))
        // Positive offset shows lines earlier.
        assertEquals(1, entries.currentIndexAt(4_500, offsetMs = 1_000))
    }
}
