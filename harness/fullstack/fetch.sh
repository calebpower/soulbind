#!/bin/sh
# Fetches the pinned stack artefacts, verifying every checksum.
#
# Refuses to proceed on a mismatch rather than warning. A jar that is not the
# one this repository pinned is a jar nobody reviewed, and continuing with it
# would make every result below it meaningless -- while looking exactly like a
# passing run.
set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
CACHE=${SOULBIND_STACK_CACHE:-$HERE/.cache}
. "$HERE/pins.env"

mkdir -p "$CACHE"

verify() {
    file=$1
    expected=$2
    if command -v sha256sum >/dev/null 2>&1; then
        actual=$(sha256sum "$file" | awk '{print $1}')
    else
        # FreeBSD ships sha256(1) rather than sha256sum(1). Both, so the script
        # runs on the workstation and in the Linux guest without a branch
        # somebody has to remember.
        actual=$(sha256 -q "$file")
    fi
    if [ "$actual" != "$expected" ]; then
        echo "checksum mismatch for $file" >&2
        echo "  expected $expected" >&2
        echo "  actual   $actual" >&2
        echo "Refusing to continue: this is not the artefact this repository pinned." >&2
        rm -f "$file"
        exit 1
    fi
}

fetch() {
    url=$1
    sha=$2
    out=$3
    if [ -f "$out" ]; then
        verify "$out" "$sha"
        echo "cached  $(basename "$out")"
        return
    fi
    echo "fetch   $(basename "$out")"
    curl -fsSL "$url" -o "$out.partial"
    mv "$out.partial" "$out"
    verify "$out" "$sha"
}

fetch "$VELOCITY_URL" "$VELOCITY_SHA256" "$CACHE/velocity-$VELOCITY_VERSION-$VELOCITY_BUILD.jar"
fetch "$PAPER_URL" "$PAPER_SHA256" "$CACHE/paper-$PAPER_VERSION-$PAPER_BUILD.jar"

echo "stack artefacts verified in $CACHE"
