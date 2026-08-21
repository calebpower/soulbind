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
# Toolchain resolution lives HERE, and is deliberately LAZY.
#
# Two mistakes to avoid, both already made once:
#
#   * Never default JAVA to the bare string. stack.sh treats a set JAVA as "the
#     operator chose a JVM" and skips its pinned-toolchain branch, so defaulting
#     and exporting it here meant the pinned JDK was fetched, checksum-verified,
#     extracted -- and then ignored, on the one machine that has no other JVM.
#     The sentinel worked; this file defeated it.
#   * Resolve LAZILY, not once at startup. On a cold guest the cache is empty
#     until `up` runs fetch.sh, so a single resolution at the top finds nothing
#     and every later stage falls back to a launcher that does not exist.
#
# A caller-set JAVA always wins, and JAVA_HOME is derived FROM it -- this
# workstation carries JAVA_HOME=/usr/local/openjdk17, and honouring that over an
# explicit JAVA is how Gradle start scripts ended up on Java 17 while the
# version check cheerfully reported 25.
CACHE_DIR=${SOULBIND_STACK_CACHE:-$HERE/.cache}
JAVA_FROM_CALLER=${JAVA:-}
NODE_FROM_CALLER=${NODE:-}

resolve_toolchain() {
    # shellcheck disable=SC1090
    . "$HERE/pins.env"

    if [ -n "$JAVA_FROM_CALLER" ]; then
        JAVA="$JAVA_FROM_CALLER"
    elif [ -x "$CACHE_DIR/jdk-$JDK_VERSION/bin/java" ]; then
        JAVA="$CACHE_DIR/jdk-$JDK_VERSION/bin/java"
    else
        JAVA=""
    fi

    if [ -n "$JAVA" ]; then
        export JAVA
        JAVA_HOME=$(cd "$(dirname "$JAVA")/.." && pwd)
        export JAVA_HOME
    fi

    if [ -n "$NODE_FROM_CALLER" ]; then
        NODE="$NODE_FROM_CALLER"
    elif [ -x "$CACHE_DIR/node-$NODE_VERSION/bin/node" ]; then
        NODE="$CACHE_DIR/node-$NODE_VERSION/bin/node"
    else
        NODE=""
    fi
    if [ -n "$NODE" ]; then
        export NODE
        PATH="$(dirname "$NODE"):$PATH"
        export PATH
    fi
}

resolve_toolchain

export SOULBIND_STACK_RUN="$RUN"
export SOULBIND_STACK_CACHE="$CACHE"
export CORE_BACKEND="$DB"

# Results come OUT of the guest: reaper syncs back out/ and nothing else, so an
# artifact written anywhere else is an artifact destroyed with the VM. That
# lesson cost a red battery whose only trace died with the machine.
OUT="$REPO/out/fullstack/$DB"

# The bring-up status file is RUN state, not a result. Kept out of $OUT so it is
# not rsynced back as though it were evidence, and so clearing the results
# directory cannot silently delete or preserve it.
RUN_STATUS="${TMPDIR:-/tmp}/soulbind-stack-status-$DB.$$"

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
STAGES="up migrate journeys sim plan groups t10 fuzz down"

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
    rm -f "$RUN_STATUS"
    ( "$HERE/stack.sh" --keep 2>&1; echo $? > "$RUN_STATUS" ) | tee "$OUT/stack.log"
    if [ "$(cat "$RUN_STATUS" 2>/dev/null || echo 1)" -eq 0 ] && core_is_listening; then
        # Pristine is STACK-UP, not end-of-run (§12): a later stage that dirties
        # the databases must be able to roll back to a healthy stack rather than
        # to an empty machine, or every reset pays for the whole bring-up again.
        if [ -n "${REAPER_CONTROL:-}" ] && [ -x "$REAPER_CONTROL/snapshot" ]; then
            # NAMED per backend. Both axes run in one session, so an unnamed
            # snapshot means the mariadb bring-up silently replaces the sqlite
            # one and @pristine ends up naming whichever ran last -- a stack
            # that has since been torn down, which `reset` can no longer reach.
            "$REAPER_CONTROL/snapshot" "stack-$DB"
            log "snapshot taken as stack-$DB: the stack is up and this point is pristine"
            # NOTHING IN THIS SCRIPT ROLLS BACK TO IT, and that is worth saying
            # rather than leaving somebody to discover a snapshot with no
            # consumer.
            #
            # It is an affordance for a PERSON. Every stage here is runnable
            # alone against a stack that is already up, so somebody debugging a
            # failure can roll back to this point and re-run one stage against a
            # known-good deployment instead of spending ninety seconds
            # rebuilding the world. §12 asks for the snapshot at stack-up rather
            # than end-of-run for exactly that: rolling back should land you on
            # a working stack, not an empty machine.
            #
            # Automating a rollback BETWEEN stages was considered and rejected:
            # it means stopping the stack, rolling a dataset back underneath
            # processes holding files open, and restarting -- and the one
            # ordering problem it would have solved (hostile input reaching a
            # deployment that a later stage asserts on) is solved for free by
            # running `fuzz` last.
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
    # Re-resolved: `up` ran fetch.sh, so the pinned toolchain exists by now even
    # if it did not when this script started.
    resolve_toolchain
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
    resolve_toolchain
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

stage_sim() {
    resolve_toolchain
    result_open sim
    log "simulated users: three committed seeds against this deployment"

    creds="$RUN/core/sim-credentials"
    if [ ! -s "$creds" ]; then
        result_fail sim "the committed seeds run clean against a real core" \
            "no credentials at $creds -- the up stage did not register the cast"
        return 1
    fi

    # The run tag comes from OUTSIDE the seeded stream, per §11: it distinguishes
    # this run's rows from an earlier run's against the same database, and a tag
    # drawn from the seed would make a replayed seed collide with the data the
    # original run left behind.
    tag=$(date +%s)

    # Built through Gradle and run from its start script, rather than a
    # classpath assembled here. A hand-built classpath is a second declaration
    # of the module's dependencies, and the two disagree the first time one
    # changes.
    sim_home="$REPO/harness/sim/build/install/soulbind-sim"
    # ALWAYS, never "if the binary is missing".
    #
    # The guest's work directory survives between `reaper test` invocations in
    # one session, so a start script built by an earlier run is still sitting
    # there. The first version of this skipped the build when it found one --
    # and a run therefore executed the PREVIOUS run's tier against the current
    # run's source. It reported green, on both backends, for code it had not
    # built.
    #
    # Caught because the output was missing a line the current source prints.
    # Nothing else would have said so: the stage passed, the seeds passed, and
    # the only evidence was an absence.
    #
    # Gradle's own up-to-date checking makes the unconditional call nearly free
    # when nothing changed, which is the correct place for that decision -- it
    # knows what the inputs are and this script does not.
    log "building the simulated-user tier"
    ( cd "$REPO" && JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon --quiet :sim:installDist )

    if SOULBIND_SIM_CORE_URL="http://127.0.0.1:$CORE_PORT" \
        SOULBIND_SIM_CREDENTIALS="$creds" \
        SOULBIND_SIM_TAG="$tag" \
        JAVA_HOME="$JAVA_HOME" \
        "$sim_home/bin/soulbind-sim" \
            > "$OUT/evidence/sim-$DB.log" 2>&1; then
        result_pass sim "the committed seeds run clean against a real core"
        sed 's/^/    /' "$OUT/evidence/sim-$DB.log"
    else
        sed 's/^/    /' "$OUT/evidence/sim-$DB.log"
        result_fail sim "the committed seeds run clean against a real core" \
            "the trace is in $OUT/evidence/sim-$DB.log; the seed that failed is named" \
            "above, and the line to add to seeds.txt with it"
        return 1
    fi
}

stage_groups() {
    result_open groups
    log "groups: does the proxy's effector reach LuckPerms?"

    creds="$RUN/core/creds.env"
    if [ ! -s "$creds" ]; then
        result_fail groups "a link reaches a real permissions plugin" \
            "no credentials at $creds -- the up stage did not write them"
        return 1
    fi
    # shellcheck disable=SC1090
    . "$creds"

    if "$HERE/group-check.sh" "$RUN" "$OUT/evidence" \
            "http://127.0.0.1:$CORE_PORT" "$HARNESS_CRED"; then
        result_pass groups "a link reaches a real permissions plugin"
    else
        result_fail groups "a link reaches a real permissions plugin" \
            "a player linked through the real flow, and the permissions plugin" \
            "never recorded the group -- see the [groups] diagnostics above for" \
            "which link in the chain broke"
        return 1
    fi
}

stage_t10() {
    result_open t10
    log "t10: deep reads over the sim-accumulated world"

    # Its own principal, not the harness credential: the top-up needs
    # audit-source, and widening fs-harness's grant for one stage would teach
    # the capability model backwards. Registered here rather than in stack.sh
    # because only this stage needs it, and recorded in harness/principals.txt
    # like every other grant.
    auditor=$("$REPO/core/build/install/core/bin/core" register --name t10-auditor --quiet \
        --capabilities audit-source,config-management \
        --config "$RUN/core/soulbind.toml" 2>/dev/null) || {
        # Re-runs against a standing stack find the name taken. That is this
        # stage re-entering, not a defect; but a taken name with no way to
        # recover the credential means this run cannot proceed as the auditor.
        result_fail t10 "deep audit reads page cleanly past the single-query ceiling" \
            "could not register t10-auditor -- if a previous run left it behind," \
            "the stack was not reset between runs"
        return 1
    }

    # BEFORE fuzz, always. This tier's watchdog blames core for any 5xx, and
    # running it after hostile input would make that blame ambiguous -- the
    # same ordering reasoning that put fuzz last in the first place.
    if "$HERE/t10-audit.sh" "http://127.0.0.1:$CORE_PORT" "$auditor" "$OUT/evidence"; then
        result_pass t10 "deep audit reads page cleanly past the single-query ceiling"
    else
        result_fail t10 "deep audit reads page cleanly past the single-query ceiling" \
            "the trace is in $OUT/evidence/t10-audit.log; the watchdog names the" \
            "operation and the failing property"
        return 1
    fi
}

stage_fuzz() {
    result_open fuzz
    log "fuzz: hostile input against the live deployment"

    creds="$RUN/core/creds.env"
    if [ ! -s "$creds" ]; then
        result_fail fuzz "hostile input never makes the deployment answer a 5xx" \
            "no credentials at $creds -- the up stage did not write them"
        return 1
    fi
    # shellcheck disable=SC1090
    . "$creds"

    # LAST, and after `plan`, for two reasons that pull the same way.
    #
    # The point of this stage over :core:fuzzTest is that the database is no
    # longer empty -- subjects, identities, spent codes, rules and an audit log
    # are all there, and a malformed request reaches query paths a fresh core
    # has nothing in. Later is therefore better: more accumulated state.
    #
    # And nothing asserts on the deployment after this. Throwing hostile input
    # at a live core and THEN asking Plan to render link data would make a plan
    # failure ambiguous -- damage this stage caused, or a defect in the
    # connector? Ordering removes the question without needing a rollback
    # between them, which would mean stopping the stack, rolling the dataset
    # back under processes holding files open, and restarting.
    if "$HERE/fuzz-live.sh" "http://127.0.0.1:$CORE_PORT" "$HARNESS_CRED" \
            "$OUT/evidence" 400; then
        result_pass fuzz "hostile input never makes the deployment answer a 5xx"
    else
        result_fail fuzz "hostile input never makes the deployment answer a 5xx" \
            "the seed and every request are in $OUT/evidence/fuzz-live.log; replay with" \
            "SOULBIND_FUZZ_SEED"
        return 1
    fi
}

stage_plan() {
    resolve_toolchain
    result_open plan
    log "does Plan render the link data?"
    if "$HERE/plan-check.sh" "$RUN" "$OUT/evidence"; then
        result_pass plan "Plan renders link data for a player linked through the real flow"
    else
        result_fail plan "Plan renders link data for a player linked through the real flow" \
            "see $OUT/evidence/plan-player.json for exactly what Plan returned"
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
