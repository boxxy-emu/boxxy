#!/bin/bash
# Create Boxxy's release signing key, once.
#
#   android/tools/make-release-key.sh
#
# Every Boxxy release must be signed with this same key: Android installs an
# update only over an app signed with the key it was installed with.  Lose
# the key or its password and no later release can update existing installs.
#
# The certificate inside the key is public -- every APK carries it -- so it
# names only "Boxxy", nothing personal.  The password is never stored in the
# key or the APK.
#
# What it writes:
#   ~/.android/boxxy-release.jks      the key (PKCS12), outside the repository
#   ~/.gradle/gradle.properties       where Gradle finds it, so local release
#                                     builds are signed with it
set -euo pipefail

KEYSTORE="$HOME/.android/boxxy-release.jks"
ALIAS=boxxy
PROPS="$HOME/.gradle/gradle.properties"
DNAME="CN=Boxxy, O=Boxxy"

if [[ -e "$KEYSTORE" ]]; then
    echo "A release key already exists at $KEYSTORE; not replacing it." >&2
    echo "(Replacing it would stop updates installing over existing copies.)" >&2
    exit 1
fi
if ! command -v keytool > /dev/null; then
    echo "keytool not found: install a JDK (17 or newer)." >&2
    exit 1
fi

echo "Choose a password for Boxxy's release key (at least 8 characters)."
echo "Store it in a password manager: it cannot be recovered."
while true; do
    read -r -s -p "Password: " PASS; echo
    read -r -s -p "Again:    " PASS2; echo
    if [[ "$PASS" != "$PASS2" ]]; then
        echo "The passwords differ; try again."
    elif (( ${#PASS} < 8 )); then
        echo "Too short; try again."
    else
        break
    fi
done

export PASS_ENV="$PASS"     # keytool reads it from the environment, not argv
mkdir -p "$(dirname "$KEYSTORE")"
# PKCS12 uses one password for the store and the key.  RSA 4096, valid for
# 100 years: it must outlive every Boxxy release.
keytool -genkeypair -v \
    -keystore "$KEYSTORE" -storetype PKCS12 \
    -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 36500 \
    -dname "$DNAME" \
    -storepass:env PASS_ENV -keypass:env PASS_ENV \
    > /dev/null < /dev/null \
    || { rm -f "$KEYSTORE"; echo "keytool failed." >&2; exit 1; }
chmod 600 "$KEYSTORE"

mkdir -p "$(dirname "$PROPS")"
touch "$PROPS"
chmod 600 "$PROPS"
grep -v '^BOXXY_RELEASE_' "$PROPS" > "$PROPS.tmp" || true
cat >> "$PROPS.tmp" <<EOF
BOXXY_RELEASE_STORE_FILE=$KEYSTORE
BOXXY_RELEASE_STORE_PASSWORD=$PASS
BOXXY_RELEASE_KEY_ALIAS=$ALIAS
EOF
mv "$PROPS.tmp" "$PROPS"
chmod 600 "$PROPS"          # it holds the password

echo
echo "Created $KEYSTORE"
keytool -list -v -keystore "$KEYSTORE" -storepass:env PASS_ENV 2>/dev/null \
    | grep -E "Owner:|SHA256:" | sed 's/^/  /'
cat <<EOF

Next:
 1. Back up $KEYSTORE and its password somewhere safe, off this Mac.
 2. Give CI the key, so it can sign releases (your repository only):
      gh secret set BOXXY_KEYSTORE_BASE64 -R boxxy-emu/boxxy < <(base64 < "$KEYSTORE")
      gh secret set BOXXY_KEYSTORE_PASSWORD -R boxxy-emu/boxxy
    (the second asks for the password).
EOF
