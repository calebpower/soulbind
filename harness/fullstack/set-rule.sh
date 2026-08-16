#!/bin/sh
# Writes a join rule through the REAL admin path.
#
# Not a database insert. The rule arrives the way an operator's tooling would
# send it, so this exercises the same authorization and the same storage a
# deployment does -- and if that path breaks, this breaks with it rather than
# quietly working around it.
set -eu

CORE=$1
CREDENTIAL=$2
GATE=$3
# Optional, and the defaults are exactly what this script did when it took three
# arguments -- so the Phase 5 caller keeps its behaviour byte for byte while the
# forum tier, which needs several different rules across its passes, can ask for
# them here instead of growing a second copy of the signing code.
REQUIRE_LINKED=${4:-true}
DEFAULT_EFFECT=${5:-deny}
DETAIL=${6:-}

python3 - "$CORE" "$CREDENTIAL" "$GATE" "$REQUIRE_LINKED" "$DEFAULT_EFFECT" "$DETAIL" <<'PYEOF'
import hashlib
import hmac
import json
import sys
import time
import urllib.error
import urllib.request
import uuid

core, credential, gate = sys.argv[1], sys.argv[2], sys.argv[3]
require_linked = sys.argv[4].lower() == "true" if len(sys.argv) > 4 else True
default_effect = sys.argv[5] if len(sys.argv) > 5 else "deny"
detail = sys.argv[6] if len(sys.argv) > 6 and sys.argv[6] else None

body = json.dumps({
    "schema": 1,
    "op": "rule.set",
    "id": str(uuid.uuid4()),
    "payload": {
        "gate": gate,
        # requireLinked, not a required kind: the harness links a game account
        # to a second platform, and "must be linked to something" is the rule
        # that expresses what the flow actually establishes.
        "requireLinked": require_linked,
        "requiredKinds": [],
        "graceSeconds": 0,
        "defaultEffect": default_effect,
        **({"detail": detail} if detail else {}),
    },
}, separators=(",", ":"))

timestamp = int(time.time())
nonce = str(uuid.uuid4())
canonical = f"{timestamp}\n{nonce}\n{body}".encode("utf-8")
signature = hmac.new(credential.encode("utf-8"), canonical, hashlib.sha256).hexdigest()

request = urllib.request.Request(
    f"{core}/v1/rpc",
    data=body.encode("utf-8"),
    headers={
        "Content-Type": "application/json",
        "Authorization": f"Bearer {credential}",
        "X-Soulbind-Timestamp": str(timestamp),
        "X-Soulbind-Nonce": nonce,
        "X-Soulbind-Signature": signature,
    },
    method="POST",
)

try:
    with urllib.request.urlopen(request, timeout=15) as response:
        payload = json.loads(response.read().decode("utf-8"))
except urllib.error.URLError as e:
    print(f"could not reach core at {core}: {e}", file=sys.stderr)
    sys.exit(1)

if not payload.get("ok"):
    # Reported rather than swallowed. A rule that silently failed to apply
    # would make the very next assertion -- that an unlinked player is refused
    # -- fail for a reason nobody could see from its message.
    print(f"rule.set was refused: {json.dumps(payload.get('error'))}", file=sys.stderr)
    sys.exit(1)

print(f"rule set on {gate}: requireLinked={require_linked} default={default_effect}")
PYEOF
