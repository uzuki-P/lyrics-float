package dev.lyricsfloat.mpris

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/** MPRIS Seeked signal, so lyric sync reacts to seeking without waiting for the next poll. */
@DBusInterfaceName("org.mpris.MediaPlayer2.Player")
interface MprisPlayer : DBusInterface {
    class Seeked : DBusSignal {
        val position: Long

        constructor(objectPath: String, position: Long) : super(objectPath, position) {
            this.position = position
        }
    }
}

/**
 * Watches the session bus for MPRIS players, picks the active one with the same
 * heuristic KDE's libkmpris uses, and exposes an interpolated playback position.
 *
 * Correctness never depends on signals: a 250 ms GetAll poll is the source of
 * truth, Seeked and PropertiesChanged only make updates instant when they arrive.
 */
class NowPlayingMonitor {
    private val playersInternal = MutableStateFlow<List<PlayerInfo>>(emptyList())
    val players: StateFlow<List<PlayerInfo>> = playersInternal.asStateFlow()

    private val activeInternal = MutableStateFlow<ActivePlayback?>(null)
    val active: StateFlow<ActivePlayback?> = activeInternal.asStateFlow()

    private var connection: DBusConnection? = null
    private var job: Job? = null

    // bus name -> player runtime state
    private val runtimes = ConcurrentHashMap<String, PlayerRuntime>()
    // unique connection name (the sender on signals) -> requested bus name
    private val owners = ConcurrentHashMap<String, String>()

    /** Preferred player identity; null means "auto" (the KDE multiplexer heuristic). */
    @Volatile
    var preferredIdentity: String? = null

    fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch(Dispatchers.IO) { run() }
    }

    fun stop() {
        job?.cancel()
        job = null
        connection?.let { runCatching { it.disconnect() } }
        connection = null
        runtimes.clear()
        owners.clear()
        playersInternal.value = emptyList()
        activeInternal.value = null
    }

    private suspend fun run() {
        val conn = try {
            DBusConnectionBuilder.forSessionBus().build()
        } catch (t: Throwable) {
            println("Lyrics Float: no session bus (${t.message}); song detection disabled")
            return
        }
        connection = conn

        try {
            conn.addSigHandler(DBus.NameOwnerChanged::class.java) { sig ->
                val name = sig.name
                if (!name.startsWith(MPRIS_PREFIX)) return@addSigHandler
                when {
                    sig.newOwner.isNotEmpty() -> {
                        owners[sig.newOwner] = name
                        addPlayer(name)
                    }
                    sig.oldOwner.isNotEmpty() -> removePlayer(name, sig.oldOwner)
                }
            }

            conn.addSigHandler(MprisPlayer.Seeked::class.java) { sig ->
                val busName = sig.source?.let(owners::get) ?: return@addSigHandler
                runtimes[busName]?.seeked(sig.position)
            }

            conn.addSigHandler(Properties.PropertiesChanged::class.java) { sig ->
                if (sig.interfaceName != PLAYER_IFACE) return@addSigHandler
                val busName = sig.source?.let(owners::get) ?: return@addSigHandler
                // Metadata can change (track change) without PlaybackStatus changing.
                runtimes[busName]?.markStale()
            }

            val dbus = conn.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java)
            dbus.ListNames().filter { it.startsWith(MPRIS_PREFIX) }.forEach { addPlayer(it) }
        } catch (t: Throwable) {
            println("Lyrics Float: MPRIS discovery failed: ${t.message}")
        }

        var currentBusName: String? = null
        while (coroutineContext.isActive) {
            try {
                // Refresh every player's status first, so the selection heuristic
                // can see who is playing; only the chosen player gets a full read.
                for ((name, runtime) in runtimes) {
                    runtime.status = try {
                        val props = conn.getRemoteObject(name, OBJECT_PATH, Properties::class.java)
                        unpackString(props.Get(PLAYER_IFACE, "PlaybackStatus"))
                            ?.let(::parseStatus) ?: PlaybackStatus.STOPPED
                    } catch (t: Throwable) {
                        PlaybackStatus.STOPPED // player died between polls
                    }
                }
                val selected = choosePlayer(currentBusName)
                val playback = selected?.let { readPlayer(conn, it) }
                activeInternal.value = playback
                currentBusName = selected
            } catch (t: Throwable) {
                // A dead player or a bus hiccup should not kill the monitor loop.
                if (activeInternal.value != null) activeInternal.value = null
            }
            delay(POLL_MS)
        }
    }

    private fun addPlayer(busName: String) {
        if (runtimes.containsKey(busName)) return
        val identity = try {
            val conn = connection ?: return
            val props = conn.getRemoteObject(busName, OBJECT_PATH, Properties::class.java)
            unpackString(props.Get(ROOT_IFACE, "Identity")) ?: busName.removePrefix(MPRIS_PREFIX)
        } catch (t: Throwable) {
            busName.removePrefix(MPRIS_PREFIX)
        }
        val uniqueOwner = try {
            connection
                ?.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java)
                ?.GetNameOwner(busName)
        } catch (t: Throwable) {
            null
        }
        if (uniqueOwner != null) owners[uniqueOwner] = busName
        runtimes[busName] = PlayerRuntime(busName, identity)
        publishPlayers()
    }

    private fun removePlayer(busName: String, owner: String) {
        owners.remove(owner)
        runtimes.remove(busName)
        publishPlayers()
    }

    private fun publishPlayers() {
        playersInternal.value = runtimes.values
            .sortedBy { it.identity.lowercase() }
            .map { PlayerInfo(it.busName, it.identity) }
    }

    /** KDE multiplexer heuristic: keep the current player while it plays, else prefer a playing one. */
    private fun choosePlayer(current: String?): String? {
        if (runtimes.isEmpty()) return null
        preferredIdentity?.let { preferred ->
            runtimes.values.firstOrNull { it.identity == preferred }?.let { return it.busName }
        }
        current?.let { name ->
            val runtime = runtimes[name]
            if (runtime != null && runtime.status == PlaybackStatus.PLAYING) return name
        }
        runtimes.values.firstOrNull { it.status == PlaybackStatus.PLAYING }?.let { return it.busName }
        return current?.takeIf { runtimes.containsKey(it) } ?: runtimes.keys.firstOrNull()
    }

    private fun readPlayer(conn: DBusConnection, busName: String): ActivePlayback? {
        val runtime = runtimes[busName] ?: return null
        val props = conn.getRemoteObject(busName, OBJECT_PATH, Properties::class.java)
        val all = props.GetAll(PLAYER_IFACE)

        val metadata = (all["Metadata"] as? Variant<*>)?.value as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val status = unpackString(all["PlaybackStatus"])?.let(::parseStatus) ?: PlaybackStatus.STOPPED
        val rate = (unpackNumber(all["Rate"]) ?: 1.0).takeIf { it > 0 } ?: 1.0
        val positionUs = (unpackNumber(all["Position"]) ?: 0.0).toLong()

        val track = parseTrack(metadata)
        runtime.update(status, rate, positionUs, track)
        return runtime.snapshot()
    }

    /**
     * Chromium-based browsers keep their MPRIS service registered with stale metadata
     * after the media tab closes, so treat empty title/artist as "nothing playing".
     */
    private fun parseTrack(metadata: Map<*, *>): TrackInfo? {
        if (metadata.isEmpty()) return null
        val title = unpackString(metadata["xesam:title"])?.trim().orEmpty()
        // 'as' can arrive as a List or a raw array depending on the marshaller;
        // every a{sv} value is Variant-wrapped, so unwrap first.
        val artistRaw = (metadata["xesam:artist"] as? Variant<*>)?.value ?: metadata["xesam:artist"]
        val artists = when (artistRaw) {
            is List<*> -> artistRaw.filterIsInstance<String>()
            is Array<*> -> artistRaw.filterIsInstance<String>()
            is String -> listOf(artistRaw)
            else -> emptyList()
        }
        val artist = artists.joinToString(", ").trim()
        if (title.isBlank() && artist.isBlank()) return null
        val trackIdRaw = (metadata["mpris:trackid"] as? Variant<*>)?.value ?: metadata["mpris:trackid"]
        val trackId = when (trackIdRaw) {
            is DBusPath -> trackIdRaw.path
            else -> trackIdRaw?.toString().orEmpty()
        }
        val lengthUs = unpackNumber(metadata["mpris:length"])?.toLong()
        return TrackInfo(
            trackId = trackId,
            title = title,
            artist = artist,
            album = unpackString(metadata["xesam:album"])?.trim()?.takeIf { it.isNotEmpty() },
            artUrl = unpackString(metadata["mpris:artUrl"])?.trim()?.takeIf { it.isNotEmpty() },
            lengthMs = lengthUs?.let { it / 1000 },
        )
    }

    private fun parseStatus(value: String): PlaybackStatus = when (value) {
        "Playing" -> PlaybackStatus.PLAYING
        "Paused" -> PlaybackStatus.PAUSED
        else -> PlaybackStatus.STOPPED
    }

    // dbus-java auto-unwraps the variant for Properties.Get but keeps values
    // inside a{sv} maps wrapped, so accept both shapes.
    private fun unpackString(v: Any?): String? {
        val value = (v as? Variant<*>)?.value ?: v
        return value as? String
    }

    private fun unpackNumber(v: Any?): Double? = when (val value = (v as? Variant<*>)?.value ?: v) {
        is Number -> value.toDouble()
        else -> null
    }

    /** Per-player state that survives between polls, so signals can rebase the position clock. */
    private class PlayerRuntime(val busName: String, val identity: String) {
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

    companion object {
        const val MPRIS_PREFIX = "org.mpris.MediaPlayer2."
        const val OBJECT_PATH = "/org/mpris/MediaPlayer2"
        const val ROOT_IFACE = "org.mpris.MediaPlayer2"
        const val PLAYER_IFACE = "org.mpris.MediaPlayer2.Player"
        const val POLL_MS = 250L
        private const val REBASE_TOLERANCE_US = 500_000L // 500 ms
    }
}
