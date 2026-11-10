#!/bin/sh
# create-release-key.sh creates the release key in signing/release.jks. Run it once, through
# make release-key. Back up the keystore and its password: losing either means no update can
# ever be installed over a release again, and every user has to reinstall.
#
# The password comes from SOJOURN_KEYSTORE_PASSWORD, or is asked for.
set -eu

keystore=signing/release.jks
[ ! -e "$keystore" ] || { echo "$keystore already exists" >&2; exit 1; }
mkdir -p signing

if [ -z "${SOJOURN_KEYSTORE_PASSWORD:-}" ]; then
    trap 'stty echo 2>/dev/null' EXIT
    stty -echo
    printf 'New release keystore password (at least 6 characters): '
    read -r SOJOURN_KEYSTORE_PASSWORD
    printf '\nRepeat the password: '
    read -r confirmation
    printf '\n'
    stty echo
    [ "$SOJOURN_KEYSTORE_PASSWORD" = "$confirmation" ] || { echo "The passwords differ" >&2; exit 1; }
fi
export SOJOURN_KEYSTORE_PASSWORD

keytool -genkeypair \
    -keystore "$keystore" \
    -storetype PKCS12 \
    -storepass:env SOJOURN_KEYSTORE_PASSWORD \
    -alias sojourn \
    -keyalg RSA \
    -keysize 4096 \
    -validity 36500 \
    -dname "CN=Sojourn"

echo "Created $keystore. Back it up with its password; never commit it."
