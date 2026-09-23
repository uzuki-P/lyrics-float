package dev.lyricsfloat.lyrics

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max

@Serializable
data class Track(
    val id: Long,
    val trackName: String,
    val artistName: String,
    val duration: Double,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
)

internal fun List<Track>.bestMatchingFor(durationMs: Long?, trackName: String?, artistName: String?): Track? {
    if (isEmpty()) return null
    return if (durationMs == null) {
        // No length reported (some players omit mpris:length): match by name.
        if (trackName != null && artistName != null) findBestMatch(trackName, artistName)
        else firstOrNull { it.syncedLyrics != null } ?: firstOrNull()
    } else {
        // Metrolist semantics: when the duration is known, only duration decides.
        // A name-identical track far off in duration is usually a different version
        // (live, remix), which shows visibly wrong lyrics.
        bestMatchingForDuration(durationMs, toleranceMs = 5_000)
    }
}

/** Duration must match within tolerance, preferring synced lyrics. */
private fun List<Track>.bestMatchingForDuration(durationMs: Long, toleranceMs: Long): Track? {
    val synced = filter { it.syncedLyrics != null }
        .minByOrNull { abs(it.durationMillis() - durationMs) }
        ?.takeIf { abs(it.durationMillis() - durationMs) <= toleranceMs }
    if (synced != null) return synced
    return minByOrNull { abs(it.durationMillis() - durationMs) }
        ?.takeIf { abs(it.durationMillis() - durationMs) <= toleranceMs }
}

private fun List<Track>.findBestMatch(trackName: String, artistName: String): Track? {
    val normalizedTrackName = trackName.trim().lowercase()
    val normalizedArtistName = artistName.trim().lowercase()
    return maxByOrNull { track ->
        var score = (similarity(normalizedTrackName, track.trackName.trim().lowercase()) +
            similarity(normalizedArtistName, track.artistName.trim().lowercase())) / 2.0
        if (track.syncedLyrics != null) score += 0.1
        score
    }?.takeIf { track ->
        (similarity(normalizedTrackName, track.trackName.trim().lowercase()) +
            similarity(normalizedArtistName, track.artistName.trim().lowercase())) / 2.0 > 0.6
    }
}

private fun similarity(str1: String, str2: String): Double {
    if (str1 == str2) return 1.0
    if (str1.isEmpty() || str2.isEmpty()) return 0.0
    val containsScore = if (str1.contains(str2) || str2.contains(str1)) 0.8 else 0.0
    val distanceScore = 1.0 - (levenshtein(str1, str2).toDouble() / max(str1.length, str2.length))
    return maxOf(containsScore, distanceScore)
}

private fun levenshtein(str1: String, str2: String): Int {
    val matrix = Array(str1.length + 1) { IntArray(str2.length + 1) }
    for (i in str1.indices) matrix[i + 1][0] = i + 1
    for (j in str2.indices) matrix[0][j + 1] = j + 1
    for (i in str1.indices) for (j in str2.indices) {
        val cost = if (str1[i] == str2[j]) 0 else 1
        matrix[i + 1][j + 1] = minOf(matrix[i][j + 1] + 1, matrix[i + 1][j] + 1, matrix[i][j] + cost)
    }
    return matrix[str1.length][str2.length]
}

fun Track.durationMillis(): Long = (duration * 1000).toLong()
