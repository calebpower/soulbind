#!/bin/sh
# Tier 7 against the DEPLOYMENT, not against a fresh embedded core.
#
#     fuzz-live.sh <core-url> <credential> <evidence-dir> [iterations]
#
# `:core:fuzzTest` already drives real HTTP and real signing against a core it
# starts itself. What it cannot do is drive them against a core that has been
# RUNNING — one the journeys and the simulated-user tier have already filled
# with subjects, identities, spent codes, rules and an audit log.
#
# That difference is the whole reason this stage exists, and it is §11's own
# argument for the nemesis living in the weighted pool: "faults land at
# arbitrary depths in an accumulated history rather than against a clean
# fixture". A malformed request against an empty database exercises the decoder.
# The same request against a populated one exercises the decoder, the query
# paths it reaches, and whatever the accumulated state makes reachable.
#
# The oracle is Tier 7's, unchanged, because it needs no second implementation
# of any rule:
#
#   1. no 5xx, ever;
#   2. every response is a well-formed envelope with an `ok` field;
#   3. never an INTERNAL error code -- that is core admitting it did not mean to;
#   4. core is still alive and answering correctly afterwards.
#
# "The right things are rejected" is deliberately NOT asserted. It would need a
# second implementation of every validation rule to compare against, and would
# be wrong wherever the two disagreed with no way to tell which.
set -eu

CORE=${1:?usage: fuzz-live.sh <core-url> <credential> <evidence-dir> [iterations]}
CREDENTIAL=${2:?}
EVIDENCE=${3:?}
ITERATIONS=${4:-400}
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)

mkdir -p "$EVIDENCE"

python3 - "$CORE" "$CREDENTIAL" "$REPO/corpus/hostile-inputs.txt" "$ITERATIONS" \
    "$EVIDENCE/fuzz-live.log" <<'PYEOF'
import hashlib, hmac, json, os, random, sys, time, uuid
import urllib.error, urllib.request

core, credential, corpus_path, iterations, log_path = sys.argv[1:6]
iterations = int(iterations)

# The seed is printed unconditionally and replayable, for the same reason
# ProtocolFuzzTest prints its own: a run that passes and a run that fails must be
# equally reproducible, or the first failure is the first time anybody tries.
seed = int(os.environ.get("SOULBIND_FUZZ_SEED", str(int(time.time()))))
rng = random.Random(seed)
print(f"[fuzz-live] seed={seed}  (replay with SOULBIND_FUZZ_SEED={seed})")

def load_corpus(path):
    values = []
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            # The corpus stores escapes literally so the file stays ASCII.
            values.append(line.encode("utf-8").decode("unicode_escape"))
    if not values:
        raise SystemExit("the corpus is empty; this stage would fuzz nothing")
    return values

corpus = load_corpus(corpus_path)
print(f"[fuzz-live] {len(corpus)} corpus entries")

OPERATIONS = ["code.issue", "code.redeem", "identity.describe", "decide",
              "rule.set", "audit.query", "heartbeat", "hello"]
FIELDS = ["platformKind", "platformId", "display", "code", "gate", "key", "value"]

def post(body, timestamp=None, nonce=None, token=None):
    timestamp = int(time.time()) if timestamp is None else timestamp
    nonce = str(uuid.uuid4()) if nonce is None else nonce
    token = credential if token is None else token
    canonical = f"{timestamp}\n{nonce}\n{body}".encode("utf-8")
    signature = hmac.new(token.encode("utf-8"), canonical, hashlib.sha256).hexdigest()
    request = urllib.request.Request(
        f"{core}/v1/rpc", data=body.encode("utf-8"),
        headers={"Content-Type": "application/json",
                 "Authorization": f"Bearer {token}",
                 "X-Soulbind-Timestamp": str(timestamp),
                 "X-Soulbind-Nonce": nonce,
                 "X-Soulbind-Signature": signature},
        method="POST")
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            return response.status, response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:
        # A core that DIED is the single most important thing this stage can
        # find, and the first version let it escape as a traceback: urllib
        # raises URLError for a refused connection, which is not an HTTPError,
        # so the process would abort mid-run with a stack trace instead of
        # reporting "the deployment stopped answering". Status 0 is not a status
        # core can return, so it cannot be confused with one.
        return 0, f"unreachable: {e}"

def a_case():
    """One request, hostile somewhere."""
    hostile = rng.choice(corpus)
    op = rng.choice(OPERATIONS)
    shape = rng.randrange(6)
    if shape == 0:
        op = hostile                                    # hostile operation name
        payload = {}
    elif shape == 1:
        payload = {rng.choice(FIELDS): hostile}         # hostile field value
    elif shape == 2:
        payload = {hostile: "x"}                        # hostile field NAME
    elif shape == 3:
        payload = hostile                               # payload is not an object
    elif shape == 4:
        payload = {f: hostile for f in FIELDS}          # hostile everywhere
    else:
        payload = {"platformKind": "game", "platformId": hostile,
                   "display": hostile, "code": hostile}
    body = json.dumps({"schema": 1, "op": op, "id": str(uuid.uuid4()),
                       "payload": payload}, separators=(",", ":"))
    return op, body

failures = []
log = open(log_path, "w", encoding="utf-8")
log.write(f"seed={seed}\n")

for i in range(iterations):
    op, body = a_case()
    status, text = post(body)
    log.write(f"{i} {status} op={op!r} body={body[:200]!r}\n")

    if status == 0:
        failures.append(f"core stopped answering after {i} requests: {text}")
        break
    if status >= 500:
        failures.append(f"HTTP {status} for op={op!r} body={body[:300]!r}")
        continue
    try:
        answer = json.loads(text)
    except ValueError:
        failures.append(f"response is not JSON for op={op!r}: {text[:300]!r}")
        continue
    if "ok" not in answer:
        failures.append(f"response is not an envelope for op={op!r}: {text[:300]!r}")
        continue
    code = (answer.get("error") or {}).get("code", "")
    if code == "internal":
        failures.append(f"INTERNAL error for op={op!r} body={body[:300]!r}")
    if len(failures) > 20:
        failures.append("... stopping after 20")
        break

# Alive afterwards, and answering CORRECTLY -- a process that is still listening
# but answering nonsense has not survived in any sense worth asserting.
status, text = post(json.dumps({"schema": 1, "op": "heartbeat",
                                "id": str(uuid.uuid4()), "payload": {}},
                               separators=(",", ":")))
alive = status == 200 and json.loads(text).get("ok") is True
log.write(f"heartbeat after: {status} {text[:200]}\n")
log.close()

if not alive:
    failures.append(f"core did not answer a valid heartbeat afterwards: HTTP {status}")

print(f"[fuzz-live] {iterations} hostile requests, {len(failures)} failure(s)")
for failure in failures:
    print(f"[fuzz-live]   {failure}")
sys.exit(1 if failures else 0)
PYEOF
