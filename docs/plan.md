# Lyrics Float — Metrolist lyrics feature port

Goal: bring the whole lyrics feature set of Metrolist to the floating overlay,
plus fix window drag/resize. Reference sources:

- Metrolist: `~/projects/_sandbox/_github/Metrolist` (GPL-3.0, GitHub
  [MetrolistProject/Metrolist](https://github.com/MetrolistProject/Metrolist))
- pray-time drag implementation: `~/projects/_sandbox/pray-time`
  (`platform/LinuxWindowMover.kt`, EWMH `_NET_WM_MOVERESIZE` via JNA)

Status legend: `[ ]` pending · `[~]` in progress · `[x]` done.

## 1. Window drag (pray-time approach)

- [x] Add JNA dependency (`net.java.dev.jna:jna`, `jna-platform`).
- [x] Port `LinuxWindowMover` to `dev.lyricsfloat.platform`, plus
      `requestInteractiveResize(window, direction)` for step 2
      (EWMH hints 0–7 are the eight resize directions, 8 = move).
- [x] Drag start prefers the WM's native move (`_NET_WM_MOVERESIZE` +
      `XUngrabPointer`); `setLocation` with accumulated deltas stays as the
      fallback. Per-event `setLocation` jitters under XWayland; the WM move
      tracks the pointer per frame.
- [x] `LinuxWindowMover.warmUp()` on `Dispatchers.IO` at startup so JNA's
      native load never runs during the first drag.

## 2. Window resize

- [~] Overlay window becomes `resizable = true`; pill fills the window and
      grows with it (lyrics show more context lines when taller).
- [x] Edge/corner resize handles appear on hover; drag starts a WM
      interactive resize through `LinuxWindowMover` (hints 0–7). Fallback:
      manual `setSize` drag when the WM refuses (returns false).
- [x] Persist size (`overlay.width`/`overlay.height`, min 380x120 dp) and
      restore it; anchor math uses the current window size.
- [x] Save position/size after a resize settles (watch AWT size, debounce).

## 3. Extended LRC parsing (Metrolist format)

- [x] `LyricsEntry` upgraded to Metrolist shape: `words: List<WordTimestamp>?`,
      `agent: String?` (v1/v2/v1000), `isBackground`, `romanizedTextFlow`.
- [x] Port Metrolist `parseLyrics`: rich sync (inline `<MM:SS.mm>` tags),
      standalone `<word:start:end|...>` continuation lines, `{agent:vN}` and
      `{bg}` markers, Paxsenix `[mm:ss.mmm]v1:` / `[bg: ...]` line forms,
      HTML entity decode, credit-line filtering, plain fallback.
- [x] Interval gaps: insert `Indicator` items when the instrumental gap
      exceeds 4000 ms (Metrolist `mergedLyricsList` rule), carrying
      gapStart/gapEnd so the UI can animate progress inside the gap.
- [x] Port `findActiveLineIndices` (overlapping active lines with word
      timings, background vocals excluded from the cut rule).
- [x] Unit tests for: standard LRC, extended LRC with words+agent+bg,
      Paxsenix forms, gap indicators.

## 4. Lyrics providers (Metrolist registry)

- [x] `LyricsProvider` interface + registry, default order
      BetterLyrics → Lrclib → KuGou → Paxsenix → LyricsPlus.
      (YouTube/YouTubeSubtitle need a videoId that MPRIS does not give us;
      skipped.)
- [x] Port `TTMLParser` (JVM `javax.xml`), same extended-LRC serialization
      (`{agent:…}`, `{bg}`, `<word:start:end|…>` blocks).
- [x] BetterLyrics: `https://lyrics-api.boidu.dev/getLyrics` → TTML → LRC.
- [x] KuGou: song search (`mobileservice.kugou.com`), lyrics search by
      hash/keyword, Base64 LRC download, banned-head/tail-line trimming.
- [x] Paxsenix: Apple Music token scrape from `beta.music.apple.com`, search
      `amp-api.music.apple.com`, lyrics from `lyrics.paxsenix.org`
      (ttmlContent → elrcMultiPerson → elrc → plain → content array), quality
      scoring (3 = word sync).
- [x] LyricsPlus: server failover list + `v2/lyrics/get`, plus the Binimum
      TTML API; JSON → extended LRC conversion with agent mapping.
- [x] Repository: sequential fallback with per-provider timeout, in-memory
      per-track cache, manual picks generalized to `(provider, resultId)`
      overrides (Lrclib picks keep working).
- [x] Settings: per-provider enable toggles (all on by default, LyricsPlus
      off by default like Metrolist's provider default).

## 5. Manual search

- [x] Manual search runs when the Search button or Enter is pressed, without a debounce.
- [x] Manual picks use the current MPRIS track even when the repository has not set its target key yet.

## 6. Lyrics animation (Metrolist-style)

- [x] Word-level karaoke fill on the active line (per-word progress from
      `WordTimestamp`; lines without word timings synthesize fake timings so
      they still animate).
- [x] Line alpha by distance from the active line, animated line swap.
- [x] Interval indicator: pulsing dots rendered while playback sits inside a
      > 4 s gap, with a toggle (`Show interval indicator`, default on).
- [x] Agent positioning (Metrolist `RespectAgentPositioningKey`): v1 lines
      align start, v2 align end, v1000 center; toggleable, default on.
- [x] Background vocal lines render smaller/italic; next-line preview kept.

## 7. Romaji (Japanese, offline)

- [x] Dependency `com.atilika.kuromoji:kuromoji-ipadic:0.9.0`.
- [x] Port `romanizeJapanese` (kuromoji tokenization + katakana→romaji map
      with digraphs, dakuten, sokuon doubling, chōonpu) + `isJapanese` /
      `isChinese` detection; JP only for now.
- [x] Romaji computed lazily per line off the UI thread into
      `romanizedTextFlow`; shown as a sub-line under the active line.
- [x] Toggle `Romanize Japanese lyrics` (default on, like Metrolist).
- [x] Separate persisted romaji font size control (8–30 sp, default 10 sp).

## 8. Lyrics offset options

- [x] Offset stepper steps ±50 ms, slider −3000..+3000 ms (100 ms steps),
      live value, reset; persisted (±10 s clamp).

## 9. Docs

- [x] `AGENTS.md` describing the project and the Metrolist reference clone in
      `~/projects/_sandbox/_github/Metrolist` (plus pray-time for the drag).
- [x] README mentions both upstream projects and the local clone paths.
- [x] This plan file updated per step.

## Out of scope (for now)

- [x] Save successful provider lyrics and manual picks under
      `$XDG_DATA_HOME/lyricsfloat/lyrics` (or `~/.local/share/lyricsfloat/lyrics`)
      so previously fetched lyrics work after restart and offline.
- [x] Add a Start on login setting using an XDG autostart entry for the
      installed launcher or AppImage; refresh its path on launch.

- Translation (OpenRouter/DeepL) — Metrolist has it, desktop port pending a
  decisions pass on API keys.
- Romaji for Korean/Chinese/Cyrillic — JP only until the user asks.
- YouTube / YouTube-subtitle providers — need a videoId, which MPRIS metadata
  rarely carries.

## 10. Round 2 — Metrolist fidelity pass

- [x] Pill visuals: drop the window shadow/border look, allow 0% background
      opacity (slider 0–100%), readability via stronger text shadows.
- [x] Lyrics viewport: real scrolling area that fills the window (resizing
      reveals more lines), Metrolist-style animated auto-scroll
      (750 ms FastOutSlowIn, anchor at ~38% height), manual drag/wheel scroll
      disables auto-scroll, "Sync" button to re-sync, setting `Auto scroll`.
- [x] Remove the slim progress bar (time scrubbing UI).
- [x] Romaji shown under every line that has it (not only the active one);
      unit test pins kuromoji behavior so it cannot silently break.
- [x] Manual search: results carry a provider badge; provider filter chips
      (All / per provider); non-Lrclib picks persist as provider replays.
- [x] Settings page rebuilt following Metrolist's lyrics settings: Lyrics
      section (text size, text position Left/Center/Right, auto scroll,
      karaoke, interval indicator, agent positioning, romaji), providers,
      overlay, theme, song source; anchor chips stop wrapping badly.

## 11. Round 2 addenda

- [x] Outline toggle: lyric text renders as a stroked back layer + filled
      front layer (`drawStyle = Stroke`), sized with the font; toggle
      `Text outline` in settings, shadow fallback when off.
- [x] Scroll fix: drag/wheel handlers clamped against a stale empty height
      snapshot (pinned offset to 0); they now measure live heights, and the
      auto-scroll target counts the stack spacing, anchoring the active line
      at 50% of the viewport.
- [x] Interval indicator follows Metrolist's IntervalIndicator
      (LyricsCommon.kt): 36 dp circular wavy progress ring (Material3
      expressive), accent on a 20% track, determinate progress lerped at
      100 ms, 200 ms height+alpha reveal, hidden 650 ms before the next line
      and while the user scrolls manually. Replaces the pulsing dots.
- [x] Indicator insertion follows Metrolist's merged-list rules: omit blank
      lyric rows; use word end times or blank-line timestamps as gap starts;
      require a gap strictly longer than 4 s; include gaps before background
      vocals; and remove indicator items entirely when the setting is off.
- [x] Use the raw playback position for the ring's visibility and progress,
      and Metrolist's zero-gap placement before indicators and background
      vocals.

## 12. Round 3 — search fixes

- [x] Search dialog opens pre-filled with the current song (cleaned
      title + artist) and auto-searches it after the debounce.
- [x] Manual pick crash: LazyColumn keys collided when providers returned
      same-titled rows (KuGou does constantly); keys are now index-prefixed.
- [x] KuGou fetch was dead: candidate `id` arrives as a JSON string (decode
      into Long failed the whole response) and the download payload dropped
      its legacy `data` wrapper (top-level `content` now accepted too).
      Verified end-to-end against the live API.
- [x] BetterLyrics/LyricsPlus/Paxsenix single-match rows now query with the
      playing track's real title/artist and duration (BetterLyrics 401s
      without the duration parameter); the free-text field only drives the
      Lrclib/KuGou list searches.
- [x] Manual picks bypass the per-track lyric cache, which previously
      re-showed the cached auto pick instead of the selection.

## 13. Round 3 fix — top clipping

- [x] Lyrics no longer crop at the pill's top edge: the scroll flow gets
      half-viewport anchor padding above and below (Metrolist's padded-ends
      trick), so the offset clamping to 0 at song start or on short lyrics
      still renders the first line fully centered instead of clipped. Dim
      context-line alphas raised (0.55/0.4/0.3) so distant lines stay
      readable on light backgrounds.

## 14. Round 3 fix — manual pick feedback

- [x] Picking a result now applies synchronously with per-row feedback:
      "Applying…" spinner on the picked row, "Couldn't load (network?) —
      click to retry" when the provider fetch fails, and the pill falls back
      to the previously shown lyrics instead of silently walking the whole
      auto chain. Pick fetch is a single focused request (fast failure).
- [x] Legend added under the provider chips: pink dot = synced (karaoke)
      lyrics, gray dot = plain text; best-match rows (no dot) load on pick.

## 15. Round 3 fix — "network error" root cause

- [x] Live probe of every provider showed all of them healthy except one
      deterministic failure: BetterLyrics' API rejects requests without the
      duration parameter (HTTP 401), so picks stored with a length-less
      player (no mpris:length) always failed and were mislabeled "network?".
- [x] BetterLyrics now fails fast with an explicit reason when the player
      reports no duration, and pick failures carry the real reason
      ("BetterLyrics: HTTP 401", "Lrclib: track not found", ...) which the
      picked row displays instead of guessing at the network.

## 16. Round 3 fix — "lyrics fully gone"

- [x] The pill was gone because the app never started: `clean` + the failed
      `.deb` step left a broken packaged launcher (no `lib/`), and even a
      complete package segfaults at launch when `LD_LIBRARY_PATH` points into
      the T3 Code AppImage mount (Compose native launcher, `setenv`, same
      coredump as Sep 23). Root cause verified by sanitized-env launch.
- [x] `just build` now packages the AppImage only (packageDeb needs
      dpkg-deb, absent on Fedora); `just run-package` scrubs
      `LD_LIBRARY_PATH` before launching the packaged app.
- [x] Crash log handler installed earlier now guarantees a trace under
      ~/.local/share/lyricsfloat/crash.log for uncaught exceptions.

## 17. Round 3 fix — the real invisible-lyrics bug

- [x] Root cause found with a visual repro (fake playback pill): the anchor
      padding consumed the entire viewport (top + bottom = 100% height), so
      the scroll column measured its content at zero pixels tall and drew
      nothing. Replaced layout padding with a static anchor offset in
      `graphicsLayer.translationY` (`anchor - scrollOffset`); verified with a
      screenshot that lines render, dim correctly, and auto-scroll centers
      the active line.
- [x] Search dialog prefill now keys on the current song: when the track
      changes, the query field refills (and stale results re-search) instead
      of keeping the old song's text.

## 18. Round 3 fix — resize cropping

- [x] The scroll span now ends when the content bottom reaches the viewport
      bottom (previously the span ignored the viewport, so after a resize the
      content could detach — cropped top line, empty bottom half). Manual
      offsets are re-clamped on resize.
- [x] Metrolist-style edge fades: lines dissolve over a 26 dp band at the
      pill's top/bottom instead of being cut mid-glyph. Verified with the
      visual repro (fade + anchor centering on screen).
- [x] Clip the translated lyric stack to its viewport so hover controls and
      resized window edges cannot be overdrawn by lyrics.
- [x] Measure the full lyric stack with unbounded height and top alignment.
      The viewport-sized stack lost offscreen line heights, and Compose's
      default center alignment shifted the measured stack offscreen.
