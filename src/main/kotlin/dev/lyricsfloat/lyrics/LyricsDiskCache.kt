package dev.lyricsfloat.lyrics

import dev.lyricsfloat.mpris.TrackInfo
import java.io.File
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Raw provider lyrics survive restarts, including extended LRC word timings. */
internal class LyricsDiskCache(private val directory: File = defaultDirectory()) {
    @Serializable
    data class Entry(val title: String, val artist: String, val durationMs: Long?,
                     val text: String, val provider: String, val manual: Boolean)

    private val json = Json { ignoreUnknownKeys = true }

    fun read(track: TrackInfo): Entry? = runCatching {
        json.decodeFromString<Entry>(fileFor(track).readText())
            .takeIf { it.title == track.title && it.artist == track.artist && it.durationMs == track.lengthMs }
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
