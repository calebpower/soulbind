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
fetch "$PLAN_URL" "$PLAN_SHA256" "$CACHE/plan-$PLAN_VERSION.jar"
fetch "$LUCKPERMS_URL" "$LUCKPERMS_SHA256" "$CACHE/luckperms-$LUCKPERMS_VERSION.jar"
# Named exactly as Plan's downloader names them, so pre-seeding the directory is
# indistinguishable from Plan having fetched them itself: <group>-<artifact>-<version>.jar
fetch "$PLAN_MYSQL_DRIVER_URL" "$PLAN_MYSQL_DRIVER_SHA256" \
    "$CACHE/com.mysql-mysql-connector-j-$PLAN_MYSQL_DRIVER_VERSION.jar"
fetch "$PLAN_PROTOBUF_URL" "$PLAN_PROTOBUF_SHA256" \
    "$CACHE/com.google.protobuf-protobuf-java-$PLAN_PROTOBUF_VERSION.jar"

# --- toolchains -------------------------------------------------------------
#
# Linux only, and that asymmetry is deliberate rather than an oversight: neither
# Temurin nor nodejs.org publishes a FreeBSD build, and the workstation has its
# own JDK and Node. The guest has neither -- "no language toolchains" is what its
# registration says -- and the guest is where nobody is watching, so that is
# where the pinned bytes matter.
#
# Extracted next to the tarball, with the extraction marked by the presence of
# the bin/ directory rather than by a flag file. A flag file that outlives a
# half-finished extraction is a cache that lies.
extract_toolchain() {
    tarball=$1
    dir=$2
    flag=$3
    if [ -x "$dir/$flag" ]; then
        echo "cached  $(basename "$dir")"
        return
    fi
    rm -rf "$dir.partial" "$dir"
    mkdir -p "$dir.partial"
    # --strip-components=1: these tarballs each contain a single versioned top
    # directory, and stripping it keeps the path stable when the pin moves.
    tar -xf "$tarball" -C "$dir.partial" --strip-components=1
    [ -x "$dir.partial/$flag" ] || {
        echo "extracted $tarball but $flag is missing -- this is not the archive expected" >&2
        rm -rf "$dir.partial"
        exit 1
    }
    mv "$dir.partial" "$dir"
    echo "extract $(basename "$dir")"
}

if [ "$(uname -s)" = "Linux" ]; then
    fetch "$JDK_URL" "$JDK_SHA256" "$CACHE/jdk-$JDK_VERSION.tar.gz"
    extract_toolchain "$CACHE/jdk-$JDK_VERSION.tar.gz" "$CACHE/jdk-$JDK_VERSION" bin/java

    fetch "$NODE_URL" "$NODE_SHA256" "$CACHE/node-$NODE_VERSION.tar.xz"
    extract_toolchain "$CACHE/node-$NODE_VERSION.tar.xz" "$CACHE/node-$NODE_VERSION" bin/node
else
    echo "toolchains: using the system JDK and Node ($(uname -s) has no pinned build)"
fi

echo "stack artefacts verified in $CACHE"
