package dev.lyricsfloat.lyrics

/** One lyric line, timed in milliseconds like Metrolist's LyricsEntry. */
data class LyricsEntry(
    val time: Long,
    val text: String,
) : Comparable<LyricsEntry> {
    override fun compareTo(other: LyricsEntry): Int = time.compareTo(other.time)

    companion object {
        /** Blank pseudo-line shown before the first lyric. */
        val HEAD = LyricsEntry(0L, "")
    }
}

object LyricsParser {
    private val LINE_REGEX = Regex("""((\[\d\d:\d\d\.\d{2,3}\] ?)+)(.*)""")
    private val TIME_REGEX = Regex("""\[(\d\d):(\d\d)\.(\d{2,3})\]""")

    data class Parsed(val entries: List<LyricsEntry>, val synced: Boolean)

    /**
     * Parses standard LRC ("[mm:ss.xx] line", multiple timestamps per line
     * supported, 2- or 3-digit fractions) or falls back to unsynced plain lines
     * with evenly spread fake timestamps across [durationMs].
     */
    fun parse(text: String, durationMs: Long?): Parsed {
        val lines = text.trim().replace("\\n", "\n").lines()
        val syncedEntries = ArrayList<LyricsEntry>()
        val plainLines = ArrayList<String>()

        for (line in lines) {
            val match = LINE_REGEX.matchEntire(line.trim())
            if (match == null) {
                if (line.isNotBlank()) plainLines.add(line.trim())
                continue
            }
            val content = match.groupValues[3].trim()
            for (timeMatch in TIME_REGEX.findAll(match.groupValues[1])) {
                val minutes = timeMatch.groupValues[1].toLong()
                val seconds = timeMatch.groupValues[2].toLong()
                val fraction = timeMatch.groupValues[3]
                val millis = fraction.toLong() * (if (fraction.length == 2) 10L else 1L)
                syncedEntries.add(LyricsEntry(minutes * 60_000 + seconds * 1_000 + millis, content))
            }
        }

        if (syncedEntries.isNotEmpty()) {
            return Parsed(listOf(LyricsEntry.HEAD) + syncedEntries.distinct().sorted(), synced = true)
        }

        if (plainLines.isEmpty()) return Parsed(emptyList(), synced = false)
        val perLine = (durationMs ?: (plainLines.size * 3_500L)).coerceAtLeast(1) / plainLines.size
        val fake = plainLines.mapIndexed { index, text -> LyricsEntry(index * perLine, text) }
        return Parsed(listOf(LyricsEntry.HEAD) + fake, synced = false)
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
