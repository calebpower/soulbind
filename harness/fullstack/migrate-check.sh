#!/bin/sh
# Migration idempotence against the live deployment (§14 Phase 8, T6).
#
#     migrate-check.sh <run-dir> <backend>
#
# Core migrates on every Storage.open, so a deployed server re-runs migrations
# on every restart. If a second apply were not a no-op, the schema would drift
# once per restart -- a failure that only shows on a long-lived server, and that
# no test against a fresh database can see. So this runs against a database that
# has already been migrated AND used, which is the state the stack leaves.
#
# The work is done by MigrationFingerprint.java on core's own installed
# classpath: same Flyway, same drivers, same Storage.open the server calls. A
# shell re-implementation of "apply the migrations" would be a second definition
# that could agree with itself while disagreeing with the server.
set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
RUN=$1
DB=$2
JAVA=${JAVA:-java}

# The runtime, checked BEFORE anything is attempted.
#
# core targets Java 25, and on this workstation the bare `java` is dispatched to
# an older install. Without this the failure arrives as
# `UnsupportedClassVersionError: class file version 69.0` from javac's class
# reader, which is a true statement about a confusing thing -- and it names the
# harness source file, so it reads as a defect in this script rather than as the
# wrong JVM. stack.sh learned the same lesson; this is the same check.
JAVA_VERSION=$("$JAVA" -version 2>&1 | head -1 | sed -n 's/.*version "\([0-9]*\).*/\1/p')
if [ -z "$JAVA_VERSION" ] || [ "$JAVA_VERSION" -lt 25 ]; then
    echo "[migrate] this check needs Java 25 or newer; '$JAVA' is ${JAVA_VERSION:-unknown}." >&2
    echo "[migrate] Set JAVA=/path/to/java25/bin/java." >&2
    exit 1
fi

LIB="$REPO/core/build/install/core/lib"
if [ ! -d "$LIB" ]; then
    echo "[migrate] core is not installed at $LIB -- run the up stage first" >&2
    exit 1
fi

# The same coordinates the running core was configured with, read from the
# config the stack wrote. Re-deriving them here would be a second place that
# decides where the database is, and the two would disagree the first time one
# changed.
CONFIG="$RUN/core/soulbind.toml"
[ -f "$CONFIG" ] || { echo "[migrate] no config at $CONFIG" >&2; exit 1; }

# Parsed with tomllib, the standard library's TOML reader.
#
# Two hand-rolled versions were wrong, both silently:
#
#   * sed matched `^url` anywhere in the file and took `head -1`, which is file
#     order -- and `[server]` already precedes `[storage]`, so a stray `url`
#     pointed the idempotence check at a DIFFERENT database, which then passed,
#     because whatever it found was idempotent too;
#   * the section-aware rewrite fixed that and remained string-blind: it split
#     on `#` regardless of quoting, so `password = "s3cr#t"` became `s3cr`; a
#     `key = value` line inside a multi-line basic string shadowed the real key
#     -- the same wrong-database failure the rewrite existed to eliminate; and
#     an array continuation line like `  ["a", "b"]` was read as a section
#     header, silently skipping every later [storage] key.
#
# Writing a third one would be optimism. TOML is a real grammar, this repository
# ships a TOML parser precisely because hand-parsing config is a bug factory,
# and the shell side does not get an exemption from that reasoning.
#
# tomllib is stdlib from 3.11. If it is missing the script says so and stops --
# a silent fallback to a hand parser is how the two bugs above would come back.
#
# The variables are prefixed: plain `USER` is exported on every POSIX system, and
# assigning to it re-exported an empty one to the java child.
read_storage() {
    python3 -c '
import sys
try:
    import tomllib
except ModuleNotFoundError:
    sys.stderr.write("[migrate] python3 has no tomllib (needs 3.11+); refusing to "
                     "hand-parse the config\n")
    sys.exit(2)
with open(sys.argv[2], "rb") as fh:
    doc = tomllib.load(fh)
value = doc.get("storage", {}).get(sys.argv[1], "")
print(value if value is not None else "")
' "$1" "$CONFIG" || exit 2
}

STORAGE_URL=$(read_storage url)
STORAGE_USER=$(read_storage user)
STORAGE_PASSWORD=$(read_storage password)
[ -n "$STORAGE_URL" ] || { echo "[migrate] no url in the [storage] section of $CONFIG" >&2; exit 1; }

echo "[migrate] re-applying migrations to the live $DB database"
# The classpath is core's install lib, so the drivers and Flyway are the
# deployed ones. --enable-native-access silences the SQLite JNI warning that
# would otherwise be the loudest thing in a passing run's output.
exec "$JAVA" --enable-native-access=ALL-UNNAMED \
    -cp "$LIB/*" "$HERE/MigrationFingerprint.java" \
    "$DB" "$STORAGE_URL" "$STORAGE_USER" "$STORAGE_PASSWORD"
