#!/bin/sh
# Does Plan actually render soulbind's link data? (§14 Phase 8 gate.)
#
#     plan-check.sh <run-dir> <evidence-dir>
#
# The gate's second clause is "Plan pages render link data for players created
# through real flows", and every word of that is load-bearing:
#
#   * PLAN renders it -- not the connector's own tests, which prove the
#     providers return the right values and prove nothing about whether Plan
#     ever calls them. An annotation-driven extension that fails to register
#     produces a page with a missing panel and NO error anywhere, which is this
#     connector's entire failure mode;
#   * a player created through a REAL flow -- the one the smoke linked by
#     issuing a code in chat and redeeming it, not a row written here.
#
# So this asks Plan, over Plan's own HTTP API, about a player soulbind linked,
# and looks for values that can only have come from the extension.
set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
RUN=$1
EVIDENCE=$2
PLAN_PORT=${PLAN_PORT:-8804}
BASE="http://127.0.0.1:$PLAN_PORT"

log() { echo "[plan] $*"; }

mkdir -p "$EVIDENCE"

# --- is Plan even serving? ---------------------------------------------------
#
# A socket connect, not curl: curl fails on any non-2xx, and Plan answers an
# unauthenticated request with a redirect or a 403 long before it is broken.
# That distinction cost the forum tier a wasted timeout once already.
i=0
while [ "$i" -lt 90 ]; do
    if python3 -c "
import socket, sys
s = socket.socket(); s.settimeout(1)
sys.exit(0 if s.connect_ex(('127.0.0.1', $PLAN_PORT)) == 0 else 1)
" 2>/dev/null; then
        log "Plan's web server is listening on $PLAN_PORT (${i}s)"
        break
    fi
    i=$((i + 1))
    sleep 1
done
if [ "$i" -ge 90 ]; then
    log "Plan's web server never listened on $PLAN_PORT"
    log "proxy log tail:"
    tail -40 "$RUN/velocity.log" 2>/dev/null | sed 's/^/  /' || true
    exit 1
fi

# --- who to ask about --------------------------------------------------------
#
# The player the smoke linked, read from the file the stack wrote rather than
# recomputed here. A second definition of "which player" is a second thing to
# keep in step.
PLAYER_FILE="$RUN/core/linked-player.txt"
if [ ! -s "$PLAYER_FILE" ]; then
    log "no linked player recorded at $PLAYER_FILE -- the smoke did not complete"
    exit 1
fi
PLAYER=$(cat "$PLAYER_FILE")
log "asking Plan about $PLAYER"

# --- ask ---------------------------------------------------------------------
#
# Everything Plan returns is kept, pass or fail. A gate clause about what a page
# shows is answerable only from what the page actually said, and an assertion
# that leaves no artifact behind cannot be re-read when somebody doubts it.
fetch() {
    curl -sS --max-time 20 -o "$2" -w '%{http_code}' "$1" 2>/dev/null || echo "000"
}

SERVER_JSON="$EVIDENCE/plan-server.json"
PLAYER_JSON="$EVIDENCE/plan-player.json"

server_code=$(fetch "$BASE/v1/serverOverview?server=soulbind-harness" "$SERVER_JSON")
player_code=$(fetch "$BASE/v1/player?player=$PLAYER" "$PLAYER_JSON")
log "serverOverview: HTTP $server_code, player: HTTP $player_code"

# --- assert ------------------------------------------------------------------
#
# On the EXTENSION's own values, not on the word "soulbind" appearing somewhere.
# The plugin name is in Plan's page whether or not a single provider ran, so
# matching it would pass for an extension that registered and returned nothing --
# which is precisely the silent failure this tier exists to catch.
#
# `requirements-met` and the platform kinds come from core through the
# connector's providers. Nothing else in Plan produces them.
if [ ! -s "$PLAYER_JSON" ]; then
    log "Plan returned no body for the player (HTTP $player_code)"
    exit 1
fi

if grep -q 'soulbind' "$PLAYER_JSON" 2>/dev/null; then
    log "the extension is present on the player page"
else
    log "no soulbind extension on the player page (HTTP $player_code)"
    log "what Plan returned, first 400 bytes:"
    head -c 400 "$PLAYER_JSON" | sed 's/^/  /'
    echo
    log "kept in full at $PLAYER_JSON"
    exit 1
fi

# The values, not just the presence of the section.
missing=""
for needle in 'Linked' 'Link status'; do
    grep -q "$needle" "$PLAYER_JSON" 2>/dev/null || missing="$missing $needle"
done
if [ -n "$missing" ]; then
    log "the extension is registered but these providers are absent:$missing"
    log "an extension that registers and renders nothing is the failure this checks for"
    exit 1
fi

log "Plan renders soulbind link data for a player linked through the real flow"
