package dev.lyricsfloat.lyrics

import kotlinx.coroutines.flow.MutableStateFlow

/** Word-level timing, seconds; ported from Metrolist's LyricsEntry model. */
data class WordTimestamp(
    val text: String,
    val startTime: Double,
    val endTime: Double,
    val hasTrailingSpace: Boolean = true,
)

/**
 * One lyric line, timed in milliseconds like Metrolist's LyricsEntry. Carries
 * everything extended LRC can express: word timings, the singing agent
 * (v1/v2/v1000), and the background-vocal flag. Romanization lands lazily in
 * [romanizedTextFlow] so lines can update without reparsing.
 */
data class LyricsEntry(
    val time: Long,
    val text: String,
    val words: List<WordTimestamp>? = null,
    val agent: String? = null,
    val isBackground: Boolean = false,
    val romanizedTextFlow: MutableStateFlow<String?> = MutableStateFlow(null),
) : Comparable<LyricsEntry> {
    override fun compareTo(other: LyricsEntry): Int = time.compareTo(other.time)

    companion object {
        /** Blank pseudo-line shown before the first lyric. */
        val HEAD = LyricsEntry(0L, "")
    }
}

object LyricsParser {
    data class Parsed(val entries: List<LyricsEntry>, val synced: Boolean)

    private val LINE_REGEX = """((\[\d\d:\d\d\.\d{2,3}\] ?)+)(.*)""".toRegex()
    private val TIME_REGEX = """\[(\d\d):(\d\d)\.(\d{2,3})\]""".toRegex()

    // Rich sync: [MM:SS.mm]<MM:SS.mm> word <MM:SS.mm> word ... (inline words)
    private val RICH_SYNC_LINE_REGEX = """\[(\d{1,2}):(\d{2})\.(\d{2,3})\](.*)""".toRegex()
    private val RICH_SYNC_WORD_REGEX = """<(\d{1,2}):(\d{2})\.(\d{2,3})>([^<]+)""".toRegex()

    // Paxsenix forms: [00:00.000]v1: <00:00.000>I <00:00.154>promise...
    //                 [bg: <02:18.078>Yeah<02:19.341>]
    private val PAXSENIX_AGENT_LINE_REGEX = """\[(\d{1,2}):(\d{2})\.(\d{2,3})\](v\d+):\s*(.*)""".toRegex()
    private val PAXSENIX_BG_LINE_REGEX = """^\[bg:\s*(.*)\]$""".toRegex()

    // Agent / background markers of the canonical extended LRC format.
    private val AGENT_REGEX = """\{agent:([^}]+)\}""".toRegex()
    private val BACKGROUND_REGEX = """^\{bg\}""".toRegex()

    private val HEX_ENTITY_REGEX = """&#x([0-9a-fA-F]+);""".toRegex()
    private val DEC_ENTITY_REGEX = """&#(\d+);""".toRegex()

    /**
     * Parses extended LRC (words, agent, bg, Paxsenix variants), standard LRC,
     * or falls back to unsynced plain lines with evenly spread fake timestamps
     * across [durationMs]. Ported from Metrolist's LyricsUtils.parseLyrics.
     */
    fun parse(text: String, durationMs: Long?): Parsed {
        if (text.isBlank()) return Parsed(emptyList(), synced = false)

        // Fast unescape: some providers hand back a JSON-string-encoded blob.
        val unescaped = if (text.contains('\\') || text.startsWith("\"")) {
            val s = text.trim().removePrefix("\"").removeSuffix("\"")
            buildString(s.length) {
                var i = 0
                while (i < s.length) {
                    val c = s[i]
                    if (c == '\\' && i + 1 < s.length) {
                        when (val next = s[i + 1]) {
                            '\\' -> append('\\')
                            'n' -> append('\n')
                            'r' -> append('\r')
                            't' -> append('\t')
                            else -> append(c).append(next)
                        }
                        i += 2
                    } else {
                        append(c)
                        i++
                    }
                }
            }
        } else {
            text
        }

        val decoded = decodeHtmlEntities(unescaped)

        val lines = decoded.lines()
            .filter { it.isNotBlank() || it.trim().startsWith("[") || it.trim().startsWith("<") }
            .filter { !it.trim().startsWith("[offset:") }

        val isRichSync = lines.any { line ->
            RICH_SYNC_LINE_REGEX.matches(line.trim()) && RICH_SYNC_WORD_REGEX.containsMatchIn(line)
        }

        return if (isRichSync) {
            Parsed(listOf(LyricsEntry.HEAD) + parseRichSyncLyrics(lines), synced = true)
        } else {
            val (entries, synced) = parseStandardOrPlain(lines, durationMs)
            Parsed(listOf(LyricsEntry.HEAD) + entries, synced)
        }
    }

    /** Extended-LRC rich sync, including the Paxsenix line forms. */
    private fun parseRichSyncLyrics(lines: List<String>): List<LyricsEntry> {
        val result = mutableListOf<LyricsEntry>()
        var lastNonBgAgent: String? = null

        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()

            // [bg: <02:18.078>Yeah<02:19.341>]
            PAXSENIX_BG_LINE_REGEX.find(trimmed)?.let { bgMatch ->
                val content = bgMatch.groupValues[1]
                val (clean, squareEnd) = splitTrailingSquareEnd(content)
                val wordTimings = parseRichSyncWords(clean, index, lines, squareEnd)
                    ?: continuationWords(lines, index)
                val plainText = clean.replace(Regex("<\\d{1,2}:\\d{2}\\.\\d{2,3}>\\s*"), "").trim()
                val timeMs = wordTimings?.firstOrNull()?.startTime?.let { (it * 1000).toLong() } ?: 0L
                result.add(LyricsEntry(timeMs, plainText, wordTimings, agent = lastNonBgAgent ?: "bg", isBackground = true))
                return@forEachIndexed
            }

            // [00:00.000]v1: <00:00.000>I <00:00.154>promise...
            PAXSENIX_AGENT_LINE_REGEX.find(trimmed)?.let { agentMatch ->
                val timeMs = lrcTimeToMs(
                    agentMatch.groupValues[1],
                    agentMatch.groupValues[2],
                    agentMatch.groupValues[3],
                )
                val agent = agentMatch.groupValues[4]
                val content = agentMatch.groupValues[5]
                val (clean, squareEnd) = splitTrailingSquareEnd(content)
                val wordTimings = parseRichSyncWords(clean, index, lines, squareEnd)
                    ?: continuationWords(lines, index)
                val plainText = clean.replace(Regex("<\\d{1,2}:\\d{2}\\.\\d{2,3}>\\s*"), "").trim()
                if (agent.isNotBlank()) lastNonBgAgent = agent
                result.add(LyricsEntry(timeMs, plainText, wordTimings, agent = agent, isBackground = false))
                return@forEachIndexed
            }

            // [MM:SS.mm]{agent:v1}line / [MM:SS.mm]{bg}line, words inline
            RICH_SYNC_LINE_REGEX.matchEntire(trimmed)?.let { match ->
                val timeMs = lrcTimeToMs(match.groupValues[1], match.groupValues[2], match.groupValues[3])
                var content = match.groupValues[4].trimStart()

                val agentMatch = AGENT_REGEX.find(content)
                val agent = agentMatch?.groupValues?.get(1)
                if (agentMatch != null) content = content.replaceFirst(AGENT_REGEX, "")

                val isBackground = BACKGROUND_REGEX.containsMatchIn(content)
                if (isBackground) content = content.replaceFirst(BACKGROUND_REGEX, "")

                val (clean, squareEnd) = splitTrailingSquareEnd(content)
                val wordTimings = parseRichSyncWords(clean, index, lines, squareEnd)
                    ?: continuationWords(lines, index)
                val plainText = clean.replace(Regex("<\\d{1,2}:\\d{2}\\.\\d{2,3}>\\s*"), "").trim()

                if (!isBackground && !agent.isNullOrBlank()) lastNonBgAgent = agent
                result.add(
                    LyricsEntry(
                        timeMs, plainText, wordTimings,
                        agent = if (isBackground) lastNonBgAgent ?: "bg" else agent,
                        isBackground = isBackground,
                    ),
                )
            }
        }

        return result.sorted()
    }

    private val TRAILING_SQUARE_END_REGEX = Regex("""\[(\d{1,2}):(\d{2})\.(\d{2,3})\]\s*$""")

    /** Splits a trailing `[mm:ss.xx]` end timestamp off rich-sync content. */
    private fun splitTrailingSquareEnd(content: String): Pair<String, Double?> {
        val end = TRAILING_SQUARE_END_REGEX.find(content) ?: return content to null
        return content.substring(0, end.range.first) to end.groupValues.run { lrcSeconds(get(1), get(2), get(3)) }
    }

    /** Word timings on the following standalone `<word:start:end|...>` line. */
    private fun continuationWords(lines: List<String>, index: Int): List<WordTimestamp>? {
        val next = lines.getOrNull(index + 1)?.trim() ?: return null
        if (!next.startsWith("<") || !next.endsWith(">")) return null
        return parseWordTimestamps(next.removeSurrounding("<", ">"))
    }

    /**
     * Inline `<MM:SS.mm> word` groups; the last group may end with a trailing
     * end timestamp in angle or square brackets. Groups split into words with
     * evenly distributed times. Ported from Metrolist.
     */
    private fun parseRichSyncWords(
        content: String,
        currentIndex: Int,
        allLines: List<String>,
        squareEndTime: Double? = null,
    ): List<WordTimestamp>? {
        val wordMatches = RICH_SYNC_WORD_REGEX.findAll(content).toList()
        if (wordMatches.isEmpty()) return null

        // Optional angle-bracket end timestamp after the last word.
        val lastMatchEnd = wordMatches.last().range.last
        val trailingContent = content.substring(lastMatchEnd + 1).trim()
        val angleTrailing = """<(\d{1,2}):(\d{2})\.(\d{2,3})>""".toRegex().find(trailingContent)
        val trailingMatch = angleTrailing
        val trailingEndTime: Double? = trailingMatch?.let {
            it.groupValues.run { lrcSeconds(get(1), get(2), get(3)) }
        } ?: squareEndTime

        val timings = mutableListOf<WordTimestamp>()

        wordMatches.forEachIndexed { index, match ->
            val startTimeSeconds = match.groupValues.run { lrcSeconds(get(1), get(2), get(3)) }
            val rawText = match.groupValues[4]
            val hasTrailingSpace = rawText.endsWith(" ")
            val words = rawText.trim().split(Regex("\\s+")).filter { it.isNotBlank() }

            val nextTimestamp: Double
            val nextLineTime: Double?
            if (index < wordMatches.size - 1) {
                nextTimestamp = wordMatches[index + 1].groupValues.run { lrcSeconds(get(1), get(2), get(3)) }
                nextLineTime = null
            } else {
                nextLineTime = getNextLineStartTime(currentIndex, allLines)
                nextTimestamp = trailingEndTime ?: nextLineTime ?: (startTimeSeconds + 0.5)
            }

            words.forEachIndexed { wordIndex, word ->
                val isLastInGroup = wordIndex == words.lastIndex
                val isLastOverall = index == wordMatches.lastIndex && isLastInGroup

                val wordStart = startTimeSeconds + (nextTimestamp - startTimeSeconds) * wordIndex / words.size
                val wordEnd = when {
                    !isLastInGroup -> startTimeSeconds + (nextTimestamp - startTimeSeconds) * (wordIndex + 1) / words.size
                    !isLastOverall -> nextTimestamp
                    else -> trailingEndTime ?: nextLineTime ?: (startTimeSeconds + 0.5)
                }
                val wordTrailingSpace = when {
                    !isLastInGroup -> true
                    !isLastOverall -> hasTrailingSpace
                    else -> {
                        val textAfter = if (trailingMatch != null) {
                            trailingContent.substring(0, trailingMatch.range.first)
                        } else {
                            trailingContent
                        }
                        textAfter.isNotBlank()
                    }
                }
                if (word.isNotBlank()) {
                    timings.add(WordTimestamp(word, wordStart, wordEnd, wordTrailingSpace))
                }
            }
        }

        return timings.ifEmpty { null }
    }

    /** Start time of the next non-background line; ends the last word. */
    private fun getNextLineStartTime(currentIndex: Int, allLines: List<String>): Double? {
        // Background vocals overlap the line they accompany, so skip them (and
        // their timing continuation lines) when looking for the next start.
        var next = currentIndex + 1
        while (next < allLines.size && isBackgroundLine(allLines[next].trim())) {
            next++
            if (next < allLines.size && isTimingContinuationLine(allLines[next].trim())) next++
        }
        if (next >= allLines.size) return null
        val match = RICH_SYNC_LINE_REGEX.matchEntire(allLines[next].trim()) ?: return null
        return match.groupValues.run { lrcSeconds(get(1), get(2), get(3)) }
    }

    private fun isBackgroundLine(line: String): Boolean {
        if (PAXSENIX_BG_LINE_REGEX.matches(line)) return true
        val content = RICH_SYNC_LINE_REGEX.matchEntire(line)?.groupValues?.get(4) ?: return false
        return BACKGROUND_REGEX.containsMatchIn(content.trim())
    }

    private fun isTimingContinuationLine(line: String): Boolean =
        line.startsWith("<") && line.endsWith(">")

    /** Standard `[mm:ss.xx] text` LRC; falls back to plain unsynced lines. */
    private fun parseStandardOrPlain(lines: List<String>, durationMs: Long?): Pair<List<LyricsEntry>, Boolean> {
        val entries = mutableListOf<LyricsEntry>()

        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (!line.startsWith("<") || !line.endsWith(">")) {
                LINE_REGEX.matchEntire(line)?.let { match ->
                    val times = match.groupValues[1]
                    var text = match.groupValues[3]

                    val agentMatch = AGENT_REGEX.find(text)
                    val agent = agentMatch?.groupValues?.get(1)
                    if (agentMatch != null) text = text.replaceFirst(AGENT_REGEX, "")

                    val isBackground = BACKGROUND_REGEX.containsMatchIn(text)
                    if (isBackground) text = text.replaceFirst(BACKGROUND_REGEX, "")

                    // A standalone <word:start:end|...> line right after
                    // carries this line's word timings.
                    var words: List<WordTimestamp>? = null
                    val next = lines.getOrNull(i + 1)?.trim()
                    if (next != null && next.startsWith("<") && next.endsWith(">")) {
                        words = parseWordTimestamps(next.removeSurrounding("<", ">"))
                        if (words != null) i++
                    }

                    for (timeMatch in TIME_REGEX.findAll(times)) {
                        val ms = timeMatch.groupValues.run { lrcTimeToMs(get(1), get(2), get(3)) }
                        entries.add(LyricsEntry(ms, text.trim(), words, agent = agent, isBackground = isBackground))
                    }
                }
            }
            i++
        }

        if (entries.isNotEmpty()) {
            return entries.distinctBy { Triple(it.time, it.text, it.isBackground) }.sorted() to true
        }

        val plain = lines.filter { it.isNotBlank() }.map { it.trim() }
        if (plain.isEmpty()) return emptyList<LyricsEntry>() to false
        val perLine = (durationMs ?: (plain.size * 3_500L)).coerceAtLeast(1) / plain.size
        val fake = plain.mapIndexed { index, text -> LyricsEntry(index * perLine, text) }
        return fake to false
    }

    /** `<word:startSec:endSec|word:startSec:endSec>` continuation block. */
    private fun parseWordTimestamps(data: String): List<WordTimestamp>? {
        if (data.isBlank()) return null
        return data.split("|").mapNotNull { wordData ->
            val parts = wordData.split(":")
            if (parts.size >= 3) {
                WordTimestamp(
                    text = parts.dropLast(2).joinToString(":"),
                    startTime = parts[parts.size - 2].toDoubleOrNull() ?: 0.0,
                    endTime = parts[parts.size - 1].toDoubleOrNull() ?: 0.0,
                    hasTrailingSpace = wordData != data.split("|").last(),
                )
            } else {
                null
            }
        }.takeIf { it.isNotEmpty() }
    }

    /** Drops "synced by ..." style credit lines providers like to prepend. */
    fun filterCreditLines(lyrics: String): String = lyrics.lines().filter { line ->
        var text = line.trim()
        var stripping = true
        while (stripping) {
            val before = text.length
            text = text
                .replaceFirst(Regex("""^\[\d\d:\d\d\.\d{2,3}\]"""), "")
                .replaceFirst(Regex("""^\{agent:[^}]+\}"""), "")
                .replaceFirst(Regex("""^\{bg\}"""), "")
                .replaceFirst(Regex("""^\[bg:.*\]"""), "")
                .replaceFirst(Regex("""^v\d+:"""), "")
                .trim()
            stripping = text.length < before
        }
        val lower = text.lowercase()
        val isCredit = lower.startsWith("synced by") ||
            lower.startsWith("lyrics by") ||
            lower.startsWith("music by") ||
            lower.startsWith("arranged by")
        !isCredit
    }.joinToString("\n")

    private fun decodeHtmlEntities(text: String): String {
        if (!text.contains('&')) return text
        return text
            .replace("&apos;", "'")
            .replace("&quot;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .replace(HEX_ENTITY_REGEX) { m ->
                m.groupValues[1].toIntOrNull(16)?.takeIf { Character.isValidCodePoint(it) }
                    ?.let { String(Character.toChars(it)) } ?: m.value
            }
            .replace(DEC_ENTITY_REGEX) { m ->
                m.groupValues[1].toIntOrNull()?.takeIf { Character.isValidCodePoint(it) }
                    ?.let { String(Character.toChars(it)) } ?: m.value
            }
            .replace("&amp;", "&")
    }

    private fun lrcTimeToMs(minutes: String, seconds: String, fraction: String): Long {
        val frac = fraction.toLongOrNull() ?: 0L
        val millis = frac * (if (fraction.length == 2) 10L else 1L)
        return (minutes.toLongOrNull() ?: 0L) * 60_000 + (seconds.toLongOrNull() ?: 0L) * 1_000 + millis
    }

    private fun lrcSeconds(minutes: String, seconds: String, fraction: String): Double {
        val frac = (fraction.toLongOrNull() ?: 0L).toDouble()
        val part = if (fraction.length == 3) frac / 1000.0 else frac / 100.0
        return (minutes.toLongOrNull() ?: 0L) * 60.0 + (seconds.toLongOrNull() ?: 0L) + part
    }
}

/** Last line whose start time is at or before [positionMs] (Metrolist's +100 ms threshold). */
fun List<LyricsEntry>.currentIndexAt(positionMs: Long, offsetMs: Long = 0): Int {
    if (isEmpty()) return -1
    var index = -1
    for (i in indices) {
        if (this[i].time <= positionMs + offsetMs + 100) index = i else break
    }
    return index
}

/**
 * Indices of lines currently being sung. With word timings lines can overlap
 * (duets, background vocals); without them only the latest main line counts.
 * Ported from Metrolist's findActiveLineIndices.
 */
fun List<LyricsEntry>.findActiveLineIndices(positionMs: Long, offsetMs: Long = 0): Set<Int> {
    val active = mutableSetOf<Int>()
    val hasWordTimings = any { !it.words.isNullOrEmpty() }
    val pos = positionMs + offsetMs

    for (index in indices) {
        val line = this[index]
        if (line.time > pos) break

        val lineEndMs = if (!line.words.isNullOrEmpty()) {
            (line.words.last().endTime * 1000).toLong()
        } else {
            if (index + 1 < size) this[index + 1].time else Long.MAX_VALUE
        }

        if (pos <= lineEndMs) active.add(index)
    }

    if (!hasWordTimings && active.size > 1) {
        val mainActive = active.filter { !this[it].isBackground }
        if (mainActive.size > 1) {
            val maxTime = mainActive.maxOf { this[it].time }
            active.removeAll { it in mainActive && this[it].time < maxTime }
        }
    }

    return active
}
