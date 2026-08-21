#!/bin/sh
# One signed call to core, for the harnesses.
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
# ONE implementation of the signing, deliberately. There were about to be three
# -- set-rule.sh, and a second for issuing codes -- and three copies of an HMAC
# canonical form is three chances to drift from the thing the golden vectors
# exist to keep identical.
#
#   rpc.sh <core-url> <credential> <operation> <payload-json>
#
# Prints the response payload as JSON on success, and exits non-zero with the
# refusal on stderr otherwise. A caller that ignores the exit code gets an empty
# string rather than a plausible-looking wrong answer.
set -eu

CORE=$1
CREDENTIAL=$2
OPERATION=$3
PAYLOAD=$4

python3 - "$CORE" "$CREDENTIAL" "$OPERATION" "$PAYLOAD" <<'PYEOF'
import hashlib
import hmac
import json
import sys
import time
import urllib.error
import urllib.request
import uuid

core, credential, operation, payload_json = sys.argv[1:5]

try:
    payload = json.loads(payload_json)
except ValueError as e:
    print(f"payload is not JSON: {e}", file=sys.stderr)
    sys.exit(2)

body = json.dumps({
    "schema": 1,
    "op": operation,
    "id": str(uuid.uuid4()),
    "payload": payload,
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
        answer = json.loads(response.read().decode("utf-8"))
except urllib.error.URLError as e:
    print(f"could not reach core at {core}: {e}", file=sys.stderr)
    sys.exit(1)

if not answer.get("ok"):
    # Reported rather than swallowed. An operation that silently failed would
    # make the very next assertion fail for a reason nobody could see from its
    # message.
    print(f"{operation} was refused: {json.dumps(answer.get('error'))}", file=sys.stderr)
    sys.exit(1)

print(json.dumps(answer.get("payload", {})))
PYEOF
