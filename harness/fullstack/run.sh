#!/bin/sh
# The full-stack stage runner (specification §12).
#
#     ./run.sh <stage>...
#     SOULBIND_DB=mariadb ./run.sh up journeys down
#
# Brings the stack up (or attaches to a running one), runs the named stages,
# and emits a result per stage into out/. The backend matrix is an axis:
# SOULBIND_DB selects core's storage, and every stage runs identically against
# either -- which is the storage seam's claim, and this is what tests it.
#
# WHY A STAGE RUNNER AND NOT ONE SCRIPT. stack.sh proves a single happy path
# end to end. A gate that asks for several tiers against one live deployment
# needs them to be separately nameable, separately reportable, and separately
# re-runnable against a stack that is already up -- bringing a Paper server and
# a proxy up once per tier would dominate the run and discourage adding tiers.
#
# THE INVARIANT THIS FILE EXISTS TO HOLD: a stage cannot report success for
# work it did not do. Every stage must emit a result; a stage that exits 0
# without emitting one fails the run, loudly, naming itself. That is not
# defensive programming -- it is the specific failure this project has hit
# repeatedly, where a task reported green having executed nothing.
set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)

# Core's storage backend for this run. The gate asks for the battery green on
# both, in one session.
DB=${SOULBIND_DB:-sqlite}
case "$DB" in
    sqlite|mariadb) ;;
    *) echo "run.sh: SOULBIND_DB must be sqlite or mariadb, not '$DB'" >&2; exit 2 ;;
esac

# All MUTABLE state under REAPER_STATE when reaper set it, because that is the
# only thing `reset` rolls back (reaper tenants.md). Everything else -- fetched
# jars, caches -- deliberately lives outside it, so a reset does not throw away
# a hundred megabytes that would only be downloaded again.
#
# Off a reaper session the fallback is local, so the same script is runnable on
# a workstation. The two paths differ in location only, never in behaviour.
STATE_ROOT=${REAPER_STATE:-$HERE}
RUN=${SOULBIND_STACK_RUN:-$STATE_ROOT/run-$DB}
CACHE=${SOULBIND_STACK_CACHE:-$HERE/.cache}
# The JVM is exported for every stage, not left to each one to rediscover.
# core targets Java 25 and the bare `java` on a BSD workstation is dispatched to
# an older install, so the README's advertised one-liner failed at `migrate`
# while `up` had already succeeded -- a confusing place to learn it.
JAVA=${JAVA:-java}
export JAVA

# JAVA_HOME as well, because Gradle's generated start scripts ignore $JAVA
# entirely and use $JAVA_HOME/bin/java or a bare `java`. Exporting only JAVA got
# `up` through (stack.sh sets JAVA_HOME for itself) and then failed `journeys`
# with an UnsupportedClassVersionError from the chat driver -- the identical trap
# stack.sh documents, reintroduced in the one stage the export did not reach.
# An explicit JAVA overrides an inherited JAVA_HOME, for the reason stack.sh
# records at length: this workstation carries JAVA_HOME=/usr/local/openjdk17, so
# deriving it only when unset kept Java 17 and every Gradle start script used it.
if [ "$JAVA" != "java" ]; then
    JAVA_HOME=$(cd "$(dirname "$JAVA")/.." && pwd)
fi
[ -n "${JAVA_HOME:-}" ] && export JAVA_HOME

export SOULBIND_STACK_RUN="$RUN"
export SOULBIND_STACK_CACHE="$CACHE"
export CORE_BACKEND="$DB"

# Results come OUT of the guest: reaper syncs back out/ and nothing else, so an
# artifact written anywhere else is an artifact destroyed with the VM. That
# lesson cost a red battery whose only trace died with the machine.
OUT="$REPO/out/fullstack/$DB"

# The path is validated HERE, at its definition, because everything below
# creates or deletes under it.
#
# `rm -rf` on a derived path is only as safe as the derivation, and this one
# begins `REPO=$(cd "$HERE/../.." && pwd)`. Shells differ on whether a failed cd
# in that position aborts under `set -e` -- FreeBSD's sh does, and relying on
# that across the guest's dash and every future shell is a bet with no upside.
# An empty REPO would make $OUT `/out/fullstack/sqlite`, an absolute path
# outside this repository.
[ -n "$REPO" ] && [ -d "$REPO" ] || {
    echo "run.sh: the repository root did not resolve; refusing to create or delete anything" >&2
    exit 2
}
# Not just "a directory" -- THIS repository. `HERE` resolves where the script
# lives, and a symlinked run.sh resolves to the symlink's directory, so $REPO
# became an unrelated tree and `rm -rf "$OUT"` deleted a stranger's out/.
[ -f "$REPO/settings.gradle.kts" ] || {
    echo "run.sh: '$REPO' does not look like the soulbind repository (no settings.gradle.kts);" >&2
    echo "        refusing to create or delete anything under it" >&2
    exit 2
}
case "$OUT" in
    "$REPO"/out/fullstack/*) ;;
    *)
        echo "run.sh: refusing to use '$OUT' -- it is not under $REPO/out/fullstack" >&2
        exit 2 ;;
esac

# Ports are per-backend so the two axes can be up at once without colliding --
# the same mistake the forum tier made when both engines defaulted to one port.
case "$DB" in
    sqlite)  CORE_PORT=${CORE_PORT:-7100}; PAPER_PORT=${PAPER_PORT:-25566}; PROXY_PORT=${PROXY_PORT:-25577} ;;
    mariadb) CORE_PORT=${CORE_PORT:-7101}; PAPER_PORT=${PAPER_PORT:-25568}; PROXY_PORT=${PROXY_PORT:-25579} ;;
esac
export CORE_PORT PAPER_PORT PROXY_PORT

log() { echo "[fullstack:$DB] $*"; }

# --- results ----------------------------------------------------------------
#
# JUnit-ish, because that is what every reader of a CI artifact already has a
# tool for. Not a bespoke format nobody will open.

STAGE_STARTED=

result_open() {
    mkdir -p "$OUT"
    STAGE_STARTED=$1
    STAGE_T0=$(date +%s)
    rm -f "$OUT/$1.xml"
}

# result_pass <stage> <testname>
result_pass() {
    _elapsed=$(( $(date +%s) - STAGE_T0 ))
    cat > "$OUT/$1.xml" <<XML
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="fullstack.$DB.$1" tests="1" failures="0" errors="0" skipped="0" time="$_elapsed" timestamp="$(date -u +%Y-%m-%dT%H:%M:%S)">
  <testcase classname="fullstack.$DB" name="$2" time="$_elapsed"/>
</testsuite>
XML
    # The write is VERIFIED, and STAGE_STARTED is cleared only if it worked.
    #
    # `cat >` can fail -- an unwritable out/, a full disk -- and the previous
    # version cleared STAGE_STARTED regardless. The fault check then saw "not
    # started, a file exists" and passed, reporting a stage as green on a stale
    # file from an earlier run. Leaving STAGE_STARTED set on a failed write makes
    # the existing invariant catch it instead of papering over it.
    if ! grep -q "fullstack.$DB.$1" "$OUT/$1.xml" 2>/dev/null; then
        log "HARNESS FAULT: could not write the result for $1"
        return 1
    fi
    log "PASS $1 (${_elapsed}s)"
    STAGE_STARTED=
}

# result_fail <stage> <testname> <message>
result_fail() {
    _elapsed=$(( $(date +%s) - STAGE_T0 ))
    cat > "$OUT/$1.xml" <<XML
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="fullstack.$DB.$1" tests="1" failures="1" errors="0" skipped="0" time="$_elapsed" timestamp="$(date -u +%Y-%m-%dT%H:%M:%S)">
  <testcase classname="fullstack.$DB" name="$2" time="$_elapsed">
    <failure message="$3">$3</failure>
  </testcase>
</testsuite>
XML
    if ! grep -q "fullstack.$DB.$1" "$OUT/$1.xml" 2>/dev/null; then
        log "HARNESS FAULT: could not write the failure result for $1"
        return 1
    fi
    log "FAIL $1: $3"
    STAGE_STARTED=
}

# There is deliberately no result_skip.
#
# A skip is how a tier stops running without anybody noticing, and every skip
# this project has needed so far -- MariaDB with no server, PHPUnit with no
# extension -- is expressed as a NARROWING with a stated reason at the point
# that narrows, not as a green result carrying the word "skipped". If a stage
# genuinely cannot run here, that is a failure of this harness to provide what
# it promised, and it should read as one.

# --- stages -----------------------------------------------------------------
#
# Adding a name here without a stage_<name> function is caught by the dispatcher
# below, and FullstackStagesGuardTest asserts this list matches both the
# functions defined and the stages documented in the README. A stage that is
# listed but does no work is the exact shape this file exists to prevent.
STAGES="up migrate journeys down"

# The post-condition, checked rather than assumed.
#
# `up` used to report PASS on stack.sh's exit status alone -- and stack.sh's EXIT
# trap tore the stack down on success, so every later stage ran against nothing.
# An exit status says the script finished; it does not say a stack is there.
core_is_listening() {
    python3 -c "
import socket, sys
s = socket.socket()
s.settimeout(2)
sys.exit(0 if s.connect_ex(('127.0.0.1', $CORE_PORT)) == 0 else 1)
" 2>/dev/null
}

stage_up() {
    result_open up
    log "bringing the stack up on $DB"
    # Tee'd into out/, because the failure message below points a reader at
    # $OUT/stack.log and nothing was writing it -- so on a session the bring-up
    # log died with the VM, which is the exact loss this harness is supposed to
    # prevent.
    #
    # The status goes through a FILE, not through the pipeline. `if cmd | tee`
    # tests tee's exit status, and tee essentially always succeeds -- so a failed
    # bring-up would have been reported as a pass. Written here after making
    # precisely that mistake in this function, minutes after catching the same
    # shape in a verification command.
    # Removed FIRST, and a missing file means failure.
    #
    # The previous version wrote the status inside the subshell and read it back
    # -- but the up-front clearing loop clears $OUT/<stage>.xml and nothing else,
    # so .stack-status survived between runs. Under FreeBSD's /bin/sh, `set -e`
    # is not suspended inside that subshell, so a failing stack.sh kills it
    # before `echo $?` runs and the PREVIOUS run's 0 is read: `up` reported PASS
    # on a bring-up that failed, and the pristine snapshot was taken on a broken
    # stack.
    #
    # A stale-file read introduced by the fix for a pipeline-masking bug, one
    # filename away from the loop written to prevent stale reads. Clearing it
    # first and failing closed on absence covers both the set -e death and any
    # other way that write never happens.
    rm -f "$OUT/.stack-status"
    ( "$HERE/stack.sh" --keep 2>&1; echo $? > "$OUT/.stack-status" ) | tee "$OUT/stack.log"
    if [ "$(cat "$OUT/.stack-status" 2>/dev/null || echo 1)" -eq 0 ] && core_is_listening; then
        # Pristine is STACK-UP, not end-of-run (§12): a later stage that dirties
        # the databases must be able to roll back to a healthy stack rather than
        # to an empty machine, or every reset pays for the whole bring-up again.
        if [ -n "${REAPER_CONTROL:-}" ] && [ -x "$REAPER_CONTROL/snapshot" ]; then
            "$REAPER_CONTROL/snapshot"
            log "snapshot taken: the stack is up and this point is pristine"
        else
            log "no REAPER_CONTROL; not snapshotting (running outside a session)"
        fi
        result_pass up "the stack comes up and the smoke flow completes"
    else
        result_fail up "the stack comes up and the smoke flow completes" \
            "stack.sh exited non-zero, or core is not listening on $CORE_PORT afterwards; see $OUT/stack.log"
        return 1
    fi
}

stage_migrate() {
    result_open migrate
    log "migration idempotence on $DB"
    # The exit STATUS is distinguished, so the verdict matches the failure.
    #   1 = a second apply changed the database
    #   2 = the fingerprint measured too little to mean anything
    #   3 = there is no migrated database to test (usually: up failed)
    # Reporting all three as "not a no-op" is what the first real run did, and it
    # pointed the reader at idempotence when the stack had simply never come up.
    if "$HERE/migrate-check.sh" "$RUN" "$DB"; then
        result_pass migrate "migrations are idempotent: a second apply changes nothing"
    else
        case $? in
            3) reason="there is no migrated database -- the up stage did not complete" ;;
            2) reason="the fingerprint measured too little to compare; see the log" ;;
            *) reason="re-applying migrations to an already-migrated database was not a no-op" ;;
        esac
        result_fail migrate "migrations are idempotent: a second apply changes nothing" "$reason"
        return 1
    fi
}

stage_journeys() {
    result_open journeys
    log "journeys: real flows, emitting evidence"
    if "$HERE/journeys.sh" "$RUN" "$DB" "$OUT/evidence"; then
        result_pass journeys "a player links through the real flow, and core agrees"
    else
        result_fail journeys "a player links through the real flow, and core agrees" \
            "see $OUT/evidence for what the run recorded before it stopped"
        return 1
    fi
}

stage_down() {
    result_open down
    log "tearing down"
    # NOT `|| true`. The previous version swallowed the exit status and then
    # reported PASS unconditionally, so this stage was green with stack.sh
    # deleted from disk -- a green result for work not done, produced by exactly
    # the construct this project forbids, in the file whose entire subject is
    # that failure mode.
    if "$HERE/stack.sh" --down >>"$OUT/stack.log" 2>&1; then
        result_pass down "the stack is torn down"
    else
        result_fail down "the stack is torn down" \
            "stack.sh --down exited non-zero; see $OUT/stack.log"
        return 1
    fi
}

# --- dispatcher -------------------------------------------------------------

usage() {
    echo "usage: SOULBIND_DB=sqlite|mariadb $0 <stage>..." >&2
    echo "stages: $STAGES" >&2
    exit 2
}

[ $# -gt 0 ] || usage

# Every requested stage is checked BEFORE any of them runs. A typo in the third
# stage should not be discovered after a four-minute bring-up.
for requested in "$@"; do
    known=no
    for candidate in $STAGES; do
        [ "$requested" = "$candidate" ] && known=yes
    done
    if [ "$known" = no ]; then
        echo "run.sh: unknown stage '$requested'" >&2
        usage
    fi
    if ! command -v "stage_$requested" >/dev/null 2>&1 \
        && ! type "stage_$requested" >/dev/null 2>&1; then
        echo "run.sh: stage '$requested' is listed but has no stage_$requested function" >&2
        exit 2
    fi
done

mkdir -p "$OUT"

# The WHOLE result directory belongs to this invocation.
#
# Clearing only the requested stages was not enough, three separate ways:
#
#   * a stage that dies before result_open never reaches the clear inside it, so
#     the previous run's file survived and the invariant -- which only asks
#     whether a result EXISTS -- counted last run's PASS as this run's;
#   * `run.sh up down` followed by a later failing `run.sh up` left a green
#     down.xml beside it, and a collector globbing the directory reports a stage
#     that this run never asked for, green, against a stack that never came up;
#   * evidence/ was never cleared at all, so a journeys stage that died before
#     writing anything pointed the reader at the PREVIOUS run's complete,
#     passing transcript -- in the tier whose entire deliverable is the evidence.
#
# out/ is the only thing reaper syncs back, so these files are exactly what a
# reader sees. Evidence that outlives the run which produced it is worse than no
# evidence, because it looks current.
#
# The cost, stated: results do not accumulate across invocations. Run the stages
# you want reported in one invocation -- which is what the gate does anyway.
# A caller-set RUN directory must not live inside the results directory.
#
# SOULBIND_STACK_RUN is caller-settable and holds the LIVE state -- creds.env,
# the SQLite database. Pointed inside $OUT, the clear below destroys the running
# stack's state. Under the old per-stage `rm -f` that was harmless; `rm -rf` is
# what made it destructive, and the forum tier already keeps its run directory
# under out/, so this is a shape that exists here rather than a hypothetical.
case "$RUN/" in
    "$OUT"/*)
        echo "run.sh: SOULBIND_STACK_RUN ($RUN) is inside the results directory ($OUT)," >&2
        echo "        which is cleared at the start of every invocation. Move it outside." >&2
        exit 2 ;;
esac

rm -rf "$OUT"
mkdir -p "$OUT"

failed=0

for requested in "$@"; do
    STAGE_STARTED=
    if "stage_$requested"; then :; else failed=1; fi

    # The invariant. A stage that returned without emitting a result has told
    # us nothing, and treating "no result" as success is precisely how a tier
    # comes to report green having run nothing at all.
    if [ -n "$STAGE_STARTED" ] || [ ! -f "$OUT/$requested.xml" ]; then
        log "HARNESS FAULT: stage '$requested' finished without emitting a result"
        result_open "$requested"
        result_fail "$requested" "the stage reports what it did" \
            "the stage returned without emitting a result, so nothing it claims can be trusted"
        failed=1
    fi
done

log "results in $OUT"
exit $failed
