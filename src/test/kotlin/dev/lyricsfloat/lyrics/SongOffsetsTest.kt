package dev.lyricsfloat.lyrics

import dev.lyricsfloat.mpris.TrackInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SongOffsetsTest {
    @Test
    fun storesClampsAndRestoresPerSongOffsets() {
        var stored: String? = null
        val offsets = SongOffsets(read = { stored }, write = { stored = it })
        val song = songKey(TrackInfo("", "Song (Remastered)", "Artist", null, null, 180_000))

        offsets.set(song, 1_250)
        offsets.set("Other|Track", 90_000)

        assertEquals(1_250, offsets.get(song))
        assertEquals(SongOffsets.MAX_MS, offsets.get("Other|Track"))
        assertEquals(0, offsets.get("Missing|Song"))
        assertEquals(0, offsets.get(null))

        val restored = SongOffsets(read = { stored }, write = { stored = it })
        assertEquals(1_250, restored.get(song))
    }

    @Test
    fun zeroRemovesTheEntry() {
        var stored: String? = null
        val offsets = SongOffsets(read = { stored }, write = { stored = it })
        offsets.set("A|B", -400)
        offsets.set("A|B", 0)

        assertNull(offsets.all.value["A|B"])
        assertEquals("{}", stored)
    }
}
