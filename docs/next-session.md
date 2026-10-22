# Next session: open items

Brightness (menu → Brightness, plus a hue-preserving tone curve and per-scene normalisation) and own
colours (menu → Colours → Custom) are done. What is still open, and what to ask the owner:

## Feedback to collect from the phone

- Is the default Brightness right? Normal measures at 0 % clipped and 0 % near-white in every scene,
  even on the dense stress track, and is about 25 % darker on average than before. If Normal still
  feels too bright on the OLED in a dark room, make Soft the default (`DEFAULT_BRIGHTNESS` in
  `TavRenderer.java` and `web/engine.js`).
- A phone camera exposes for the dark room and clips anything bright, so photos of the screen look
  whiter than the screen itself. Judge by eye, or with a screenshot (power + volume down).
- Motion → Calm (no beat pulses, beat ripples or drop flashes) is new and not yet on a phone. Check that
  the name and the wording in the menu are clear, and whether it should be the default for some users.
- The web edition has no Motion setting yet, so Calm applies to the Android app only.
- The equalizer (menu → Equalizer) is new and not yet on a phone. Its defaults (Punch: punch 70 %, beat
  130 %) and the absolute level range (−60 to −6 dB, `AudioAnalyzer.ABS_FLOOR_DB`/`ABS_RANGE_DB`) were set
  on a synthetic groove. Check them on a real song: loud sections should hit clearly harder than quiet
  ones, and boosted bands should not white out the scenes (the brightness measurement is the guardrail).
- The equalizer is Android only. The web edition and the terminal edition still react with the flat look.

## Scenes composed for portrait, drops that transform (2026-10-22)

Every scene now fills a phone held upright (Halo's outer ring and rays, Ferro's mirror floor, Spiro's stretched
rosette and spectrogram curtain, Nebula's disc along the long axis, Pulsar's aurora) and has a drop move on
the `drop()` envelope; the music clock surges on a drop (`flowExtra` in `TavRenderer` and `engine.js`). The
composite adds the palette's deep tone (`palBg()`) as ambient, and `palHi()` replaces the plain whites.
Album colours keep the cover's saturation and brightness above a floor (`Palettes.ALBUM_MIN_SAT/VALUE`).

- Normal measures 0 % clipped and 0 % near-white on the dense track in every scene; mean luma roughly
  doubled (0.06 to about 0.12) because the screen is no longer half black. If Normal now feels too bright
  on the phone, lower the ambient (`palBg() * 0.8` in `composite.frag`) before touching the scenes.
- Not judged on a phone: the drop moves' strength (`drop()` terms in each scene), the Ferro floor ripple,
  and whether Mandala's fold-count re-roll on a drop is welcome or jarring.
- Still open from the review: saved looks with thumbnails, music-aware autopilot and a share sheet.

## Two new scenes and per-scene sliders (2026-10-22, not yet on a phone)

Ink (`fluid.frag`) and Horizon (`landscape.frag`) are new, and every scene has three sliders (menu → Tune
this scene; web: the Tune button or `t`) that reach the shaders as `uTune`. The middle of a slider is the
look the scene had before; the ends were picked so Normal brightness stays at 0 % clipped on the dense track
(`web/tools/measure.js --track dense --tune 1,1,1`).

- Ink: judged only in headless Chromium. The current (`vel`), the dye amount (`gain`) and the fade
  (0.978 per 60 Hz frame) were set so a quiet verse leaves thin wisps and a drop fills the screen. If it
  reads as too dark between drops on the phone, raise `gain` first; the ink stacks in the feedback, so
  re-measure.
- Horizon: 64 march steps and three spectrogram taps per step; check Quality → Battery on an older phone
  (AdaptiveScale should catch it). The grid lines alias into a shimmer near the horizon; the haze hides most
  of it, and a cheaper fix would be to fade the lines with distance.
- The sliders' ends (`tune(k, lo, hi)` in each shader) are guesses from the measurement, not from a phone:
  Halo at full Trails and Spiro at full Trails give large solid (not white) blobs; shorten those ranges if
  they look muddy on an OLED.
- The Look format (PR #6) carries the sliders as `"tune": {id: 0..1}` for the Look's scene; whichever of the
  two PRs merges last wires `captureLook`/`applyLook` to `tune_<scene>_<k>` and `setTune`.

## Colour motion (new, not yet on a phone)

- Menu → Colour motion: Still (the old look), Drift, Beat (default), Bands, Custom (five sliders). Plus the
  Ocean, Candy and Rainbow palettes. The web edition has the same (button or `d`). The default is
  `ColourDrive.DEFAULT` and `DEFAULT_COLOUR_MODE` in `web/engine.js`; if Beat is too busy on the phone,
  Drift is the calm alternative.
- The beat step (1/16 turn) and the drop jump (1/3 turn) were picked on the demo beat. On a real song, check
  that Beat is a pulse and not a strobe, and that Bands reads as "bass is colour 1, treble is colour 3".
- Band pump (Bands: 40 %) raises one colour by up to that much when its band dominates; the measurement stays
  at 0 % clipped on the dense track, but check Bright on the phone.
- The cloud thread stores a preset as the JSON from `Palettes.toJson` / `exportPreset` (`v`, `name`,
  `colours`, `rainbow`, `motion`). The app itself still keeps everything in separate prefs; a "save as named
  preset" UI is the next step and would use that document.

## Colour editor follow-ups (ask first)

- A "Shuffle" chip that rolls a harmonious random palette (golden-ratio hue steps).
- Several named custom palettes instead of one (`custom0`–`custom2` is a single slot today).
- The web edition has the Brightness control (button or `b`) but no custom-colour editor.

## Cloud (accounts, sync, events)

- The Supabase project does not exist yet. The owner creates it and follows `cloud/README.md`
  (ten minutes); until then both clients run offline and the Account entry is hidden.
- Open for the next session: the preset gallery UI (list, publish, like, fork; the SQL and the
  client calls exist in `web/cloud.js` and `Cloud.java`, nothing draws them yet), "preset of the
  day" on the start card, and the Android menu chips refreshing after a newer cloud copy of the
  settings lands (today they show the new choice after a restart).
- Android cannot be built in the cloud session without the SDK build tools; CI builds the APK.
  The sign-in deep link `tav://auth` and the Google flow have not run on a phone yet.

## Sharing and release state

- The repo is public, so the link https://github.com/Christian-Ernst/TAV/releases/tag/latest can be
  shared as it is.
- As of commit 4bb16d9 the signing secrets are **not** set. Every CI release is therefore signed
  with a new one-off key, and installing an update means uninstalling first. Before wider sharing,
  the owner should run step 4 of `bash setup.sh` in Termux (or `android/tools/setup-signing.sh`).
- A new session works on its own branch. CI uploads the APK as an artifact on every push, but the
  `latest` release only changes when the work lands on `ccr-00429428-wmfa7f`. Open a PR into that
  branch and let the owner merge it.
- There is no LICENSE file. Sharing the APK doesn't need one. If the owner wants others to reuse the
  code, ask which license to add.
