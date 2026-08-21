#!/bin/sh
# Does tools/audit-export.sh actually export the whole log -- and does it
# notice when it has not?
#
#     harness/tools/audit-export-smoke.sh
#
# Copyright (c) 2026 Caleb L. Power
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Two halves, and the second is the one that matters.
#
#   CONTROL -- against a REAL core on SQLite, with more rows than a page: the
#   export must contain every row exactly once, in order, and resuming from the
#   printed cursor must return only what happened after it.
#
#   MUTANTS -- against a replay server standing in for a core that lies about
#   its paging. An export tool is only worth having if a truncated read is
#   distinguishable from a complete one, so each mutant makes core lie in a way
#   that would silently shorten the export, and the tool is required to either
#   refuse or produce a demonstrably different answer. The mutation is in the
#   OBSERVATIONS -- core's responses -- not in the script under test, because
#   mutating the script would only prove the script can be broken.
set -eu

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
WORK=${TMPDIR:-/tmp}/soulbind-audit-export.$$
PORT=${AUDIT_EXPORT_PORT:-7194}
REPLAY_PORT=${AUDIT_EXPORT_REPLAY_PORT:-7195}
ROWS=${AUDIT_EXPORT_ROWS:-120}
PAGE=25

# shellcheck disable=SC1090
. "$HERE/core-env.sh"   # before the trap, so cleanup can always call core_stop

log() { echo "[audit-export] $*"; }
replay_pid=""
cleanup() {
    core_stop
    [ -n "$replay_pid" ] && kill "$replay_pid" 2>/dev/null
    rm -rf "$WORK"
    return 0
}
trap cleanup EXIT INT TERM

mkdir -p "$WORK"

wait_for_port() {
    i=0
    while [ "$i" -lt 60 ]; do
        if python3 -c "
import socket, sys
s = socket.socket(); s.settimeout(1)
sys.exit(0 if s.connect_ex(('127.0.0.1', $1)) == 0 else 1)" 2>/dev/null; then
            return 0
        fi
        i=$((i + 1)); sleep 1
    done
    return 1
}

# ---------------------------------------------------------------------------
# CONTROL: a real core, a log longer than one page.
# ---------------------------------------------------------------------------
log "building core"
core_env_init "$WORK" "$PORT" "$REPO"
core_serve || exit 1

CRED=$(core_cli register --name audit-export-smoke --quiet \
    --capabilities audit-source,config-management)

log "pushing $ROWS audit rows"
python3 - "$PORT" "$CRED" "$ROWS" <<'PY'
import hashlib, hmac, json, sys, time, urllib.request, uuid
port, credential, rows = sys.argv[1], sys.argv[2], int(sys.argv[3])
# In-process rather than $ROWS invocations of rpc.sh: this is arranging the
# fixture, not exercising the wire format, and 120 python startups is a minute
# of a stage that should take seconds. The tool under test still goes through
# rpc.sh for every request it makes.
for i in range(rows):
    body = json.dumps({"schema": 1, "op": "audit.push", "id": str(uuid.uuid4()),
                       "payload": {"action": "smoke.row", "subjectId": "row-%d" % i}},
                      separators=(",", ":"))
    ts, nonce = int(time.time()), str(uuid.uuid4())
    sig = hmac.new(credential.encode(), ("%d\n%s\n%s" % (ts, nonce, body)).encode(),
                   hashlib.sha256).hexdigest()
    req = urllib.request.Request(
        "http://127.0.0.1:%s/v1/rpc" % port, data=body.encode(),
        headers={"Content-Type": "application/json",
                 "Authorization": "Bearer " + credential,
                 "X-Soulbind-Timestamp": str(ts), "X-Soulbind-Nonce": nonce,
                 "X-Soulbind-Signature": sig}, method="POST")
    with urllib.request.urlopen(req, timeout=15) as response:
        payload = json.loads(response.read().decode())
    if not payload.get("ok"):
        raise SystemExit("audit.push refused: %s" % payload.get("error"))
PY

log "exporting, page size $PAGE"
SOULBIND_AUDIT_PAGE=$PAGE sh "$REPO/tools/audit-export.sh" \
    "$(core_url)" "$CRED" > "$WORK/export.jsonl" 2> "$WORK/export.err"

CURSOR=$(sed -n 's/.*resume with after-sequence //p' "$WORK/export.err")
PAGES=$(sed -n 's/.*in \([0-9]*\) request(s).*/\1/p' "$WORK/export.err")

python3 - "$WORK/export.jsonl" "$ROWS" "$CURSOR" "$PAGES" <<'PY'
import json, sys
path, rows, cursor, pages = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4])
entries = [json.loads(line) for line in open(path, encoding="utf-8")]

# More than one request, or the paging was never exercised and every assertion
# below would hold for a tool that cannot page at all.
if pages < 2:
    raise SystemExit("the export took %d request(s): paging was never exercised, so"
                     " this run proves nothing about a log longer than a page" % pages)

seqs = [e["sequence"] for e in entries]
if seqs != sorted(set(seqs)):
    raise SystemExit("sequences are out of order or repeated: paging lost its place")
if seqs != list(range(1, len(seqs) + 1)):
    raise SystemExit("a gap in the exported sequences: rows are missing")

pushed = sum(1 for e in entries if e.get("action") == "smoke.row")
if pushed != rows:
    raise SystemExit("exported %d of the %d rows pushed" % (pushed, rows))
if cursor != seqs[-1]:
    raise SystemExit("the resume cursor (%d) is not the last row (%d)" % (cursor, seqs[-1]))
print("control: %d rows over %d requests, contiguous, cursor at %d"
      % (len(entries), pages, cursor))
PY

# Resuming from the cursor with nothing new must return nothing -- otherwise a
# nightly archive re-exports the whole log every night.
SOULBIND_AUDIT_PAGE=$PAGE sh "$REPO/tools/audit-export.sh" \
    "$(core_url)" "$CRED" "$CURSOR" > "$WORK/resume-empty.jsonl" 2>/dev/null
if [ -s "$WORK/resume-empty.jsonl" ]; then
    log "FAIL resuming from the cursor re-exported $(wc -l < "$WORK/resume-empty.jsonl") rows"
    exit 1
fi

# And resuming after three new rows must return exactly those three.
for i in 1 2 3; do
    sh "$REPO/tools/rpc.sh" "$(core_url)" "$CRED" audit.push \
        "{\"action\":\"smoke.later\",\"subjectId\":\"later-$i\"}" > /dev/null
done
SOULBIND_AUDIT_PAGE=$PAGE sh "$REPO/tools/audit-export.sh" \
    "$(core_url)" "$CRED" "$CURSOR" > "$WORK/resume.jsonl" 2>/dev/null
got=$(wc -l < "$WORK/resume.jsonl" | tr -d ' ')
if [ "$got" != "3" ]; then
    log "FAIL resuming after three new rows returned $got"
    exit 1
fi
log "control: resume returns only what is new"

core_stop

# ---------------------------------------------------------------------------
# MUTANTS: a core that lies about its paging.
# ---------------------------------------------------------------------------
failures=0
checked=0

for mutant in truncate-silently freeze-cursor empty-but-more; do
    checked=$((checked + 1))
    MUTANT=$mutant python3 "$HERE/audit-replay.py" "$REPLAY_PORT" > "$WORK/replay.log" 2>&1 &
    replay_pid=$!
    if ! wait_for_port "$REPLAY_PORT"; then
        log "FAIL replay server for $mutant never listened"
        failures=$((failures + 1))
        continue
    fi

    set +e
    SOULBIND_AUDIT_PAGE=$PAGE sh "$REPO/tools/audit-export.sh" \
        "http://127.0.0.1:$REPLAY_PORT" ignored-credential \
        > "$WORK/$mutant.jsonl" 2> "$WORK/$mutant.err"
    status=$?
    set -e

    lines=$(wc -l < "$WORK/$mutant.jsonl" | tr -d ' ')
    case "$mutant" in
        truncate-silently)
            # Core claims the log ends after one page. The tool CANNOT catch
            # this -- a core that lies about `more` is indistinguishable from a
            # short log, and no client-side check can tell them apart. What is
            # asserted is that the tool reports the shortfall it saw rather
            # than a whole-log figure, so the operator has something to compare
            # against the row count they expect.
            if [ "$lines" != "$PAGE" ]; then
                log "FAIL $mutant: exported $lines rows, expected the single page of $PAGE"
                failures=$((failures + 1))
            elif ! grep -q "exported $PAGE rows in 1 request" "$WORK/$mutant.err"; then
                log "FAIL $mutant: the summary does not state how little it got"
                cat "$WORK/$mutant.err" | sed 's/^/  /'
                failures=$((failures + 1))
            fi ;;
        freeze-cursor|empty-but-more)
            # Core says more rows remain but never advances past them. A tool
            # that trusts this loops forever, writing the same page: the stage
            # hangs, which reads as an infrastructure problem rather than the
            # defect it is. Both must exit non-zero, promptly.
            if [ "$status" -eq 0 ]; then
                log "FAIL $mutant: the export reported success against a core"
                log "     that never finished paging (wrote $lines rows)"
                failures=$((failures + 1))
            fi ;;
    esac

    kill "$replay_pid" 2>/dev/null || true
    wait "$replay_pid" 2>/dev/null || true
    replay_pid=""
done

if [ "$checked" -eq 0 ]; then
    log "FAIL no mutants ran, so this stage asserted nothing"
    exit 1
fi
if [ "$failures" -ne 0 ]; then
    log "FAIL $failures of $checked mutants were not caught"
    exit 1
fi

log "OK control green, $checked mutants caught"
