package dev.lyricsfloat.mpris

import dev.lyricsfloat.platform.AppPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

/** One SMTC session as the PowerShell helper prints it. */
@Serializable
internal data class SmtcSession(
    /** Unique per tick: the app id, with `#n` appended for repeats. */
    val key: String,
    /** SourceAppUserModelId, e.g. `Spotify.exe` or `...!App` for Store apps. */
    val app: String = key,
    val status: String = "",
    val rate: Double = 1.0,
    val positionMs: Long = 0,
    val lengthMs: Long = 0,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
)

@Serializable
internal data class SmtcSnapshot(
    /** App id of the session Windows itself treats as current, if any. */
    val current: String? = null,
    val sessions: List<SmtcSession> = emptyList(),
)

/**
 * Windows now-playing source built on System Media Transport Controls (the
 * sessions the volume flyout shows). The WinRT session manager is reached
 * through a bundled Windows PowerShell 5.1 helper (`windows/smtc-monitor.ps1`)
 * that prints one JSON line per 250 ms tick; this avoids shipping a compiled
 * native helper. Selection and position interpolation mirror the MPRIS
 * [NowPlayingMonitor].
 */
class SmtcNowPlayingMonitor : NowPlayingSource {
    private val playersInternal = MutableStateFlow<List<PlayerInfo>>(emptyList())
    override val players: StateFlow<List<PlayerInfo>> = playersInternal.asStateFlow()

    private val activeInternal = MutableStateFlow<ActivePlayback?>(null)
    override val active: StateFlow<ActivePlayback?> = activeInternal.asStateFlow()

    @Volatile
    override var preferredIdentity: String? = null

    private var job: Job? = null
    private val runtimes = ConcurrentHashMap<String, PlayerRuntime>()
    private var currentKey: String? = null

    override fun start(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch(Dispatchers.IO) { run() }
    }

    override fun stop() {
        job?.cancel()
        job = null
        runtimes.clear()
        playersInternal.value = emptyList()
        activeInternal.value = null
    }

    private suspend fun run() {
        var failures = 0
        while (coroutineContext.isActive) {
            val startedAt = System.nanoTime()
            try {
                runHelper()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                println("Lyrics Float: SMTC helper failed: ${t.message}")
            }
            runtimes.clear()
            playersInternal.value = emptyList()
            activeInternal.value = null
            // A helper that ran for a while restarts quickly; one that keeps
            // dying at startup (SMTC missing, PowerShell blocked) backs off.
            failures = if (System.nanoTime() - startedAt > 60_000_000_000L) 0 else failures + 1
            delay((2_000L shl failures.coerceAtMost(4)).coerceAtMost(30_000L))
        }
    }

    private suspend fun runHelper() {
        val script = helperScript()
        val log = File(AppPaths.dataDir, "smtc-helper.log")
        val process = ProcessBuilder(
            powershellPath(),
            "-NoLogo", "-NoProfile", "-NonInteractive",
            "-ExecutionPolicy", "Bypass",
            "-File", script.absolutePath,
        ).apply {
            environment()["LYRICSFLOAT_PARENT_PID"] = ProcessHandle.current().pid().toString()
            redirectError(ProcessBuilder.Redirect.to(log))
        }.start()
        process.outputStream.close()
        // Blocking readLine cannot be cancelled; killing the helper ends it.
        val cancelHandle = coroutineContext.job.invokeOnCompletion { process.destroyForcibly() }
        try {
            process.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    coroutineContext.ensureActive()
                    val snapshot = parseSmtcLine(line) ?: continue
                    apply(snapshot)
                }
            }
        } finally {
            cancelHandle.dispose()
            process.destroyForcibly()
        }
    }

    private fun apply(snapshot: SmtcSnapshot) {
        val seen = snapshot.sessions.map { it.key }.toSet()
        runtimes.keys.retainAll(seen)
        for (session in snapshot.sessions) {
            val runtime = runtimes.getOrPut(session.key) { PlayerRuntime(session.key, smtcPlayerName(session.app)) }
            val status = smtcStatus(session.status)
            // Positions below zero or a stopped session would reset the clock;
            // PlayerRuntime handles both.
            runtime.update(status, session.rate.takeIf { it > 0 } ?: 1.0, session.positionMs * 1000, session.toTrack())
        }
        val players = runtimes.values
            .map { PlayerInfo(it.busName, it.identity) }
            .distinctBy { it.identity }
            .sortedBy { it.identity.lowercase() }
        if (players != playersInternal.value) playersInternal.value = players

        val choices = runtimes.values.map { SmtcChoice(it.busName, it.identity, it.status) }
        val selected = chooseSmtcSession(choices, currentKey, preferredIdentity, snapshot.current)
        currentKey = selected
        activeInternal.value = selected?.let { runtimes[it]?.snapshot() }
    }

    private fun helperScript(): File {
        val text = requireNotNull(
            SmtcNowPlayingMonitor::class.java.classLoader.getResourceAsStream("windows/smtc-monitor.ps1"),
        ) { "missing SMTC helper script" }.use { it.readBytes() }
        val target = File(AppPaths.dataDir, "smtc-monitor.ps1")
        target.parentFile.mkdirs()
        // Rewrite only when an update changed the script; a running older
        // helper keeps its file handle closed after PowerShell reads it.
        if (!target.isFile || !target.readBytes().contentEquals(text)) target.writeBytes(text)
        return target
    }

    private fun powershellPath(): String {
        val root = System.getenv("SystemRoot")?.takeIf(String::isNotBlank) ?: "C:\\Windows"
        val path = File(root, "System32\\WindowsPowerShell\\v1.0\\powershell.exe")
        return if (path.isFile) path.path else "powershell.exe"
    }
}

private val smtcJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

/** Parses one helper line; null for anything that is not a snapshot. */
internal fun parseSmtcLine(line: String): SmtcSnapshot? {
    val trimmed = line.trim()
    if (!trimmed.startsWith("{")) return null
    return runCatching {
        val root = smtcJson.parseToJsonElement(trimmed).jsonObject
        // Windows PowerShell's ConvertTo-Json can collapse a one-element array
        // into a bare object; accept both shapes.
        val sessions = root["sessions"]
        val normalized = if (sessions is JsonObject) {
            JsonObject(root + ("sessions" to JsonArray(listOf(sessions))))
        } else {
            root
        }
        smtcJson.decodeFromJsonElement<SmtcSnapshot>(normalized)
    }.getOrNull()
}

/** SMTC PlaybackStatus names: Closed, Opened, Changing, Stopped, Playing, Paused. */
internal fun smtcStatus(value: String): PlaybackStatus = when (value) {
    "Playing" -> PlaybackStatus.PLAYING
    // Changing is the brief state between tracks; treating it as stopped would
    // zero the clock mid-song.
    "Paused", "Changing" -> PlaybackStatus.PAUSED
    else -> PlaybackStatus.STOPPED
}

/** Browser tabs often publish a session with no metadata; treat that as nothing playing. */
internal fun SmtcSession.toTrack(): TrackInfo? {
    val cleanTitle = title?.trim().orEmpty()
    val cleanArtist = artist?.trim().orEmpty()
    if (cleanTitle.isBlank() && cleanArtist.isBlank()) return null
    val cleanAlbum = album?.trim()?.takeIf(String::isNotEmpty)
    return TrackInfo(
        // SMTC has no track id; the metadata identifies the track instead.
        trackId = "$cleanArtist\u0000$cleanTitle\u0000${cleanAlbum.orEmpty()}",
        title = cleanTitle,
        artist = cleanArtist,
        album = cleanAlbum,
        artUrl = null,
        lengthMs = lengthMs.takeIf { it > 0 },
    )
}

private val knownSmtcApps = mapOf(
    "msedge" to "Microsoft Edge",
    "chrome" to "Google Chrome",
    "firefox" to "Firefox",
    "brave" to "Brave",
    "opera" to "Opera",
    "vivaldi" to "Vivaldi",
    "spotify" to "Spotify",
    "zunemusic" to "Media Player",
    "applemusicwin" to "Apple Music",
)

/**
 * A readable player name from a SourceAppUserModelId: `Spotify.exe`,
 * `C:\...\foobar2000.exe`, or a Store app id such as
 * `SpotifyAB.SpotifyMusic_zpdnekdrzrea0!Spotify`.
 */
internal fun smtcPlayerName(appId: String): String {
    val id = appId.trim()
    if (id.isEmpty()) return "Unknown player"
    val base = if ('!' in id) {
        val (family, entry) = id.split('!', limit = 2)
        // Store apps with a generic entry point ("App") are named by package.
        val packageName = family.substringBefore('_')
        if (entry.equals("App", ignoreCase = true) || entry.isBlank()) packageName.substringAfterLast('.') else entry.substringAfterLast('.')
    } else {
        id.substringAfterLast('\\').substringAfterLast('/').removeSuffix(".exe").removeSuffix(".EXE")
    }
    knownSmtcApps[base.lowercase()]?.let { return it }
    return base.replaceFirstChar { it.uppercaseChar() }
}

internal data class SmtcChoice(val key: String, val identity: String, val status: PlaybackStatus)

/**
 * Same order as the MPRIS heuristic: the preferred player, then the current
 * one while it plays, then any playing session. When nothing plays, Windows'
 * own current session wins over an arbitrary first one.
 */
internal fun chooseSmtcSession(
    sessions: List<SmtcChoice>,
    current: String?,
    preferredIdentity: String?,
    systemCurrentApp: String?,
): String? {
    if (sessions.isEmpty()) return null
    preferredIdentity?.let { preferred ->
        sessions.firstOrNull { it.identity == preferred }?.let { return it.key }
    }
    current?.let { key ->
        if (sessions.any { it.key == key && it.status == PlaybackStatus.PLAYING }) return key
    }
    sessions.firstOrNull { it.status == PlaybackStatus.PLAYING }?.let { return it.key }
    current?.takeIf { key -> sessions.any { it.key == key } }?.let { return it }
    systemCurrentApp?.let { app -> sessions.firstOrNull { it.key == app }?.let { return it.key } }
    return sessions.first().key
}
