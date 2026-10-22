#!/usr/bin/env bash
# Fast checks that need no phone: every shader through Khronos' reference compiler
# (GLSL ES 3.00, exactly as the app assembles them) and the analyzer self-test on the JVM.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=build/check
rm -rf "$OUT"
mkdir -p "$OUT/glsl" "$OUT/classes"

if command -v glslangValidator >/dev/null 2>&1; then
    echo "[shaders]"
    S=assets/shaders
    for f in "$S"/*.frag "$S"/*.vert; do
        name=$(basename "$f")
        {
            echo "#version 300 es"
            cat "$S/common.glsl"
            echo
            if [[ $name == *.frag ]]; then
                grep -q vUv "$f" && echo "in vec2 vUv;"
                echo "out vec4 fragColor;"
            fi
            cat "$f"
        } > "$OUT/glsl/$name"
        glslangValidator "$OUT/glsl/$name" > "$OUT/glsl/$name.log" 2>&1 || { cat "$OUT/glsl/$name.log"; exit 1; }
    done
    (cd "$OUT/glsl" && glslangValidator -l fullscreen.vert halo.frag > /dev/null \
        && glslangValidator -l nebula.vert particle.frag > /dev/null \
        && glslangValidator -l scope.vert particle.frag > /dev/null \
        && glslangValidator -l stroke.vert stroke.frag > /dev/null) || { echo "link check failed"; exit 1; }
    echo "  $(ls "$S" | grep -c -E '\.(frag|vert)$') shaders compile and link"
else
    echo "[shaders] skipped: glslangValidator not installed"
fi

echo "[analyzer]"
JAR=${ANDROID_JAR:-${ANDROID_HOME:-}/platforms/android-35/android.jar}
[ -f "$JAR" ] || { echo "set ANDROID_HOME (needs platforms;android-35)"; exit 1; }
# every source, against a stand-in R class with whatever ids and drawables the sources name (aapt2
# generates the real one in build.sh): the Look check pulls in most of them
mkdir -p "$OUT/gen"
{
    echo "package io.github.christianernst.tav;"
    echo "public final class R {"
    for kind in $(grep -rhoE '\bR\.[a-z]+\.[A-Za-z0-9_]+' src | cut -d. -f2 | sort -u); do
        echo "  public static final class $kind {"
        n=1; for name in $(grep -rhoE "\\bR\\.$kind\\.[A-Za-z0-9_]+" src | cut -d. -f3 | sort -u); do
            echo "    public static final int $name = $n;"; n=$((n + 1))
        done
        echo "  }"
    done
    echo "}"
} > "$OUT/gen/R.java"
javac -nowarn -d "$OUT/classes" -cp "$JAR" src/io/github/christianernst/tav/*.java "$OUT/gen/R.java" \
    test/io/github/christianernst/tav/AnalyzerCheck.java test/io/github/christianernst/tav/AdaptiveScaleCheck.java \
    test/io/github/christianernst/tav/EqualizerCheck.java test/io/github/christianernst/tav/ColourDriveCheck.java \
    test/io/github/christianernst/tav/LookCheck.java test/io/github/christianernst/tav/AutopilotCheck.java
java -cp "$OUT/classes" io.github.christianernst.tav.AnalyzerCheck
java -cp "$OUT/classes" io.github.christianernst.tav.AdaptiveScaleCheck
java -cp "$OUT/classes" io.github.christianernst.tav.EqualizerCheck
java -cp "$OUT/classes" io.github.christianernst.tav.ColourDriveCheck
java -cp "$OUT/classes" io.github.christianernst.tav.AutopilotCheck

echo "[looks]"
# Look (saved looks, share payload, shuffle) uses org.json, which android.jar only stubs: run it
# against the real library, fetched once (~70 KB). Without network the check is skipped, not failed.
JSON=${JSON_JAR:-$HOME/.cache/tav/json-20240303.jar}
if [ ! -s "$JSON" ]; then
    mkdir -p "$(dirname "$JSON")"
    curl -fsSL -o "$JSON.part" https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar && mv "$JSON.part" "$JSON" || rm -f "$JSON.part"
fi
if [ -s "$JSON" ]; then
    java -cp "$JSON:$OUT/classes:$JAR" io.github.christianernst.tav.LookCheck
else
    echo "  skipped: could not fetch org.json (set JSON_JAR)"
fi

if command -v node >/dev/null 2>&1; then
    echo "[web]"
    node ../web/tools/autopilot-check.js | tail -1
else
    echo "[web] skipped: node not installed"
fi
