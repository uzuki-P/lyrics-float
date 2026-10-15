package dev.lyricsfloat.mpris

import kotlin.math.abs

private const val REBASE_TOLERANCE_US = 500_000L // 500 ms

/** Per-player state that survives between polls, so signals can rebase the position clock. */
internal class PlayerRuntime(val busName: String, val identity: String) {
    @Volatile var status: PlaybackStatus = PlaybackStatus.STOPPED
    @Volatile var rate: Double = 1.0
    @Volatile var track: TrackInfo? = null
    @Volatile private var basePositionUs: Long = 0
    @Volatile private var baseNs: Long = System.nanoTime()
    @Volatile private var stale: Boolean = false

    fun update(status: PlaybackStatus, rate: Double, positionUs: Long, track: TrackInfo?) {
        val statusChanged = status != this.status
        val rateChanged = rate != this.rate
        val trackChanged = trackIdOf(track) != trackIdOf(this.track)
        this.status = status
        this.rate = rate
        this.track = track
        val drifted = abs(positionUs - observedUs()) > REBASE_TOLERANCE_US
        // Re-base on any discontinuity; steady-state polls just let the clock run.
        if (statusChanged || rateChanged || trackChanged || stale || drifted ||
            status == PlaybackStatus.STOPPED
        ) {
            basePositionUs = if (status == PlaybackStatus.STOPPED) 0 else positionUs
            baseNs = System.nanoTime()
            stale = false
        }
    }

    fun seeked(positionUs: Long) {
        basePositionUs = positionUs
        baseNs = System.nanoTime()
        stale = false
    }

    fun markStale() {
        stale = true
    }

    private fun observedUs(): Long {
        if (status != PlaybackStatus.PLAYING) return basePositionUs
        val elapsedUs = (System.nanoTime() - baseNs) / 1000
        return basePositionUs + (elapsedUs * rate).toLong()
    }

    fun snapshot() = ActivePlayback(
        busName = busName,
        identity = identity,
        track = track,
        status = status,
        rate = rate,
        basePositionUs = basePositionUs,
        baseMonotonicNs = baseNs,
    )

    private fun trackIdOf(track: TrackInfo?): String = track?.trackId ?: ""
}
