#!/bin/sh
# What out/browser-evidence/<backend>/ contains when the forum tier is over.
#
#     harness/flarum/evidence-test.sh
#
# Executed, not sourced. Wired into .reaper.toml beside credential-smoke.
#
# WHY THIS EXISTS. The forum tier's evidence lifecycle is two functions whose
# correctness is a question of ORDER: one clears the directory, one fills it,
# and the tier itself writes a Tier 11 transcript in between. When both halves
# lived in the EXIT trap, the clear ran after the transcript was written and
# deleted it -- on every run, including green ones -- leaving a run.json that
# said "passed, N of N specs" as the sole evidence of a run whose required §11
# deliverable it had just destroyed. Nothing caught it: the tier was green, the
# stamp was green, and the missing file was one nobody opened until they needed
# it.
#
# A guard over the text of stack.sh could not have caught that. The rm and the
# transcript were both correct in isolation and each carried a comment
# explaining why it belonged where it was; only running them in sequence shows
# what they do to each other. So this runs them, against a fake REPO, with no
# podman, no forum and no browser -- which is why it can be cheap enough to run
# every time rather than only in a full battery.
set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
FAILED=0
WORK=${TMPDIR:-/tmp}/soulbind-evidence-test.$$
trap 'rm -rf "$WORK"' EXIT INT TERM

ok()   { echo "  ok   - $1"; }
fail() { echo "  FAIL - $1"; FAILED=$((FAILED + 1)); }

check() { # check <description> <expected> <actual>
    if [ "$2" = "$3" ]; then ok "$1"; else
        fail "$1"
        echo "         expected: $2"
        echo "         actual:   $3"
    fi
}

# One run of the tier, with the real functions, in a throwaway REPO.
#
#   $1  a name for the case
#   $2  exit status the tier body ends with (0 green, non-zero red)
#   $3  "transcript" to have the body emit the Tier 11 transcript, "" to not
#   $4  "counts" to have the body reach the spec count, "" to die before it
#   $5  "noreset" to skip reset_browser_evidence, simulating a death so early
#       the tier never got to it
run_tier() {
    REPO="$WORK/$1"; export REPO
    RUN="$REPO/run"
    mkdir -p "$RUN"
    ( # a subshell IS the tier: its exit fires the trap, exactly as stack.sh does
        set -eu
        REPO="$REPO"; RUN="$RUN"
        log() { : ; }
        . "$HERE/evidence.sh"

        cleanup() {
            status=$?
            if [ "$status" -ne 0 ]; then EVIDENCE_STATUS=failed; else EVIDENCE_STATUS=passed; fi
            keep_browser_evidence
            return $status
        }

        [ "$5" = "noreset" ] || reset_browser_evidence
        trap cleanup EXIT INT TERM

        if [ "$3" = "transcript" ]; then
            mkdir -p "$REPO/out/browser-evidence/sqlite/forum-first-user"
            echo "# forum-first-user" \
                > "$REPO/out/browser-evidence/sqlite/forum-first-user/transcript.md"
        fi
        if [ "$4" = "counts" ]; then EXPECTED_SPECS=12; ACTUAL_SPECS=12; fi
        exit "$2"
    ) || true
    DEST="$REPO/out/browser-evidence/sqlite"
}

stamp() { cat "$DEST/run.json" 2>/dev/null || echo "<no run.json>"; }

echo "[evidence] a green run keeps the transcript it wrote"
# THE REGRESSION. The tier writes the transcript ~150 lines before it ends; the
# EXIT trap must not be the thing that removes it.
run_tier green 0 transcript counts ""
check "the Tier 11 transcript survives" \
    "# forum-first-user" \
    "$(cat "$DEST/forum-first-user/transcript.md" 2>/dev/null || echo '<deleted>')"
check "and the stamp records the run" \
    '{"backend":"sqlite","status":"passed","specsRan":12,"specsExpected":12}' \
    "$(stamp)"

echo "[evidence] a red run keeps the transcript too"
# A failed run is when the transcript is most worth having: it shows how far the
# journey got before it stopped.
run_tier red 1 transcript "" ""
check "the transcript survives a failure" \
    "# forum-first-user" \
    "$(cat "$DEST/forum-first-user/transcript.md" 2>/dev/null || echo '<deleted>')"

echo "[evidence] unmeasured spec counts are null, not zero"
# The counts are taken in the last twenty lines of stack.sh, so nearly every
# failure reaches the stamp with neither set. 0/0 is self-consistent and reads
# as a measurement -- and "measured, and it was zero" is a real, separate and
# serious result: every spec failing to match its --grep tag looks exactly like
# that. The two must not share bytes.
check "a failure before the count says null" \
    '{"backend":"sqlite","status":"failed","specsRan":null,"specsExpected":null}' \
    "$(stamp)"

echo "[evidence] a measured zero is still reported as zero"
run_tier zero 1 "" "" ""
( REPO="$WORK/zero"; RUN="$REPO/run"; log() { : ; }
  . "$HERE/evidence.sh"
  EVIDENCE_STATUS=failed; ACTUAL_SPECS=0; EXPECTED_SPECS=12
  keep_browser_evidence )
check "zero of twelve is not confused with unmeasured" \
    '{"backend":"sqlite","status":"failed","specsRan":0,"specsExpected":12}' \
    "$(cat "$WORK/zero/out/browser-evidence/sqlite/run.json")"

echo "[evidence] last run's artifacts do not survive into this one"
# The property the original rm existed for, and the one a naive fix to the
# above would lose: reaper's backward sync never deletes, so a directory left
# by an earlier FAILED run sat in out/ reporting itself as current.
run_tier stale 0 transcript counts ""
# mkdir -p, not a bare redirect: a broken lifecycle may have deleted the
# directory this is trying to seed, and the test must REPORT that below rather
# than die here under set -e with its remaining checks unrun.
mkdir -p "$DEST/forum-first-user"
echo "stale trace from an earlier run" > "$DEST/trace-from-run-3.txt"
echo "# stale transcript" > "$DEST/forum-first-user/transcript.md"
run_tier_again() { ( set -eu
    REPO="$WORK/stale"; RUN="$REPO/run"; log() { : ; }
    . "$HERE/evidence.sh"
    cleanup() { status=$?; EVIDENCE_STATUS=passed; keep_browser_evidence; return $status; }
    reset_browser_evidence
    trap cleanup EXIT INT TERM
    EXPECTED_SPECS=12; ACTUAL_SPECS=12
    exit 0 ) || true; }
run_tier_again
check "the earlier run's trace is gone" \
    "gone" \
    "$([ -f "$DEST/trace-from-run-3.txt" ] && echo present || echo gone)"
check "and so is its transcript" \
    "gone" \
    "$([ -f "$DEST/forum-first-user/transcript.md" ] && echo present || echo gone)"

echo "[evidence] a run that dies before the reset does not stamp stale artifacts"
# EVIDENCE_RESET is what tells the two cases apart. Without it, a tier that died
# before reaching the reset would stamp a directory full of the PREVIOUS run's
# files -- re-creating the stale-reads-as-current bug the rm was for.
run_tier early 0 transcript counts ""
mkdir -p "$DEST"
echo "stale trace" > "$DEST/trace-from-run-3.txt"
run_tier early 1 "" "" noreset
check "the stale trace is cleared" \
    "gone" \
    "$([ -f "$DEST/trace-from-run-3.txt" ] && echo present || echo gone)"
check "and the stamp is this run's" \
    '{"backend":"sqlite","status":"failed","specsRan":null,"specsExpected":null}' \
    "$(stamp)"

echo "[evidence] a signal does not make the stamp run twice"
# cleanup is trapped on EXIT INT TERM, so on a signal it runs and then EXIT runs
# it again.
( REPO="$WORK/twice"; RUN="$REPO/run"; mkdir -p "$RUN"; log() { : ; }
  . "$HERE/evidence.sh"
  reset_browser_evidence
  EVIDENCE_STATUS=passed; EXPECTED_SPECS=12; ACTUAL_SPECS=12
  keep_browser_evidence
  ACTUAL_SPECS=999          # a second call must not re-stamp with new values
  keep_browser_evidence )
check "the second call is a no-op" \
    '{"backend":"sqlite","status":"passed","specsRan":12,"specsExpected":12}' \
    "$(cat "$WORK/twice/out/browser-evidence/sqlite/run.json")"

if [ "$FAILED" -ne 0 ]; then
    echo "[evidence] $FAILED check(s) failed"
    exit 1
fi
echo "[evidence] all checks passed"
