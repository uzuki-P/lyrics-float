# AGENTS.md

Lyrics Float is a Linux (Wayland/KDE-focused) Compose Desktop app that shows
synced lyrics in a small transparent, always-on-top, draggable pill. It watches
every MPRIS player on the D-Bus session bus, interpolates playback position
locally, and renders karaoke-style lyrics. Settings persist as a key=value
file under `$XDG_CONFIG_HOME/lyricsfloat/state.properties`.

## Reference projects

- **Metrolist** — `~/projects/_sandbox/_github/Metrolist`
  (GitHub: <https://github.com/MetrolistProject/Metrolist>, GPL-3.0). The
  lyrics feature set (providers, extended-LRC parsing, word karaoke, agent
  positioning, interval indicator, romaji) is ported from there. Read it
  before changing anything under `src/main/kotlin/dev/lyricsfloat/lyrics/` or
  the lyrics rendering in `ui/`. It is a reference clone: never edit, update,
  or clean it.
- **pray-time** — `~/projects/_sandbox/pray-time`. Source of the native
  window-drag technique (`platform/LinuxWindowMover.kt`, EWMH
  `_NET_WM_MOVERESIZE` over JNA/Xlib). Anything about window dragging or
  resizing under XWayland should follow it.

## Layout

- `src/main/kotlin/dev/lyricsfloat/`
  - `Main.kt` — windows (overlay + search + settings dialogs), wiring.
  - `lyrics/` — providers, extended-LRC parser, repository/overrides.
  - `mpris/` — D-Bus now-playing monitor with interpolated clock.
  - `platform/` — tray (StatusNotifierItem), state persistence,
    `LinuxWindowMover`, screen anchor math.
  - `ui/` — Compose views (overlay pill, search, settings, theme).
- `docs/plan.md` — current work plan; keep checkbox states in sync with the
  code when you finish or start a step.
- Tests live in `src/test/kotlin/`, run with `just test` (or
  `./gradlew test`). Build with `just build`, dev-run with `just dev`.
  `just install` copies the newest AppImage to `~/apps/lyrics-float.AppImage`
  (stable name) and registers the KDE/vicinae launcher entry
  (`scripts/install-appimage`, `--uninstall` to remove). `just update` builds,
  stages a dated `_apk/` copy, and installs in one command.

## Conventions

- Linux/XWayland quirks are heavily commented where they are worked around;
  keep those comments when touching the code.
- Do not add network providers without a User-Agent where the API asks for
  one (lrclib.net requires it).
- Extended LRC is the canonical lyrics format providers must serialize into
  (see `docs/plan.md` section 3 for the exact shape).
- Song lyrics often use kanji and kana combinations with readings that a
  character-by-character romaji conversion gets wrong. Add phrase-level romaji
  fixes for these cases, such as 一人 → `hitori`.
