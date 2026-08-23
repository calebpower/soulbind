#!/bin/sh
# The control-and-mutants check for the migration idempotence fingerprint.
#
#     migrate-selftest.sh
#
# Copyright (c) 2026 Caleb L. Power
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# WHY THIS EXISTS. The migrate stage compares two fingerprints of a LIVE
# deployment's database, and the fingerprint deliberately covers the contents
# of every small table -- which is right, because a repeatable migration that
# rewrote operational rows would otherwise be invisible. The consequence went
# unnoticed for fifteen sessions: the two samples are taken seconds apart while
# core is serving and connectors are draining the outbox, so the verdict
# depended on whether that traffic happened to pause. Run 31 lost that race and
# the stage reported MIGRATIONS ARE NOT IDEMPOTENT for a moved `event_cursor`
# row -- pointing at Flyway for a row Flyway cannot write.
#
# So there are three cases here and the middle one is the whole point:
#
#   quiescent  a settled database reads as idempotent            -> exit 0
#   busy       a database under continuous write reads as        -> exit 4
#              "would not hold still", NOT as an idempotence
#              failure
#   absent     no migrated database is a precondition failure    -> exit 3
#
# Without the busy case this check would go green against the version that
# produced run 31's wrong verdict. It is the reason the file exists; the other
# two are here so a fix that makes everything return 4 cannot pass either.
set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../../.." && pwd)
LIB="$REPO/core/build/install/core/lib"
FINGERPRINT="$HERE/../MigrationFingerprint.java"
CHURN="$HERE/Churn.java"

WORK=${TMPDIR:-/tmp}/soulbind-migrate-selftest.$$
mkdir -p "$WORK"
trap 'rm -rf "$WORK"' EXIT

fail() { echo "[migrate-selftest] FAIL: $*" >&2; exit 1; }

# The same Java the fullstack tier uses, resolved the same way.
#
# The guest ships no toolchains, so a bare `java` is not there at all; on the
# workstation it IS there and is dispatched to an older install, which is worse,
# because the failure then arrives as a class-file version error naming this
# harness rather than the JVM. Both are avoided by preferring the checksum-pinned
# JDK the tier already downloaded, exactly as run.sh does.
. "$HERE/../pins.env"
CACHE_DIR=${SOULBIND_STACK_CACHE:-$HERE/../.cache}
if [ -z "${JAVA:-}" ] && [ -x "$CACHE_DIR/jdk-$JDK_VERSION/bin/java" ]; then
    JAVA="$CACHE_DIR/jdk-$JDK_VERSION/bin/java"
fi
JAVA=${JAVA:-java}
JAVA_VERSION=$("$JAVA" -version 2>&1 | head -1 | sed -n 's/.*version "\([0-9]*\).*/\1/p')
if [ -z "$JAVA_VERSION" ] || [ "$JAVA_VERSION" -lt 25 ]; then
    echo "[migrate-selftest] needs Java 25 or newer; '$JAVA' is ${JAVA_VERSION:-unknown}." >&2
    echo "[migrate-selftest] Set JAVA=/path/to/java25/bin/java." >&2
    exit 3
fi

[ -d "$LIB" ] || fail "core is not installed at $LIB -- run ./gradlew :core:installDist"

DB="$WORK/selftest.db"
URL="jdbc:sqlite:$DB"

run_fingerprint() {
    set +e
    "$JAVA" --enable-native-access=ALL-UNNAMED -cp "$LIB/*" \
        "$FINGERPRINT" sqlite "$URL" >"$WORK/out" 2>"$WORK/err"
    status=$?
    set -e
    return $status
}

# --- case 3: no migrated database ------------------------------------------
#
# First, because it needs no setup and because conflating it with an
# idempotence verdict is the mistake this script's exit statuses exist to
# prevent -- the first session run reported "not a no-op" when the truth was
# that nothing had been migrated at all.
echo "[migrate-selftest] absent: a database nothing has migrated"
if run_fingerprint; then
    fail "an absent database reported success"
fi
[ "$status" -eq 3 ] || fail "expected exit 3 for an absent database, got $status"

# --- case 1: the control ----------------------------------------------------
#
# Storage.open migrates, so opening once IS the setup. A settled database must
# read as idempotent, or every other case here proves nothing: a check that
# fails on everything is not distinguishing anything.
echo "[migrate-selftest] control: a settled database reads as idempotent"
"$JAVA" -cp "$LIB/*" "$HERE/Seed.java" "$URL" >"$WORK/seed.log" 2>&1 \
    || fail "could not seed the control database: $(head -3 "$WORK/seed.log")"
run_fingerprint || fail "a settled database reported exit $status: $(head -3 "$WORK/err")"

# --- case 2: THE POINT ------------------------------------------------------
#
# A second process writes continuously while the fingerprint runs. Before the
# quiesce loop this produced exit 1 and the words MIGRATIONS ARE NOT IDEMPOTENT,
# which is a true observation attributed to the wrong cause.
echo "[migrate-selftest] busy: a database under write must not read as non-idempotent"
"$JAVA" -cp "$LIB/*" "$CHURN" "$URL" 30000 >"$WORK/churn.log" 2>&1 &
CHURN_PID=$!
# Checked, not assumed. A writer that failed to start would leave the database
# quiescent, this case would pass for the wrong reason, and the regression it
# exists to catch would walk straight through it.
sleep 3
if ! kill -0 "$CHURN_PID" 2>/dev/null; then
    fail "the writer exited before the check ran: $(head -3 "$WORK/churn.log")"
fi

if run_fingerprint; then
    kill "$CHURN_PID" 2>/dev/null || true
    fail "a database under continuous write reported SUCCESS -- the check cannot see traffic"
fi
kill "$CHURN_PID" 2>/dev/null || true
wait "$CHURN_PID" 2>/dev/null || true

if [ "$status" -eq 1 ]; then
    fail "a busy database was reported as an IDEMPOTENCE failure (exit 1). This is run 31's
       wrong verdict: the difference is application traffic, not migration drift, and
       blaming Flyway sends whoever reads it somewhere Flyway cannot have written."
fi
[ "$status" -eq 4 ] || fail "expected exit 4 for a busy database, got $status: $(head -3 "$WORK/err")"

grep -q "would not hold still" "$WORK/err" \
    || fail "exit 4 was returned without saying why; the reader needs the reason, not the code"

echo "[migrate-selftest] OK -- settled reads idempotent, busy reads unquiescent, absent reads absent"
