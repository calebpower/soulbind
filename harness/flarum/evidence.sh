#!/bin/sh
# The forum tier's evidence lifecycle: what out/browser-evidence/<backend>/
# contains when the run is over.
#
# Sourced, never executed -- the same seam as harness/transcript.sh, and for the
# same reason given there: this is the tier's deliverable, so it should have one
# definition. Here there is a second reason. These two functions are the only
# code in the tier whose correctness is a question of ORDER rather than of
# content, and order is what a sourced file can be tested for without standing
# up podman, a forum and a browser. harness/flarum/evidence-test.sh does exactly
# that.
#
# Callers must set: REPO, RUN, CORE_BACKEND (optional, defaults sqlite), and a
# log() function. Named rather than inherited, per transcript.sh.
#
# --- the ordering bug this file exists to prevent ---------------------------
#
# The clearing and the keeping used to live in ONE function called from the EXIT
# trap: it rm -rf'd the destination, recreated it, and wrote what it had. The
# rm was there for a real defect -- reaper's backward sync never deletes, so a
# directory left by an earlier FAILED run sat in out/ reporting itself as
# passed, and the artifact from run 3 read as current after run 4 succeeded.
#
# But by the time the EXIT trap runs, THIS run has already written into that
# directory: stack.sh emits the Tier 11 forum-first-user transcript there, some
# 150 lines before the end. So the rm aimed at last run's artifacts destroyed
# this run's, on every run, green or red. What survived was run.json alone,
# saying "passed, N of N specs" -- a stamp asserting a successful run whose
# required §11 deliverable it had just deleted. journeys.sh documents that the
# transcript "lands in out/browser-evidence/<backend>/", which it did, for about
# a second.
#
# The fix is that clearing is not a teardown concern. Staleness is a question
# about what was there BEFORE this run, so it is answered before this run writes
# anything, and the EXIT path only ever ADDS. Both properties survive: no
# artifact from an earlier run is mistaken for this one's, and nothing this run
# produced is destroyed by the code whose job is to preserve it.

evidence_dir() {
    echo "$REPO/out/browser-evidence/${CORE_BACKEND:-sqlite}"
}

# Called ONCE, early, before anything writes into the directory.
reset_browser_evidence() {
    dest=$(evidence_dir)
    rm -rf "$dest"
    mkdir -p "$dest"
    # Read by keep_browser_evidence to tell "this run cleared it" from "this run
    # died before it got that far". The distinction matters: in the second case
    # the directory still holds the PREVIOUS run's artifacts, and stamping it
    # without clearing would re-create exactly the stale-reads-as-current bug.
    EVIDENCE_RESET=1
}

# Called from the EXIT trap. Adds; never clears content this run wrote.
keep_browser_evidence() {
    # cleanup is trapped on EXIT INT TERM, so on a signal it runs and then the
    # EXIT trap runs it again. Copying twice is harmless but the log line reads
    # like two separate failures.
    [ -n "${EVIDENCE_KEPT:-}" ] && return 0
    EVIDENCE_KEPT=1

    dest=$(evidence_dir)

    # The early reset never ran, so nothing in here belongs to this run and
    # everything in here belongs to a previous one. Clear it now, for the
    # reason the original rm existed.
    if [ -z "${EVIDENCE_RESET:-}" ]; then
        rm -rf "$dest"
    fi
    mkdir -p "$dest" || return 0

    if [ "${EVIDENCE_STATUS:-unknown}" != "passed" ]; then
        if [ -d "$REPO/harness/flarum/browser/test-results" ]; then
            cp -r "$REPO/harness/flarum/browser/test-results/." "$dest/" 2>/dev/null || true
        fi
        cp "$RUN"/playwright-*.json "$dest/" 2>/dev/null || true
    fi

    # A stamp, on EVERY run including a green one.
    #
    # Two problems it solves, both found by a QA pass over a session that had
    # just gone green. First, a passing run kept nothing at all, so "N of M
    # specs ran" existed only as a line in a log nobody keeps -- the claim with
    # the least corroboration in the tier was the one it makes most often.
    # Second, the staleness described at the top of this file.
    #
    # specsRan and specsExpected are `null` when they were not measured, and a
    # NUMBER only when they were. The counts are taken in the last twenty lines
    # of stack.sh, so every failure before that point -- which is nearly all of
    # them -- reaches here with neither set. Defaulting those to 0 wrote
    # {"specsRan":0,"specsExpected":0}, which is self-consistent, reads as a
    # measurement, and says the tier ran zero of zero specs. "Not measured" and
    # "measured, and the answer was zero" must not be the same bytes, because
    # the second is a real and serious result: it is what a suite whose specs
    # all failed to match their --grep tags looks like.
    printf '{"backend":"%s","status":"%s","specsRan":%s,"specsExpected":%s}\n' \
        "${CORE_BACKEND:-sqlite}" \
        "${EVIDENCE_STATUS:-unknown}" \
        "${ACTUAL_SPECS:-null}" \
        "${EXPECTED_SPECS:-null}" \
        > "$dest/run.json"

    if [ -n "$(ls -A "$dest" 2>/dev/null)" ]; then
        log "browser evidence kept in out/browser-evidence/${CORE_BACKEND:-sqlite}"
    fi

    # Loud, where it used to be silent. A FAILING run that captured no report is
    # the case where evidence matters most, and rmdir-ing the empty directory
    # made it indistinguishable from a run that was never asked for one.
    if [ "${EVIDENCE_STATUS:-unknown}" != "passed" ] \
        && [ ! -f "$dest/results.json" ] \
        && [ -z "$(ls "$dest"/playwright-*.json 2>/dev/null)" ]; then
        log "WARNING: the browser tier failed and produced no playwright report."
        log "Nothing here explains the failure; look at the tier's own log output."
    fi
}
