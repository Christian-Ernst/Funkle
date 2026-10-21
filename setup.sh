#!/usr/bin/env bash
# Everything TAV needs on a phone, in one go. Run inside Termux, from the TAV folder:
#
#   bash setup.sh
#
#   1. updates TAV (and restarts itself if this script changed)
#   2. repairs Termux packages: official mirror, discontinued packages, full upgrade
#   3. installs the build tools
#   4. sets up release signing on GitHub (optional; asks you to log in once)
#   5. builds the Android app and opens the installer
#   6. installs the terminal edition as `tav`
#
# Safe to run again: every step checks what is already done.

main() {
    [[ "${PREFIX:-}" == *com.termux* ]] || { echo "Run this inside Termux."; exit 1; }
    cd "$(dirname "${BASH_SOURCE[0]}")"
    local apt_opts=(-y -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold)
    export DEBIAN_FRONTEND=noninteractive

    step "1/6  Updating TAV"
    local before
    before=$(git rev-parse HEAD 2>/dev/null || echo none)
    git pull --ff-only || echo "(could not update; continuing with what is here)"
    if [ "$(git rev-parse HEAD 2>/dev/null || echo none)" != "$before" ] && [ -z "${TAV_SETUP_UPDATED:-}" ]; then
        TAV_SETUP_UPDATED=1 exec bash setup.sh "$@"
    fi

    step "2/6  Repairing Termux packages"
    # openjdk-21-x was dropped from the X11 repo; an old copy pins Java to a version that no
    # longer exists and blocks every upgrade.
    if dpkg -s openjdk-21-x >/dev/null 2>&1; then
        echo "Removing the discontinued openjdk-21-x (it blocks the Java upgrade)"
        dpkg --remove openjdk-21-x
    fi
    # Use the official mirror (what termux-change-repo does): a lagging mirror is what left
    # the package set half-upgraded.
    if [ -f "$PREFIX/etc/termux/mirrors/default" ]; then
        rm -rf "$PREFIX/etc/termux/chosen_mirrors"
        ln -s "$PREFIX/etc/termux/mirrors/default" "$PREFIX/etc/termux/chosen_mirrors"
    fi
    unset TERMUX_PKG_NO_MIRROR_SELECT
    pkg --check-mirror update
    apt-get "${apt_opts[@]}" --fix-broken install
    apt-get "${apt_opts[@]}" full-upgrade

    step "3/6  Installing tools"
    apt-get "${apt_opts[@]}" install git curl zip unzip openjdk-21 aapt2 d8 apksigner gh \
        clang make pulseaudio ffmpeg

    step "4/6  Release signing on GitHub"
    if [ -s "$HOME/.config/tav/github" ]; then
        echo "Already set up (key in ~/.config/tav, secrets on $(cat "$HOME/.config/tav/github"))."
    elif ask "Set up signing so app updates install over each other? Needs a GitHub login." y; then
        bash android/tools/setup-signing.sh \
            || echo "Signing setup did not finish (see above). Continuing; run it again later with: bash setup.sh"
    else
        echo "Skipped. Builds on this phone still get a key of their own in ~/.config/tav."
    fi

    step "5/6  Building the Android app"
    bash android/build.sh
    if ask "Is TAV already installed on this phone?" n; then
        echo "It has to go once: it was signed with a different key, so Android refuses to update it."
        echo "Android opens TAV's app info now: tap Uninstall, then come back to Termux."
        am start -a android.settings.APPLICATION_DETAILS_SETTINGS \
            -d package:io.github.christianernst.tav >/dev/null 2>&1 \
            || echo "(Could not open it: long-press TAV's icon -> App info -> Uninstall.)"
        read -r -p "Press Enter when it's uninstalled... " _ || true
    fi
    echo "Opening the installer (allow Termux to install apps if Android asks)."
    echo "If it says \"App not installed\", an old TAV is still there: uninstall it and run"
    echo "  termux-open android/build/TAV.apk"
    termux-open android/build/TAV.apk

    step "6/6  Terminal edition"
    make -C termux
    make -C termux install PREFIX="$PREFIX"

    step "Done"
    cat <<'MSG'
  Android app: open TAV, tap "Let it listen", play something in Spotify.
               Long-press the screen for the menu (scenes, colours, playback controls).
  Terminal:    tav song.mp3     play a file and watch it
               tav              visualize what Termux plays (e.g. ncspot)
               tav --help       everything else
  Back up ~/.config/tav/ (e.g. to your PC): it holds the app's signing key.
MSG
    exit 0
}

step() { printf '\n\033[1;35m== %s ==\033[0m\n' "$*"; }

# ask "question" y|n  -> success for yes; Enter picks the default
ask() {
    local hint=y/N reply
    [ "$2" = y ] && hint=Y/n
    read -r -p "$1 [$hint] " reply || reply=""
    reply=${reply:-$2}
    [[ "$reply" =~ ^[Yy] ]]
}

set -Eeuo pipefail
trap 'echo; echo "setup.sh stopped at the step above. Fix what it printed, then run: bash setup.sh"' ERR
# Everything runs inside main, which bash has fully read before it starts, so `git pull` and
# the bash upgrade can safely replace files under the running script.
main "$@"
