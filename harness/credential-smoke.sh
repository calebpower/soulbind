#!/bin/sh
# Does every principal the harnesses register hold what it needs?
#
#     harness/credential-smoke.sh
#
# Thirty seconds, on a workstation, against a real core on SQLite. It exists
# because the alternative was two thirty-minute session runs: moving
# `identity.describe` from `code-display` to `link-state-reader` broke four
# callers, and they were found one battery at a time.
#
# It reads `harness/principals.txt`, registers each principal with exactly the
# capabilities recorded there, and attempts each operation that principal must
# be permitted. The ONLY failure it reports is a `missing-capability` refusal --
# every other refusal is fine and expected, because the payloads here are
# deliberately minimal and core is entitled to reject them on their contents.
#
# That distinction is the whole design. An assertion of "the operation
# succeeded" would need valid arguments for every operation, which means knowing
# each one's payload shape -- a second implementation of the protocol, in shell,
# that would drift. "Was not refused for lack of a capability" needs none of
# that and is exactly the property that broke.
set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/.." && pwd)
WORK=${TMPDIR:-/tmp}/soulbind-credential-smoke.$$
PORT=${CREDENTIAL_SMOKE_PORT:-7190}

# Sourced before the trap, so cleanup can always call core_stop. It picks
# between a host JDK and the pinned toolchain container, because the
# workstation has the first and the reaper guest has only the second -- and
# this script running gradle on the guest host was the failure that kept it off
# the session run.
# shellcheck disable=SC1090
. "$HERE/tools/core-env.sh"

log() { echo "[credentials] $*"; }
cleanup() { core_stop; rm -rf "$WORK"; return 0; }
trap cleanup EXIT INT TERM

mkdir -p "$WORK"

log "building core"
core_env_init "$WORK" "$PORT" "$REPO"
core_serve || exit 1

failures=0
checked=0

while IFS='|' read -r name caps ops || [ -n "$name" ]; do
    case "$name" in ''|\#*) continue ;; esac
    name=$(echo "$name" | tr -d ' ')
    caps=$(echo "$caps" | tr -d ' ')
    [ -n "$caps" ] || continue

    cred=$(core_cli register --name "$name" --quiet --capabilities "$caps" 2>/dev/null)
    if [ -z "$cred" ]; then
        log "FAIL $name: core would not register it with $caps"
        failures=$((failures + 1))
        continue
    fi

    for op in $ops; do
        checked=$((checked + 1))
        # A minimal payload. It is often wrong for the operation, and that is
        # fine: a MALFORMED refusal means the request reached the dispatcher,
        # which means the capability was accepted.
        out=$(sh "$REPO/tools/rpc.sh" "$(core_url)" "$cred" "$op" \
            '{"platformKind":"game","platformId":"smoke","gate":"smoke.gate","limit":1}' 2>&1 || true)
        case "$out" in
            *missing-capability*)
                log "FAIL $name may not $op -- it holds $caps"
                failures=$((failures + 1)) ;;
            *)
                : ;;
        esac
    done
done < "$HERE/principals.txt"

# A run that checked nothing passes every assertion above.
if [ "$checked" -eq 0 ]; then
    log "no operations were checked -- principals.txt is empty or unparseable"
    exit 1
fi

if [ "$failures" -ne 0 ]; then
    log "$failures capability problem(s) across $checked checks"
    exit 1
fi
log "$checked operations across every registered principal, no capability refusals"
