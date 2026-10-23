# Next session: open items

## The menu as a bottom sheet (2026-10-22, judged on an emulator only)

The long-press menu is a bottom sheet with pages (`Sheet.java`, `MainActivity.buildSheet`), a quick bar over the
visuals (scene ‹ ›, pause, shuffle, clear) and a first-run "Three moves" card for the gestures. Not yet on a phone:

- Haptics (`Sheet.tick`, CLOCK_TICK on chips, tiles and slider release) are silent on the emulator; check they
  feel right and not chatty. The drag-to-close threshold (22 % of the sheet's height) is a guess.
- The sheet takes up to 68 % of the screen (`Sheet.MAX_SHARE`); on a short phone the home page scrolls. The
  see-through on slider drag (`setGhost`, alpha 0.38) is meant for judging a change on the scene itself.
- The scene thumbnails are the web edition's frames at the demo's drop (Neon palette); rerun
  `android/tools/thumbs.sh` when a scene's look changes, or they lie.
- Pause freezes the frame (render mode WHEN_DIRTY) and keeps the quick bar on screen; Exit Funkle finishes the
  activity, which stops rendering, capture and the media watcher (onStop). The notification listener service
  stays, by design, while notification access is granted.

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

## Spotify integration (branch claude/project-thread-7cbnl1)

- New and not yet on a phone: cover art + progress hairline in the title overlay, "Open Spotify" in the menu,
  and the opt-in **Light up when music starts** notification (`NowPlayingNudge`). Check on the phone that the
  notification appears when Spotify starts with TAV closed, vanishes on pause, and never shows while TAV is open.
- Android 13+ on a sideloaded APK: notification access is a "restricted setting" (the card explains it), and
  POST_NOTIFICATIONS is asked for after access is granted.
- Not done, by design: Spotify Web API / App Remote (nothing to gain: audio features are closed to new apps, the
  media session already has title, artist and cover). Clips cannot carry Spotify audio (Visualizer is 8-bit for
  visuals only, Spotify opts out of AudioPlaybackCapture, and the licence forbids it): share silent loops.
- Web edition: browsers cannot see Spotify's playback. A possible path is tab-audio capture (`getDisplayMedia`
  with audio on the Spotify tab) plus the Web API's currently-playing endpoint for cover and title, which would
  need a Spotify developer app (client id, redirect URI, PKCE); not started.

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
- Still open from the review: saved looks with thumbnails and a share sheet.

## Quality: Auto, Low, Medium, High (2026-10-22, not yet on a phone)

The Quality row became Auto (default; starts at 70 % of the screen's pixels and climbs to 100 % after 45 s of fast
frames, steps down to about a third when frames run late), Low (50 %), Medium (70 %) and High (100 %); the web
edition has the same button (`q`) and the same `AdaptiveScale` class, and the setting syncs between devices.

- On the phone: with Auto, watch whether the climb to 100 % is visible as a sharpness step (three steps of 15 %);
  if it bothers, lengthen `RAISE_AFTER` or make the steps smaller. "Fast" follows the panel's refresh rate
  (`Display.getRefreshRate()`, re-read when the panel switches): 55 fps on 60 Hz, 83 fps on 90/120 Hz, so a
  Samsung at 120 Hz climbs only with real headroom and sits still at a steady 60 fps. Not yet seen on a phone.
- A phone on the edge gets a doubling back-off (15, 30, 60, 120 s) before each new climb; the self-tests cover it.

## Two new scenes and per-scene sliders (2026-10-22, not yet on a phone)

Ink (`fluid.frag`) and Horizon (`landscape.frag`) are new, and every scene has three sliders (menu → Tune
this scene; web: the Tune button or `t`) that reach the shaders as `uTune`. The middle of a slider is the
look the scene had before; the ends were picked so Normal brightness stays at 0 % clipped on the dense track
(`web/tools/measure.js --track dense --tune 1,1,1`).

- Ink, Horizon and Mandala were redone on 2026-10-23 after the owner saw them on the phone (Ink did not move,
  Horizon's terrain was "really bad", Mandala "weird"). Ink: the current is the curl of a domain-warped noise
  that evolves with the music clock (`psi()`), the dye fades at 0.99 per 60 Hz frame (half-life about a
  second, so the streaks show the flow) and four droppers feed it. Horizon: no longer a raymarched terrain
  (that was also a near twin of Pulsar's ridgelines); it is a night sea that mirrors a striped sun and a
  skyline made of the spectrogram at ages 96..120, with crests rolling toward the camera on the music clock.
  It costs one `sky()` evaluation per pixel (two on the water) and no march. Mandala: filled gems (`glow(max(d,
  0))`) on six rings up to r = 1.72, a gentler echo (zoom 1.012 per frame), a smooth lace. All three judged
  only in headless Chromium: check on the phone that Ink's motion is smooth and visible between drops (raise
  `gain` or the fade if it is too thin), that Horizon's skyline varies on a real song (the dense test track
  saturates it into a plateau) and that Mandala's gems are not too big at Jewels = 100 %.
- The sliders' ends (`tune(k, lo, hi)` in each shader) are guesses from the measurement, not from a phone:
  Halo at full Trails and Spiro at full Trails give large solid (not white) blobs; shorten those ranges if
  they look muddy on an OLED.
- The Look format (PR #6) carries the sliders as `"tune": {id: 0..1}` for the Look's scene; whichever of the
  two PRs merges last wires `captureLook`/`applyLook` to `tune_<scene>_<k>` and `setTune`.

## Music-aware autopilot (new, not yet on a phone)

- Menu → Autopilot: Off, Scenes, Scenes + colours (default). The director is `Autopilot.java` / `Autopilot` in
  `web/engine.js`; the thresholds (energy bands 0.3 and 0.65, 125 bpm for Bands, 12 s minimum section length,
  the 45–90 s hold) were set on the synthetic song in `AutopilotCheck`. Check on real songs that a verse stays
  calm, that the build-up hold is noticeable, and that the switch one bar after the drop feels like the music
  rather than late. If drops switch too late, lower the bar wait (`dropBeats >= 4`); if colours change too
  often, raise the 12 s section minimum.
- Each scene's energy range (`Autopilot.MOODS`, `SCENE_MOODS`) is a guess from the look of the scene. A new scene
  gets the full range until it has an entry.
- The default is Scenes + colours because a new install has Album colours, which it leaves alone, so the default
  costs nothing there and shows the feature on presets. If the colour changes read as unwanted, make Scenes the
  default (`Autopilot.DEFAULT`, `DEFAULT_AUTOPILOT`).

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

## Looks, sharing and the first run (new, not yet on a phone)

- Menu → Looks: Save this look, My looks (tap to apply, Share, ✕), Open a code or link. Sharing uses the
  system share sheet with a link `https://christian-ernst.github.io/TAV/web/#l=code&d=payload`; the payload
  is the look itself, so the link works without the cloud, and the code comes only when the cloud is
  configured and signed in (a shared look is made public in the presets table). **GitHub Pages must be on**
  (Settings → Pages → deploy from the default branch, root) for the links to open in a browser; without it
  the app still catches `tav://look` and the https link when chosen as the handler.
- The https intent filter is not verified (no assetlinks.json), so on Android 12+ a tapped link opens the
  browser; the web page then shows *Open in the TAV app* (`tav://look?d=...`). Verifying needs the page
  `/.well-known/assetlinks.json` with the release key's fingerprint, once signing is settled.
- First run: the visuals start on the built-in beat (DemoTrack, synthesized in a background thread) before
  the permission card; *Hear the demo* plays it out loud. The `first_visual` event records the seconds from
  first open. Check on the phone that the beat starts within a couple of seconds and that AudioTrack
  playback lines up with the visuals.
- Shuffle: Colours → Shuffle and the chip in the editor (Android), `x` or the button (web).
- Not done: a QR code for a look, a gallery of other people's public looks (the SQL exists), the daily look.
- The web edition has the Brightness control (button or `b`) but no custom-colour editor; a look with custom
  colours shows as *Colours: <name>* there.

## Cloud (accounts, sync, events)

- The Supabase project does not exist yet. The owner creates it and follows `cloud/README.md`
  (ten minutes); until then both clients run offline and the Account entry is hidden.
- Open for the next session: the preset gallery UI (list, publish, like, fork; the SQL and the
  client calls exist in `web/cloud.js` and `Cloud.java`, nothing draws them yet), "preset of the
  day" on the start card. The menu chips now follow a newer cloud copy of the settings (`refreshChips`).
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

## Touch: drawing fixed, gestures that bend the picture (2026-10-22, not yet on a phone)

The drawing sat away from the finger because `stroke.vert` turned every stroke with the music clock (`uFlow`),
so after a minute of music the line was a quarter turn from where it was drawn; copy 0 now stays put. New
gestures (`TavView`, same in `web/index.html`): tap = burst (rings plus spectrum sparks, `ripples.frag`, and
a shove of the paint around it), drag = draw and stir (the paint under the finger comes along, a curve turns
it), pinch = zoom in around the fingers (springs back 1.5 s after they lift), tilt = the scene leans with the
phone (gravity sensor, deviations from how it is held), three-finger tap = clear. All of it goes through
`uView`/`uTouch`/`uPush` in `toP()` of `common.glsl`, so every scene has it. `web/tools/touch-check.js` plays
the gestures in headless Chromium and fails when the drawn light is not under the finger.

- Judged only in headless Chromium: the strengths (`TavRenderer.STIR_MAX`, `LENS_MAX`, `BLAST`, `TILT_PAN`,
  `TOUCH_REACH`; the same `TOUCH` table in `web/engine.js`) are guesses. On the phone, check that stirring a
  circle visibly turns the trails without smearing the whole scene, that a tap's shove is felt and not jarring,
  and that the tilt is a lean, not a wobble (raise `TILT_PAN` if it is invisible, lower it if it drifts).
- Zoom springs back on purpose (a user can't get stuck zoomed in); if people want it to stay, drop the
  `ZOOM_HOLD` reset in `touchFrame`.
- Ink and Horizon have no feedback trails, so the stir and the drag move only the live picture there; the lens
  twist is what shows.
- The Modern menu thread's quick-action bar calls `renderer.clearStrokes()`/`undoStroke()` and listens to
  `Host.onStrokesChanged(count)`.
