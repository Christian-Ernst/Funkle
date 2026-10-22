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

## Colour editor follow-ups (ask first)

- A "Shuffle" chip that rolls a harmonious random palette (golden-ratio hue steps).
- Several named custom palettes instead of one (`custom0`–`custom2` is a single slot today).
- The web edition has the Brightness control (button or `b`) but no custom-colour editor.

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
