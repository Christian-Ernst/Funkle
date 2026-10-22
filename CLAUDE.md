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
    `eq_g0`–`eq_g3` (floats), `custom0`–`custom2`, the custom palette as ARGB ints, `cm_mode` (the Colour
    motion chip, see `ColourDrive.MODE_NAMES`), `cm_drift`/`cm_beat`/`cm_drop`/`cm_tilt`/`cm_pump` (floats) and
    `prefs_version` (2: Custom moved from palette index 8 to 11 when Ocean, Candy and Rainbow came)) and
    reach the GL thread through `view.queueEvent(() -> renderer.setX(..))`. Colours → Custom opens the
    colour editor card (`showColourEditor`, sliders are `GradientSlider.java`). The equalizer and the colour
    motion each have a chip row and a slider card (`sliderCard`/`openCard`/`closeCard`, `showEqualizer`,
    `showColourMotion`).
  - `TavRenderer.java`: the frame is scene pass (feedback ping-pong FBOs) → points/particles →
    transition blend → dual-Kawase bloom → `composite.frag`. `uExposure` and `uBloomStrength` come from
    the Brightness setting (`EXPOSURE`/`BLOOM`, the same table as `BRIGHTNESS` in `web/engine.js`).
  - `ColourDrive.java`: colour motion. Immutable settings (hue drift per second, hue step per beat, jump per
    drop, band tilt, band pump) and a `State` the renderer steps every frame: the hue in turns and the
    smoothed bass/mids/treble shares. They reach the shaders as `uColour` (hue, tilt, pump, rainbow flag) and
    `uBandMix`, and `pal()` in `common.glsl` applies them, so every scene gets them for free. `Palettes.java`
    also holds the preset JSON (`toJson`/`fromJson`, `{"v":1,"name","colours":[3 hex],"rainbow","motion":{..}}`)
    that the cloud sync uses; `web/engine.js` has `ColourDrive`, `COLOUR_MODES`, `exportPreset`/`importPreset`
    with the same numbers.
  - `Look.java`: a saved look (the colour preset JSON from `Palettes.toJson` plus `palette`, `scene`, `sym`,
    `brightness`, `calm`, `eq`), its share payload (`#d=` base64url JSON, opens without the cloud) and the
    link parsers; `LookStore.java` is the shelf in prefs `tav_looks`, mirrored to the cloud presets table
    (a public row gets the six-letter `#l=` code). `web/looks.js` is the same for the browser. The Looks
    cards, share sheet and the `tav://look` / web-link handling are in `MainActivity` (`showSaveLook`,
    `showLooks`, `showOpenCode`, `applyLook`, `shareLook`, `handleLink`); `refreshChips()` restyles every
    menu row from prefs. `Palettes.shuffle()` is the harmonious shuffle (JVM-safe, no android.graphics).
  - `DemoTrack.java`: the built-in beat (port of `web/demo.js`); `AudioCapture.startDemo` feeds it to the
    visuals on a clock, or out loud through an AudioTrack, so the first run moves before the permission ask.
  - `Equalizer.java`: how hard the scenes react. A master, a gain per band, punch (blend from the
    auto-gained bands `an.bands` toward the absolute ones `an.bandsAbs`) and the beat strength. The
    renderer hands the scenes `eq.shapeBands`/`shapeLevel`/`shapeBeat`, never the raw analyzer values.
  - `AudioAnalyzer.java`: FFT, bands, beat, drop and flow; also `bandsAbs`, the bands in dB without
    auto-gain. The same logic lives in `web/analyzer.js` and
    `termux/tav.c`, so keep all three in sync.
  - `AdaptiveScale.java`: picks the render resolution from frame times (pure Java; shader builds are
    not counted as slow frames). `TavRenderer` builds the other shaders a frame at a time at start-up.
  - `AudioCapture.java` (Visualizer on session 0, mic fallback), `MediaWatcher.java` (MediaSession and
    Spotify broadcasts, play/pause/next/previous), `Palettes.java` (presets plus colours from album
    art → `uPal[3]`), `Scene.java`, `Shapes.java`, `TavView.java`.
- `android/assets/shaders/`: `common.glsl` is prepended to every shader. It has the uniforms,
  `pal()` (with the colour motion and the Rainbow palette), `glow()`, `feedback()` and `angle()`.
- `android/build.sh`: Gradle-free build (aapt2, javac `-source 8`, d8, apksigner). It works in Termux
  and with `ANDROID_HOME`.
- `android/tools/check.sh`: runs glslangValidator on every shader and link pair, plus the analyzer,
  adaptive-scale, equalizer and colour-drive self-tests (EqualizerCheck includes a groove with a verse 8 dB under
  the chorus) and LookCheck (needs a real org.json jar, fetched once; android.jar's is a stub).
- `web/tools/looks-check.js`: the same for `web/looks.js`, in node, including a payload written by Look.java.
- `web/tools/measure.js`: renders every scene at the demo's drop in headless Chromium on a virtual clock
  and prints clipped, near-white and saturated-flat shares per scene. `--track dense` stands in for a
  loud mastered song; `--root` measures another checkout (a `git worktree`) for before/after; `--colour N`
  and `--palette 9` (Rainbow) cover the colour motion.
- `cloud/`: the Supabase side (see `cloud/README.md`): `supabase/migrations/0001_init.sql` is the schema
  (profiles, `user_settings`, `presets` as opaque jsonb with `v` and `name`, append-only `events`, and the
  SQL functions `sync_settings`, `ingest_events`, `trending_presets`, `preset_of_the_day`,
  `recommend_presets`, `me_summary`), `tools/check.sh` applies it to any Postgres and runs
  `tools/scenario.sql` (CI does this). The clients are `web/cloud.js` and `Cloud.java`, plain HTTPS and
  JSON, no SDK; both are no-ops without `web/cloud-config.js` / `android/assets/cloud.json` (committed; the publishable key is public).
  Events are batched and only ever written through `ingest_events`; the account card has the opt-out.
- `setup.sh`: one-shot Termux setup (repair packages, tools, optional signing, build, install).
- `.github/workflows/build.yml`: CI runs the checks, builds the APK (as an artifact on every push),
  builds `termux/tav` with gcc and clang `-Werror` and runs the cloud schema test on a Postgres service. The `latest` release is republished only on pushes
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
