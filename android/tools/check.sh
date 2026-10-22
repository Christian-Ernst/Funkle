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
javac -nowarn -d "$OUT/classes" -cp "$JAR" src/io/github/christianernst/tav/AudioAnalyzer.java \
    src/io/github/christianernst/tav/AudioCapture.java src/io/github/christianernst/tav/AdaptiveScale.java \
    src/io/github/christianernst/tav/Equalizer.java \
    test/io/github/christianernst/tav/AnalyzerCheck.java test/io/github/christianernst/tav/AdaptiveScaleCheck.java \
    test/io/github/christianernst/tav/EqualizerCheck.java
java -cp "$OUT/classes" io.github.christianernst.tav.AnalyzerCheck
java -cp "$OUT/classes" io.github.christianernst.tav.AdaptiveScaleCheck
java -cp "$OUT/classes" io.github.christianernst.tav.EqualizerCheck
