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
#
# `--compressed`, which is NOT optional. Plan's JettyResponseSender gzips every
# application/json response and sets Content-Encoding: gzip -- on mime type
# alone, never looking at Accept-Encoding. curl's default is to send no
# Accept-Encoding and hand back whatever arrives verbatim, so the first run of
# this stage wrote 2 KB of gzip into the evidence file, every grep missed, and it
# reported "no soulbind extension" about a page that had rendered all six
# providers correctly. The failure was here, not in the connector.
#
# DEPENDS ON Plan having no certificate. Plan disables web authorization only
# because it is serving HTTP; a deployment with a Cert.jks would answer every
# /v1/ call with a redirect or a 403 and this stage would fail for a reason
# having nothing to do with soulbind. Stated because it is invisible otherwise.
fetch() {
    curl -sS --compressed --max-time 20 -o "$2" -w '%{http_code}' "$1" 2>/dev/null || echo "000"
}

# Reads a provider's VALUE out of Plan's JSON, by provider name.
value_of() {
    python3 -c '
import json, sys
name = sys.argv[1]
try:
    doc = json.load(open(sys.argv[2], encoding="utf-8"))
except Exception:
    sys.exit(1)

def walk(node):
    if isinstance(node, dict):
        # Plan renders a provider as an object carrying its name and value.
        if node.get("name") == name and "value" in node:
            print(node["value"])
            raise SystemExit(0)
        for v in node.values():
            walk(v)
    elif isinstance(node, list):
        for v in node:
            walk(v)

walk(doc)
sys.exit(1)
' "$1" "$2" 2>/dev/null
}

PLAYER_JSON="$EVIDENCE/plan-player.json"
SERVER_JSON="$EVIDENCE/plan-server.json"

player_code=$(fetch "$BASE/v1/player?player=$PLAYER" "$PLAYER_JSON")
log "player page: HTTP $player_code"

# Whatever happens below, the body is on disk and readable. The first version
# dumped it on only ONE of its failure branches -- the least likely one -- so the
# likelier failure produced a verdict with no evidence behind it.
dump_and_fail() {
    log "$1"
    log "HTTP $player_code; first 600 bytes of what Plan returned:"
    head -c 600 "$PLAYER_JSON" 2>/dev/null | sed 's/^/  /'
    echo
    log "kept in full at $PLAYER_JSON"
    exit 1
}

[ -s "$PLAYER_JSON" ] || dump_and_fail "Plan returned no body for the player"

# --- assert, on VALUES ---------------------------------------------------
#
# Not on the labels. `Linked` and `Link status` are the annotations' text= --
# Plan renders them whether the provider returned true or false, so an extension
# reporting "not linked" for a player who IS linked passed the first version of
# this check identically. The unlinked bot in the same run produced exactly those
# rows.
#
# Not on the plugin name either. `soulbind` appears in extensionInformation
# whether or not a single provider ever ran, which is precisely the silent
# failure -- a registered extension that renders nothing -- this tier exists to
# catch.
linked=$(value_of linked "$PLAYER_JSON" || true)
status=$(value_of linkStatus "$PLAYER_JSON" || true)
platforms=$(value_of platforms "$PLAYER_JSON" || true)
proof=$(value_of proof "$PLAYER_JSON" || true)
since=$(value_of linkedSince "$PLAYER_JSON" || true)

log "linked=$linked linkStatus=$status platforms=$platforms proof=$proof linkedSince=$since"

[ "$linked" = "True" ] || [ "$linked" = "true" ] \
    || dump_and_fail "Plan does not show this player as linked (linked=$linked), but the smoke linked them through the real flow"
[ "$status" = "linked" ] \
    || dump_and_fail "link status reads '$status', expected 'linked'"
case "$platforms" in
    *game*) ;;
    *) dump_and_fail "platforms reads '$platforms' and does not include the game kind" ;;
esac
[ -n "$proof" ] && [ "$proof" != "-" ] \
    || dump_and_fail "no proof method rendered (got '$proof')"

# Milliseconds, not seconds. Core speaks seconds and Plan's DATE_YEAR expects
# ms; getting it wrong renders 1970 on every page, which reads as a data problem
# rather than a units one. A seconds value would be ~1.7e9; ms is ~1.7e12.
case "$since" in
    ''|*[!0-9]*) dump_and_fail "linkedSince is not a number: '$since'" ;;
esac
if [ "$since" -lt 100000000000 ]; then
    dump_and_fail "linkedSince=$since looks like SECONDS, not milliseconds -- Plan would render 1970"
fi

log "the player page renders real link state, in the right units"

# --- the server-wide providers -------------------------------------------
#
# /v1/extensionData, NOT /v1/serverOverview. The first version asked
# serverOverview?server=soulbind-harness, which returned HTTP 400 on every run
# and was never asserted on -- so the four server-wide providers and the table
# were covered by nothing at all while the stage reported green.
#
# Two reasons it could never have worked: Server.ServerName is ignored on a
# proxy (Plan registers it as `Velocity`), and serverOverview carries no
# extension data even when it succeeds.
#
# Retried, because Plan gathers SERVER_PERIODICAL on its own schedule and the
# values are absent for a minute or two after stack-up. A single shot here would
# be a flake that looks like a defect.
server_ok=0
i=0
while [ "$i" -lt 24 ]; do
    server_code=$(fetch "$BASE/v1/extensionData?server=Velocity" "$SERVER_JSON")
    if [ "$server_code" = "200" ] && grep -q 'linkedPlayers' "$SERVER_JSON" 2>/dev/null; then
        server_ok=1
        break
    fi
    i=$((i + 1))
    sleep 5
done

if [ "$server_ok" -ne 1 ]; then
    log "the server-wide providers never appeared (last HTTP $server_code after $((i * 5))s)"
    head -c 600 "$SERVER_JSON" 2>/dev/null | sed 's/^/  /'
    echo
    exit 1
fi

for provider in linkedPlayers unlinkedPlayers unknownPlayers; do
    grep -q "$provider" "$SERVER_JSON" \
        || { log "server page is missing the $provider provider"; exit 1; }
done
grep -q 'unlinkedTable' "$SERVER_JSON" \
    || { log "server page is missing the unlinked-players table"; exit 1; }

log "the server page renders all four server-wide providers"
log "Plan renders soulbind link data for a player linked through the real flow"
