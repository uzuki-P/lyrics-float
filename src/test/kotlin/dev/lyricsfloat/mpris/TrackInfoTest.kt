package dev.lyricsfloat.mpris

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackInfoTest {
    @Test
    fun titleWithoutArtistIsAUsableTrack() {
        val track = TrackInfo("/track/1", "Song title", "", null, null, null)
        assertTrue(track.hasContent)
    }

    @Test
    fun missingTitleIsNotAUsableTrack() {
        val track = TrackInfo("/track/1", "", "Artist", null, null, null)
        assertFalse(track.hasContent)
    }
}
