# TAV — Termux Audio Visualizer

Music turned into light on your phone. TAV listens to whatever is playing — Spotify, YouTube, a file —
and drives GPU shaders with it: rings that ripple out on every kick, a tunnel that speeds up with the
track, Joy Division ridgelines built from the spectrogram, a ferrofluid ball that grows spikes per
frequency. And you can draw on it: whatever shape you trace becomes a line made of the live sound wave.

![TAV scenes](docs/scenes.jpg)

There are two editions sharing one audio engine:

| | **Android app** (`android/`) | **Terminal** (`termux/`) |
|---|---|---|
| Reacts to the Spotify **app** | ✅ hooks the phone's output mix | — (Android won't let Termux hear other apps) |
| Reacts to Spotify **in Termux** (`ncspot`) | ✅ | ✅ |
| Plays + shows a music file | via any player | ✅ `tav song.mp3` |
| Renderer | OpenGL ES 3.0, bloom, feedback trails | braille dots + truecolor glow |
| Touch | tap = ripple, drag = draw with sound | tap = ripple, drag = draw with sound |

---

## Android app

### Install

1. On your phone, open the **[latest release](https://github.com/Christian-Ernst/TAV/releases/tag/latest)** and download **TAV.apk**.
2. Open it and allow installing from your browser when Android asks.
3. Start TAV, tap **Let it listen**, then play something in Spotify.

Or build it on the phone itself, in Termux:

```sh
pkg install git openjdk-21 aapt2 d8 apksigner zip unzip
git clone https://github.com/Christian-Ernst/TAV && cd TAV/android
./build.sh && termux-open build/TAV.apk
```

The first build downloads Android's framework stubs once (~64 MB).

### How it hears Spotify

TAV attaches Android's `Visualizer` effect to audio session 0, the phone's global output mix, so it sees
exactly what you hear from any app without that app's cooperation. Android puts this behind the
"record audio" permission. TAV only analyses the samples frame by frame; nothing is recorded, stored or sent.

If a phone's ROM blocks this (some do), TAV falls back to the microphone automatically. You can also pick
**Listen to → Microphone** yourself.

**Album colours (optional):** choose **Colours → Album** and grant notification access. TAV then reads the
current track's artwork from the media session and recolours every scene from it. It also shows the title
and artist when the song changes. Without notification access, Spotify can still announce the track
if you enable *Settings → Device Broadcast Status* in Spotify.

### Controls

| Gesture | Does |
|---|---|
| Tap | Ripple from that point; its rim is the live waveform |
| Drag | Draw a line the sound wave travels along |
| Two-finger swipe | Next / previous scene |
| Long-press or ☰ | Menu: scenes, shape stamps, mirror (mandala) mode, colours, audio source, autopilot, quality |

**Autopilot** (on by default) changes scenes when the track changes, when a drop hits, or every 45 s.
**Mirror** repeats everything you draw 2–12 times around the centre.

### Scenes

**Halo** — a plasma core, a waveform ring and spectral rays; every beat launches a ripple.
**Wormhole** — a neon tunnel flown at the speed of the music.
**Pulsar** — the spectrogram as receding ridgelines ("Unknown Pleasures").
**Mandala** — a kaleidoscope that folds over its own previous frame.
**Ferro** — a raymarched ferrofluid sphere; each spike listens to its own frequency.
**Nebula** — a 48 000-star galaxy whose rings breathe per band, with a shockwave on every beat.
**Spiro** — spirograph lace etched with the waveform.

---

## Terminal edition (Termux)

![The terminal edition in Termux](docs/terminal.jpg)

```sh
pkg install git
git clone https://github.com/Christian-Ernst/TAV && cd TAV/termux
./install.sh
```

```sh
tav song.mp3        # play a file and watch it
tav                 # visualize whatever Termux plays through PulseAudio
tav --mic           # listen through the microphone (grant Termux:API the mic permission first)
tav --help
```

**Spotify in the terminal:** `pkg install ncspot` (Spotify Premium), start `ncspot` in one Termux
session and `tav` in another. ncspot plays through PulseAudio, and `tav` listens to PulseAudio's monitor.

Keys: `space`/arrows switch scenes, `1`–`7` jump to one, `s` stamps a shape, `m` mirror mode, `c` clear,
`k` colours, `a` autopilot, `h` help, `q` quit. Tap the screen for ripples and drag to draw.
Pinch-zoom Termux out for more resolution. If it stutters, try `--fps 20` or `--lite`.
If sound and picture drift apart in file mode, use `--delay MS`.

---

## Browser edition (`web/`)

The same shaders in WebGL2, driven by a synthesized demo track or any song you open. Serve the repository
root and open `/web/`:

```sh
python3 -m http.server 8000    # then visit http://localhost:8000/web/
```

Turn on GitHub Pages for the repository and it works at `https://<user>.github.io/TAV/web/` too.
Browsers can't hear other apps, so it can't follow Spotify; that's what the Android app is for.

---

## How it works

```
samples ──► AudioAnalyzer ──► 512-bin log spectrum + peaks, triggered loop-seamless waveform,
            (FFT 1024, Hann)    spectrogram history, 4 auto-gained bands, beat pulse
                                (bass spectral flux vs. adaptive threshold), drops, music clock
                    │
                    ▼
   scene shader (reads its own last frame) → particles → your ripples & strokes
   → scene crossfade → dual-Kawase bloom → tone map, chromatic aberration on beats, grain
```

* `android/src/…/AudioAnalyzer.java`, `web/analyzer.js` and the analyzer in `termux/tav.c` are the same
  algorithm, so all editions react identically.
* Scenes are GLSL ES 3.00 files in `android/assets/shaders/`. The same files run in WebGL2
  (`web/engine.js`), which is how they were designed and screenshotted.
* Feedback warps are scaled by frame time, so 90/120 Hz screens look like 60 Hz ones.
  Resolution adapts when the GPU can't keep up.
* No Gradle and no libraries: `android/build.sh` drives `aapt2`, `javac`, `d8` and `apksigner`
  directly, which is why it also runs inside Termux.

### Checks

```sh
android/tools/check.sh   # every shader through glslangValidator + analyzer self-test (needs ANDROID_HOME)
android/build.sh         # builds android/build/TAV.apk
```

CI runs both, builds the terminal edition with gcc and clang, and publishes the APK as the
`latest` release on every push.
