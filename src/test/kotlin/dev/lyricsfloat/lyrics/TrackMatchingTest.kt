package dev.lyricsfloat.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrackMatchingTest {
    private fun track(
        id: Long,
        name: String,
        artist: String,
        duration: Double,
        synced: Boolean = true,
    ) = Track(
        id = id,
        trackName = name,
        artistName = artist,
        duration = duration,
        plainLyrics = if (synced) null else "plain",
        syncedLyrics = if (synced) "[00:01.00] x" else null,
    )

    @Test
    fun prefersSyncedWithinTolerance() {
        val tracks = listOf(
            track(1, "Song", "Artist", 100.0, synced = false),
            track(2, "Song", "Artist", 101.0, synced = true),
        )
        val best = listOf(tracks[0], tracks[1]).bestMatchingFor(101_000, "Song", "Artist")
        assertEquals(2L, best?.id)
    }

    @Test
    fun rejectsDurationOutsideTolerance() {
        val tracks = listOf(track(1, "Song", "Artist", 130.0))
        assertNull(listOf(tracks[0]).bestMatchingFor(100_000, "Song", "Artist"))
    }

    @Test
    fun fallsBackToNameMatchWithoutDuration() {
        val tracks = listOf(
            track(1, "Totally Different", "Someone", 50.0),
            track(2, "Song", "Artist", 90.0),
        )
        val best = listOf(tracks[0], tracks[1]).bestMatchingFor(null, "Song", "Artist")
        assertEquals(2L, best?.id)
    }

    @Test
    fun titleCleaningStripsOfficialTags() {
        assertEquals("Song", LrcLib.cleanTitle("Song (Official Video)"))
        assertEquals("Song", LrcLib.cleanTitle("Song [HD Remaster]"))
        assertEquals("Song", LrcLib.cleanTitle("Song feat. Someone"))
        assertEquals("Song", LrcLib.cleanTitle("Song | From Album X"))
    }

    @Test
    fun artistCleaningKeepsPrimaryArtist() {
        assertEquals("A", LrcLib.cleanArtist("A & B"))
        assertEquals("A", LrcLib.cleanArtist("A feat. B"))
        assertEquals("A", LrcLib.cleanArtist("A x B"))
    }
}
