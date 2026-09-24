package dev.lyricsfloat.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LyricsParserTest {
    @Test
    fun standardLrc() {
        val text = """
            [00:01.00]First line
            [00:04.00]Second line
        """.trimIndent()
        val parsed = LyricsParser.parse(text, durationMs = 100_000)
        assertTrue(parsed.synced)
        // HEAD + two lines
        assertEquals(3, parsed.entries.size)
        assertEquals("First line", parsed.entries[1].text)
        assertEquals(1_000, parsed.entries[1].time)
        assertNull(parsed.entries[1].words)
        assertNull(parsed.entries[1].agent)
        assertFalse(parsed.entries[1].isBackground)
    }

    @Test
    fun extendedLrcWithWordContinuationAndAgent() {
        val text = """
            [00:10.00]{agent:v1}Hello world
            <Hello:10.0:10.5|world:10.5:11.8>
            [00:12.00]{bg}echo
        """.trimIndent()
        val parsed = LyricsParser.parse(text, durationMs = 100_000)
        assertTrue(parsed.synced)
        val hello = parsed.entries.first { it.text == "Hello world" }
        assertEquals("v1", hello.agent)
        assertFalse(hello.isBackground)
        assertNotNull(hello.words)
        assertEquals(2, hello.words!!.size)
        assertEquals("Hello", hello.words!![0].text)
        assertEquals(10.0, hello.words!![0].startTime, 0.001)
        assertEquals("world", hello.words!![1].text)
        assertEquals(11.8, hello.words!![1].endTime, 0.001)
        val bg = parsed.entries.first { it.text == "echo" }
        assertTrue(bg.isBackground)
    }

    @Test
    fun richSyncInlineWords() {
        val text = "[00:05.000]<00:05.000>Na <00:05.500>na <00:06.000>na[00:06.500]"
        val parsed = LyricsParser.parse(text, durationMs = 100_000)
        assertTrue(parsed.synced)
        val line = parsed.entries.single { it.text.contains("Na") }
        assertEquals("Na na na", line.text)
        assertNotNull(line.words)
        assertEquals(3, line.words!!.size)
        assertEquals(5.0, line.words!![0].startTime, 0.001)
        assertEquals(6.5, line.words!!.last().endTime, 0.001)
        assertFalse(line.words!![2].hasTrailingSpace)
        assertTrue(line.words!![0].hasTrailingSpace)
    }

    @Test
    fun paxsenixAgentAndBgForms() {
        val text = """
            [00:00.000]v1: <00:00.000>I <00:00.154>promise
            [bg: <02:18.078>Yeah<02:19.341>]
        """.trimIndent()
        val parsed = LyricsParser.parse(text, durationMs = 200_000)
        assertTrue(parsed.synced)
        val main = parsed.entries.first { it.text == "I promise" }
        assertEquals("v1", main.agent)
        assertNotNull(main.words)
        val bg = parsed.entries.first { it.text == "Yeah" }
        assertTrue(bg.isBackground)
        assertEquals("v1", bg.agent)
    }

    @Test
    fun plainFallbackSpreadsFakeTimes() {
        val text = "just\nplain\nlines"
        val parsed = LyricsParser.parse(text, durationMs = 9_000)
        assertFalse(parsed.synced)
        // HEAD + three lines
        assertEquals(4, parsed.entries.size)
        assertEquals(0L, parsed.entries[1].time)
        assertEquals(3_000L, parsed.entries[2].time)
    }

    @Test
    fun creditLinesAndOffsetTagsAreIgnored() {
        val text = """
            [offset:0]
            [00:01.00]Real line
            [00:99.00]synced by someone
        """.trimIndent()
        val filtered = LyricsParser.filterCreditLines(text)
        assertTrue(filtered.contains("Real line"))
        assertFalse(filtered.contains("synced by"))
    }

    @Test
    fun htmlEntitiesDecoded() {
        val parsed = LyricsParser.parse("[00:01.00]A &amp; B &#65;", null)
        assertEquals("A & B A", parsed.entries[1].text)
    }

    @Test
    fun intervalIndicatorsInserted() {
        val entries = listOf(
            LyricsEntry(0, ""),
            LyricsEntry(1_000, "One", words = listOf(WordTimestamp("One", 1.0, 2.0))),
            LyricsEntry(10_000, "Two"),
        )
        val items = entries.withIntervalIndicators()
        // The blank head is omitted; its short gap has no indicator.
        assertEquals(3, items.size)
        val indicator = items.filterIsInstance<LyricsItem.Indicator>().single()
        assertEquals(2_000, indicator.gapStartMs)
        assertEquals(10_000, indicator.gapEndMs)
        assertEquals(1, indicator.afterLineIndex)
    }

    @Test
    fun intervalIndicatorMatchesMetrolistGapRules() {
        val plain = listOf(LyricsEntry(0, "Plain"), LyricsEntry(10_000, "Next"))
        assertTrue(plain.withIntervalIndicators().none { it is LyricsItem.Indicator })

        val exactThreshold = listOf(LyricsEntry(0, ""), LyricsEntry(4_000, "Next"))
        assertTrue(exactThreshold.withIntervalIndicators().none { it is LyricsItem.Indicator })

        val beforeBackground = listOf(LyricsEntry(0, ""), LyricsEntry(5_000, "Echo", isBackground = true))
        assertEquals(1, beforeBackground.withIntervalIndicators().filterIsInstance<LyricsItem.Indicator>().size)

        val disabled = beforeBackground.withIntervalIndicators(showIntervalIndicator = false)
        assertEquals(1, disabled.size)
        assertTrue(disabled.single() is LyricsItem.Line)
    }

    @Test
    fun activeLinesWithOverlap() {
        val text = """
            [00:01.00]{agent:v1}main
            <1.0:1.0|main:1.0:4.0>
            [00:02.00]{bg}echo
            <1.0:2.0|echo:2.0:3.0>
        """.trimIndent()
        val entries = LyricsParser.parse(text, null).entries
        val at2s = entries.findActiveLineIndices(2_000)
        // word timings exist, so both the main line and its bg echo are active
        assertTrue(entries.indices.any { entries[it].text == "main" && it in at2s })
        assertTrue(entries.indices.any { entries[it].text == "echo" && it in at2s })
    }

    @Test
    fun activeLinesWithoutWordsCollapseToLatest() {
        val text = """
            [00:01.00]Slow line
            [00:09.00]Next
        """.trimIndent()
        val entries = LyricsParser.parse(text, null)
        val at2s = entries.entries.findActiveLineIndices(2_000)
        assertEquals(setOf(1), at2s)
        val at95s = entries.entries.findActiveLineIndices(9_500)
        assertEquals(setOf(2), at95s)
    }

    @Test
    fun currentIndexRespectsOffset() {
        val entries = LyricsParser.parse("[00:05.00]Hi", null).entries
        // before anything: HEAD pseudo-entry is current
        assertEquals(0, entries.currentIndexAt(3_000))
        assertEquals(-1, entries.currentIndexAt(-150))
        assertEquals(1, entries.currentIndexAt(3_000, offsetMs = 3_000))
    }
}
