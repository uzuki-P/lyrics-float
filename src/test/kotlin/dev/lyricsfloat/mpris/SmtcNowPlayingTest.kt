package dev.lyricsfloat.mpris

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SmtcNowPlayingTest {
    @Test
    fun parsesHelperLine() {
        val line = """{"current":"Spotify.exe","sessions":[{"key":"Spotify.exe","app":"Spotify.exe","status":"Playing","rate":1,"positionMs":61234,"lengthMs":215000,"title":"夜に駆ける","artist":"YOASOBI","album":""},{"key":"chrome#1","app":"chrome","status":"Paused","rate":1.0,"positionMs":0,"lengthMs":0,"title":"","artist":"","album":""}]}"""
        val snapshot = parseSmtcLine(line)!!
        assertEquals("Spotify.exe", snapshot.current)
        assertEquals(2, snapshot.sessions.size)
        val spotify = snapshot.sessions[0]
        assertEquals(61234L, spotify.positionMs)
        assertEquals(1.0, spotify.rate)
        val track = spotify.toTrack()!!
        assertEquals("夜に駆ける", track.title)
        assertEquals("YOASOBI", track.artist)
        assertNull(track.album)
        assertEquals(215000L, track.lengthMs)
        // A metadata-less browser session is not a track.
        assertNull(snapshot.sessions[1].toTrack())
    }

    @Test
    fun acceptsCollapsedSingleSessionAndNulls() {
        val line = """{"current":null,"sessions":{"key":"foobar2000.exe","status":"Stopped","title":null}}"""
        val snapshot = parseSmtcLine(line)!!
        assertNull(snapshot.current)
        assertEquals("foobar2000.exe", snapshot.sessions.single().key)
        assertEquals("foobar2000.exe", snapshot.sessions.single().app)
        assertEquals(parseSmtcLine("""{"current":null,"sessions":[]}""")!!.sessions, emptyList())
    }

    @Test
    fun ignoresNoise() {
        assertNull(parseSmtcLine(""))
        assertNull(parseSmtcLine("WARNING: something"))
        assertNull(parseSmtcLine("{broken"))
    }

    @Test
    fun mapsStatus() {
        assertEquals(PlaybackStatus.PLAYING, smtcStatus("Playing"))
        assertEquals(PlaybackStatus.PAUSED, smtcStatus("Paused"))
        assertEquals(PlaybackStatus.PAUSED, smtcStatus("Changing"))
        assertEquals(PlaybackStatus.STOPPED, smtcStatus("Stopped"))
        assertEquals(PlaybackStatus.STOPPED, smtcStatus("Closed"))
    }

    @Test
    fun namesPlayers() {
        assertEquals("Spotify", smtcPlayerName("Spotify.exe"))
        assertEquals("Spotify", smtcPlayerName("SpotifyAB.SpotifyMusic_zpdnekdrzrea0!Spotify"))
        assertEquals("Media Player", smtcPlayerName("Microsoft.ZuneMusic_8wekyb3d8bbwe!Microsoft.ZuneMusic"))
        assertEquals("Apple Music", smtcPlayerName("AppleInc.AppleMusicWin_nzyj5cx40ttqa!App"))
        assertEquals("Microsoft Edge", smtcPlayerName("MSEdge"))
        assertEquals("Google Chrome", smtcPlayerName("chrome"))
        assertEquals("Foobar2000", smtcPlayerName("C:\\Program Files\\foobar2000\\foobar2000.exe"))
        assertEquals("Unknown player", smtcPlayerName(" "))
    }

    @Test
    fun choosesLikeMpris() {
        val spotify = SmtcChoice("Spotify.exe", "Spotify", PlaybackStatus.PAUSED)
        val chrome = SmtcChoice("chrome", "Google Chrome", PlaybackStatus.PLAYING)
        val edge = SmtcChoice("msedge", "Microsoft Edge", PlaybackStatus.STOPPED)
        val all = listOf(spotify, chrome, edge)

        assertNull(chooseSmtcSession(emptyList(), null, null, null))
        // Preferred player wins even when paused.
        assertEquals("Spotify.exe", chooseSmtcSession(all, "chrome", "Spotify", null))
        // Otherwise a playing session beats the paused current one.
        assertEquals("chrome", chooseSmtcSession(all, "Spotify.exe", null, null))
        // Nothing playing: keep the current session.
        val idle = listOf(spotify, edge)
        assertEquals("msedge", chooseSmtcSession(idle, "msedge", null, "Spotify.exe"))
        // No current one yet: Windows' own current session.
        assertEquals("msedge", chooseSmtcSession(idle, null, null, "msedge"))
        assertEquals("Spotify.exe", chooseSmtcSession(idle, null, null, null))
        // Several playing: Windows' current session beats list order.
        val video = SmtcChoice("msedge", "Microsoft Edge", PlaybackStatus.PLAYING)
        val music = SmtcChoice("Spotify.exe", "Spotify", PlaybackStatus.PLAYING)
        assertEquals("Spotify.exe", chooseSmtcSession(listOf(video, music), null, null, "Spotify.exe"))
        assertEquals("msedge", chooseSmtcSession(listOf(video, music), null, null, null))
    }
}
