# TAV: notes for Claude

A music visualizer with three editions sharing one audio engine: an Android app (`android/`, Java and
GLES 3.0, no Gradle), a terminal edition for Termux (`termux/tav.c`) and a browser edition (`web/`,
which loads the Android shaders). README.md is the user-facing description. Open work is in
`docs/next-session.md`.

## Map

- `android/src/io/github/christianernst/tav/`
  - `MainActivity.java`: all UI is built in code (no XML layouts). It holds the intro and permission
    cards, the long-press menu (`addRow(label, options, selected, chips, pick)`, one chip row per
    setting), the now-playing section with `TransportButton`s, the song-title overlay and gestures.
    Settings are in SharedPreferences `prefs` (keys `scene`, `sym`, `palette`, `brightness`, `source`,
    `autopilot`, `titleAlways`, `quality`, `motion` (0 Full, 1 Calm), `eq_mode` (0 Flat, 1 Punch, 2 Custom), `eq_master`, `eq_punch`, `eq_beat`,
    `eq_g0`–`eq_g3` (floats), and `custom0`–`custom2`, the custom palette as ARGB ints) and
    reach the GL thread through `view.queueEvent(() -> renderer.setX(..))`. Colours → Custom opens the
    colour editor card (`showColourEditor`, sliders are `GradientSlider.java`). The equalizer is the
    Equalizer row and its card (`showEqualizer`, `editEqualizer`).
  - `TavRenderer.java`: the frame is scene pass (feedback ping-pong FBOs) → points/particles →
    transition blend → dual-Kawase bloom → `composite.frag`. `uExposure` and `uBloomStrength` come from
    the Brightness setting (`EXPOSURE`/`BLOOM`, the same table as `BRIGHTNESS` in `web/engine.js`).
  - `Equalizer.java`: how hard the scenes react. A master, a gain per band, punch (blend from the
    auto-gained bands `an.bands` toward the absolute ones `an.bandsAbs`) and the beat strength. The
    renderer hands the scenes `eq.shapeBands`/`shapeLevel`/`shapeBeat`, never the raw analyzer values.
  - `AudioAnalyzer.java`: FFT, bands, beat, drop and flow; also `bandsAbs`, the bands in dB without
    auto-gain. The same logic lives in `web/analyzer.js` and
    `termux/tav.c`, so keep all three in sync.
  - `AdaptiveScale.java`: picks the render resolution from frame times (pure Java; shader builds are
    not counted as slow frames). `TavRenderer` builds the other shaders a frame at a time at start-up.
  - `AudioCapture.java` (Visualizer on session 0, mic fallback), `MediaWatcher.java` (MediaSession and
    Spotify broadcasts, play/pause/next/previous, position for the `Hairline` under the title, open the
    player), `MediaListenerService.java` + `NowPlayingNudge.java` (notification access; watches the media
    sessions in the background and posts the opt-in, silent "music started" notification, pref `nudge`:
    0 Off, 1 Spotify, 2 Any player, quiet for an hour after a swipe-away), `Palettes.java` (presets plus colours from album
    art → `uPal[3]`), `Scene.java`, `Shapes.java`, `TavView.java`.
- `android/assets/shaders/`: `common.glsl` is prepended to every shader. It has the uniforms,
  `pal()`, `glow()`, `feedback()` and `angle()`.
- `android/build.sh`: Gradle-free build (aapt2, javac `-source 8`, d8, apksigner). It works in Termux
  and with `ANDROID_HOME`.
- `android/tools/check.sh`: runs glslangValidator on every shader and link pair, plus the analyzer,
  adaptive-scale and equalizer self-tests (EqualizerCheck includes a groove with a verse 8 dB under the chorus).
- `web/tools/measure.js`: renders every scene at the demo's drop in headless Chromium on a virtual clock
  and prints clipped, near-white and saturated-flat shares per scene. `--track dense` stands in for a
  loud mastered song; `--root` measures another checkout (a `git worktree`) for before/after.
- `setup.sh`: one-shot Termux setup (repair packages, tools, optional signing, build, install).
- `.github/workflows/build.yml`: CI runs the checks, builds the APK (as an artifact on every push) and
  builds `termux/tav` with gcc and clang `-Werror`. The `latest` release is republished only on pushes
  to the default branch `ccr-00429428-wmfa7f`.

## Rules

- Never commit signing keys (`*.keystore` and `*.jks` are gitignored). Release signing uses the repo
  secrets `TAV_KEYSTORE_B64` and `TAV_KS_PASS`, which `android/tools/setup-signing.sh` sets from the
  owner's phone. Without them, CI signs each release with a one-off key.
- Language level is Java 8, with no AndroidX and no libraries. `android.jar` has no
  LambdaMetafactory: a stub in `tools/stubs` covers javac and d8 desugars lambdas. minSdk is 24,
  targetSdk is 35.
- Shaders are GLSL ES 3.00. Keep them NaN-free (one NaN texel spreads through bloom) and use
  `angle()` instead of a bare `atan(y, x)`. Feedback scales with `uStep` for frame-rate independence,
  and time uniforms wrap.
- Brightness: the tone curve in `composite.frag` is hue-preserving, so hot light stays coloured. Light
  that a scene adds into its own feedback stacks up to `gain / (1 - fade)`, and Mirror copies stack
  too, so keep such terms faint and divide by `sqrt(copies)`. Check a change with `web/tools/measure.js`
  (`--track dense`) before and after; Normal should stay at about 0 % clipped and near-white.
- No phone or emulator is available in the cloud. Verify with `android/tools/check.sh`, a full
  `android/build.sh` against the SDK, and screenshots of `web/index.html` in headless Chromium. Serve
  the repo root over http: the page loads `../android/assets/shaders`, so it renders the real Android
  shaders.
- The owner runs TAV on an Android phone with the Spotify app and builds in Termux.
