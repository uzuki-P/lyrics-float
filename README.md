# Lyrics Float

Floating synced lyrics for Linux. Lyrics Float watches any media player that
speaks [MPRIS](https://specifications.freedesktop.org/mpris-spec/latest/)
(browsers playing YouTube Music, Spotify, mpv, Elisa, ...) over the D-Bus
session bus, fetches timed lyrics from [LRCLIB](https://lrclib.net), and shows
the current line in a small always-on-top pill you can park anywhere on screen.

Built with Kotlin and Compose Multiplatform for desktop. The lyrics feature
set (providers, extended-LRC parsing, word karaoke, agent positioning,
interval indicator, romaji) is ported from
[Metrolist](https://github.com/MetrolistProject/Metrolist) — a local reference
clone lives at `~/projects/_sandbox/_github/Metrolist`. The native window-drag
technique is adapted from pray-time (`~/projects/_sandbox/pray-time`).

![Linux](https://img.shields.io/badge/platform-Linux/Wayland-blue)

## Features

- Now-playing detection over MPRIS, with the same player-selection heuristic
  KDE Plasma's media widget uses. Works with YouTube Music in Firefox and
  Chromium; any MPRIS player works.
- Multiple lyrics providers tried in order: BetterLyrics, Lrclib, KuGou,
  Paxsenix (Apple Music) and LyricsPlus — each toggleable in settings.
  Extended-LRC word timings, duet agent markers and background vocals are
  understood everywhere.
- Metrolist-style lyrics rendering: karaoke word-by-word fill, sliding line
  transitions, interval indicator dots during instrumental gaps, agent
  positioning (v1 left / v2 right), and offline romaji for Japanese lyrics.
  The playback position is interpolated locally, so the highlight stays
  smooth without hammering D-Bus.
- Manual lyrics search (1 s debounce) with per-track picks that persist.
  Sync offset fine-tuning (±50 ms steps, −3..+3 s slider).
- Floating overlay: transparent, always-on-top, native WM drag and
  edge/corner resize, hover controls, progress bar, adjustable text size and
  opacity. Context lines scale with the window size.
- Settings: theme (system/light/dark), sync offset, screen anchor,
  preferred player, auto-hide.
- System tray via StatusNotifierItem with show/hide, search, settings and quit.

## Requirements

- Linux with a Wayland session (KDE Plasma recommended). The app talks to the
  session bus for both song detection and the tray icon; no tray fallback is
  provided, so a desktop implementing StatusNotifierItem is expected.
- Java 17+ to run from source (Gradle toolchain provisioning can fetch one).

## Run from source

```bash
./gradlew run
```

or with [just](https://github.com/casey/just):

```bash
just dev
```

## Install

```bash
just build-appimage   # or: ./gradlew packageAppImageFile
```

The AppImage lands in `_apk/`.

## Wayland notes

Kotlin/Compose desktop apps run through XWayland on Wayland sessions. The
overlay sets always-on-top itself, and KWin honors it for XWayland windows.
If your compositor ignores the hint (or you want the window to survive
"toggle click to focus" games), add a KWin window rule:

System Settings → Window Management → Window Rules → Add New:

- Window class: `lyrics-float` (match substring)
- Appearance & Fixes → Keep above: Force → Yes
- Arrangement & Access → Skip taskbar: Force → Yes

Click-through is intentionally out of scope: Wayland does not let ordinary
windows forward clicks to the surface below, so the pill is a normal (tiny,
draggable) input target. Drag it somewhere out of the way.

## How detection works

1. `ListNames` plus a `NameOwnerChanged` watch discover players whose bus name
   starts with `org.mpris.MediaPlayer2.`.
2. The active player is chosen with KDE's multiplexer heuristic: keep the
   current one while it plays, else prefer whoever is playing, else the first.
   A preferred player can be pinned in settings.
3. `Metadata`, `PlaybackStatus`, `Rate` and `Position` are polled at 250 ms;
   `Seeked` and `PropertiesChanged` signals make seeks and track changes
   instant when players emit them. Chromium never emits `PropertiesChanged`
   for `Position` (only `Seeked`), so the poll is the correctness backstop.
4. Position between polls is extrapolated with `position + elapsed * rate`,
   the same math playerctl and Firefox use internally.

## License

[MIT](LICENSE)
