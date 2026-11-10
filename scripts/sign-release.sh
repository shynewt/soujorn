#!/bin/sh
# sign-release.sh signs an unsigned release APK with the release key.
#
# Usage: scripts/sign-release.sh <unsigned.apk> <signed.apk>
# Needs SOJOURN_KEYSTORE_PASSWORD; SOJOURN_KEYSTORE defaults to signing/release.jks.
set -eu

in=$1
out=$2
keystore=${SOJOURN_KEYSTORE:-signing/release.jks}
: "${SOJOURN_KEYSTORE_PASSWORD:?SOJOURN_KEYSTORE_PASSWORD must hold the release keystore password}"
[ -f "$keystore" ] || { echo "Release keystore not found at $keystore (see README, Release signing)" >&2; exit 1; }

apksigner=$(ls -d "$ANDROID_HOME"/build-tools/*/apksigner | sort -V | tail -n 1)

# minSdk 26 reads v2 signatures and Android 9+ prefers v3; v1 only matters before Android 7.
"$apksigner" sign \
    --ks "$keystore" \
    --ks-key-alias sojourn \
    --ks-pass env:SOJOURN_KEYSTORE_PASSWORD \
    --key-pass env:SOJOURN_KEYSTORE_PASSWORD \
    --v1-signing-enabled false \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --out "$out" \
    "$in"

"$apksigner" verify --verbose --print-certs "$out"
