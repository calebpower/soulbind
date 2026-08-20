#!/bin/sh
# Does plan-check.sh actually fail when Plan's answer is wrong?
#
#     mutation/run.sh [mutant-name ...]
#
# Replays the recorded Plan response over HTTP with Plan's own wire behaviour,
# once unmutated and once per entry in mutants.txt, and requires:
#
#   * the control to PASS -- a check that fails on everything kills every
#     mutant and asserts nothing;
#   * every mutant to FAIL, naming which assertion caught it.
#
# Exit 0 only if both hold for every entry.
set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
FULLSTACK=$(cd "$HERE/.." && pwd)
FIXTURES="$FULLSTACK/fixtures"
CHECK="$FULLSTACK/plan-check.sh"
WORK=${TMPDIR:-/tmp}/soulbind-mutation.$$

log() { echo "[mutation] $*"; }

cleanup() {
    [ -n "${server_pid:-}" ] && kill "$server_pid" 2>/dev/null
    rm -rf "$WORK"
}
trap cleanup EXIT INT TERM

mkdir -p "$WORK/run/core" "$WORK/evidence"

# The stage reads the linked player's id from the file the stack writes, and
# reads Plan's registration line out of the proxy log. Both come from the
# recorded evidence rather than being invented here, for the same reason the
# responses do.
python3 - "$FIXTURES/plan-player.json" > "$WORK/run/core/linked-player.txt" <<'PY'
import json, sys
doc = json.load(open(sys.argv[1], encoding="utf-8"))
def walk(node):
    if isinstance(node, dict):
        if "playerUUID" in node:
            print(node["playerUUID"])
            raise SystemExit(0)
        for value in node.values():
            walk(value)
    elif isinstance(node, list):
        for value in node:
            walk(value)
walk(doc)
raise SystemExit("no playerUUID in the recorded response")
PY
[ -s "$WORK/run/core/linked-player.txt" ] || { log "could not read the player id"; exit 1; }
printf '[00:00:00 INFO] [plan]: Registered extension: soulbind\n' > "$WORK/run/velocity.log"

# A port nobody else is on. Fixed ports collide with a parallel run and the
# collision looks like a mutant surviving.
PORT=$(python3 -c "
import socket
s = socket.socket(); s.bind(('127.0.0.1', 0))
print(s.getsockname()[1]); s.close()")

server_pid=""

# Runs the stage against one pair of response files. Echoes PASS or FAIL.
attempt() {
    cp "$1" "$WORK/player.json"
    cp "$2" "$WORK/server.json"
    python3 "$HERE/replay.py" "$WORK/player.json" "$WORK/server.json" "$PORT" &
    server_pid=$!
    # Wait for the socket rather than sleeping a guess.
    python3 - "$PORT" <<'PY'
import socket, sys, time
port = int(sys.argv[1])
for _ in range(100):
    s = socket.socket(); s.settimeout(0.2)
    if s.connect_ex(("127.0.0.1", port)) == 0:
        s.close(); raise SystemExit(0)
    time.sleep(0.05)
raise SystemExit("replay server never listened")
PY
    rm -rf "$WORK/evidence"; mkdir -p "$WORK/evidence"
    if PLAN_PORT="$PORT" PLAN_SERVER_RETRIES="${PLAN_SERVER_RETRIES:-2}" \
            sh "$CHECK" "$WORK/run" "$WORK/evidence" > "$WORK/out.txt" 2>&1; then
        result=PASS
    else
        result=FAIL
    fi
    kill "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
    server_pid=""
    echo "$result"
}

# --- the control -------------------------------------------------------------
log "control: the recorded response, unmutated"
if [ "$(attempt "$FIXTURES/plan-player.json" "$FIXTURES/plan-server.json")" != "PASS" ]; then
    log "CONTROL FAILED. plan-check.sh rejects a response Plan actually sent, so"
    log "every mutant below would be 'killed' by a check that asserts nothing."
    sed 's/^/  /' "$WORK/out.txt"
    exit 1
fi
log "control passes"

# --- the mutants -------------------------------------------------------------
wanted=$*
survived=0
ran=0

while read -r name target || [ -n "$name" ]; do
    case "$name" in ''|\#*) continue ;; esac
    if [ -n "$wanted" ]; then
        echo " $wanted " | grep -q " $name " || continue
    fi

    player="$FIXTURES/plan-player.json"
    server="$FIXTURES/plan-server.json"
    case "$target" in
        player)
            python3 "$HERE/mutate.py" "$player" "$WORK/mutant.json" "$name"
            player="$WORK/mutant.json" ;;
        server)
            python3 "$HERE/mutate.py" "$server" "$WORK/mutant.json" "$name"
            server="$WORK/mutant.json" ;;
        *) log "mutants.txt: '$name' has target '$target', expected player or server"
           exit 1 ;;
    esac

    ran=$((ran + 1))
    if [ "$(attempt "$player" "$server")" = "FAIL" ]; then
        reason=$(grep -m1 -E 'does not show|expected|SECONDS|proof method|not a count|aggregate|unlinkedTable|does not include|never appeared|Registered' "$WORK/out.txt" | sed 's/^\[plan\] //' | cut -c1-72)
        printf '  %-24s killed   %s\n' "$name" "$reason"
    else
        printf '  %-24s SURVIVED\n' "$name"
        survived=$((survived + 1))
    fi
done < "$HERE/mutants.txt"

# A run that executed no mutants is not a clean run. Every assertion above is of
# the form "each mutant died", which an empty list satisfies.
if [ "$ran" -eq 0 ]; then
    log "no mutants ran -- the catalogue is empty or the filter matched nothing"
    exit 1
fi

if [ "$survived" -ne 0 ]; then
    log "$survived of $ran mutants SURVIVED: plan-check.sh passes on responses it"
    log "claims to reject. Each one is an assertion that cannot fail."
    exit 1
fi
log "$ran mutants, all killed, control green"
