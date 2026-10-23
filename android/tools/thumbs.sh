#!/usr/bin/env bash
# Renders the scene thumbnails the menu shows (assets/thumbs/<scene id>.jpg): every scene at the
# demo's drop in headless Chromium with the Neon palette, the centre of the frame, 240x320.
# Needs node with playwright and ImageMagick's convert. Run again when a scene changes its look.
set -euo pipefail
cd "$(dirname "$0")/../.."
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
node web/tools/measure.js --palette 1 --out "$tmp" > /dev/null
mkdir -p android/assets/thumbs
for f in "$tmp"/*_17.0.png; do
    id=$(basename "$f" _17.0.png)
    convert "$f" -gravity center -crop 405x540+0+0 +repage -resize 240x320 -quality 82 "android/assets/thumbs/$id.jpg"
done
# the Canvas is black until someone paints on it: its thumbnail is the paint check's sample painting
node web/tools/paint-check.js --out "$tmp/paint" > /dev/null
convert "$tmp/paint/7-sample.png" -gravity center -crop 405x540+0+0 +repage -resize 240x320 -quality 82 android/assets/thumbs/canvas.jpg
ls -la android/assets/thumbs
