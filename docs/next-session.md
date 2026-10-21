# Next session: brightness, own colours, sharing

These are the owner's requests after the first run on a real phone (Oct 2026). Everything else works
on the device. Delete or update this file once the work is done.

## 1. Too bright: centres blow out to a white blob

**Report:** three phone photos (Nebula, Spiro, Halo/Mandala). In each one the middle of the screen is a
flat white shape, and colour only survives at the edges. The owner asked for an intensity control.

**Likely causes (check before fixing):**

- `composite.frag` tone-maps with per-channel ACES. Anything far above 1.0 desaturates to white. The
  input is `scene + bloom × 0.9 × (1 + 0.6·beat)`, multiplied by `exposure 1.0 × (1 + 0.15·beat) + flash`.
  Both 0.9 and 1.0 are hard-coded in `TavRenderer` (composite pass) and in `web/engine.js` (~line 283).
- Additive geometry piles up in the centre. The points use `glBlendFunc(GL_ONE, GL_ONE)`, and their
  intensity is not normalised:
  - Spiro draws 4096 points × `uCopies` (sym, or 6 when Mirror is off). Every rosette passes through
    the middle.
  - Nebula has 48,000 stars that thin out from the core (`nebula.vert`, `vCol … * 0.6`).
  - Strokes and feedback trails add on top.
- A phone in a dark room (OLED, high brightness) shows this much more harshly than the desktop
  screenshots that set the original tuning.

**Plan:**

1. Add a menu row "Brightness" with four chips, Dim / Soft / Normal / Bright, stored in pref
   `brightness`. Pick the default after measuring (probably Soft or Normal). It calls
   `renderer.setBrightness(level)`, which drives `uExposure` and `uBloomStrength` and scales the beat
   lift and the drop flash with them. As a starting point, try exposure 0.45 / 0.65 / 0.85 / 1.1 and
   bloom 0.45 / 0.65 / 0.85 / 1.0.
2. Fix the white-out whatever the setting. Use a hue-preserving tone curve: map luminance (or the max
   channel) through the curve, scale RGB by the same ratio, then roll only extreme highlights toward
   white. Hot cores should stay coloured.
3. Normalise the per-scene build-up. Divide point intensity by about `sqrt(uCopies)`, so Mirror does
   not multiply brightness, and reduce the core density or gain of Nebula and Spiro. Check halo,
   kaleido, pulsar and tunnel with the same measurement.
4. Mirror the defaults (and the control, if simple) in `web/engine.js`, so web screenshots stay
   representative.

**Measure, don't guess:** serve the repo root, open `web/index.html` in headless Chromium (Playwright
is preinstalled) and capture every scene during the demo track's drop. For each image, report:

- the share of pixels with all channels above 0.95 (clipped white);
- the mean saturation of the brightest 5%.

Do this before and after the change. The target at Normal is a few percent clipped at most, with
cores that keep their hue.

## 2. Own colours

**What exists:** menu → "Colours", with Album (from the album art, which needs notification access),
Neon, Sunset, Toxic, Ice, Inferno, Vapor and Aurora. A palette is 3 RGB colours (`float[3][3]`). It
goes through `Palettes`, then `MainActivity.pushPalette()`, then `renderer.setPalette()`, then
`uPal[3]`, and `pal(t)` blends the three in a loop. The owner may not have found this row, so point
it out.

**Request:** let the owner add their own colours.

**Plan:**

1. Add a "Custom" chip at the end of the Colours row. It opens an editor card in the same glass style
   as the menu. The card has three swatches; tap one to edit it with hue (0–360), saturation and
   brightness, using SeekBars or a Canvas hue ring.
2. The editor previews live: it pushes the palette while dragging.
3. Store the colours as `custom0` to `custom2` (ARGB ints). Start with one custom palette. Several
   named slots can come later if the owner wants them.
4. Keep colours vivid: clamp brightness to at least about 0.5, because the neon look depends on it.
5. Optional, ask first: a "Shuffle" chip that generates a harmonious random palette (golden-ratio
   hue steps).

## 3. Sharing and release state

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

## Done when

- `android/tools/check.sh` passes (needs the SDK's `platforms;android-35`), `android/build.sh` builds,
  and CI is green.
- Before/after numbers and screenshots are reported to the owner.
- README is updated (Controls/menu list, the new Brightness and Custom colours).
