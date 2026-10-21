#!/usr/bin/env bash
# Builds TAV.apk without Gradle: on a PC with the Android SDK, in CI, or right on
# the phone inside Termux.
#
#   Termux:   pkg install openjdk-21 aapt2 d8 apksigner zip unzip
#             ./build.sh && termux-open build/TAV.apk
#   PC / CI:  ANDROID_HOME=/path/to/sdk ./build.sh
set -euo pipefail
cd "$(dirname "$0")"

API=35
MIN_API=24
OUT=build
VERSION_NAME=${VERSION_NAME:-1.0.0-local}
# Minutes since 1970: always increases, so a newer build installs over an older one.
VERSION_CODE=${VERSION_CODE:-$(( $(date +%s) / 60 ))}
# Signing key: TAV_KEYSTORE if given (CI passes the one from repository secrets), otherwise a
# key made once for this device. Keys are never kept in the repository.
KEYSTORE=${TAV_KEYSTORE:-$HOME/.config/tav/tav.keystore}
KS_PASS=${TAV_KS_PASS:-android}
KS_ALIAS=${TAV_KS_ALIAS:-tav}

die() { echo "build.sh: $*" >&2; exit 1; }
need() { for t in "$@"; do command -v "$t" >/dev/null 2>&1 || die "missing '$t'. $HINT"; done; }

if [[ "${PREFIX:-}" == *com.termux* ]]; then
    HINT="Run: pkg install openjdk-21 aapt2 d8 apksigner zip unzip"
    need aapt2 d8 apksigner javac java zip unzip curl
    AAPT2=aapt2 D8=d8 APKSIGNER=apksigner
    JAR=${TAV_ANDROID_JAR:-$HOME/.cache/tav/android-$API.jar}
    if [ ! -s "$JAR" ]; then
        # Termux ships the build tools but not the framework stubs; fetch them once (~64 MB download).
        mkdir -p "$(dirname "$JAR")"
        echo "Downloading android-$API platform stubs (once)..."
        tmp=$(mktemp -d)
        curl -fL --progress-bar -o "$tmp/platform.zip" "https://dl.google.com/android/repository/platform-${API}_r02.zip"
        unzip -p "$tmp/platform.zip" "android-$API/android.jar" > "$JAR.part"
        mv "$JAR.part" "$JAR"
        rm -rf "$tmp"
    fi
else
    HINT="Install the Android SDK (build-tools + platforms;android-$API) and set ANDROID_HOME."
    SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
    [ -n "$SDK" ] || die "ANDROID_HOME is not set. $HINT"
    BT=$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)
    [ -n "$BT" ] || die "no build-tools in $SDK. $HINT"
    AAPT2=$BT/aapt2 D8=$BT/d8 APKSIGNER=$BT/apksigner
    JAR=$SDK/platforms/android-$API/android.jar
    [ -f "$JAR" ] || die "missing $JAR. $HINT"
    need javac java zip
fi

if [ ! -f "$KEYSTORE" ]; then
    command -v keytool >/dev/null 2>&1 || die "missing 'keytool' (part of the JDK). $HINT"
    mkdir -p "$(dirname "$KEYSTORE")"
    keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KS_PASS" -keypass "$KS_PASS" -alias "$KS_ALIAS" \
        -keyalg RSA -keysize 2048 -validity 20000 -dname "CN=TAV" >/dev/null 2>&1 || die "keytool failed"
    echo "Created a signing key for this device: $KEYSTORE"
fi

rm -rf "$OUT"
mkdir -p "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "[1/5] resources"
"$AAPT2" compile --dir res -o "$OUT/res.zip"
"$AAPT2" link -o "$OUT/base.apk" -I "$JAR" --manifest AndroidManifest.xml -A assets \
    --java "$OUT/gen" --min-sdk-version "$MIN_API" --target-sdk-version "$API" \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" "$OUT/res.zip"

echo "[2/5] java"
# android.jar lacks LambdaMetafactory; compile a stub for javac only (d8 desugars lambdas).
mkdir -p "$OUT/stubs"
javac -source 8 -target 8 -bootclasspath "$JAR" -Xlint:-options -nowarn -d "$OUT/stubs" \
    tools/stubs/java/lang/invoke/LambdaMetafactory.java
find src "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -source 8 -target 8 -bootclasspath "$JAR:$OUT/stubs" -encoding UTF-8 -Xlint:-options -nowarn \
    -d "$OUT/classes" @"$OUT/sources.txt"

echo "[3/5] dex"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$D8" --release --min-api "$MIN_API" --lib "$JAR" --output "$OUT/dex" @"$OUT/classes.txt"

echo "[4/5] package"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -9 ../unsigned.apk classes.dex)

echo "[5/5] sign"
# apksigner also 4-byte-aligns stored entries (resources.arsc), which Android 11+ requires.
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
    --ks-key-alias "$KS_ALIAS" --out "$OUT/TAV.apk" "$OUT/unsigned.apk"
"$APKSIGNER" verify "$OUT/TAV.apk"
rm -f "$OUT/TAV.apk.idsig"
echo "Built $(pwd)/$OUT/TAV.apk ($(du -k "$OUT/TAV.apk" | cut -f1) KB)"
