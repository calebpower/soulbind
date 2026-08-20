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
    # Truncated first. `curl -o` does not empty the file when the connection
    # fails, so a second call that cannot reach Plan leaves the PREVIOUS body on
    # disk -- and `[ -s "$file" ]` then passes on evidence from a fetch that did
    # not happen. run.sh removes out/ per invocation, which hides this from the
    # battery and not at all from anybody running this script directly.
    : > "$2"
    curl -sS --compressed --max-time 20 -o "$2" -w '%{http_code}' "$1" 2>/dev/null || echo "000"
}

# Reads a provider's VALUE out of Plan's JSON, by provider name.
#
# The shape is NOT {"name": ..., "value": ...}. Plan nests the name one level
# down, and `value` is a sibling of the object holding it:
#
#     {"description": {"name": "linked", "text": "Linked", ...},
#      "type": "BOOLEAN",
#      "value": true}
#
# The first version of this function looked for a single node carrying both
# `name` and `value`. No Plan response has ever contained one. That is the
# mirror image of this repository's usual defect -- not an assertion that
# cannot fail, but one that cannot PASS -- and it is worth as little: the stage
# went red on a run where all six providers had rendered correctly, and the
# commit that introduced it claimed to have made the check able to fail. It had.
# It had also made it unable to succeed. The shape was imagined rather than read
# off a real response, and a synthetic fixture built from the same imagination
# agreed with it.
#
# A JSON null exits 1, the same as a missing provider. Otherwise Python's
# `None` is printed, and a caller testing "non-empty" accepts the string "None"
# as a real value -- which is exactly how the `proof` assertion came to pass on
# a response with no proof method in it.
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
        described = node.get("description")
        if isinstance(described, dict) and described.get("name") == name \
                and "value" in node:
            if node["value"] is None:
                raise SystemExit(1)
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

# Is there a soulbind table with this name, and does it have columns?
#
# Separate from value_of because a table is not a value: it has a name, a column
# list and rows, and `grep -q unlinkedTable` is satisfied by a text file
# containing the word. That is not hypothetical -- a 76-byte plain-text file
# holding only the four provider names passed the whole server block below.
table_present() {
    python3 -c '
import json, sys
name = sys.argv[1]
try:
    doc = json.load(open(sys.argv[2], encoding="utf-8"))
except Exception:
    sys.exit(1)

def walk(node):
    if isinstance(node, dict):
        if node.get("tableName") == name:
            table = node.get("table") or {}
            if table.get("columns"):
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
# "None" is listed because it is what a JSON null used to print here. value_of
# now exits 1 on null so this cannot arise, and the case stays as a belt on a
# value that is otherwise unconstrained: `proof` is the one provider whose
# assertion accepts any non-empty string, so it is the one that quietly accepted
# garbage when the walker handed it some.
case "$proof" in
    ''|'-'|'None')
        dump_and_fail "no proof method rendered (got '$proof')" ;;
esac

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
    # Gated on the same STRUCTURED read the assertions use, not on a grep for a
    # provider name. Gating on `grep -q linkedPlayers` and then asserting the
    # same grep made the first of the four assertions below unfailable by
    # construction: the loop could not exit until it was true.
    if [ "$server_code" = "200" ] && value_of linkedPlayers "$SERVER_JSON" >/dev/null 2>&1; then
        server_ok=1
        break
    fi
    i=$((i + 1))
    [ "$i" -lt 24 ] && sleep 5
done

if [ "$server_ok" -ne 1 ]; then
    log "the server-wide providers never appeared (last HTTP $server_code after $((i * 5))s)"
    head -c 600 "$SERVER_JSON" 2>/dev/null | sed 's/^/  /'
    echo
    exit 1
fi

# On VALUES again, for the same reason as the player page.
#
# The first version grepped for the four provider NAMES. That is the identical
# defect the player page had just been rewritten to remove, left standing twenty
# lines further down: a plain-text file containing only the words
# `linkedPlayers unlinkedPlayers unknownPlayers unlinkedTable` -- no JSON, no
# extension, no Plan -- passed the entire block.
for provider in linkedPlayers unlinkedPlayers unknownPlayers; do
    count=$(value_of "$provider" "$SERVER_JSON" || true)
    case "$count" in
        ''|*[!0-9]*)
            log "server page: $provider is '$count', which is not a count"
            head -c 600 "$SERVER_JSON" | sed 's/^/  /'
            echo
            exit 1 ;;
    esac
    log "server page: $provider=$count"
done

table_present unlinkedTable "$SERVER_JSON" \
    || { log "server page has no soulbind unlinkedTable with columns"; exit 1; }

# Plan's OWN aggregate, and the one server-side number that is presently worth
# anything.
#
# It is not one of this connector's providers: Plan computes it itself by
# aggregating the per-player `linked` boolean across every player in its
# database. That makes it corroboration rather than an echo -- it says Plan
# stored what the player provider returned and could compute over it -- and it
# is non-zero on an idle server, which the three counters above are not.
aggregate=$(value_of linked_aggregate "$SERVER_JSON" || true)
case "$aggregate" in
    ''|'0%'|'0.0%')
        log "Plan's own aggregate over the stored player data reads '$aggregate'."
        log "A player IS linked in this run, so Plan either did not store the"
        log "player provider's value or could not aggregate it."
        head -c 600 "$SERVER_JSON" | sed 's/^/  /'
        echo
        exit 1 ;;
esac
log "server page: linked_aggregate=$aggregate (Plan's own aggregation)"

# KNOWN GAP, stated because it is invisible from a green run.
#
# The three counters above are asserted to be counts, and nothing more, because
# in this harness they are all legitimately 0: they are derived from
# proxy.getAllPlayers(), and no player is connected when Plan's SERVER_PERIODICAL
# fires. So they would read 0 for a working extension and 0 for a broken one,
# and an assertion of `>= 1` here would fail on correct code.
#
# That makes `linked_aggregate` the only server-side value in this stage that
# discriminates. Closing the gap properly means holding a linked player on the
# proxy across one gather -- or changing where the roster comes from, since a
# count of "linked players" that silently means "linked players online right
# now" reads as 0 on an idle server and is arguably the wrong number for a
# dashboard. docs/DECISIONS.md 8.19.
log "the server page renders four soulbind providers and a table"

# --- corroboration, kept on green as well as on red -------------------------
#
# The proxy log is where `Registered extension: soulbind` appears, and that line
# is Plan's, not soulbind's -- the one statement in this stage not made by the
# code under test. It was previously tailed on a single failure branch and
# discarded on success, so the run that PASSED kept no evidence of the thing
# most worth keeping.
if [ -f "$RUN/velocity.log" ]; then
    cp "$RUN/velocity.log" "$EVIDENCE/velocity.log"
fi
if [ -d "$RUN/proxy/plugins/plan/libraries" ]; then
    (cd "$RUN/proxy/plugins/plan/libraries" && sha256sum ./*.jar) \
        > "$EVIDENCE/plan-libraries.sha256" 2>/dev/null || true
fi

grep -q 'Registered extension: soulbind' "$EVIDENCE/velocity.log" 2>/dev/null \
    || { log "Plan never logged 'Registered extension: soulbind'. Every assertion"
         log "above reads a page; this reads Plan's own account of whether it"
         log "accepted the extension at all."
         exit 1; }
log "Plan's own log confirms it registered the soulbind extension"

# Last, so it is not printed by a run that is about to fail. The first version
# announced this two checks early, and the failing run's output read
# "Plan renders soulbind link data ..." immediately before the reason it did not.
log "Plan renders soulbind link data for a player linked through the real flow"
