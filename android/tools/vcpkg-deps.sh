#!/bin/bash
# Install the arm64-android libraries the build links, from vcpkg, with this
# repository's overlay ports (CLAUDE.md, "Custom port patches").  Used by CI
# and for setting up a new machine.  VCPKG_ROOT is a plain clone of
# microsoft/vcpkg at VCPKG_COMMIT, bootstrapped.
set -euo pipefail
cd "$(dirname "$0")/../.."
source android/tools/ndk-env.sh

# The vcpkg release these were built and tested with.
VCPKG_COMMIT=26283ac5e8a068561a718ce18b169bfad84c7dab

PACKAGES=(
    glib
    libepoxy
    libpng
    libsamplerate      # overlay: NEON stereo resampler
    libiconv           # overlay: GNU libiconv on Android too
    nlohmann-json
    openssl
    pixman
    sdl3
    zlib
)

have=$(git -C "$VCPKG_ROOT" rev-parse HEAD)
if [[ "$have" != "$VCPKG_COMMIT" ]]; then
    echo "vcpkg-deps.sh: $VCPKG_ROOT is at $have, expected $VCPKG_COMMIT" >&2
    exit 1
fi

"$VCPKG_ROOT/vcpkg" install \
    --triplet arm64-android \
    --overlay-ports="$PWD/vcpkg-overlay-ports" \
    --clean-after-build \
    "${PACKAGES[@]/%/:arm64-android}"
