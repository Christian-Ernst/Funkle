#!/usr/bin/env bash
# One-time setup: create TAV's release signing key on this device and hand it to GitHub
# Actions as the TAV_KEYSTORE_B64 and TAV_KS_PASS secrets. Afterwards every release, and
# every APK you build here with build.sh, is signed with the same key, so updates install
# over each other.
#
#   android/tools/setup-signing.sh [owner/repo]      (default: Christian-Ernst/TAV)
#
# The key never leaves this device except into GitHub's encrypted secret store. Keep
# ~/.config/tav/ backed up: without the key, the next release can't update an installed TAV.
set -euo pipefail

REPO=${1:-Christian-Ernst/TAV}
DIR=$HOME/.config/tav
KEY=$DIR/tav.keystore
PASSFILE=$DIR/password
ALIAS=tav

die() { echo "setup-signing: $*" >&2; exit 1; }

if [[ "${PREFIX:-}" == *com.termux* ]] && ! { command -v gh && command -v keytool; } >/dev/null 2>&1; then
    pkg install -y gh openjdk-21
fi
command -v gh >/dev/null 2>&1 || die "needs the GitHub CLI (gh)"
command -v keytool >/dev/null 2>&1 || die "needs keytool (part of a JDK)"

if ! gh auth status >/dev/null 2>&1; then
    echo "Log in to GitHub (a browser page asks for a one-time code):"
    gh auth login --hostname github.com --git-protocol https --web
fi

mkdir -p "$DIR"
chmod 700 "$DIR"
if [ -f "$KEY" ]; then
    # build.sh may already have made a key here (password "android"): keep it, so a TAV
    # you built on this phone stays updatable.
    PASS=$(cat "$PASSFILE" 2>/dev/null || echo android)
    keytool -list -keystore "$KEY" -storepass "$PASS" >/dev/null 2>&1 \
        || die "$KEY exists but its password is not in $PASSFILE"
    echo "Using the existing key in $KEY"
else
    PASS=$(head -c 48 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 32)
    [ ${#PASS} -ge 24 ] || die "could not generate a password"
    keytool -genkeypair -keystore "$KEY" -storepass "$PASS" -keypass "$PASS" -alias "$ALIAS" \
        -keyalg RSA -keysize 2048 -validity 20000 -dname "CN=TAV" >/dev/null 2>&1 || die "keytool failed"
    echo "Created a new release key in $KEY"
fi
(umask 077; printf '%s' "$PASS" > "$PASSFILE")

base64 < "$KEY" | tr -d '\n' | gh secret set TAV_KEYSTORE_B64 --repo "$REPO"
printf '%s' "$PASS" | gh secret set TAV_KS_PASS --repo "$REPO"
echo "$REPO" > "$DIR/github"   # tells setup.sh this is done
echo "Secrets TAV_KEYSTORE_B64 and TAV_KS_PASS are set on $REPO."

BRANCH=$(gh repo view "$REPO" --json defaultBranchRef --jq .defaultBranchRef.name)
gh workflow run build.yml --repo "$REPO" --ref "$BRANCH" && \
    echo "Started a build on $BRANCH: in a few minutes the release is signed with this key."
echo
echo "Uninstall the TAV you have now (it was signed with a one-off key), then install the new"
echo "release once. From then on, every update installs over it."
