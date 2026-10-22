# Branding

Everything the store listings and Google's OAuth branding page ask for, in one place.

## Assets (`docs/brand/`)

| File | Use |
|---|---|
| `icon.svg` | Master artwork, 108 × 108 viewBox (the adaptive-icon canvas; the middle 72 × 72 is the safe zone) |
| `icon-512.png` | Play Console app icon (512 × 512, 32-bit PNG) |
| `logo.svg`, `logo-120.png` | Google Auth Platform branding logo (120 × 120, the safe zone with rounded corners) |
| `icon-192.png`, `icon-96.png` | Web page favicon / home-screen icon |
| `gen.py` | Writes all of the above and the three vector drawables in `android/res/drawable/` (`ic_launcher_fg.xml`, `ic_launcher_bg.xml`, `ic_launcher_mono.xml`). Rendering the PNGs needs Playwright's Chromium: see the thread notes, or any SVG renderer. |

The artwork is two rings made of a sound wave around a bright core, in the app's default neon
(hot pink `#FF2E8C`, violet `#7B3CFF`, ice `#2EC8FF`) on the deep violet-black the scenes sit on.
Wider, fainter strokes stand in for bloom, since vector drawables cannot blur. The monochrome layer
is the same rings in solid white for Android 13 themed icons.

## Google Auth Platform → Branding

| Field | Value |
|---|---|
| App name | the product name (see below); it only has to match the store listing, not the package id |
| User support email | the Gmail address that owns the Cloud project |
| App logo | `docs/brand/logo-120.png` (uploading a logo on an External app triggers brand verification, which needs the three links below to be live) |
| Application home page | `https://christian-ernst.github.io/TAV/` |
| Application privacy policy link | `https://christian-ernst.github.io/TAV/privacy.html` |
| Application terms of service link | leave empty (optional) |
| Authorised domains | `christian-ernst.github.io` and `zgwpvbpqzktozxaxmqss.supabase.co` |
| Developer contact information | the same Gmail address |

The Cloud *project* name is what has the 4 to 30 character minimum; it is never shown to users, so
"TAV Visualizer" is fine there whatever the app ends up being called.

## Play Console

- App name: up to 30 characters. Short description: up to 80. Full description: up to 4000.
- Icon: `icon-512.png`. Feature graphic (1024 × 500) is optional since 2023 unless the app is promoted.
- Data safety form, from `privacy.html`: collects email (optional, account), app interactions (usage
  events, optional, deletable), no audio, no location; data encrypted in transit; deletion on request.
- Account deletion: the Deleting your data section of the privacy page is the URL Play asks for.
