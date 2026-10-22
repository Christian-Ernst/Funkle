#!/usr/bin/env bash
# Installs the APK on the connected device or emulator, launches TAV, waits, and fails if the app is
# gone or logcat shows a crash. Used by CI (build.yml, job "smoke"); works against a phone too.
set -euo pipefail
APK=${1:-android/build/TAV.apk}
PKG=io.github.christianernst.tav
WAIT=${SMOKE_SECONDS:-15}

adb wait-for-device
adb install -r -g "$APK"
adb logcat -c
adb shell am start -W -n "$PKG/.MainActivity"
echo "[smoke] launched, watching for $WAIT s"
for i in $(seq "$WAIT"); do
    sleep 1
    if ! adb shell pidof "$PKG" > /dev/null 2>&1; then
        echo "[smoke] the process is gone after $i s"
        adb logcat -d -v time '*:E' | tail -80
        exit 1
    fi
done
adb logcat -d -v time > smoke.log
if grep -E "FATAL EXCEPTION|AndroidRuntime.*$PKG" smoke.log; then
    echo "[smoke] crash in logcat"
    grep -A 40 "FATAL EXCEPTION" smoke.log | head -80
    exit 1
fi
if grep -q "shader build failed" smoke.log; then
    echo "[smoke] a shader failed to build on this GPU"
    grep -B 2 -A 12 "shader build failed" smoke.log | head -60
    exit 1
fi
FOCUS=$(adb shell dumpsys activity activities | grep -E "mResumedActivity|ResumedActivity" | head -1 || true)
echo "[smoke] resumed: $FOCUS"
case "$FOCUS" in *"$PKG"*) ;; *) echo "[smoke] TAV is not the resumed activity"; exit 1;; esac
echo "[smoke] alive after $WAIT s, no crash, shaders built"
