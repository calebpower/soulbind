#!/bin/sh
# Fetches the pinned Infection PHAR into the build context.
#
#     fetch.sh <destination-directory>
#
# Verified against the checksum in pins.env, and REFETCHED rather than trusted
# when the cached copy does not match. A cache that is checked and then used
# regardless is a cache that launders a corrupt download into a green run.
set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
DEST=${1:?usage: fetch.sh <destination-directory>}

. "$HERE/pins.env"

mkdir -p "$DEST"
target="$DEST/infection.phar"

verify() {
    [ -f "$target" ] || return 1
    actual=$(sha256sum "$target" | cut -d' ' -f1)
    [ "$actual" = "$INFECTION_SHA256" ]
}

if verify; then
    echo "[php] infection $INFECTION_VERSION already present and verified"
    exit 0
fi

echo "[php] fetching infection $INFECTION_VERSION"
rm -f "$target"
curl -fsSL --retry 3 -o "$target" "$INFECTION_URL"

if ! verify; then
    echo "[php] CHECKSUM MISMATCH for $INFECTION_URL" >&2
    echo "[php]   expected $INFECTION_SHA256" >&2
    echo "[php]   actual   $(sha256sum "$target" | cut -d' ' -f1)" >&2
    rm -f "$target"
    exit 1
fi
echo "[php] infection $INFECTION_VERSION verified"
