#!/bin/sh
# Redeems a code as some platform, through the real protocol.
#
# Stands in for a connector this stack does not run. It signs and speaks the
# real wire format -- what it is not is a backdoor: there is no path here that
# writes state without going through core's own redeem.
set -eu

CORE=$1
CREDENTIAL=$2
CODE=$3
KIND=$4
PLATFORM_ID=$5

python3 - "$CORE" "$CREDENTIAL" "$CODE" "$KIND" "$PLATFORM_ID" <<'PYEOF'
import hashlib
import hmac
import json
import sys
import time
import urllib.error
import urllib.request
import uuid

core, credential, code, kind, platform_id = sys.argv[1:6]

body = json.dumps({
    "schema": 1,
    "op": "code.redeem",
    "id": str(uuid.uuid4()),
    "payload": {
        "code": code,
        "platformKind": kind,
        "platformId": platform_id,
        "display": platform_id,
    },
}, separators=(",", ":"))

timestamp = int(time.time())
nonce = str(uuid.uuid4())
signature = hmac.new(
    credential.encode("utf-8"),
    f"{timestamp}\n{nonce}\n{body}".encode("utf-8"),
    hashlib.sha256,
).hexdigest()

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
    print(f"could not reach core: {e}", file=sys.stderr)
    sys.exit(1)

if not payload.get("ok"):
    print(f"redeem refused: {json.dumps(payload.get('error'))}", file=sys.stderr)
    sys.exit(1)

print(f"redeemed as {kind}:{platform_id}, "
      f"{len(payload['payload']['identities'])} identities")
PYEOF
