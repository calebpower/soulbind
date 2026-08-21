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

log() { echo "[credentials] $*"; }
core_pid=""
cleanup() { [ -n "$core_pid" ] && kill "$core_pid" 2>/dev/null; rm -rf "$WORK"; }
trap cleanup EXIT INT TERM

mkdir -p "$WORK"
: "${JAVA:=java}"
JAVA_HOME=$(dirname "$(dirname "$JAVA")")
export JAVA_HOME

log "building core"
(cd "$REPO" && ./gradlew --no-daemon --quiet :core:installDist)

cat > "$WORK/soulbind.toml" <<TOML
[server]
host = "127.0.0.1"
port = $PORT

[storage]
backend = "sqlite"
url = "jdbc:sqlite:$WORK/soulbind.db"

[linking]
codettlseconds = 600
TOML

CLI="$REPO/core/build/install/core/bin/core"
"$CLI" serve --config "$WORK/soulbind.toml" > "$WORK/core.log" 2>&1 &
core_pid=$!

i=0
while [ "$i" -lt 60 ]; do
    if python3 -c "
import socket, sys
s = socket.socket(); s.settimeout(1)
sys.exit(0 if s.connect_ex(('127.0.0.1', $PORT)) == 0 else 1)" 2>/dev/null; then
        break
    fi
    i=$((i + 1)); sleep 1
done
if [ "$i" -ge 60 ]; then
    log "core never listened on $PORT"
    tail -20 "$WORK/core.log" | sed 's/^/  /'
    exit 1
fi

failures=0
checked=0

while IFS='|' read -r name caps ops || [ -n "$name" ]; do
    case "$name" in ''|\#*) continue ;; esac
    name=$(echo "$name" | tr -d ' ')
    caps=$(echo "$caps" | tr -d ' ')
    [ -n "$caps" ] || continue

    cred=$("$CLI" register --name "$name" --quiet --capabilities "$caps" \
        --config "$WORK/soulbind.toml" 2>/dev/null)
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
        out=$(sh "$REPO/harness/rpc.sh" "http://127.0.0.1:$PORT" "$cred" "$op" \
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
