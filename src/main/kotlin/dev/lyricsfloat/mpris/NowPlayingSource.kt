package dev.lyricsfloat.mpris

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Where the current song and playback clock come from: MPRIS over D-Bus on
 * Linux ([NowPlayingMonitor]), System Media Transport Controls on Windows
 * ([SmtcNowPlayingMonitor]).
 */
interface NowPlayingSource {
    val players: StateFlow<List<PlayerInfo>>
    val active: StateFlow<ActivePlayback?>

    /** Preferred player identity; null means "auto". */
    var preferredIdentity: String?

    fun start(scope: CoroutineScope)

    fun stop()
}
