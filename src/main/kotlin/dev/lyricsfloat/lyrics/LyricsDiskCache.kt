package dev.lyricsfloat.lyrics

import dev.lyricsfloat.mpris.TrackInfo
import java.io.File
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.abs
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Raw provider lyrics survive restarts, including extended LRC word timings. */
internal class LyricsDiskCache(private val directory: File = defaultDirectory()) {
    @Serializable
    data class Entry(val title: String, val artist: String, val durationMs: Long?,
                     val text: String, val provider: String, val manual: Boolean)

    private val json = Json { ignoreUnknownKeys = true }

    fun read(track: TrackInfo): Entry? {
        readEntry(fileFor(track))?.takeIf {
            it.title == track.title && it.artist == track.artist && it.durationMs == track.lengthMs
        }?.let { return it }

        // MPRIS players can omit the artist or disagree by a second on song
        // length. Reuse a matching title only when the available metadata
        // identifies one clear cache entry.
        val matches = directory.listFiles { file -> file.extension == "json" }.orEmpty()
            .mapNotNull(::readEntry)
            .filter { entry ->
                if (!entry.title.trim().equals(track.title.trim(), ignoreCase = true)) return@filter false
                val sameArtist = entry.artist.trim().equals(track.artist.trim(), ignoreCase = true)
                if (!sameArtist && entry.artist.isNotBlank() && track.artist.isNotBlank()) return@filter false
                val savedLength = entry.durationMs
                val currentLength = track.lengthMs
                if (savedLength != null && currentLength != null) {
                    abs(savedLength - currentLength) <= 5_000
                } else {
                    sameArtist && entry.artist.isNotBlank()
                }
            }
            .sortedWith(compareBy<Entry> {
                if (it.artist.trim().equals(track.artist.trim(), ignoreCase = true)) 0 else 1
            }.thenBy {
                if (it.durationMs != null && track.lengthMs != null) abs(it.durationMs - track.lengthMs) else Long.MAX_VALUE
            })
        fun artistPenalty(entry: Entry) =
            if (entry.artist.trim().equals(track.artist.trim(), ignoreCase = true)) 0 else 1
        fun lengthDifference(entry: Entry) =
            if (entry.durationMs != null && track.lengthMs != null) abs(entry.durationMs - track.lengthMs)
            else Long.MAX_VALUE

        val best = matches.firstOrNull() ?: return null
        val next = matches.getOrNull(1)
        if (next != null && artistPenalty(best) == artistPenalty(next) &&
            lengthDifference(best) == lengthDifference(next)) return null
        return best
    }

    private fun readEntry(file: File): Entry? = runCatching {
        json.decodeFromString<Entry>(file.readText())
    }.getOrNull()

    fun write(track: TrackInfo, text: String, provider: String, manual: Boolean) {
        if (text.isBlank()) return
        runCatching {
            directory.mkdirs()
            val target = fileFor(track)
            val temporary = File.createTempFile("lyrics-", ".tmp", directory)
            try {
                temporary.writeText(json.encodeToString(Entry(track.title, track.artist, track.lengthMs, text, provider, manual)))
                try {
                    Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE)
                } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                    Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                temporary.delete()
            }
        }.onFailure { System.err.println("Lyrics Float: could not save lyrics: ${it.message}") }
    }

    fun remove(track: TrackInfo) {
        runCatching { fileFor(track).delete() }
    }

    private fun fileFor(track: TrackInfo): File {
        val identity = "${track.title}\u0000${track.artist}\u0000${track.lengthMs ?: ""}"
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        return File(directory, digest.joinToString("") { "%02x".format(it) } + ".json")
    }

    companion object {
        private fun defaultDirectory(): File {
            val dataHome = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
                ?: (System.getProperty("user.home") + "/.local/share")
            return File(dataHome, "lyricsfloat/lyrics")
        }
    }
}
