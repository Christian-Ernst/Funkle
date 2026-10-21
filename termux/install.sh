#!/usr/bin/env bash
# One-shot Termux setup for the terminal visualizer: tools, build, install as `tav`.
set -euo pipefail
cd "$(dirname "$0")"
if [[ "${PREFIX:-}" == *com.termux* ]]; then
    pkg install -y clang make pulseaudio ffmpeg
fi
make
make install PREFIX="${PREFIX:-/usr/local}"
cat <<'MSG'

  tav is installed.

    tav song.mp3     play a file and watch it
    tav              visualize whatever Termux plays (mpv, ncspot = Spotify, ...)
    tav --mic        listen through the microphone (needs Termux:API's mic permission)
    tav --help       everything else

  Tap the screen for ripples, drag to draw with sound, space for the next scene.
MSG
