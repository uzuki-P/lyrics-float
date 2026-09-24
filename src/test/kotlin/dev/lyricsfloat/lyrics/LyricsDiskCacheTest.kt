package dev.lyricsfloat.lyrics

import dev.lyricsfloat.mpris.TrackInfo
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LyricsDiskCacheTest {
    @Test
    fun preservesExtendedLrcAndKeepsVersionsSeparate() {
        val directory = Files.createTempDirectory("lyrics-float-test").toFile()
        try {
            val cache = LyricsDiskCache(directory)
            val song = TrackInfo("", "Song", "Artist", null, null, 180_000)
            val live = song.copy(lengthMs = 210_000)
            val lyrics = "[00:01.00]Hello\n<word:1000:1400|Hello>"
            cache.write(song, lyrics, "Lrclib", manual = true)

            assertEquals(lyrics, LyricsDiskCache(directory).read(song)?.text)
            assertEquals(true, cache.read(song)?.manual)
            assertNull(cache.read(live))
            cache.remove(song)
            assertNull(cache.read(song))
        } finally {
            directory.deleteRecursively()
        }
    }
}
