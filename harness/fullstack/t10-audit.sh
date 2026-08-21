#!/bin/sh
# Tier 10: deep reads over the world the simulated users accumulated.
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
#     t10-audit.sh <core-url> <auditor-credential> <evidence-dir>
#
# §11 Tier 10: the read paths "as an actor whose world was accumulated by a
# prior sim run: paging past thresholds, deep audit queries". Every other tier
# reads a log its own few actions produced; this one reads a log deeper than
# audit.query's single-request ceiling, which is the regime where the paging
# contract either holds or quietly truncates.
#
# WATCHDOG SEMANTICS, per the tier's definition: any response with a 5xx
# status fails the stage immediately, and fault injection never runs here --
# a 5xx observed by this tier is core failing under legitimate load, with
# nothing to blame it on.
#
# The credential is a REGISTERED PRINCIPAL of its own (t10-auditor:
# audit-source + config-management, recorded in harness/principals.txt). It
# tops the log up past the ceiling through real audit.push operations when the
# sim run alone left it short -- rows created through the real wire protocol by
# a real audit-source connector, exactly as a production event reporter would.
# The alternative was a threshold the sim sometimes crosses naturally and
# sometimes does not, which is a stage that flickers between meaningful and
# vacuous depending on the dice.
set -eu

CORE=$1
CREDENTIAL=$2
EVIDENCE=$3

mkdir -p "$EVIDENCE"

# NOT `python3 ... | tee`: in a pipeline the shell reports the LAST command's
# status, so the tee's success replaced the driver's failure and a refused
# stage exited 0. Caught on this script's first real run -- the watchdog fired
# correctly and the stage reported green anyway, which is the exact shape of
# failure this battery exists to keep out of every other stage. pipefail is not
# portable /bin/sh, so: capture, then replay the log, then exit honestly.
status=0
python3 - "$CORE" "$CREDENTIAL" > "$EVIDENCE/t10-audit.log" 2>&1 <<'PYEOF' || status=$?
import hashlib
import hmac
import json
import sys
import time
import urllib.error
import urllib.request
import uuid

core, credential = sys.argv[1], sys.argv[2]

FLOOR = 1200          # comfortably past MAX_LIMIT=1000, the single-query ceiling
PAGE = 250            # small enough that a full read is genuinely many pages
FILLER_ACTION = "t10.depth"


def call(op, payload):
    """One signed request. Returns (status, envelope-or-None).

    The watchdog lives here, so no request in this tier can dodge it: a 5xx
    or a non-envelope body raises, naming the operation.
    """
    body = json.dumps({
        "schema": 1, "op": op, "id": str(uuid.uuid4()), "payload": payload,
    }, separators=(",", ":"))
    ts, nonce = int(time.time()), str(uuid.uuid4())
    sig = hmac.new(credential.encode(), ("%d\n%s\n%s" % (ts, nonce, body)).encode(),
                   hashlib.sha256).hexdigest()
    request = urllib.request.Request(
        core + "/v1/rpc", data=body.encode(),
        headers={"Content-Type": "application/json",
                 "Authorization": "Bearer " + credential,
                 "X-Soulbind-Timestamp": str(ts), "X-Soulbind-Nonce": nonce,
                 "X-Soulbind-Signature": sig},
        method="POST")
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            status, text = response.status, response.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        status, text = e.code, e.read().decode("utf-8", "replace")
    if status >= 500:
        raise SystemExit("WATCHDOG: %s answered %d. This tier injects no faults, so a"
                         " 5xx here is core failing under legitimate read load: %s"
                         % (op, status, text[:400]))
    try:
        envelope = json.loads(text)
    except ValueError:
        raise SystemExit("WATCHDOG: %s answered status %d with a non-envelope body: %s"
                         % (op, status, text[:400]))
    if not envelope.get("ok"):
        raise SystemExit("%s refused: %s -- this tier sends only well-formed requests a"
                         " registered auditor is entitled to make, so a refusal is a"
                         " defect in the tier or in the grant, not noise"
                         % (op, json.dumps(envelope.get("error"))[:400]))
    return envelope["payload"]


def read_everything(page_size, extra=None):
    """Pages the whole log; returns (rows, requests-made). Asserts contiguity."""
    rows, cursor, requests = [], 0, 0
    while True:
        payload = {"limit": page_size, "afterSequence": cursor}
        if extra:
            payload.update(extra)
        page = call("audit.query", payload)
        requests += 1
        for entry in page["entries"]:
            rows.append(entry)
        if not page["more"]:
            break
        nxt = page["lastSequence"]
        if nxt <= cursor:
            raise SystemExit("audit.query reported more without advancing the cursor"
                             " past %d; paging cannot make progress" % cursor)
        cursor = nxt
        if requests > 10_000:
            raise SystemExit("ten thousand pages without an end; refusing to loop")
    return rows, requests


# --- how deep did the sim leave the log? -------------------------------------
existing, _ = read_everything(1000)
print("t10: the accumulated log holds %d rows" % len(existing))

# --- top up past the ceiling through the real protocol -----------------------
# At least fifty, always: the filtered-paging assertion below compares what
# comes back against what was pushed, and on a sim run that crossed the floor
# by itself a top-up of zero would make that comparison 0 == 0 -- a check that
# still runs and no longer checks anything.
need = max(50, FLOOR - len(existing))
pushed = 0
while pushed < need:
    call("audit.push", {"action": FILLER_ACTION,
                        "subjectId": "t10-%d" % pushed})
    pushed += 1
print("t10: pushed %d filler rows through audit.push (floor %d, found %d)"
      % (pushed, FLOOR, len(existing)))

# --- the deep read: the whole log, in small pages ----------------------------
rows, requests = read_everything(PAGE)
total = len(rows)
if total <= 1000:
    raise SystemExit("only %d rows after top-up; the deep read never went past the"
                     " single-query ceiling, so this stage asserted nothing about"
                     " paging at depth" % total)
if requests < 3:
    raise SystemExit("the whole log came back in %d request(s); paging was not"
                     " exercised" % requests)

sequences = [r["sequence"] for r in rows]
if sequences != sorted(set(sequences)):
    raise SystemExit("sequences repeated or out of order: paging lost its place")
gaps = [b for a, b in zip(sequences, sequences[1:]) if b != a + 1]
if gaps:
    raise SystemExit("gaps in the paged read at %s: rows are missing" % gaps[:5])
print("t10: read %d rows over %d requests, contiguous" % (total, requests))

# --- the silent-ceiling assertion, at depth ----------------------------------
# Ask for everything in one request. The limit is clamped server-side; what
# must be true is that the truncation SAYS SO -- this is the exact caller shape
# that was silently wrong before Phase 10.
clamped = call("audit.query", {"limit": 999_999_999})
if len(clamped["entries"]) > 1000:
    raise SystemExit("the server-side ceiling did not hold: %d rows in one response"
                     % len(clamped["entries"]))
if not clamped["more"]:
    raise SystemExit("a clamped response over a %d-row log claimed it was the whole"
                     " log. This is the silent truncation the paging fields exist to"
                     " make visible." % total)
print("t10: a greedy query got %d rows and admitted more remained"
      % len(clamped["entries"]))

# --- filters compose with the cursor, at depth -------------------------------
fillers, _ = read_everything(PAGE, extra={"action": FILLER_ACTION})
if len(fillers) != pushed:
    raise SystemExit("filtered paging returned %d '%s' rows, %d were pushed: the"
                     " filter and the cursor did not compose"
                     % (len(fillers), FILLER_ACTION, pushed))
if any(r["action"] != FILLER_ACTION for r in fillers):
    raise SystemExit("the action filter leaked other rows in")
print("t10: filtered deep read returned exactly the %d pushed rows" % pushed)

print("t10: OK -- depth %d, watchdog clean" % total)
PYEOF

cat "$EVIDENCE/t10-audit.log"
exit $status
