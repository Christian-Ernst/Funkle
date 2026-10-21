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
    Settings are in SharedPreferences `prefs` (keys `scene`, `sym`, `palette`, `source`, `autopilot`,
    `titleAlways`, `quality`) and reach the GL thread through `view.queueEvent(() -> renderer.setX(..))`.
  - `TavRenderer.java`: the frame is scene pass (feedback ping-pong FBOs) → points/particles →
    transition blend → dual-Kawase bloom → `composite.frag`. `uBloomStrength` 0.9 and `uExposure` 1.0
    are hard-coded in the composite pass.
  - `AudioAnalyzer.java`: FFT, bands, beat, drop and flow. The same logic lives in `web/analyzer.js` and
    `termux/tav.c`, so keep all three in sync.
  - `AudioCapture.java` (Visualizer on session 0, mic fallback), `MediaWatcher.java` (MediaSession and
    Spotify broadcasts, play/pause/next/previous), `Palettes.java` (presets plus colours from album
    art → `uPal[3]`), `Scene.java`, `Shapes.java`, `TavView.java`.
- `android/assets/shaders/`: `common.glsl` is prepended to every shader. It has the uniforms,
  `pal()`, `glow()`, `feedback()` and `angle()`.
- `android/build.sh`: Gradle-free build (aapt2, javac `-source 8`, d8, apksigner). It works in Termux
  and with `ANDROID_HOME`.
- `android/tools/check.sh`: runs glslangValidator on every shader and link pair, plus the analyzer
  self-test.
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
- No phone or emulator is available in the cloud. Verify with `android/tools/check.sh`, a full
  `android/build.sh` against the SDK, and screenshots of `web/index.html` in headless Chromium. Serve
  the repo root over http: the page loads `../android/assets/shaders`, so it renders the real Android
  shaders.
- The owner runs TAV on an Android phone with the Spotify app and builds in Termux.
