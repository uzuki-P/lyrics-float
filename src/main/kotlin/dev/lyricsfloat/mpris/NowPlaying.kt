package dev.lyricsfloat.mpris

enum class PlaybackStatus { PLAYING, PAUSED, STOPPED }

/** One track as reported by an MPRIS player's Metadata property. */
data class TrackInfo(
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val artUrl: String?,
    /** Track duration in milliseconds, or null when the player does not report it. */
    val lengthMs: Long?,
) {
    val hasContent: Boolean get() = title.isNotBlank()
}

/** A player visible on the session bus. */
data class PlayerInfo(
    val busName: String,
    val identity: String,
)

/**
 * Snapshot of the selected player. Position is interpolated from the last
 * observation, so [positionMs] can be read every frame without D-Bus traffic.
 */
data class ActivePlayback(
    val busName: String,
    val identity: String,
    val track: TrackInfo?,
    val status: PlaybackStatus,
    val rate: Double,
    private val basePositionUs: Long,
    private val baseMonotonicNs: Long,
) {
    val trackKey: String get() = "${track?.artist ?: ""}|${track?.title ?: ""}"

    fun positionMs(): Long {
        if (status != PlaybackStatus.PLAYING) return basePositionUs / 1000
        val elapsedUs = (System.nanoTime() - baseMonotonicNs) / 1000
        return ((basePositionUs + elapsedUs * rate).toLong()).coerceAtLeast(0) / 1000
    }

    /** True once the interpolated clock has run past the end of the track. */
    fun isPastEnd(): Boolean {
        val length = track?.lengthMs ?: return false
        return positionMs() > length + 1000
    }
}
